package kvs_audits.audit3

import com.atlassian.jira.config.properties.APKeys
import com.atlassian.jira.event.type.EventDispatchOption

/*
No Workplaces: Level 3 audits focus on Functional Areas only, rather than workplace-level checks.
Rotation of Functional Areas: Instead of rotating among workplaces, the system should cycle through the list of FAs (e.g., FA1 → FA2 → FA3).
Rotation of Auditors: Same as on Level 2
* */

import com.atlassian.jira.issue.Issue
import groovy.json.JsonOutput
import kvs_audits.common.AuditHandlerBase
import kvs_audits.common.AuditMailer
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.issueType.Audit
import kvs_audits.issueType.AuditPreparation

import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AuditLevel3Handler extends AuditHandlerBase {

    public AuditLevel3Handler(Issue auditPrepIssue, String kvsProjectKey, String auditLevel) {
        super(auditPrepIssue, auditLevel, kvsProjectKey);
    }

    public void createAudit(List<String> questionUsage) {
        List<String> normalized = (questionUsage ?: []).findAll { it?.trim() }

        if (normalized.isEmpty()) {
            Issue profitCenter = customFieldUtil.getCustomFieldValueFromIssuePicker(auditPreparationIssue.getIssue(), AuditPreparation.PROFIT_CENTER_FIELD_NAME) as Issue

            if (!profitCenter) {
                logger.setErrorMessage("L3 createAudit: Profit Center not found on Audit Preparation ${auditPreparationIssue.getIssue().key}")
                return
            }

            List<String> faKeys = jqlSearcher.getFunctionalAreas(profitCenter)
            if (!faKeys || faKeys.isEmpty()) {
                logger.setErrorMessage("L3 createAudit: No Functional Areas found under ${profitCenter.key}")
                return
            }
            Issue firstFa = myBaseUtil.getIssueByKey(faKeys[0])

            String seedUsage = commonHelper.buildQuestionUsage(profitCenter, firstFa, CustomFieldsConstants.AUDIT_LEVEL_3)
            questionUsage = [seedUsage]

            logger.setInfoMessage("L3 createAudit: seeding rotation with usage=${seedUsage} and rotating across ${faKeys.size()} FAs under ${profitCenter.key}")
        }

        //logger.setWarnMessage("questionUsage is: "+$questionUsage)

        // for Level3: rotate across *all* FAs under the same profit center
        executeCreateAudit(questionUsage, { pc, fa -> jqlSearcher.getFunctionalAreas(pc) }, Audit.FUNCTIONAL_AREA_FIELD_ID)
    }

    public void rotateOneAudit(String questionUsageValue, def nextFaIssueKey, def nextAuditor, List<String> specialQuestionsDue, LocalDate rotationDay) {
        Issue profitCenter = jqlSearcher.getPCFromQuestionUsage(questionUsageValue, currentAuditLevel);
        Issue rotatedFA = myBaseUtil.getIssueByKey(nextFaIssueKey)
        if (!rotatedFA) {
            logger.setErrorMessage("Rotation failed: FA ${nextFaIssueKey} not found, skipping.")
            return
        }

        def auditParams = new Audit(null).prepareAuditParams(auditPreparationIssue, profitCenter.key, rotatedFA.getKey())
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
        auditParams.setReporterId(nextAuditor)//reporterName)
        auditParams.addCustomFieldValue(CustomFieldsConstants.getCustomFieldByName(AuditPreparation.AUDIT_LEVEL_FIELD_NAME)?.id, auditLevelOptionId?.toString())
        auditParams.addCustomFieldValue(CustomFieldsConstants.getCustomFieldByName(AuditPreparation.TARGET_START_FIELD_NAME)?.id, [rotationDayString] as String[]);//auditPreparationIssue.getDate_targetStartAsDateTime())
        auditParams.addCustomFieldValue(CustomFieldsConstants.getCustomFieldByName(Audit.AUDIT_TYPE_FIELD_NAME)?.id, auditTypeOptionId?.toString())
        auditParams.addCustomFieldValue(Audit.FUNCTIONAL_AREA_FIELD_ID, [rotatedFA.getKey()] as String[])

        def auditValidationResult = validateCreate(auditParams, "Failed to validate Audit creation for " + questionUsageValue)
        def auditResult = create(auditValidationResult, "Failed to create Audit issue for " + questionUsageValue)

        def audit = new Audit(auditResult.issue)
        audit.setAuditId();
        audit.commitIssueUpdate(EventDispatchOption.DO_NOT_DISPATCH)

        //add Target end date
        if (auditPreparationIssue.getDate_target_start()) {
            commonHelper.updateTargetEndDate(auditResult.issue, rotationDay.plusDays(CustomFieldsConstants.NUM_OF_DAYS_FOR_TARGET_END))
        }
        commonHelper.verifyAndUpdateIssueMetadata(auditResult.issue, nextAuditor, auditPreparationIssue.getIssue())

        new AuditMailer().sendAuditInviteAsICS(auditResult.issue, nextAuditor,
                rotationDay);//.plusDays(CustomFieldsConstants.NUM_OF_DAYS_FOR_TARGET_END))

        logger.setInfoMessage("Successfully rotate Audit issue: " + auditResult.issue.key);
        logger.setInfoMessage("Continue with creation of Questions...");

        //region update 29.7.2025 dynamic usage logic
        String dynamicUsageKey = commonHelper.buildQuestionUsage(profitCenter, rotatedFA, currentAuditLevel)
        String faKey = myBaseUtil.getCustomFieldValue(rotatedFA, CustomFieldsConstants.FUNCTIONAL_AREA_KEY)

        if (!faKey) {
            logger.setWarnMessage("Missing 'Functional Area Key' for FA ${rotatedFA.key}, cannot determine usage key for questions - so no questions generated for: '${auditResult.issue.key}'")
            return
        }

        logger.setInfoMessage("Using dynamic usageKey: ${dynamicUsageKey} for question generation")

        //generate questions was used immediatelly
        //commonHelper.createQuestionsIssues(dynamicUsageKey, auditResult.issue, nextAuditor, specialQuestionsDue)

        // New solution persist deferred question-generation payload for ToDo -> In Progress
        def payload = [
                usageKeys : [dynamicUsageKey],
                specialDue: (specialQuestionsDue ?: []),
                skip      : false
        ]
        def cf = CustomFieldsConstants.getCustomFieldByName(Audit.KVS_GENERATION_PAYLOAD_FIELD_NAME)
        myBaseUtil.setCustomFieldValue(auditResult.issue, cf, JsonOutput.toJson(payload))

        logger.setInfoMessage("Questions payload set for rotated Audit issue: " + auditResult.issue.key + "payload:" + payload.toString());
        //endregion

        //commonHelper.createQuestionsIssues(questionUsageValue, auditResult.getIssue(), nextAuditor, specialQuestionsDue)
        //logger.setInfoMessage("Questions created for rotated Audit issue: " + auditResult.issue.key);
    }
}