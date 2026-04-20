package fields.kvs

import com.atlassian.jira.issue.Issue
import kvs_audits.issueType.Audit
import utils.MyBaseUtil

/**
 * workplaces
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

            // If a Functional Area is already selected, restrict WPs to that FA only.
            // Otherwise show all WPs under any FA of this Profit Center.
            Issue selectedFa = audit.getFunctionalArea()

            List<Issue> functionalAreas
            if (selectedFa != null) {
                functionalAreas = [selectedFa]
            } else {
                String faJql = "project = KVSPC AND issuetype = 'Functional Areas' AND 'Profit Center' = ${profitCenterKey}"
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
    }

    return 'project = KVSPC AND issuetype = "Workplace"'
}