package fields.kvs

import com.atlassian.jira.issue.Issue
import kvs_audits.issueType.Audit
import utils.CustomFieldUtil
import utils.MyBaseUtil

/**
 * workplaces
 *
 * @author chabrecek.anton
 * Created on 03/06/2025.
 */

getJql = { Issue issue, String configuredJql ->
    if (issue) {
        MyBaseUtil myBaseUtil = new MyBaseUtil()
        def customFieldUtil = new CustomFieldUtil()

        Issue profitCenter = customFieldUtil.getCustomFieldValueFromIssuePicker(
                issue,
                Audit.PROFIT_CENTER_FIELD_NAME
        ) as Issue

        Issue selectedFa = customFieldUtil.getCustomFieldValueFromIssuePicker(
                issue,
                Audit.FUNCTIONAL_AREA_FIELD_NAME
        ) as Issue


        if (!profitCenter || !profitCenter.key) {
            return 'project = KVSPC AND issuetype = "Workplace"'
        }

        String profitCenterKey = profitCenter.key

        List<Issue> functionalAreas
        if (selectedFa != null) {
            functionalAreas = [selectedFa]
        } else {
            String faJql = """
        project = KVSPC 
        AND issuetype = 'Functional Areas' 
        AND "Profit Center" = "${profitCenterKey}"
    """
            functionalAreas = myBaseUtil.findIssues(faJql)
        }

        List<String> issueKeys = new ArrayList<>()

        functionalAreas.each { faIssue ->
            issueKeys.addAll(
                    faIssue.getSubTaskObjects()
                            .findAll { it.issueType.name == 'Workplace' }
                            .collect { it.key }
            )
        }

        if (issueKeys.isEmpty()) {
            return 'issueType = "Workplace" AND project = KVSPC AND issue = EMPTY'
        }

        String keysForJql = issueKeys.collect { "\"${it}\"" }.join(", ")

        return "key in (${keysForJql})"

    }

    return 'project = KVSPC AND issuetype = "Workplace"'
}