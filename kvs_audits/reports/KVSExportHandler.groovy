package kvs_audits.reports

import com.atlassian.jira.issue.Issue
import com.atlassian.jira.component.ComponentAccessor
import groovy.json.JsonOutput
import kvs_audits.KVSLogger
import kvs_audits.issueType.Audit
import utils.CustomFieldUtil
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.MutableIssue
import kvs_audits.KVSLogger

/**
 * KVSExportHandler
 *
 * @author chabrecek.anton
 * Created on 13/05/2025.
 */
class KVSExportHandler {

    private CustomFieldUtil customFieldUtil = new CustomFieldUtil();
    protected KVSLogger logger = new KVSLogger()

    def exportToCustomField(MutableIssue auditPrep, Map<String, Object> kpiData, String customFieldName) {
        CustomField customField = customFieldUtil.getCustomFieldByName(customFieldName);
        //def customField = ComponentAccessor.getCustomFieldManager().getCustomFieldObjectByName(customFieldName)
        if (!customField) {
            logger.setErrorMessage("Custom field '${customFieldName}' not found")
            throw new IllegalStateException("Custom field '${customFieldName}' not found")
        }

        def json = JsonOutput.prettyPrint(JsonOutput.toJson(kpiData))
        auditPrep.setCustomFieldValue(customField, json)
        ComponentAccessor.issueManager.updateIssue(
                ComponentAccessor.jiraAuthenticationContext.loggedInUser,
                auditPrep,
                com.atlassian.jira.event.type.EventDispatchOption.ISSUE_UPDATED,
                false
        )
        logger.setInfoMessage("KPI export to custom fields done")

    }

    def generateCSV(Map<String, Object> kpiData) {
        def lines = []
        lines << "Kategorie,KVS-Performance"
        kpiData.performanceByCategory.each { k, v ->
            lines << "${k},${(v * 100).round(2)}%"
        }
        lines << "Gesamt,${(kpiData.performanceTotal * 100).round(2)}%"
        return lines.join("\n")
    }
}
