package fields.kvs

import com.atlassian.jira.issue.Issue
import kvs_audits.KVSLogger
import kvs_audits.common.CommonHelper
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.issueType.Audit
import kvs_audits.issueType.AuditPreparation
import kvs_audits.issueType.Question
import utils.MyBaseUtil

/**
 * questions
 *
 * @author chabrecek.anton
 * Created on 03/06/2025.
 */

getJql = { Issue issue, String configuredJql ->
    CommonHelper commonHelper = new CommonHelper()
    KVSLogger logger = new KVSLogger();

    def defaultQuery = 'project = "KVS Audit Questions" AND issuetype = "Question"';

    if (issue == null) {
        return defaultQuery
    }

    Audit audit = new Audit(issue)
    Issue profitCenter = audit.getProfitCenter()
    if (profitCenter == null) {
        return defaultQuery
    }

    Issue functionalArea = audit.getFunctionalArea()
    def selectedLevel = audit.getAuditLevel()
    if (!selectedLevel) {
        return defaultQuery
    }

    /*String questionUsage;

    return """
        project = "KVS Audit Questions" AND issuetype = "Question" AND "Question Usage" in ("${questionUsage}")
    """.stripIndent().trim()*/

    List<String> usages
    if (selectedLevel == CustomFieldsConstants.AUDIT_LEVEL_4) {
        usages = commonHelper.buildQuestionUsageKeys(profitCenter, functionalArea, selectedLevel) ?: []
    }
    else {
        def one = commonHelper.buildQuestionUsage(profitCenter, functionalArea, selectedLevel)
        usages = one ? [one] : []
    }

    if (!usages || usages.isEmpty()) return defaultQuery

    String inList = usages
            .findAll { it }
            .collect { v -> '"' + v.replace('"', '\\"') + '"' }
            .join(", ")

    String jql = """
        project = "KVS Audit Questions" AND issuetype = "Question" AND "Question Usage" in (${inList})
    """.stripIndent().trim()

    // Optionally AND with configuredJql
    if (configuredJql?.trim()) {
        jql += " AND (${configuredJql.trim()})"
    }

    return jql
}