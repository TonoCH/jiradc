package fields.kvs

import com.atlassian.jira.issue.Issue
import kvs_audits.issueType.Audit
import utils.MyBaseUtil

/**
 * functional_area
 *
 * @author chabrecek.anton
 * Created on 03/06/2025.
 */

getJql = { Issue issue, String configuredJql ->
    if (issue) {

        Audit audit = new Audit(issue)

        Issue profitCenter = audit.getProfitCenter()
        if (profitCenter != null) {
            String profitCenterKey = profitCenter.getKey()

            MyBaseUtil myBaseUtil = new MyBaseUtil()

            String faJql = "project = KVSPC AND issuetype = 'Functional Areas' AND 'Profit Center' = ${profitCenterKey}"
            List<Issue> functionalAreas = myBaseUtil.findIssues(faJql)

            List<String> functionalAreaKeys = functionalAreas.collect { it.key }

            if (functionalAreaKeys.isEmpty()) {
                return 'issueType = "Functional Areas" AND project = KVSPC AND issue = EMPTY'
            }

            String keysForJql = functionalAreaKeys.collect { "\"${it}\"" }.join(", ")

            return "key in (${keysForJql})"
        }
    }

    return 'project = KVSPC AND issuetype = "Functional Areas"'
}