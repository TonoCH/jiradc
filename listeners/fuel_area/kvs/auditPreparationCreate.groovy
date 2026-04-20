package listeners.fuel_area.kvs

import kvs_audits.KVSLogger;
import com.atlassian.jira.issue.MutableIssue
import kvs_audits.audit2.AuditLevel2Handler
import kvs_audits.audt_manual_unplanned.AuditManualUnplanned
import kvs_audits.common.CustomFieldsConstants
import utils.MyBaseUtil;
import kvs_audits.audit3.AuditLevel3Handler;
import kvs_audits.audit4.AuditLevel4Handler;
import kvs_audits.audit5.AuditLevel5Handler;
import utils.CustomFieldUtil;
import kvs_audits.issueType.Question
import kvs_audits.issueType.AuditPreparation

//ScriptRunnerImpl.getPlugin().clearCaches()
//log.info("ScriptRunner cache cleared successfully!")

KVSLogger logger = new KVSLogger();
CustomFieldUtil customFieldUtil = new CustomFieldUtil();
MutableIssue issue = (MutableIssue) event.getIssue()
MyBaseUtil myBaseUtil = new MyBaseUtil();
String kvsProjectKey = CustomFieldsConstants.PROJECT_KVS_AUDIT

if (issue.issueType.name == CustomFieldsConstants.AUDIT_PREPARATION) {

    logger.setInfoMessage("*************************************************************************************");
    logger.setInfoMessage("START process of generating project structure from issue ${CustomFieldsConstants.AUDIT_PREPARATION} issue:" + issue.key + "+");
    logger.setInfoMessage("*************************************************************************************+");
    logger.setInfoMessage("Check all fields names....")

    //ALL FIELDS IS CORRECT?
    myBaseUtil.validateFieldsNamesExist(AuditPreparation.AUDIT_PREPARATION_CUSTOM_FIELD_NAMES)
    myBaseUtil.validateFieldsNamesExist(Question.QUESTION_CUSTOM_FIELD_NAMES)
    logger.setInfoMessage("All fields names is correct")

    def auditLevel = myBaseUtil.getCustomFieldValue(issue, AuditPreparation.AUDIT_LEVEL_FIELD_NAME)
    List<String> questionUsage = customFieldUtil.getFieldValue_MultiSelectSearcher(
            myBaseUtil.getCustomFieldValue(issue, Question.QUESTION_USAGE_FIELD_NAME)
    );

    if (auditLevel == null) {
        logger.setErrorMessage(AuditPreparation.AUDIT_LEVEL_FIELD_NAME + " is missing.")
        return;
    }

    if (questionUsage == null) {
        logger.setErrorMessage(Question.QUESTION_USAGE_FIELD_NAME + " is missing.")
    }

    logger.setInfoMessage("Selected Audit level is: ${auditLevel}")
    logger.setInfoMessage("Selected " + Question.QUESTION_USAGE_FIELD_NAME + ":" + questionUsage.join(", "))

    switch (auditLevel) {
        case CustomFieldsConstants.AUDIT_LEVEL_2:
            AuditLevel2Handler auditLevel2IssueHelper = new AuditLevel2Handler(issue, kvsProjectKey, CustomFieldsConstants.AUDIT_LEVEL_2);
            auditLevel2IssueHelper.createAudit(questionUsage);
            break
        case CustomFieldsConstants.AUDIT_LEVEL_3:
            AuditLevel3Handler auditLevel3Handler = new AuditLevel3Handler(issue, kvsProjectKey, CustomFieldsConstants.AUDIT_LEVEL_3);
            auditLevel3Handler.createAudit(questionUsage);
            break
        case CustomFieldsConstants.AUDIT_LEVEL_4:
            AuditLevel4Handler auditLevel4Handler = new AuditLevel4Handler(issue, kvsProjectKey, CustomFieldsConstants.AUDIT_LEVEL_4);
            auditLevel4Handler.createAudit(questionUsage);
            break
        case CustomFieldsConstants.AUDIT_LEVEL_5:
            AuditLevel5Handler auditLevel5Handler = new AuditLevel5Handler(issue, kvsProjectKey, CustomFieldsConstants.AUDIT_LEVEL_5);
            auditLevel5Handler.createAudit(questionUsage);
            break
        default:
            break
            logger.setWarnMessage("Unknown Audit level: ${auditLevel}\n")

    }
}
else if (issue.issueType.name == CustomFieldsConstants.AUDIT) {

    logger.setInfoMessage("*************************************************************************************");
    logger.setInfoMessage("START process of generating question structure from issue ${CustomFieldsConstants.AUDIT} issue:" + issue.key + "+");
    logger.setInfoMessage("*************************************************************************************+");
    logger.setInfoMessage("Check all fields names....")

    //ALL FIELDS IS CORRECT?
    myBaseUtil.validateFieldsNamesExist(AuditPreparation.AUDIT_PREPARATION_CUSTOM_FIELD_NAMES)
    myBaseUtil.validateFieldsNamesExist(Question.QUESTION_CUSTOM_FIELD_NAMES)
    logger.setInfoMessage("All fields names is correct")
    
    new AuditManualUnplanned().handleIssueCreated(issue)
}
else {
    logger.setInfoMessage("Issue type ${issue.issueType.name} is not handled by this listener. Skipping.")
}