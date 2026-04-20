import com.atlassian.jira.issue.Issue
import kvs_audits.issueType.Audit
import utils.MyBaseUtil

getJql = { Issue issue, String configuredJql ->
    Issue auditIssue = issue.getParentObject()
    if (auditIssue) {
        Audit audit = new Audit(auditIssue)
        Issue profitCenter = audit.getProfitCenter()
        String profitCenterKey = profitCenter.getKey()

        MyBaseUtil myBaseUtil = new MyBaseUtil()

        String faJql = "project = KVSPC AND issuetype = 'Functional Areas' AND 'Profit Center' = ${profitCenterKey}"
        List<Issue> functionalAreas = myBaseUtil.findIssues(faJql)

        List<String> issueKeys = [profitCenterKey] + functionalAreas.collect { it.key }

        functionalAreas.each { faIssue ->
            issueKeys.addAll(
                    faIssue.getSubTaskObjects()
                            .findAll { it.issueType.name == 'Workplace' }
                            .collect { it.key }
            )
        }

        String keysForJql = issueKeys.collect { "\"${it}\"" }.join(", ")

        return "key in (${keysForJql})"
    }

    //def
    return 'project = KVSPC'// AND ("Field Auditors" in (currentUser()) OR reporter = currentUser() OR assignee = currentUser()) AND (issuetype != Question AND issuetype != Measure)'
}