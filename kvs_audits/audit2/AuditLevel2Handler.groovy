package kvs_audits.audit2

import com.atlassian.jira.config.properties.APKeys
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.Issue
import groovy.json.JsonOutput
import kvs_audits.common.AuditHandlerBase
import kvs_audits.common.AuditMailer
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.issueType.Audit
import kvs_audits.issueType.AuditPreparation

import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AuditLevel2Handler extends AuditHandlerBase {

    public AuditLevel2Handler(Issue auditPrepIssue, String kvsProjectKey, String auditLevel) {
        super(auditPrepIssue, auditLevel, kvsProjectKey);
    }

    public void createAudit(List<String> questionUsage) {
        // for Level2: rotate workplaces under each FA
        executeCreateAudit(questionUsage, { pc, fa -> jqlSearcher.getWorkplaces(fa) }, Audit.WORKPLACES_FIELD_ID)
    }

    //scheduled job call this
    public void rotateOneAudit(String questionUsageValue, def nextWorkplaceIssueKey, def nextAuditor, List<String> specialQuestionsDue, def rotationDay) {
        Issue profitCenter = jqlSearcher.getPCFromQuestionUsage(questionUsageValue, currentAuditLevel);
        Issue functionalArea = jqlSearcher.getFA_FromQuestionUsage(questionUsageValue, profitCenter, currentAuditLevel);
        def reporterName = auditPreparationIssue.getIssue().reporter?.name ? auditPreparationIssue.getIssue().reporter?.name : loggedInUser.name

        def auditParams = new Audit(null).prepareAuditParams(auditPreparationIssue, profitCenter.key, functionalArea.key)
        def auditLevelOptionId = customFieldUtil.getOptionIdByValue(AuditPreparation.AUDIT_LEVEL_FIELD_NAME, auditPreparationIssue.getAuditLevel())
        def auditTypeOptionId = customFieldUtil.getOptionIdByValue(Audit.AUDIT_TYPE_FIELD_NAME, Audit.PLANNED)

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
        auditParams.addCustomFieldValue(CustomFieldsConstants.getCustomFieldByName(AuditPreparation.TARGET_START_FIELD_NAME)?.id, [rotationDayString] as String[])//auditPreparationIssue.getDate_targetStartAsDateTime())
        auditParams.addCustomFieldValue(CustomFieldsConstants.getCustomFieldByName(Audit.AUDIT_TYPE_FIELD_NAME)?.id, auditTypeOptionId?.toString())

        //add Target end date
        /*def targetEndDate = rotationDay.plusDays(CustomFieldsConstants.NUM_OF_DAYS_FOR_TARGET_END)
        def formattedDate = targetEndDate.toString() // LocalDate → String "yyyy-MM-dd"
        auditParams.addCustomFieldValue(Audit.TARGET_END_FIELD_NAME, [formattedDate] as String[])*/

        //check workplaces:
        def workplacesKeys = jqlSearcher.getWorkplaces(functionalArea);
        if(workplacesKeys.contains(nextWorkplaceIssueKey)){
            auditParams.addCustomFieldValue(Audit.WORKPLACES_FIELD_ID, [nextWorkplaceIssueKey] as String[])
            logger.setInfoMessage("Assigning the workplace '${nextWorkplaceIssueKey}' on initial Audit for usage=${questionUsageValue}")
        }
        else{
            logger.setErrorMessage("Workplace key $nextWorkplaceIssueKey was not found for Functional Area: $functionalArea.key so workplace was not set")
        }

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

        //generate questions was used immediatelly
        //commonHelper.createQuestionsIssues(questionUsageValue, auditResult.getIssue(), nextAuditor, specialQuestionsDue)

        // New solution persist deferred question-generation payload for ToDo -> In Progress
        def payload = [
                usageKeys : [questionUsageValue],
                specialDue: (specialQuestionsDue ?: []),
                skip      : false
        ]
        def cf = CustomFieldsConstants.getCustomFieldByName(Audit.KVS_GENERATION_PAYLOAD_FIELD_NAME)
        myBaseUtil.setCustomFieldValue(auditResult.issue, cf, JsonOutput.toJson(payload))


        logger.setInfoMessage("Questions payload set for rotated Audit issue: " + auditResult.issue.key + "payload:"+payload.toString());
    }
}