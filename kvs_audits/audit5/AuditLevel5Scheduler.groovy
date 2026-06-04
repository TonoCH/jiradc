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
 *  - Round-robin auditors across months so every auditor gets exactly one audit per month.
 *  - At most one audit per Profit Center per month (PCs rotate).
 *
 * @author chabrecek.anton
 * Created on 5. 3. 2026.
 */
class AuditLevel5Scheduler extends AuditScheduler implements IAuditScheduler {

    public static final String auditLevel = CustomFieldsConstants.AUDIT_LEVEL_5

    /**
     * Static descriptor of behavioral rules — consumed by jobs/kvs/rulesAudit.groovy.
     * KEEP IN SYNC WITH CODE. Drift between descriptor and implementation is the whole point of the audit.
     */
    public static final Map<String, Object> AUDIT_RULES = [
            auditLevel           : CustomFieldsConstants.AUDIT_LEVEL_5,
            handlerClass         : 'kvs_audits.audit5.AuditLevel5Handler',
            rotationUnit         : 'Functional Area',
            subAreaSplit         : 'A/B (parsed from usageKey suffix _A_Level_5 / _B_Level_5)',
            lookAheadMonths      : 3,
            safetyLimit          : 12,
            intervalSource       : 'IGNORED — fixed interval enforced',
            fixedIntervalOverride: 'monthly-mid',
            auditorRotation      : 'Global cursor (data.globalAuditorIndex) + per-pick offset; advanced by picks per tick',
            usageRotation        : 'Round-robin via data.usageTurnIndex; multiple usages per tick',
            auditsPerTick        : 'Up to N audits per month, N = min(numberOfAuditors, numberOfUniqueProfitCenters)',
            crossAudits          : 'Not supported (isCross hard-coded false)',
            onePcPerTick         : true,
            specialQuestions     : 'Per-FA (wpRotation[faKey]); semi-yearly=+6M, yearly=+12M',
            rotationDataShape    : 'root.{usageTurnIndex, globalAuditorIndex, questions_usages[usageKey].{workplaces, currentWorkplaceIndex, auditors, currentAuditorIndex, rotationCount, specialQuestions[qKey].wpRotation[faKey].nextRotationDate}}',
            notes                : 'Fixed interval = 15th of each month. AuditPreparation Interval field is hidden / ignored. Each auditor gets exactly one audit per month; each PC at most one per month.'
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
     * One mid-month rotation tick: creates up to N audits (N = min(auditors, uniquePCs))
     * - each on a different Profit Center
     * - each assigned to a different auditor (round-robin via globalAuditorIndex)
     * - PC ordering shifted via usageTurnIndex so over months everyone visits every PC.
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

        // Resolve usage -> Profit Center once for this rotation
        List<Map> usagePcMap = []
        usageKeys.each { String uk ->
            Issue pc = jqlSearcher.getPCFromQuestionUsage(uk, auditLevel)
            if (pc) {
                usagePcMap << [usage: uk, pcKey: pc.key]
            } else {
                logger.setWarnMessage("PC not found for usage ${uk}; skipping it this month.")
            }
        }
        if (usagePcMap.isEmpty()) {
            advanceDate(handler, rotationDay)
            return
        }

        int uniquePcCount = (usagePcMap*.pcKey as Set).size()
        int targetCount = Math.min(auditorCount, uniquePcCount)

        int turnIdx = normalizeTurnIndex(data, usagePcMap.size())
        int gIdx = (data.globalAuditorIndex instanceof Number) ? ((Number) data.globalAuditorIndex).intValue() : 0

        // Pick up to targetCount usages with unique PCs, scanning from turnIdx
        Set<String> seenPcThisMonth = [] as Set
        List<String> pickedUsages = []
        int attempts = 0
        int cursor = turnIdx
        while (pickedUsages.size() < targetCount && attempts < usagePcMap.size()) {
            Map row = usagePcMap[cursor % usagePcMap.size()]
            if (seenPcThisMonth.add(row.pcKey as String)) {
                pickedUsages << (row.usage as String)
            }
            cursor++
            attempts++
        }

        if (pickedUsages.isEmpty()) {
            logger.setWarnMessage("Nothing to rotate for ${prepIssue.key} on ${rotationDay}.")
            advanceDate(handler, rotationDay)
            return
        }

        if (auditorCount > uniquePcCount) {
            logger.setWarnMessage("L5 ${prepIssue.key}: auditors=${auditorCount} > uniquePCs=${uniquePcCount}; only ${pickedUsages.size()} audits this month.")
        }

        pickedUsages.eachWithIndex { String usageKey, int idx ->
            String forcedAuditor = liveAuditors[(gIdx + idx) % auditorCount]
            processSinglePick(prepIssue, handler, prep, rotationDay, usageKey, usages, forcedAuditor)
        }

        // Advance global pointers AFTER picks succeed/fail (we always move forward to avoid infinite loop)
        int picks = pickedUsages.size()
        data.usageTurnIndex = (turnIdx + attempts) % usagePcMap.size()
        data.globalAuditorIndex = (gIdx + picks) % auditorCount
        data.questions_usages = usages
        handler.updateRotationData(JsonOutput.toJson(data))

        advanceDate(handler, rotationDay)
    }

    private void processSinglePick(Issue prepIssue,
                                   AuditLevel5Handler handler,
                                   AuditPreparation prep,
                                   LocalDate rotationDay,
                                   String usageKey,
                                   Map usages,
                                   String forcedAuditor) {
        def u = usages[usageKey]
        if (u == null) {
            logger.setErrorMessage("No rotation entry for ${usageKey}; skipping pick.")
            return
        }

        Issue pc = jqlSearcher.getPCFromQuestionUsage(usageKey, auditLevel)
        if (!pc) {
            logger.setErrorMessage("Invalid usageKey '${usageKey}' (PC not found); skipping pick.")
            return
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
                return
            }
        }

        List<String> liveSubAreas = jqlSearcher.getFunctionalAreasKeys(pc, subAreaLetter)
        if (!liveSubAreas) {
            logger.setErrorMessage("No Functional Areas for ${pc.key} sub-area '${subAreaLetter}' (usage ${usageKey}); skipping pick.")
            return
        }

        u.workplaces = dynamicReconcileRotationUnits(usageKey, (u.workplaces as List<String>) ?: [], liveSubAreas)
        if (!u.workplaces) {
            logger.setErrorMessage("No sub-areas remain for ${usageKey} after reconciliation; skipping pick.")
            return
        }

        // Reconcile per-usage auditors list with live ones (keeps JSON in sync)
        getLiveAuditors(prep, usageKey, u)

        u.rotationCount = (u.rotationCount ?: 0) + 1
        boolean isCross = false

        if (!forcedAuditor) {
            logger.setErrorMessage("No auditor available for usage ${usageKey}; skipping pick.")
            return
        }

        // Pick FA via per-usage workplace cursor
        List<String> wps = (u.workplaces as List<String>) ?: []
        int cur = Math.max(0, (u.currentWorkplaceIndex ?: 0))
        int idx = cur % wps.size()
        String faKey = wps[idx]
        u.currentWorkplaceIndex = (idx + 1) % wps.size()

        List<String> specialDueForThisAudit = computeSpecialDueForFa(u, faKey, rotationDay)

        logger.setInfoMessage("RotationJob(L5): usage=${usageKey}, subArea=${faKey}, auditor=${forcedAuditor}, cross=${isCross}")
        handler.rotateOneAudit(usageKey, faKey, forcedAuditor, isCross, rotationDay, specialDueForThisAudit)

        if (specialDueForThisAudit) {
            commitSpecialDue(u, faKey, specialDueForThisAudit, rotationDay)
        }
    }

    private void advanceDate(AuditLevel5Handler handler, LocalDate rotationDay) {
        LocalDate next = CommonHelper.getNextDate(rotationDay, AUDIT_RULES.fixedIntervalOverride as String)
        logger.setWarnMessage("Date of next rotation: nextRotation = ${next}")
        handler.updateDateOfNextRotation(next)
    }

    // ---------- helpers ----------

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
