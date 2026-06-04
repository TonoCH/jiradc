package fields.kvs

import com.atlassian.jira.issue.Issue
import kvs_audits.issueType.Audit
import utils.CustomFieldUtil
import utils.MyBaseUtil

/**
 * functional_area
 *
 * @author chabrecek.anton
 * Created on 03/06/2025.
 */

getJql = { Issue issue, String configuredJql ->

    if (issue) {

        def customFieldUtil = new CustomFieldUtil()

        Issue profitCenter = customFieldUtil.getCustomFieldValueFromIssuePicker(
                issue,
                Audit.PROFIT_CENTER_FIELD_NAME
        ) as Issue

        if (profitCenter != null) {
            String profitCenterKey = profitCenter.key

            MyBaseUtil myBaseUtil = new MyBaseUtil()

            String faJql = """
                project = KVSPC 
                AND issuetype = 'Functional Areas' 
                AND "Profit Center" = "${profitCenterKey}"
            """

            List<Issue> functionalAreas = myBaseUtil.findIssues(faJql) ?: []

            if (functionalAreas.isEmpty()) {
                return 'issueType = "Functional Areas" AND project = KVSPC AND issue = EMPTY'
            }

            List<String> functionalAreaKeys = functionalAreas.collect { it.key }

            String keysForJql = functionalAreaKeys.collect { "\"${it}\"" }.join(", ")

            return "key in (${keysForJql})"
        }
    }

    return 'project = KVSPC AND issuetype = "Functional Areas"' //;'issueType = "Functional Areas" AND project = KVSPC AND issue = EMPTY'
}