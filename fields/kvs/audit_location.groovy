import com.atlassian.jira.issue.Issue
import kvs_audits.issueType.Audit
import utils.CustomFieldUtil
import utils.MyBaseUtil

getJql = { Issue issue, String configuredJql ->

    Issue auditIssue = null

    //subtask (Question)
    if (issue.isSubTask()) {
        auditIssue = issue.getParentObject() ?: issue.parentObject
    }

    // Measure -> Question → Audit
    if (!auditIssue) {
        def inward = issue.getInwardLinks()
        def outward = issue.getOutwardLinks()

        def linkedIssues = []
        if (inward) linkedIssues.addAll(inward*.getSourceObject())
        if (outward) linkedIssues.addAll(outward*.getDestinationObject())

        Issue question = linkedIssues.find { it.issueType.name == "Question" }

        if (question) {
            auditIssue = question.getParentObject() ?: question.parentObject
        }
    }

    // final fallback
    if (!auditIssue) {
        return 'project = KVSPC'
    }

    //region new check on parent
    Audit audit = new Audit(auditIssue)
    Issue profitCenter = audit.getProfitCenter()

    if (!profitCenter) {
        def customFieldUtil = new CustomFieldUtil()
        profitCenter = customFieldUtil.getCustomFieldValueFromIssuePicker(
                auditIssue,
                Audit.PROFIT_CENTER_FIELD_NAME
        ) as Issue
    }

    if (!profitCenter || !profitCenter.key) {
        return 'project = KVSPC'
    }

    String profitCenterKey = profitCenter.key
    //endregion

    MyBaseUtil myBaseUtil = new MyBaseUtil()

    String faJql = """
        project = KVSPC
        AND issuetype = 'Functional Areas'
        AND "Profit Center" = "${profitCenterKey}"
    """

    List<Issue> functionalAreas = myBaseUtil.findIssues(faJql) ?: []

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