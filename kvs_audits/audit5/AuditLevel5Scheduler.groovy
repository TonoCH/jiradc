package kvs_audits.audit5

import kvs_audits.common.AuditScheduler
import kvs_audits.common.CommonHelper
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.common.IAuditScheduler
import com.atlassian.jira.issue.Issue
import kvs_audits.issueType.AuditPreparation
import kvs_audits.issueType.Question

import java.time.LocalDate
import java.time.ZoneId
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

/**
 * AuditLevel5Scheduler
 *
 * Mid-month rotation:
 *  - Fires on the 15th of each month (Interval is hidden / ignored on L5).
 *  - For each Audit Preparation creates up to N audits per month, where
 *    N = min(numberOfAuditors, numberOfUniqueProfitCenters).
 *  - Auditor assignment uses per-usage auditorHistory; when auditors <= unique PCs,
 *    each auditor can receive one audit per tick, otherwise auditors rotate over time
 *  - At most one audit per Profit Center per month (PCs rotate).
 *
 * @author chabrecek.anton
 * Created on 5. 3. 2026.
 */
class AuditLevel5Scheduler extends AuditScheduler implements IAuditScheduler {

    public static final String auditLevel = CustomFieldsConstants.AUDIT_LEVEL_5

    /**
     * Static descriptor of behavioral rules — consumed by jobs/kvs/rulesAudit.groovy.
     * Drift between descriptor and implementation is the whole point of the audit.
     */
    public static final Map<String, Object> AUDIT_RULES = [
            auditLevel           : CustomFieldsConstants.AUDIT_LEVEL_5,
            handlerClass         : 'kvs_audits.audit5.AuditLevel5Handler',
            rotationUnit         : 'Question Usage / PC sub-area; Functional Area rotates inside the usage',
            subAreaSplit         : 'A/B (parsed from usageKey suffix _A_Level_5 / _B_Level_5)',
            lookAheadMonths      : 3,
            safetyLimit          : 12,
            intervalSource       : 'IGNORED — fixed interval enforced',
            fixedIntervalOverride: 'monthly-mid',
            auditorRotation      : 'Per-usage auditorHistory; each pick assigns the live auditor with the lowest count for that usage, excluding auditors already used this tick',
            usageRotation        : 'Coverage-first: PCs are ranked by the audit count of their least-audited usage (auditorHistory sum) so every usage is picked at least once before any usage repeats; usageTurnIndex is only a tie-break seed for equally-covered PCs. pcUsageIndex[pcKey] picks WHICH usage (A/B) of a split PC is used, preferring whichever half has fewer audits',
            auditsPerTick        : 'Up to N audits per month, N = min(numberOfAuditors, numberOfUniqueProfitCenters)',
            crossAudits          : 'Not supported (isCross hard-coded false)',
            onePcPerTick         : true,
            specialQuestions     : 'Per-FA (wpRotation[faKey]); semi-yearly=+6M, yearly=+12M',
            rotationDataShape    : 'root.{usageTurnIndex, globalAuditorIndex (legacy/unused), pcUsageIndex[pcKey]->int, questions_usages[usageKey].{fas, currentFaIndex, auditors, currentAuditorIndex, rotationCount, auditorHistory[auditorName]->int, specialQuestions[qKey].wpRotation[faKey].nextRotationDate}}. Legacy keys workplaces/currentWorkplaceIndex are read transparently and migrated on write (RotationDataKeys).',
            notes                : 'Fixed interval = 15th of each month. AuditPreparation Interval field is hidden / ignored. Each PC at most one audit per month; when auditors > uniquePCs not every auditor gets an audit every month.'
    ]

    AuditLevel5Scheduler() {
        super(auditLevel)
    }

    @Override
    void execute() {
        List<Issue> prepIssues = getPreparationIssues()
        logBasicInfo(prepIssues)

        prepIssues.each { prepIssue ->
            scheduleRotationsFor(prepIssue)
        }
    }

    private void scheduleRotationsFor(Issue prepIssue) {
        AuditPreparation auditPreparation = new AuditPreparation(prepIssue)
        AuditLevel5Handler auditHandler = new AuditLevel5Handler(auditPreparation.getIssue(), kvsProjectKey, auditLevel)
        Date dateOfNextRotation = auditPreparation.getDate_of_next_rotation()

        if (!dateOfNextRotation) {
            logger.setWarnMessage("Date Of Next Rotation missing on ${prepIssue.key}, skipping.")
            return
        }

        LocalDate today = LocalDate.now()
        LocalDate rotationDay = dateOfNextRotation.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()

        int safetyCounter = 0
        final int SAFETY_LIMIT = AUDIT_RULES.safetyLimit as int
        final int LOOK_AHEAD_MONTHS = AUDIT_RULES.lookAheadMonths as int

        while (!rotationDay.isAfter(today.plusMonths(LOOK_AHEAD_MONTHS)) && safetyCounter < SAFETY_LIMIT) {
            safetyCounter++
            LocalDate before = rotationDay

            processRotation(prepIssue, auditHandler, auditPreparation, rotationDay)

            auditPreparation = new AuditPreparation(myBaseUtil.getIssueByKey(prepIssue.getKey()))
            dateOfNextRotation = auditPreparation.getDate_of_next_rotation()
            rotationDay = dateOfNextRotation.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()

            if (rotationDay.equals(before)) {
                logger.setErrorMessage("${prepIssue.getKey()} Rotation date did NOT advance (still ${rotationDay}). before date: ${before} Breaking to avoid infinite loop.")
                break
            }
        }

        if (safetyCounter == 0) {
            logger.setInfoMessage("No rotation for today")
        }

        if (safetyCounter >= SAFETY_LIMIT) {
            logger.setErrorMessage("Safety limit of ${SAFETY_LIMIT} rotations reached for ${prepIssue.key}. Exiting to prevent infinite loop.")
        }
    }

    /**
     * One mid-month rotation tick: creates up to N audits (N = min(auditors, uniquePCs)),
     * each on a different PC, each sub-area (A/B) and auditor assignment rotated independently.
     */
    private void processRotation(Issue prepIssue,
                                 AuditLevel5Handler handler,
                                 AuditPreparation prep,
                                 LocalDate rotationDay) {

        logger.setInfoMessage("Level 5 rotation for ${prepIssue.key} on ${rotationDay}")

        Map data = parseRotationData(prep)
        Map usages = data.questions_usages as Map
        if (!usages) {
            logger.setWarnMessage("No 'questions_usages' found for ${prepIssue.key}; advancing date only.")
            advanceDate(handler, rotationDay)
            return
        }

        List<String> usageKeys = (usages.keySet() as List<String>).sort()
        if (usageKeys.isEmpty()) {
            logger.setWarnMessage("No questions_usages for ${prepIssue.key}; advancing date only.")
            advanceDate(handler, rotationDay)
            return
        }

        List<String> liveAuditors = prep.getAuditors() ?: []
        if (liveAuditors.isEmpty()) {
            logger.setErrorMessage("No auditors on ${prepIssue.key}; advancing date only.")
            advanceDate(handler, rotationDay)
            return
        }
        int auditorCount = liveAuditors.size()

        // Group usages by Profit Center (split PC -> 2-entry sorted A/B list).
        Map<String, List<String>> pcToUsages = [:]
        usageKeys.each { String uk ->
            Issue pc = jqlSearcher.getPCFromQuestionUsage(uk, auditLevel)
            if (pc) {
                pcToUsages.computeIfAbsent(pc.key) { [] as List<String> } << uk
            } else {
                logger.setWarnMessage("PC not found for usage ${uk}; skipping it this month.")
            }
        }
        pcToUsages.each { k, v -> v.sort() }

        List<String> uniquePcKeys = (pcToUsages.keySet() as List<String>).sort()
        if (uniquePcKeys.isEmpty()) {
            advanceDate(handler, rotationDay)
            return
        }

        int uniquePcCount = uniquePcKeys.size()
        int targetCount = Math.min(auditorCount, uniquePcCount)

        int turnIdx = normalizeTurnIndex(data, uniquePcCount)
        int gIdx = (data.globalAuditorIndex instanceof Number) ? ((Number) data.globalAuditorIndex).intValue() : 0
        Map<String, Object> pcUsageIndex = (data.pcUsageIndex ?: [:]) as Map<String, Object>

        // Coverage-first pick: when uniquePcCount isn't a multiple of targetCount,
        // a plain modular ring (old behaviour) lets some PCs land 2x in a cycle
        // while others land only 1x — and a split (A/B) PC whose "short straw"
        // pick always lands on the already-audited half leaves its sibling usage
        // unaudited indefinitely (e.g. PCLA_B never got picked). Sort PCs by the
        // audit count of their LEAST-audited usage first, so no usage repeats
        // while another usage still has zero audits. Ties keep ring order so
        // fully-covered PCs still rotate fairly among themselves.
        Map<String, Integer> pcMinAuditCount = [:]
        uniquePcKeys.each { String pcKey ->
            pcMinAuditCount[pcKey] = pcToUsages[pcKey].collect { auditCountFor(usages[it] as Map) }.min()
        }
        Map<String, Integer> ringPos = [:]
        uniquePcKeys.eachWithIndex { String pcKey, int i ->
            ringPos[pcKey] = ((i - turnIdx) % uniquePcCount + uniquePcCount) % uniquePcCount
        }
        List<String> pickedPcKeys = (uniquePcKeys as List<String>).sort { a, b ->
            int byCoverage = pcMinAuditCount[a] <=> pcMinAuditCount[b]
            byCoverage != 0 ? byCoverage : ringPos[a] <=> ringPos[b]
        }.take(targetCount)

        if (pickedPcKeys.isEmpty()) {
            logger.setWarnMessage("Nothing to rotate for ${prepIssue.key} on ${rotationDay}.")
            advanceDate(handler, rotationDay)
            return
        }

        if (auditorCount > uniquePcCount) {
            logger.setWarnMessage("L5 ${prepIssue.key}: auditors=${auditorCount} > uniquePCs=${uniquePcCount}; only ${pickedPcKeys.size()} audits this month.")
        }

        // Auditor pick is greedy least-used-for-this-usage (not a shared modular
        // cursor with the PC ring — that caused fixed auditor<->PC pairs before).
        // pcUsageIndex / auditorHistory are only committed after processSinglePick
        // actually creates the audit, so a skipped pick doesn't burn its A/B or
        // auditor turn.
        Set<String> usedThisMonth = [] as Set
        pickedPcKeys.each { String pcKey ->
            List<String> pcUsages = pcToUsages[pcKey]
            int storedCursor = (pcUsageIndex[pcKey] instanceof Number) ? ((Number) pcUsageIndex[pcKey]).intValue() : 0
            // Pick the least-audited usage under this PC (not a blind modular
            // cursor), so an A/B half with zero audits is always chosen ahead
            // of its sibling repeating. Ties fall back to the stored cursor so
            // fully-covered split PCs still alternate A/B predictably.
            String usageKey = pickLeastAuditedUsage(pcUsages, usages, storedCursor)
            int subCursor = pcUsages.indexOf(usageKey)

            def u = usages[usageKey]
            if (u == null) {
                logger.setErrorMessage("No rotation entry for ${usageKey}; skipping pick.")
                return
            }

            Map<String, Object> history = (u.auditorHistory ?: [:]) as Map
            String best = null
            int bestCount = Integer.MAX_VALUE
            liveAuditors.each { String auditor ->
                if (usedThisMonth.contains(auditor)) return
                int count = (history[auditor] instanceof Number) ? ((Number) history[auditor]).intValue() : 0
                if (count < bestCount) {
                    bestCount = count
                    best = auditor
                }
            }
            if (best == null) {
                logger.setErrorMessage("No available auditor for ${usageKey}; skipping pick.")
                return
            }

            boolean created = processSinglePick(prepIssue, handler, prep, rotationDay, usageKey, usages, best)

            if (created) {
                usedThisMonth << best
                history[best] = bestCount + 1
                u.auditorHistory = history
                pcUsageIndex[pcKey] = (subCursor + 1) % pcUsages.size()
            } else {
                logger.setWarnMessage("L5 ${prepIssue.key}: audit not created for usage=${usageKey}, pc=${pcKey}; cursor and auditor history not advanced.")
            }
        }

        // Advance the PC ring by exactly `picks` regardless of per-pick success,
        // so the tick always moves forward (avoids infinite loop / re-audit lock).
        int picks = pickedPcKeys.size()
        int newTurnIdx = (turnIdx + picks) % uniquePcCount

        // globalAuditorIndex kept only for backward-compat/display; unused for selection now.
        int newGIdx = (gIdx + picks) % auditorCount

        data.usageTurnIndex = newTurnIdx
        data.globalAuditorIndex = newGIdx
        data.pcUsageIndex = pcUsageIndex
        data.questions_usages = usages
        handler.updateRotationData(JsonOutput.toJson(data))

        advanceDate(handler, rotationDay)
    }

    /**
     * @return true if an Audit was actually created for this pick, false if skipped.
     */
    private boolean processSinglePick(Issue prepIssue,
                                      AuditLevel5Handler handler,
                                      AuditPreparation prep,
                                      LocalDate rotationDay,
                                      String usageKey,
                                      Map usages,
                                      String forcedAuditor) {
        def u = usages[usageKey]
        if (u == null) {
            logger.setErrorMessage("No rotation entry for ${usageKey}; skipping pick.")
            return false
        }

        Issue pc = jqlSearcher.getPCFromQuestionUsage(usageKey, auditLevel)
        if (!pc) {
            logger.setErrorMessage("Invalid usageKey '${usageKey}' (PC not found); skipping pick.")
            return false
        }

        String subAreaLetter = parseSubAreaLetterOrNull(usageKey)
        if (!subAreaLetter) {
            List<String> aKeys = jqlSearcher.getFunctionalAreasKeys(pc, "A") ?: []
            List<String> bKeys = jqlSearcher.getFunctionalAreasKeys(pc, "B") ?: []
            boolean onlyA = aKeys && !bKeys
            boolean onlyB = bKeys && !aKeys
            if (onlyA) subAreaLetter = "A"
            else if (onlyB) subAreaLetter = "B"
            else {
                logger.setErrorMessage("Level 5 usage '${usageKey}' has no A/B and PC ${pc.key} has both; skipping pick.")
                return false
            }
        }

        List<String> liveSubAreas = jqlSearcher.getFunctionalAreasKeys(pc, subAreaLetter)
        if (!liveSubAreas) {
            logger.setErrorMessage("No Functional Areas for ${pc.key} sub-area '${subAreaLetter}' (usage ${usageKey}); skipping pick.")
            return false
        }

        List<String> reconciled = dynamicReconcileRotationUnits(usageKey, readRotationUnits(u), liveSubAreas)
        writeRotationUnits(u, reconciled)
        if (!reconciled) {
            logger.setErrorMessage("No sub-areas remain for ${usageKey} after reconciliation; skipping pick.")
            return false
        }

        // Reconcile per-usage auditors list with live ones (keeps JSON in sync)
        getLiveAuditors(prep, usageKey, u)

        boolean isCross = false

        if (!forcedAuditor) {
            logger.setErrorMessage("No auditor available for usage ${usageKey}; skipping pick.")
            return false
        }

        // Pick FA via per-usage rotation-units cursor
        List<String> wps = readRotationUnits(u)
        int cur = Math.max(0, readRotationIndex(u))
        int idx = cur % wps.size()
        String faKey = wps[idx]

        List<String> specialDueForThisAudit = computeSpecialDueForFa(u, faKey, rotationDay)

        logger.setInfoMessage("RotationJob(L5): usage=${usageKey}, subArea=${faKey}, auditor=${forcedAuditor}, cross=${isCross}")
        // State (rotationCount, FA cursor) advances only after rotateOneAudit succeeds (it throws on failure).
        handler.rotateOneAudit(usageKey, faKey, forcedAuditor, isCross, rotationDay, specialDueForThisAudit)
        u.rotationCount = (u.rotationCount ?: 0) + 1
        writeRotationIndex(u, (idx + 1) % wps.size())

        if (specialDueForThisAudit) {
            commitSpecialDue(u, faKey, specialDueForThisAudit, rotationDay)
        }

        return true
    }

    private void advanceDate(AuditLevel5Handler handler, LocalDate rotationDay) {
        LocalDate next = CommonHelper.getNextDate(rotationDay, AUDIT_RULES.fixedIntervalOverride as String)
        logger.setWarnMessage("Date of next rotation: nextRotation = ${next}")
        handler.updateDateOfNextRotation(next)
    }

    // ---------- helpers ----------

    /** Total audits ever created for a usage = sum of its per-auditor history counts. */
    private int auditCountFor(Map u) {
        Map history = (u?.auditorHistory ?: [:]) as Map
        int total = 0
        history.values().each { v -> total += (v instanceof Number) ? ((Number) v).intValue() : 0 }
        return total
    }

    /**
     * Picks the usage under a (possibly split A/B) PC with the fewest audits so
     * far. Guarantees a never-audited half is chosen before its sibling repeats.
     * Ties (equal audit counts) fall back to the stored rotation cursor so a
     * fully-covered split PC still alternates A/B in a stable order.
     */
    private String pickLeastAuditedUsage(List<String> pcUsages, Map usages, int storedCursor) {
        int size = pcUsages.size()
        if (size <= 1) return pcUsages[0]
        int fallbackCursor = ((storedCursor % size) + size) % size
        // Fixed original-index map so tie-break positions stay stable while sorting a copy.
        Map<String, Integer> originalIndex = [:]
        pcUsages.eachWithIndex { String uk, int i -> originalIndex[uk] = i }
        List<String> copy = new ArrayList<String>(pcUsages)
        return copy.sort { a, b ->
            int countA = auditCountFor(usages[a] as Map)
            int countB = auditCountFor(usages[b] as Map)
            if (countA != countB) return countA <=> countB
            int posA = ((originalIndex[a] - fallbackCursor) % size + size) % size
            int posB = ((originalIndex[b] - fallbackCursor) % size + size) % size
            return posA <=> posB
        }.first()
    }

    private Map parseRotationData(AuditPreparation prep) {
        Map m = new JsonSlurper().parseText(prep.getRotation_data()) as Map
        if (!(m.globalAuditorIndex instanceof Number)) {
            m.globalAuditorIndex = 0
        }
        return m
    }

    private int normalizeTurnIndex(Map data, int usageCount) {
        int turnIdx = (data.usageTurnIndex instanceof Number) ? (int) data.usageTurnIndex : 0
        return ((turnIdx % usageCount) + usageCount) % usageCount
    }

    private List<String> computeSpecialDueForFa(Map u, String faKey, LocalDate rotationDay) {
        List<String> specialDueForThisAudit = []
        Map specials = (u.specialQuestions ?: [:]) as Map
        specials.each { aqKey, detail ->
            def nextStr = (detail?.wpRotation ?: [:])?[faKey]?.nextRotationDate
            if (nextStr) {
                LocalDate due = LocalDate.parse(nextStr as String)
                if (!due.isAfter(rotationDay)) {
                    specialDueForThisAudit << (aqKey as String)
                }
            }
        }
        return specialDueForThisAudit
    }

    private void commitSpecialDue(Map u, String faKey, List<String> specials, LocalDate rotationDay) {
        specials.each { aqKey ->
            String occ = getQuestionOccurrence(aqKey)
            int months = monthsForOccurrence(occ)
            if (months > 0) {
                def curStr = u.specialQuestions[aqKey]?.wpRotation?[faKey]?.nextRotationDate
                LocalDate curD = curStr ? LocalDate.parse(curStr as String) : rotationDay
                LocalDate nxt = curD.plusMonths(months)

                if (!u.specialQuestions[aqKey]) u.specialQuestions[aqKey] = [wpRotation: [:]]
                if (!u.specialQuestions[aqKey].wpRotation) u.specialQuestions[aqKey].wpRotation = [:]
                u.specialQuestions[aqKey].wpRotation[faKey] = [nextRotationDate: nxt.toString()]

                logger.setInfoMessage("Advanced special ${aqKey} for ${faKey} → ${nxt}")
            } else {
                logger.setWarnMessage("Special ${aqKey}: unsupported occurrence '${occ}' – skipping advance.")
            }
        }
    }

    private int monthsForOccurrence(String occ) {
        if (!occ) return 0
        occ = occ.toLowerCase()
        if (occ.contains("semi")) return 6
        if (occ.contains("year")) return 12
        return 0
    }

    private String getQuestionOccurrence(String questionKey) {
        def qIssue = myBaseUtil.getIssueByKey(questionKey)
        if (!qIssue) return null
        def raw = myBaseUtil.getCustomFieldValue(qIssue, Question.AUDIT_INTERVAL_OCCURANCE_FIELD_NAME)
        return (raw ?: "") as String
    }

    private String parseSubAreaLetterOrNull(String usageKey) {
        // *_A_Level_5 or *_B_Level_5
        def m = (usageKey =~ /_(A|B)_Level_5$/)
        return m ? m[0][1] : null
    }
}