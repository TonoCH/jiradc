package jobs.sap.export

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.link.IssueLink
import com.atlassian.jira.issue.link.IssueLinkManager
import utils.MyBaseUtil

/**
 * IssueHierarchyResolver
 *
 * @author chabrecek.anton
 * Created on 29. 1. 2026.
 */
class IssueRelationsResolver {

    private final IssueLinkManager issueLinkManager = ComponentAccessor.getIssueLinkManager()
    private final CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager()
    private final def issueManager = ComponentAccessor.getIssueManager()

    private final CustomField cfEpicLink = customFieldManager.getCustomFieldObject("customfield_10001")
    private final CustomField cfParentLink = customFieldManager.getCustomFieldObject("customfield_10301")
    private final MyBaseUtil myBaseUtil = new MyBaseUtil()
    private static final String ISSUE_LINKING = "Hierarchy"
    private List<String> errors
    private List<String> messages

    IssueRelationsResolver(List<String> messages, List<String> errors) {
        this.messages = messages
        this.errors = errors
    }

    IssueRelations resolve(Issue currentIssue, int maxNodes = 30000) {
        if (currentIssue == null) return null

        def visited = new LinkedHashSet<Long>()
        def counter = [n: 0]

        return buildNode(currentIssue, null, visited, counter, maxNodes)
    }

    private IssueRelations buildNode(Issue currentIssue,
                                     Issue parentIssue,
                                     Set<Long> visited,
                                     Map counter,
                                     int maxNodes) {

        messages << "currentIssue is:"+currentIssue;

        if (currentIssue == null) return null
        if (counter.n >= maxNodes) {
            return new IssueRelations(currentIssue, parentIssue, new LinkedHashSet<IssueRelations>(), resolveLinked(currentIssue))
        }

        if (!visited.add(currentIssue.id)) {
            return new IssueRelations(currentIssue, parentIssue, new LinkedHashSet<IssueRelations>(), resolveLinked(currentIssue))
        }

        counter.n++

        def node = new IssueRelations(currentIssue, parentIssue, new LinkedHashSet<IssueRelations>(), resolveLinked(currentIssue))

        def childIssues = resolveChildrenIssues(currentIssue)

        childIssues.each { Issue ch ->
            if (ch == null) return
            def chNode = buildNode(ch, currentIssue, visited, counter, maxNodes)
            if (chNode != null) node.addChild(chNode)
        }

        return node
    }

    private List<Issue> resolveChildrenIssues(Issue issue) {
        def out = [] as List<Issue>
        if (issue == null) return out

        out.addAll(issue.subTaskObjects ?: [])

        if (cfEpicLink) {
            def epicLinkIssues = myBaseUtil.findIssues("\"Epic Link\" = \"${issue.key}\"")
            messages << "\"Epic Link\" = \"${issue.key}\" was result: ${epicLinkIssues.size()}"
            out.addAll(epicLinkIssues)
        }
        if (cfParentLink) {
            def parentLinkIssues = myBaseUtil.findIssues("\"Parent Link\" = \"${issue.key}\"")
            messages << "\"Parent Link\" = \"${issue.key}\" was result: ${parentLinkIssues.size()}"
            out.addAll(parentLinkIssues)
        }

        out.addAll(findChildViaIssueLinks(issue))

        // de-dup
        return out.findAll { it != null }.unique { it.id }
    }

    private List<Issue> findChildViaIssueLinks(Issue issue) {
        def links = [] as List<IssueLink>
        links.addAll(issueLinkManager.getOutwardLinks(issue.id) ?: [])
        links.addAll(issueLinkManager.getInwardLinks(issue.id) ?: [])

        messages << "\"links\" is \"${links.toString()}"

        links.findAll { it.issueLinkType?.name == ISSUE_LINKING }
                .collect { IssueLink l ->
                    l.sourceObject?.id == issue.id ? l.destinationObject : l.sourceObject
                }
                .findAll { it != null }
                .unique { it.id }
    }

    private List<Issue> resolveLinked(Issue issue) {
        def links = [] as List<IssueLink>
        links.addAll(issueLinkManager.getOutwardLinks(issue.id) ?: [])
        links.addAll(issueLinkManager.getInwardLinks(issue.id) ?: [])

        links = links.findAll { it.issueLinkType?.name != ISSUE_LINKING }

        links.collect { it.sourceObject?.id == issue.id ? it.destinationObject : it.sourceObject }
                .findAll { it != null }
                .unique { it.id }
    }

    Issue resolveParentIssue(Issue issue) {
        if (issue == null) return null
        if (issue.isSubTask()) return issue.parentObject

        def epicKey = cfEpicLink ? issue.getCustomFieldValue(cfEpicLink)?.toString() : null
        if (epicKey) {
            def epic = issueManager.getIssueByCurrentKey(epicKey)
            if (epic) return epic
        }

        def parentKey = cfParentLink ? issue.getCustomFieldValue(cfParentLink)?.toString() : null
        if (parentKey) {
            def p = issueManager.getIssueByCurrentKey(parentKey)
            if (p) return p
        }

        // parent via inward Parent/Child
        def inward = issueLinkManager.getInwardLinks(issue.id) ?: []
        def p2 = inward.findAll { it.issueLinkType?.name == ISSUE_LINKING }
                .collect { it.sourceObject }
                .find { it != null }
        return p2
    }
}