package kvs_audits.audit4

import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.config.properties.APKeys
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.Issue
import groovy.json.JsonOutput
import kvs_audits.common.AuditHandlerBase
import kvs_audits.common.AuditMailer
import kvs_audits.common.CommonHelper
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.issueType.Audit
import kvs_audits.issueType.AuditPreparation
import kvs_audits.issueType.BaseIssue

import java.sql.Timestamp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AuditLevel4Handler extends AuditHandlerBase{

    public AuditLevel4Handler(Issue auditPrepIssue, String kvsProjectKey, String auditLevel) {
        super(auditPrepIssue, auditLevel, kvsProjectKey);
    }

    public void createAudit(List<String> questionUsages) {

        List<String> seed = (questionUsages ?: []).findAll { it?.trim() }

        if (seed.isEmpty()) {
            Issue profitCenter = customFieldUtil.getCustomFieldValueFromIssuePicker(auditPreparationIssue.getIssue(),AuditPreparation.PROFIT_CENTER_FIELD_NAME) as Issue

            if (!profitCenter) {
                logger.setErrorMessage("L4 createAudit: Profit Center not found on Audit Preparation ${auditPreparationIssue.getIssue().key}")
                return
            }

            String pcCode = (myBaseUtil.getCustomFieldValue(profitCenter, CustomFieldsConstants.PROFIT_CENTER_KEY) ?: "") as String
            logger.setInfoMessage("L4 createAudit: resolved PC key $pcCode")


            List<String> allLevel4Usages = commonHelper.getQuestionUsageValuesForLevel(CustomFieldsConstants.AUDIT_LEVEL_4) ?: []
            logger.setWarnMessage("allLevel4Usages "+allLevel4Usages)

            List<String> candidates = allLevel4Usages.findAll { u -> u && u.split('_')[0] == pcCode }

            List<String> derived = []
            candidates.each { u ->
                String[] parts = u.split('_')
                boolean hasAB = (parts.size() > 2)

                if (hasAB) {
                    derived << u
                } else {
                    List<String> aKeys = jqlSearcher.getFunctionalAreasKeys(profitCenter, "A") ?: []
                    List<String> bKeys = jqlSearcher.getFunctionalAreasKeys(profitCenter, "B") ?: []
                    boolean onlyA = aKeys && !bKeys
                    boolean onlyB = bKeys && !aKeys

                    if (onlyA) {
                        derived << "${pcCode}_A_Level_4"
                        logger.setInfoMessage("L4 createAudit: '${u}' -> 'A' -> ${derived[-1]}")
                    } else if (onlyB) {
                        derived << "${pcCode}_B_Level_4"
                        logger.setInfoMessage("L4 createAudit: '${u}' -> 'B' -> ${derived[-1]}")
                    } else {
                        logger.setWarnMessage("L4 createAudit: PC '${pcCode}' has A and B; usage withou A/B ('${u}') skip it.")
                    }
                }
            }

            logger.setWarnMessage("derived "+derived)

            if (!derived) {
                logger.setWarnMessage("L4 createAudit: No Question Usage values found for Profit Center ${pcIssue.key} at Level 4.")
                return
            }

            questionUsages = derived.unique()
            logger.setInfoMessage("L4 createAudit: Derived usages for ${profitCenter.key}: ${questionUsages}")
        }

        executeCreateAudit(
                questionUsages,
                { pc, fa -> jqlSearcher.getSubAreas(pc) },
                BaseIssue.KVS_PC_SUB_AREA_FIELD_ID
        )
    }

    @Override
    protected void initializeRotationData(Map<String, Map<String, Object>> questions_usages, int initialTurnIndex = 0) {

        //CHANGES 8.8.25 no longer persist the cross‑auditor pool in the rotation JSON
        LocalDate startDate = Optional.ofNullable(auditPreparationIssue.getDate_target_start())
                .map { ts ->
                    ts instanceof Timestamp ? ts.toLocalDate() : ts.toLocalDate()
                }
                .orElse(LocalDate.now())
        def rotation =
                [
                        questions_usages: questions_usages,
                        usageTurnIndex  : initialTurnIndex,
                        globalAuditorIndex: 0
                ]
        //def external = auditPreparationIssue.getExternalAuditors()

        questions_usages.each { key, data ->
            //data.subAreas = data.workplaces          // rename
            //data.currentSubAreaIndex = 0
            data.workplaces            = data.workplaces
            data.currentWorkplaceIndex    = 0
            data.currentAuditorIndex   = 0
            //data.crossAuditors         = external
            data.currentCrossAuditorIndex = 0
            data.rotationCount         = 0
            data.crossAuditHistory     = []

            logger.setInfoMessage(">> [INIT] ${key}: currentWorkplaceIndex=${data.currentWorkplaceIndex}, workplaces=${data.workplaces}")
            logger.setInfoMessage(">> [INIT] ${key}: currentAuditorIndex=${data.currentAuditorIndex}, auditors=${data.auditors}")

            //data.remove('workplaces')
        }

        updateRotationData(JsonOutput.toJson(rotation))
        updateDateOfNextRotation(CommonHelper.getNextDate(startDate, auditPreparationIssue.getInterval()))
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
        def fmt        = DateTimeFormatter.ofPattern(dateFormat)
        def rotationDayString = rotationDay.format(fmt)

        if (!nextAuditor) {
            //nextAuditor = reporterName
            nextAuditor = auditPreparationIssue.getAuditors()?.getAt(0) ?: loggedInUser.name
        }

        auditParams.setAssigneeId(nextAuditor)
        auditParams.setReporterId(nextAuditor)
        auditParams.addCustomFieldValue(CustomFieldsConstants.getCustomFieldByName(AuditPreparation.AUDIT_LEVEL_FIELD_NAME)?.id, auditLevelOptionId?.toString())
        auditParams.addCustomFieldValue(CustomFieldsConstants.getCustomFieldByName(AuditPreparation.TARGET_START_FIELD_NAME)?.id, [rotationDayString] as String[]);//auditPreparationIssue.getDate_targetStartAsDateTime())
        auditParams.addCustomFieldValue(CustomFieldsConstants.getCustomFieldByName(Audit.AUDIT_TYPE_FIELD_NAME)?.id, auditTypeOptionId?.toString())

        //Audit doesnt have allowed this custom field and also there needs to be put inside values from FA
        /*auditParams.addCustomFieldValue(
                CustomFieldsConstants.getCustomFieldByName(BaseIssue.KVS_PC_SUB_AREA_FIELD_NAME)?.getId(),
                [nextFaKey] as String[]
        )*/

        logger.setInfoMessage(">> [DEBUG] rotationDayString: $rotationDayString");

        IssueService.CreateValidationResult valid = validateCreate(auditParams, "Validate Level 4 Audit failed for $usageKey")
        def result = create(valid, "Create Level 4 Audit failed for $usageKey")

        def audit = new Audit(result.issue)
        audit.setAuditId()
        audit.commitIssueUpdate(EventDispatchOption.DO_NOT_DISPATCH)

        if (auditPreparationIssue.getDate_target_start()) {
            commonHelper.updateTargetEndDate(result.issue, rotationDay.plusDays(CustomFieldsConstants.NUM_OF_DAYS_FOR_TARGET_END))
        }

        commonHelper.verifyAndUpdateIssueMetadata(result.issue, nextAuditor, auditPreparationIssue.getIssue())

        new AuditMailer().sendAuditInviteAsICS(result.issue, nextAuditor,
                rotationDay);//.plusDays(CustomFieldsConstants.NUM_OF_DAYS_FOR_TARGET_END))

        boolean skip = isCrossAudit;
        /*if (isCrossAudit) {
            //generate questions was used immediatelly
            //commonHelper.createQuestionsIssues(usageKey, result.issue, nextAuditor, [])
            skip = true;
        }*/

        logger.setInfoMessage("Level 4 Audit ${result.issue.key} created (subArea=${nextFaKey}, auditor=${nextAuditor}, cross=${isCrossAudit}) skip = ${skip}.")

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

        logger.setInfoMessage("Questions payload set for rotated Audit issue: " + result.issue.key + "payload:"+payload.toString());

        logger.setInfoMessage("Level 4 Audit ${result.issue.key} created (subArea=${nextFaKey}, auditor=${nextAuditor}, cross=${isCrossAudit})")
    }

}