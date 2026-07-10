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
 * Mid-month rotation (15th, Interval field ignored):
 *  - up to N audits per month, N = min(auditors, unique Profit Centers)
 *  - max one audit per PC per month; PCs picked by least-audited, then longest-unaudited
 *  - usages and auditors sync live from the Audit Preparation each tick
 *
 * @author chabrecek.anton
 * Created on 5. 3. 2026.
 */
class AuditLevel5Scheduler extends AuditScheduler implements IAuditScheduler {

    public static final String auditLevel = CustomFieldsConstants.AUDIT_LEVEL_5

    /** Behavioral descriptor consumed by jobs/kvs/rulesAudit.groovy. */
    public static final Map<String, Object> AUDIT_RULES = [
            auditLevel           : CustomFieldsConstants.AUDIT_LEVEL_5,
            handlerClass         : 'kvs_audits.audit5.AuditLevel5Handler',
            rotationUnit         : 'Question Usage / PC sub-area; Functional Area rotates inside the usage',
            subAreaSplit         : 'A/B (parsed from usageKey suffix _A_Level_5 / _B_Level_5)',
            lookAheadMonths      : 3,
            safetyLimit          : 12,
            intervalSource       : 'IGNORED — fixed interval enforced',
            fixedIntervalOverride: 'monthly-mid',
            auditorRotation      : 'Per-usage auditorHistory: each pick takes the live auditor with the lowest count for that usage; no auditor twice per tick. Auditors sync live from the prep. When auditors > uniquePCs, surplus auditors skip that month (one-audit-per-PC cap), evening out via history.',
            usageRotation        : 'PCs ranked by (min audit count of their usages, oldest lastAuditedDate, ring position); within a split PC the A/B half with fewer audits / older date goes first. Gives even coverage AND even per-PC cadence; robust to usage add/remove (no positional cursor).',
            usageSync            : 'questions_usages reconciled from the prep each tick: new usages join as full init-shaped entries (specials due on current rotation day), deselected ones soft-removed (active=false, history kept), reselected re-activate. No new Audit Preparation needed.',
            auditsPerTick        : 'Up to N audits per month, N = min(numberOfAuditors, numberOfUniqueProfitCenters)',
            crossAudits          : 'Not supported (isCross hard-coded false)',
            onePcPerTick         : true,
            specialQuestions     : 'Per-FA (wpRotation[faKey]); semi-yearly=+6M, yearly=+12M',
            rotationDataShape    : 'root.{usageTurnIndex, globalAuditorIndex (legacy/unused), pcUsageIndex[pcKey]->int, questions_usages[usageKey].{active(bool; false=soft-removed), fas, currentFaIndex, auditors, currentAuditorIndex, rotationCount, lastAuditedDate(ISO; last planned Target End), auditorHistory[auditorName]->int, specialQuestions[qKey].wpRotation[faKey].nextRotationDate}}. Legacy keys workplaces/currentWorkplaceIndex migrated on write (RotationDataKeys).',
            notes                : 'Fixed interval = 15th of each month. AuditPreparation Interval field is hidden / ignored. Each PC at most one audit per month; when auditors > uniquePCs not every auditor gets an audit every month.'
    ]

    AuditLevel5Scheduler() {
        super(auditLevel)
    }

    @Override
    void execute() {
        List<Issue> prepIssues = getPreparationIssues()
        logBasicInfo(prepIssues)
        prepIssues.each { scheduleRotationsFor(it) }
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
                logger.setErrorMessage("${prepIssue.getKey()} Rotation date did NOT advance (still ${rotationDay}). Breaking to avoid infinite loop.")
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

    /** One mid-month tick: up to N audits (N = min(auditors, uniquePCs)), one PC each. */
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

        // Usages sync live from the prep; soft-removed ones keep their history.
        syncUsagesWithPrep(prep, usages, rotationDay)

        List<String> usageKeys = (usages.keySet() as List<String>)
                .findAll { isUsageActive(usages[it]) }
                .sort()
        if (usageKeys.isEmpty()) {
            logger.setWarnMessage("No active questions_usages for ${prepIssue.key}; advancing date only.")
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

        // Group usages by Profit Center (split PC -> sorted A/B list).
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
        int turnIdx = mod(asInt(data.usageTurnIndex), uniquePcCount)
        Map<String, Object> pcUsageIndex = (data.pcUsageIndex ?: [:]) as Map<String, Object>

        // Rank PCs: least audited first, then longest-unaudited (lastAuditedDate =
        // planned Target End; "" = never), then ring position. Even coverage AND even
        // per-PC cadence; usage add/remove can't corrupt it (no positional cursor).
        Map<String, Integer> pcMinCount = [:]
        Map<String, String> pcOldestDate = [:]
        uniquePcKeys.each { String pcKey ->
            List<String> pcUsages = pcToUsages[pcKey]
            int minCount = pcUsages.collect { auditCountFor(usages[it] as Map) }.min()
            pcMinCount[pcKey] = minCount
            // Date only from min-count usages — the actual pick candidates (a split
            // PC must not gain priority via its already-more-audited half's date).
            pcOldestDate[pcKey] = pcUsages
                    .findAll { auditCountFor(usages[it] as Map) == minCount }
                    .collect { lastAuditedDateFor(usages[it] as Map) }
                    .min()
        }
        Map<String, Integer> ringPos = [:]
        uniquePcKeys.eachWithIndex { String pcKey, int i -> ringPos[pcKey] = mod(i - turnIdx, uniquePcCount) }

        List<String> pickedPcKeys = uniquePcKeys.sort(false) { a, b ->
            int c = pcMinCount[a] <=> pcMinCount[b]
            if (c) return c
            c = pcOldestDate[a] <=> pcOldestDate[b]
            c ?: ringPos[a] <=> ringPos[b]
        }.take(targetCount)

        if (auditorCount > uniquePcCount) {
            logger.setWarnMessage("L5 ${prepIssue.key}: auditors=${auditorCount} > uniquePCs=${uniquePcCount}; only ${pickedPcKeys.size()} audits this month.")
        }

        // State (cursor, history) is committed only when the audit was really created,
        // so a skipped pick doesn't burn its A/B or auditor turn.
        Set<String> usedThisMonth = [] as Set
        pickedPcKeys.each { String pcKey ->
            List<String> pcUsages = pcToUsages[pcKey]
            String usageKey = pickLeastAuditedUsage(pcUsages, usages, asInt(pcUsageIndex[pcKey]))
            def u = usages[usageKey]
            if (u == null) {
                logger.setErrorMessage("No rotation entry for ${usageKey}; skipping pick.")
                return
            }

            // Least-used live auditor for this usage, at most one audit per auditor per tick.
            Map history = (u.auditorHistory ?: [:]) as Map
            String auditor = liveAuditors.findAll { !usedThisMonth.contains(it) }.min { asInt(history[it]) }
            if (!auditor) {
                logger.setErrorMessage("No available auditor for ${usageKey}; skipping pick.")
                return
            }

            if (processSinglePick(handler, prep, rotationDay, usageKey, u, auditor)) {
                usedThisMonth << auditor
                history[auditor] = asInt(history[auditor]) + 1
                u.auditorHistory = history
                pcUsageIndex[pcKey] = mod(pcUsages.indexOf(usageKey) + 1, pcUsages.size())
            } else {
                logger.setWarnMessage("L5 ${prepIssue.key}: audit not created for usage=${usageKey}, pc=${pcKey}; state not advanced.")
            }
        }

        // Ring always advances by the number of picks so the tick moves forward.
        data.usageTurnIndex = mod(turnIdx + pickedPcKeys.size(), uniquePcCount)
        data.globalAuditorIndex = mod(asInt(data.globalAuditorIndex) + pickedPcKeys.size(), auditorCount) // legacy/display only
        data.pcUsageIndex = pcUsageIndex
        data.questions_usages = usages
        handler.updateRotationData(JsonOutput.toJson(data))

        advanceDate(handler, rotationDay)
    }

    /** @return true if an Audit was actually created for this pick. */
    private boolean processSinglePick(AuditLevel5Handler handler,
                                      AuditPreparation prep,
                                      LocalDate rotationDay,
                                      String usageKey,
                                      def u,
                                      String auditor) {
        Issue pc = jqlSearcher.getPCFromQuestionUsage(usageKey, auditLevel)
        if (!pc) {
            logger.setErrorMessage("Invalid usageKey '${usageKey}' (PC not found); skipping pick.")
            return false
        }

        List<String> liveSubAreas = resolveLiveSubAreas(usageKey, pc)
        if (liveSubAreas == null) {
            logger.setErrorMessage("Level 5 usage '${usageKey}' has no A/B and PC ${pc.key} has both; skipping pick.")
            return false
        }
        if (!liveSubAreas) {
            logger.setErrorMessage("No Functional Areas for ${pc.key} sub-area of usage ${usageKey}; skipping pick.")
            return false
        }

        List<String> reconciled = dynamicReconcileRotationUnits(usageKey, readRotationUnits(u), liveSubAreas)
        writeRotationUnits(u, reconciled)
        if (!reconciled) {
            logger.setErrorMessage("No sub-areas remain for ${usageKey} after reconciliation; skipping pick.")
            return false
        }

        // Keeps the per-usage auditors list in the JSON in sync with the prep.
        getLiveAuditors(prep, usageKey, u)

        // FA via per-usage cursor.
        List<String> wps = readRotationUnits(u)
        int idx = Math.max(0, readRotationIndex(u)) % wps.size()
        String faKey = wps[idx]

        List<String> specialDue = computeSpecialDueForFa(u, faKey, rotationDay)

        logger.setInfoMessage("RotationJob(L5): usage=${usageKey}, subArea=${faKey}, auditor=${auditor}, cross=false")
        // State advances only after rotateOneAudit succeeds (it throws on failure).
        handler.rotateOneAudit(usageKey, faKey, auditor, false, rotationDay, specialDue)
        u.rotationCount = asInt(u.rotationCount) + 1
        // Last planned Target End (same formula as rotateOneAudit); drives the cadence tie-break.
        u.lastAuditedDate = CommonHelper.snapToNearestMonday(rotationDay)
                .plusDays(CustomFieldsConstants.NUM_OF_DAYS_FOR_TARGET_END).toString()
        writeRotationIndex(u, mod(idx + 1, wps.size()))

        if (specialDue) {
            commitSpecialDue(u, faKey, specialDue, rotationDay)
        }
        return true
    }

    private void advanceDate(AuditLevel5Handler handler, LocalDate rotationDay) {
        LocalDate next = CommonHelper.getNextDate(rotationDay, AUDIT_RULES.fixedIntervalOverride as String)
        logger.setWarnMessage("Date of next rotation: nextRotation = ${next}")
        handler.updateDateOfNextRotation(next)
    }

    // ---------- helpers ----------

    /**
     * Reconciles questions_usages with the usages currently selected on the prep:
     * new usages join as full entries, deselected ones are soft-removed
     * (active=false, history kept), reselected ones re-activate.
     */
    private void syncUsagesWithPrep(AuditPreparation prep, Map usages, LocalDate rotationDay) {
        Map<String, Issue> livePcByUsage = [:]
        (prep.getQuestionUsage() ?: [])
                .findAll { it?.trim() }
                .collect { it.trim() }
                .unique()
                .each { String uk ->
                    Issue pc = jqlSearcher.getPCFromQuestionUsage(uk, auditLevel)
                    if (pc) livePcByUsage[uk] = pc
                }

        livePcByUsage.each { String uk, Issue pc ->
            def u = usages[uk]
            if (u == null) {
                usages[uk] = newUsageEntry(uk, pc, rotationDay)
                logger.setInfoMessage("L5 sync: added new Question Usage ${uk} to rotation.")
            } else if (u.active == false) {
                u.active = true
                logger.setInfoMessage("L5 sync: re-activated Question Usage ${uk}.")
            }
        }

        usages.each { String uk, def u ->
            if (!livePcByUsage.containsKey(uk) && u?.active != false) {
                u.active = false
                logger.setWarnMessage("L5 sync: Question Usage ${uk} no longer on prep; marked inactive (history kept).")
            }
        }
    }

    /**
     * Fresh init-shaped entry for a usage added via sync. Specials are seeded due
     * on the current rotation day (= included in the usage's first audit),
     * mirroring the pool-only init in AuditHandlerBase.
     */
    private Map newUsageEntry(String uk, Issue pc, LocalDate rotationDay) {
        List<String> fas = resolveLiveSubAreas(uk, pc) ?: []
        if (!fas) {
            logger.setWarnMessage("L5 sync: no Functional Areas resolved for new usage ${uk} yet; pick-time reconciliation will fill them in.")
        }

        Map<String, Object> specials = [:]
        (jqlSearcher.findSpecialQuestionsByUsage(uk) ?: []).each { qIssue ->
            specials[qIssue.key] = [wpRotation: fas.collectEntries { [(it): [nextRotationDate: rotationDay.toString()]] }]
        }

        return [
                usageKey                : uk,
                active                  : true,
                fas                     : fas,
                currentFaIndex          : 0,
                auditors                : [],
                currentAuditorIndex     : 0,
                currentCrossAuditorIndex: 0,
                rotationCount           : 0,
                lastAuditedDate         : "",
                auditorHistory          : [:],
                crossAuditHistory       : [],
                specialQuestions        : specials
        ]
    }

    /**
     * Live FAs for a usage's sub-area; no A/B suffix falls back to the PC's single
     * sub-area. Returns null when ambiguous (no suffix, PC has both A and B).
     */
    private List<String> resolveLiveSubAreas(String usageKey, Issue pc) {
        String letter = parseSubAreaLetterOrNull(usageKey)
        if (!letter) {
            List<String> aKeys = jqlSearcher.getFunctionalAreasKeys(pc, "A") ?: []
            List<String> bKeys = jqlSearcher.getFunctionalAreasKeys(pc, "B") ?: []
            if (aKeys && !bKeys) letter = "A"
            else if (bKeys && !aKeys) letter = "B"
            else return null
        }
        return jqlSearcher.getFunctionalAreasKeys(pc, letter) ?: []
    }

    private boolean isUsageActive(def u) {
        return u != null && u.active != false
    }

    /** Last planned Target End ("" = never audited, sorts as oldest). */
    private String lastAuditedDateFor(Map u) {
        def d = u?.lastAuditedDate
        return (d instanceof String) ? d : ""
    }

    /** Total audits for a usage = sum of its auditorHistory counts. */
    private int auditCountFor(Map u) {
        ((u?.auditorHistory ?: [:]) as Map).values().sum { asInt(it) } ?: 0
    }

    /** Least-audited usage of a (possibly A/B split) PC; ties: older date, then stored cursor. */
    private String pickLeastAuditedUsage(List<String> pcUsages, Map usages, int storedCursor) {
        int size = pcUsages.size()
        if (size <= 1) return pcUsages[0]
        int cursor = mod(storedCursor, size)
        return pcUsages.min { a, b ->
            int c = auditCountFor(usages[a] as Map) <=> auditCountFor(usages[b] as Map)
            if (c) return c
            c = lastAuditedDateFor(usages[a] as Map) <=> lastAuditedDateFor(usages[b] as Map)
            c ?: mod(pcUsages.indexOf(a) - cursor, size) <=> mod(pcUsages.indexOf(b) - cursor, size)
        }
    }

    private Map parseRotationData(AuditPreparation prep) {
        new JsonSlurper().parseText(prep.getRotation_data()) as Map
    }

    private List<String> computeSpecialDueForFa(Map u, String faKey, LocalDate rotationDay) {
        List<String> due = []
        ((u.specialQuestions ?: [:]) as Map).each { aqKey, detail ->
            def nextStr = (detail?.wpRotation ?: [:])?[faKey]?.nextRotationDate
            if (nextStr && !LocalDate.parse(nextStr as String).isAfter(rotationDay)) {
                due << (aqKey as String)
            }
        }
        return due
    }

    private void commitSpecialDue(Map u, String faKey, List<String> specials, LocalDate rotationDay) {
        specials.each { aqKey ->
            String occ = getQuestionOccurrence(aqKey)
            int months = monthsForOccurrence(occ)
            if (months > 0) {
                def curStr = u.specialQuestions[aqKey]?.wpRotation?[faKey]?.nextRotationDate
                LocalDate nxt = (curStr ? LocalDate.parse(curStr as String) : rotationDay).plusMonths(months)

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
        (myBaseUtil.getCustomFieldValue(qIssue, Question.AUDIT_INTERVAL_OCCURANCE_FIELD_NAME) ?: "") as String
    }

    private String parseSubAreaLetterOrNull(String usageKey) {
        def m = (usageKey =~ /_(A|B)_Level_5$/)  // *_A_Level_5 / *_B_Level_5
        return m ? m[0][1] : null
    }

    private static int asInt(def v) {
        (v instanceof Number) ? ((Number) v).intValue() : 0
    }

    private static int mod(int a, int n) {
        ((a % n) + n) % n
    }
}