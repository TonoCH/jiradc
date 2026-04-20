package workflows.kvs_audit

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import kvs_audits.KVSLogger
import kvs_audits.common.CommonHelper
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.issueType.Audit
import utils.MyBaseUtil
import com.atlassian.jira.component.ComponentAccessor

/**
 * audit_questions_gen
 *
 * @author chabrecek.anton
 * Created on 22. 8. 2025.
 *
 * Runs on transition ToDo -> In Progress for Audit issue
 */

CommonHelper commonHelper = new CommonHelper();
KVSLogger logger = new KVSLogger()
def audit = new Audit(issue)
def payloadCf = CustomFieldsConstants.getCustomFieldByName(Audit.KVS_GENERATION_PAYLOAD_FIELD_NAME)
def fieldData = new MyBaseUtil().getCustomFieldValue(audit.getIssue(), Audit.KVS_GENERATION_PAYLOAD_FIELD_NAME)

if (!issue || !CustomFieldsConstants.AUDIT.equalsIgnoreCase(issue.issueType?.name)) {
    log.warn("It is not $CustomFieldsConstants.AUDIT issue type so workflow do nothing - no Question generated")
    return true
}

if (!fieldData) {
    def pc = audit.getProfitCenter()
    def fa = audit.getFunctionalArea()
    def level = audit.getAuditLevel()

    List<String> usageKeys = commonHelper.buildQuestionUsageKeys(pc, fa, level)
    fieldData = JsonOutput.toJson([usageKeys: usageKeys, specialDue: [], skip: false])
}

def data = new JsonSlurper().parseText(fieldData)
if (data.skip == true) {
    logger.setWarnMessage("Cross-audit from Level 4 was set – questions generation skipped for ${issue.key}")
    return
}

def assignee = issue.assignee?.name ?: ComponentAccessor.jiraAuthenticationContext.loggedInUser?.name
int total = 0

// Generate questions
(data.usageKeys as List<String>).each { usage ->
    commonHelper.createQuestionsIssues(usage, issue, assignee, (data.specialDue as List<String>) )
    total++
}

// Clear payload to avoid duplicates
new MyBaseUtil().setCustomFieldValue(issue, payloadCf, null)
logger.setInfoMessage("Generated questions (${total}) usages for ${issue.key} on workflow ehen goest to step In Progress.")

//region check if question was generated before we cen disable multiple generation when todo - prograse
/*try {
    // Generate questions for each usage key
    (data.usageKeys as List<String>).each { String usage ->
        commonHelper.createQuestionsIssues(usage, issue, assignee, (data.specialDue as List<String>))
        total++
    }

    // --- IMPORTANT CHANGE ---
    // Persist payload with skip=true so repeated transitions won't regenerate questions
    def updatedPayload = [
            usageKeys : (data.usageKeys as List<String>),
            specialDue: (data.specialDue as List<String>),
            skip      : true
    ]
    myUtil.setCustomFieldValue(issue, payloadCf, JsonOutput.toJson(updatedPayload))

    logger.setInfoMessage("Generated questions (${total}) usages for ${issue.key} on workflow when moved to In Progress. Payload updated with skip=true.")
} catch (Throwable t) {
    // In case of failure, do NOT set skip=true, so generation can be retried after fix
    log.error("Questions generation failed for ${issue.key}: ${t.message}", t)
    logger.setErrorMessage("Questions generation failed for ${issue.key}: ${t.message}")
    throw t
}*/
