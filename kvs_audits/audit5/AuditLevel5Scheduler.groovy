package kvs_audits.audit5

import kvs_audits.common.AuditScheduler
import kvs_audits.common.CommonHelper
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.common.IAuditScheduler
import com.atlassian.jira.issue.Issue
import kvs_audits.issueType.AuditPreparation

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

/**
 * AuditLevel5Scheduler
 *
 * @author chabrecek.anton
 * Created on 5. 3. 2026.
 */
class AuditLevel5Scheduler extends AuditScheduler implements IAuditScheduler {

    public static final String auditLevel = CustomFieldsConstants.AUDIT_LEVEL_5

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
        final int SAFETY_LIMIT = 100

        while (!rotationDay.isAfter(today.plusMonths(3)) && safetyCounter < SAFETY_LIMIT) {
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

    private void processRotation(Issue prepIssue,
                                 AuditLevel5Handler handler,
                                 AuditPreparation prep,
                                 LocalDate rotationDay) {

        logger.setInfoMessage("Level 5 rotation for ${prepIssue.key} on ${rotationDay}")

        Map data = parseRotationData(prep)

        Map usages = data.questions_usages as Map
        if (!usages) {
            logger.setWarnMessage("No 'questions_usages' found for ${prepIssue.key}; skipping.")
            return
        }

        List<String> usageKeys = (usages.keySet() as List<String>).sort()
        if (usageKeys.isEmpty()) {
            logger.setWarnMessage("No questions_usages for ${prepIssue.key}; skipping.")
            return
        }

        int turnIdx = normalizeTurnIndex(data, usageKeys.size())
        String usageKey = usageKeys[turnIdx]
        def u = usages[usageKey]

        // auditors missing -> advance usageTurnIndex and persist
        if (!u.auditors || u.auditors.isEmpty()) {
            logger.setErrorMessage("No internal auditors defined for ${usageKey}; skipping this usage")
            data.usageTurnIndex = (turnIdx + 1) % usageKeys.size()
            handler.updateRotationData(JsonOutput.toJson(data))
            return
        }

        Issue pc = jqlSearcher.getPCFromQuestionUsage(usageKey, auditLevel)
        if (!pc) {
            logger.setErrorMessage("Rotation failed: invalid usageKey '${usageKey}', profit center not found")
            data.usageTurnIndex = (turnIdx + 1) % usageKeys.size()
            handler.updateRotationData(JsonOutput.toJson(data))
            return
        }

        String subAreaLetter = resolveSubAreaOrSkip(handler, data, usageKeys, turnIdx, usageKey, pc)
        if (!subAreaLetter) return

        List<String> liveSubAreas = jqlSearcher.getFunctionalAreasKeys(pc, subAreaLetter)
        if (!liveSubAreas) {
            logger.setErrorMessage("No Functional Areas for ${pc.key} with sub-area '${subAreaLetter}' for usage ${usageKey}")
            data.usageTurnIndex = (turnIdx + 1) % usageKeys.size()
            handler.updateRotationData(JsonOutput.toJson(data))
            return
        }

        // reconcile workplaces vs live
        u.workplaces = dynamicReconcileRotationUnits(usageKey, (u.workplaces as List<String>) ?: [], liveSubAreas)
        if (!u.workplaces) {
            logger.setErrorMessage("No sub-areas remain for ${usageKey} after reconciliation; skipping.")
            data.usageTurnIndex = (turnIdx + 1) % usageKeys.size()
            handler.updateRotationData(JsonOutput.toJson(data))
            return
        }

        // (cross audit disabled like L4)
        u.rotationCount = (u.rotationCount ?: 0) + 1
        boolean isCross = false

        // auditor selection (global round robin like L4)
        String auditor = pickAuditor(prepIssue, prep, rotationDay, usageKey, u, data)
        if (!auditor) return

        // pick FA
        String faKey = pickFunctionalAreaOrSkip(handler, data, usageKeys, turnIdx, usageKey, u)
        if (!faKey) return

        // special due (only for non-cross)
        List<String> specialDueForThisAudit = computeSpecialDueForFa(u, faKey, rotationDay)

        logger.setInfoMessage("RotationJob(L5): usage=${usageKey}, subArea=${faKey}, auditor=${auditor}, cross=${isCross}")
        handler.rotateOneAudit(usageKey, faKey, auditor, isCross, rotationDay, specialDueForThisAudit)

        // commit special due nextRotationDate
        if (specialDueForThisAudit) {
            commitSpecialDue(u, faKey, specialDueForThisAudit, rotationDay)
        }

        // persist json
        data.usageTurnIndex = (turnIdx + 1) % usageKeys.size()
        data.questions_usages = usages
        handler.updateRotationData(JsonOutput.toJson(data))

        // advance date
        LocalDate next = CommonHelper.getNextDate(rotationDay, prep.getInterval())
        logger.setWarnMessage("Date of next rotation: nextRotation = ${next}")
        handler.updateDateOfNextRotation(next)
    }

    // ---------- helpers (same behavior as L4) ----------

    private Map parseRotationData(AuditPreparation prep) {
        Map m = new JsonSlurper().parseText(prep.getRotation_data()) as Map
        if (!(m.globalAuditorIndex instanceof Number)) {
            m.globalAuditorIndex = 0
        }
        return m
    }

    private int normalizeTurnIndex(Map data, int usageCount) {
        int turnIdx = (data.usageTurnIndex instanceof Number) ? (int) data.usageTurnIndex : 0
        return turnIdx % usageCount
    }

    private String resolveSubAreaOrSkip(AuditLevel5Handler handler, Map data, List<String> usageKeys, int turnIdx,
                                        String usageKey, Issue pc) {
        String subAreaLetter = parseSubAreaLetterOrNull(usageKey)
        if (!subAreaLetter) {
            List<String> aKeys = jqlSearcher.getFunctionalAreasKeys(pc, "A") ?: []
            List<String> bKeys = jqlSearcher.getFunctionalAreasKeys(pc, "B") ?: []

            boolean onlyA = aKeys && !bKeys
            boolean onlyB = bKeys && !aKeys

            if (onlyA) subAreaLetter = "A"
            else if (onlyB) subAreaLetter = "B"
            else {
                logger.setErrorMessage("Level 5 usage '${usageKey}' no A/B and PC ${pc.key} has A and B – skip it.")
                data.usageTurnIndex = (turnIdx + 1) % usageKeys.size()
                handler.updateRotationData(JsonOutput.toJson(data))
                return null
            }
        }
        return subAreaLetter
    }

    private String pickAuditor(Issue prepIssue,
                               AuditPreparation prep,
                               LocalDate rotationDay,
                               String usageKey,
                               Map u,
                               Map data) {

        // reconcile auditors live (same helper from AuditScheduler)
        String liveAuditor = getLiveAuditors(prep, usageKey, u)
        if (!liveAuditor) {
            logger.setErrorMessage("No internal auditors available for ${usageKey} after reconciliation – skipping usage.")
            return null
        }

        List<String> curAuditors = (u.auditors as List<String>) ?: []
        if (!curAuditors) {
            logger.setErrorMessage("No internal auditors on usage ${usageKey} – skipping.")
            return null
        }

        int gIdx = (data?.globalAuditorIndex instanceof Number) ? ((Number) data.globalAuditorIndex).intValue() : 0
        String auditor = curAuditors[gIdx % curAuditors.size()]
        data.globalAuditorIndex = (gIdx + 1) % curAuditors.size()

        return auditor
    }

    private String pickFunctionalAreaOrSkip(AuditLevel5Handler handler, Map data, List<String> usageKeys, int turnIdx,
                                            String usageKey, Map u) {
        List<String> wps = (u.workplaces as List<String>) ?: []
        if (wps.isEmpty()) {
            logger.setErrorMessage("No sub-areas remain for ${usageKey} after reconciliation; skipping.")
            data.usageTurnIndex = (turnIdx + 1) % usageKeys.size()
            handler.updateRotationData(JsonOutput.toJson(data))
            return null
        }

        int cur = Math.max(0, (u.currentWorkplaceIndex ?: 0))
        int idx = cur % wps.size()
        String faKey = wps[idx]
        u.currentWorkplaceIndex = (idx + 1) % wps.size()
        return faKey
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

    //TODO NOT IN USE FOR NOW if needed, we can copy monthsForOccurrence+getQuestionOccurrence
    private void commitSpecialDue(Map u, String faKey, List<String> specials, LocalDate rotationDay) {
        specials.each { aqKey ->

        }
    }

    private String parseSubAreaLetterOrNull(String usageKey) {
        // *_A_Level_5 or *_B_Level_5
        def m = (usageKey =~ /_(A|B)_Level_5$/)
        return m ? m[0][1] : null
    }
}
