package jobs.sap.export

import com.atlassian.jira.issue.Issue;

/**
 * IssueHierarchy
 *
 * @author chabrecek.anton
 * Created on 28. 1. 2026.
 */
class IssueRelations {

    final Issue currentIssue
    final Issue parent
    final Set<IssueRelations> children
    final List<Issue> linked

    IssueRelations(Issue currentIssue,
                   Issue parent = null,
                   Set<IssueRelations> children = null,
                   List<Issue> linked = null) {
        this.currentIssue = currentIssue
        this.parent = parent
        this.children = (children ?: new LinkedHashSet<IssueRelations>())
        this.linked = (linked ?: [])
    }

    boolean hasParent() { parent != null }
    boolean hasChildren() { children != null && !children.isEmpty() }
    boolean hasLinked() { linked != null && !linked.isEmpty() }

    void addChild(IssueRelations child) {
        if (child == null || child.currentIssue == null) return
        children.add(child)
    }

    void addChildIssue(Issue childIssue) {
        if (childIssue == null) return
        children.add(new IssueRelations(childIssue, this.currentIssue))
    }
}
