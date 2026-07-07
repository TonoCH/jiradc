package kvs_audits.audit5

import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.config.properties.APKeys
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.Issue
import groovy.json.JsonOutput
import kvs_audits.common.AuditHandlerBase
import kvs_audits.common.AuditMailer
import kvs_audits.common.CommonHelper
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.common.RotationDataKeys
import kvs_audits.issueType.Audit
import kvs_audits.issueType.AuditPreparation
import kvs_audits.issueType.BaseIssue

import java.sql.Timestamp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AuditLevel5Handler extends AuditHandlerBase {

    public AuditLevel5Handler(Issue auditPrepIssue, String kvsProjectKey, String auditLevel) {
        super(auditPrepIssue, auditLevel, kvsProjectKey);
    }

    // FIX: remember which usages received an initial audit so initializeRotationData
    // can advance cursors only for those usages (pool-only usages must stay at 0).
    private List<String> orderedUsagesForInit = []
    private int maxInitialAuditsForInit = 0

    public void createAudit(List<String> questionUsages) {


        List<String> selectedUsages = (questionUsages ?: [])
                .findAll { it?.trim() }
                .collect { it.trim() }
                .unique()

        List<String> invalidUsages = selectedUsages.findAll { !isCorrectAuditLevel(it) }

        if (invalidUsages) {
            logger.setWarnMessage("L5 createAudit: Ignoring non-Level-5 Question Usages selected on AP ${auditPreparationIssue.getIssue().key}: ${invalidUsages}")
        }

        // Sorted so a split PC's "A" usage is always first, matching the scheduler's ordering.
        questionUsages = selectedUsages.findAll { isCorrectAuditLevel(it) }.sort()


        if (!questionUsages) {
            logger.setErrorMessage(
                    "L5 createAudit: No Question Usages selected on Audit Preparation ${auditPreparationIssue.getIssue().key}. " +
                            "Level 5 strict mode requires explicit Question Usages."
            )
            return
        }

        logger.setInfoMessage("L5 createAudit: Using only AP-selected Question Usages: ${questionUsages}")

        List<String> auditors = auditPreparationIssue.getAuditors() ?: []
        if (!auditors) {
            logger.setErrorMessage(
                    "L5 createAudit: No auditors selected on Audit Preparation ${auditPreparationIssue.getIssue().key}. " +
                            "Level 5 requires at least one auditor."
            )
            return
        }

        // STRICT MODE: max 1 audit per Profit Center per month
        int auditorCount = auditors.size()

        List<Map> usageWithPc = questionUsages.collect { u ->
            Issue pc = jqlSearcher.getPCFromQuestionUsage(u, currentAuditLevel)
            [usage: u, pcKey: pc?.key]
        }.findAll { it.pcKey }

        Set<String> seenPc = [] as Set
        List<String> uniquePcUsagesInOrder = []

        usageWithPc.each { row ->
            if (seenPc.add(row.pcKey)) {
                uniquePcUsagesInOrder << row.usage
            }
        }

        int uniquePcCount = uniquePcUsagesInOrder.size()

        if (uniquePcCount == 0) {
            logger.setWarnMessage(
                    "L5 strict createAudit: no usable Profit Centers resolved from Question Usages: ${questionUsages}"
            )
            return
        }

        int maxInitialAudits = Math.min(auditorCount, uniquePcCount)

        if (auditorCount > uniquePcCount) {
            logger.setWarnMessage(
                    "L5 strict createAudit: auditors=${auditorCount} but uniqueProfitCenters=${uniquePcCount}. " +
                            "Only ${maxInitialAudits} initial audits will be created."
            )
        }

        List<String> rest = questionUsages - uniquePcUsagesInOrder
        List<String> orderedUsages = uniquePcUsagesInOrder + rest

        this.orderedUsagesForInit = orderedUsages
        this.maxInitialAuditsForInit = maxInitialAudits

        executeCreateAudit(
                orderedUsages,
                { pc, fa -> jqlSearcher.getSubAreas(pc) },
                BaseIssue.KVS_PC_SUB_AREA_FIELD_ID,
                maxInitialAudits
        )
    }

    @Override
    protected void initializeRotationData(Map<String, Map<String, Object>> questions_usages, int initialTurnIndex = 0) {

        LocalDate startDate = Optional.ofNullable(auditPreparationIssue.getDate_target_start())
                .map { ts -> ts instanceof Timestamp ? ts.toLocalDate() : ts }
                .orElse(LocalDate.now())

        // Usages that already got an initial audit (first maxInitialAudits of orderedUsages).
        List<String> usagesWithInitialOrdered = orderedUsagesForInit
                ? orderedUsagesForInit.take(Math.max(0, maxInitialAuditsForInit)) as List<String>
                : []
        Set<String> usagesWithInitial = usagesWithInitialOrdered as Set

        List<String> auditors = auditPreparationIssue.getAuditors() ?: []
        int auditorCount = auditors ? auditors.size() : 1
        int initialGlobalAuditorIndex = (auditorCount > 0)
                ? (usagesWithInitial.size() % auditorCount)
                : 0

        // Seed pcUsageIndex: a PC whose first usage already got an initial audit
        // must start pointing at the OTHER usage (e.g. "B" if "A" was just used).
        Map<String, List<String>> pcGroups = [:]
        questions_usages.keySet().each { String uk ->
            Issue pc = jqlSearcher.getPCFromQuestionUsage(uk as String, currentAuditLevel)
            if (pc) {
                pcGroups.computeIfAbsent(pc.key) { [] as List<String> } << (uk as String)
            }
        }
        Map<String, Integer> pcUsageIndex = [:]
        pcGroups.each { pcKey, usagesForPc ->
            usagesForPc.sort()
            boolean hadInitial = usagesForPc.any { usagesWithInitial.contains(it) }
            pcUsageIndex[pcKey] = (hadInitial && usagesForPc.size() > 0) ? (1 % usagesForPc.size()) : 0
        }

        def rotation = [
                questions_usages  : questions_usages,
                usageTurnIndex    : initialTurnIndex,
                globalAuditorIndex: initialGlobalAuditorIndex,
                pcUsageIndex      : pcUsageIndex
        ]

        questions_usages.each { key, data ->
            // Normalize rotation-units keys to canonical (migrate legacy workplaces/currentWorkplaceIndex if present).
            List<String> wps = RotationDataKeys.readUnits(data)
            RotationDataKeys.writeUnits(data, wps)

            // Usage with an initial audit already consumed fas[0]; start its cursor at 1.
            boolean hadInitial = usagesWithInitial.contains(key as String)
            int wpSize = wps.size()
            RotationDataKeys.writeIndex(data, (hadInitial && wpSize > 0) ? (1 % wpSize) : 0)
            data.currentAuditorIndex     = hadInitial ? 1 : 0   // not used by L5 scheduler, kept for JSON consistency
            data.currentCrossAuditorIndex = 0
            data.rotationCount            = 0
            data.crossAuditHistory        = []
            data.auditorHistory           = [:]

            logger.setInfoMessage(">> [INIT] ${key}: hadInitial=${hadInitial}, currentFaIndex=${data.currentFaIndex}, fas=${data.fas}")
            logger.setInfoMessage(">> [INIT] ${key}: globalAuditorIndex=${initialGlobalAuditorIndex}, auditors=${auditors}")
        }

        // Seed auditorHistory for initial-audit usages (auditor = auditors[k % auditorCount],
        // matching AuditHandlerBase.executeCreateAudit's assignment for the same entries).
        if (auditorCount > 0) {
            usagesWithInitialOrdered.eachWithIndex { String uk, int k ->
                def u = questions_usages[uk]
                if (u != null) {
                    String usedAuditor = auditors[k % auditorCount]
                    u.auditorHistory = [(usedAuditor): 1]
                }
            }
        }

        updateRotationData(JsonOutput.toJson(rotation))

        // Bug #2 guard: when target_start_date lands in the first half of a
        // month, getNextDate("monthly-mid") returns the 15th of the SAME month,
        // and the scheduler would create a second wave of audits in the same
        // calendar month as the initial audits — violating "max 1 audit per
        // PC / per auditor per month". Push to the 15th of the following month.
        LocalDate nextRotation = CommonHelper.getNextDate(startDate, effectiveInterval())
        if (nextRotation.year == startDate.year && nextRotation.monthValue == startDate.monthValue) {
            nextRotation = nextRotation.plusMonths(1).withDayOfMonth(15)
            logger.setInfoMessage("L5 init: nextRotation pushed to ${nextRotation} to avoid double-booking the initial month")
        }
        updateDateOfNextRotation(nextRotation)
        def apReload = new AuditPreparation(myBaseUtil.getIssueByKey(auditPreparationIssue.getIssue().getKey()))
        logger.setInfoMessage("AP ${apReload.getIssue().key} nextRotation advanced to ${apReload.getDate_of_next_rotation()}")
    }

    public void rotateOneAudit(String usageKey,
                               String nextFaKey,
                               String nextAuditor,
                               boolean isCrossAudit,
                               LocalDate rotationDay,
                               List<String> specialDue) {

        Issue pc = jqlSearcher.getPCFromQuestionUsage(usageKey, currentAuditLevel)

        def auditParams = new Audit(null).prepareAuditParams(auditPreparationIssue, pc.key, nextFaKey)
        //def auditParams = new Audit(null).prepareAuditParams(auditPreparationIssue, pc.key, null) //We dont store FA anymore
        def auditLevelOptionId = customFieldUtil.getOptionIdByValue(AuditPreparation.AUDIT_LEVEL_FIELD_NAME, auditPreparationIssue.getAuditLevel())
        def auditTypeOptionId = customFieldUtil.getOptionIdByValue(Audit.AUDIT_TYPE_FIELD_NAME, Audit.PLANNED)
        def reporterName = auditPreparationIssue.getIssue().reporter?.name ? auditPreparationIssue.getIssue().reporter?.name : loggedInUser.name

        def dateFormat = applicationProperties.getString(APKeys.JIRA_DATE_PICKER_JAVA_FORMAT) // napr. "dd.MM.yyyy"
        def fmt = DateTimeFormatter.ofPattern(dateFormat)
        // L5 audits must START on a Monday (mid-month). The 15th rotation anchor is
        // rarely a Monday, so snap Target start/end (and the ICS invite) to the
        // nearest Monday. The 15th stays the scheduling anchor (Date Of Next Rotation).
        LocalDate targetStartDay = CommonHelper.snapToNearestMonday(rotationDay)
        def rotationDayString = targetStartDay.format(fmt)

        if (!nextAuditor) {
            //nextAuditor = reporterName
            nextAuditor = auditPreparationIssue.getAuditors()?.getAt(0) ?: loggedInUser.name
        }

        auditParams.setAssigneeId(nextAuditor)
        auditParams.setReporterId(nextAuditor)
        auditParams.addCustomFieldValue(CustomFieldsConstants.getCustomFieldByName(AuditPreparation.AUDIT_LEVEL_FIELD_NAME)?.id, auditLevelOptionId?.toString())
        auditParams.addCustomFieldValue(CustomFieldsConstants.getCustomFieldByName(AuditPreparation.TARGET_START_FIELD_NAME)?.id, [rotationDayString] as String[]);
//auditPreparationIssue.getDate_targetStartAsDateTime())
        auditParams.addCustomFieldValue(CustomFieldsConstants.getCustomFieldByName(Audit.AUDIT_TYPE_FIELD_NAME)?.id, auditTypeOptionId?.toString())

        //Audit doesnt have allowed this custom field and also there needs to be put inside values from FA
        /*auditParams.addCustomFieldValue(
                CustomFieldsConstants.getCustomFieldByName(BaseIssue.KVS_PC_SUB_AREA_FIELD_NAME)?.getId(),
                [nextFaKey] as String[]
        )*/

        logger.setInfoMessage(">> [DEBUG] rotationDayString: $rotationDayString");

        IssueService.CreateValidationResult valid = validateCreate(auditParams, "Validate Level 5 Audit failed for $usageKey")
        def result = create(valid, "Create Level 5 Audit failed for $usageKey")

        def audit = new Audit(result.issue)
        audit.setAuditId()

        //region info for decription and summary
        audit.setInfoDescriptionAndSummary(pc.key, nextFaKey, usageKey, currentAuditLevel)
        //endregion

        audit.commitIssueUpdate(EventDispatchOption.DO_NOT_DISPATCH)

        // Target end = Target start + 4 days always applies; not gated on the AP's own Target start field.
        commonHelper.updateTargetEndDate(result.issue, targetStartDay.plusDays(CustomFieldsConstants.NUM_OF_DAYS_FOR_TARGET_END))

        commonHelper.verifyAndUpdateIssueMetadata(result.issue, nextAuditor, auditPreparationIssue.getIssue())

        new AuditMailer().sendAuditInviteAsICS(result.issue, nextAuditor,
                targetStartDay);//.plusDays(CustomFieldsConstants.NUM_OF_DAYS_FOR_TARGET_END))

        boolean skip = isCrossAudit;
        /*if (isCrossAudit) {
            //generate questions was used immediatelly
            //commonHelper.createQuestionsIssues(usageKey, result.issue, nextAuditor, [])
            skip = true;
        }*/

        logger.setInfoMessage("Level 5 Audit ${result.issue.key} created (subArea=${nextFaKey}, auditor=${nextAuditor}, cross=${isCrossAudit}) skip = ${skip}.")

        // New solution persist deferred question-generation payload for ToDo -> In Progress
        def payload = [
                usageKeys : [usageKey],
                //specialDue: specialDue ?: [],
                //specialDue: isCrossAudit ?: specialDue ?: [], // optional: pre-fill
                specialDue: isCrossAudit ? [] : (specialDue ?: []),
                skip      : skip,
        ]
        def cf = CustomFieldsConstants.getCustomFieldByName(Audit.KVS_GENERATION_PAYLOAD_FIELD_NAME)
        myBaseUtil.setCustomFieldValue(result.issue, cf, JsonOutput.toJson(payload))

        logger.setInfoMessage("Questions payload set for rotated Audit issue: " + result.issue.key + "payload:" + payload.toString());

        logger.setInfoMessage("Level 5 Audit ${result.issue.key} created (subArea=${nextFaKey}, auditor=${nextAuditor}, cross=${isCrossAudit})")
    }
}