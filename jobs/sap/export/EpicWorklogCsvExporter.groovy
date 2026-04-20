package jobs.sap.export

import com.atlassian.jira.issue.Issue
import utils.IssueTypeHierarchy

/**
 * EpicWorklogCsvExporter
 *
 * @author chabrecek.anton
 * Created on 23. 2. 2026.
 * Exports worklogs for Epic-based hierarchy (e.g. Minor requirements).
 */

class EpicWorklogCsvExporter extends BaseWorklogCsvExporter {

    /**
     * Execution-level types under an Epic (plus Sub-task).
     * Taken from IssueTypeHierarchy: executionTypes + SUB_TASK.
     */
    private static final Set<String> ALLOWED_EXECUTION_TYPES = ([
            IssueTypeHierarchy.TASK,
            IssueTypeHierarchy.STORY,
            IssueTypeHierarchy.BUG,
            IssueTypeHierarchy.MONITORING_TASK,
            IssueTypeHierarchy.SUB_TASK
    ] as Set<String>)

    EpicWorklogCsvExporter(List<String> messages, List<String> errors) {
        super(messages, errors)
    }

    @Override
    protected String getRootLabel() {
        "Epic"
    }

    /**
     * We still keep this to validate the "root" issues from the filter.
     * These are all "epic-level" types in hierarchy.
     */
    @Override
    protected Set<String> getAllowedRootTypes() {
        [
                IssueTypeHierarchy.EPIC,
                IssueTypeHierarchy.REQUIREMENT,
                IssueTypeHierarchy.DESIGN_AND_ESTIMATE_SOLUTION,
                IssueTypeHierarchy.PROJECT
        ] as Set<String>
    }

    String exportCsvForEpics(Collection<Issue> epics, String jiraBaseUrl) {
        return exportCsv(epics, jiraBaseUrl)
    }

    /**
     *  - Allowed nodes below root:
     *    execution-level types (Task/Story/Bug/Monitoring Task/Sub-task)
     *  - We do NOT traverse through Feature / Initiative / other Epics.
     *
     * This prevents an Epic from "absorbing" siblings via its parent Feature,
     * which was the reason why ITSAP-103 pulled in ITSAP-188, ITSAP-151, etc.
     */
    @Override
    protected List<Issue> collectIssuesHierarchyDown(Issue root) {
        if (!root) return []

        IssueRelations tree
        try {
            tree = issueHierarchyResolver.resolve(root)
        } catch (Exception e) {
            errors << "Hierarchy resolve failed for ${root?.key}: ${e.class.simpleName}: ${e.message}"
            return []
        }

        if (debug) {
            debugHierarchyTree(tree, messages, errors)
        }

        List<Issue> out = [] as List<Issue>
        Set<Long> visited = new LinkedHashSet<Long>()
        collectEpicTree(tree, visited, out, root)
        return out
    }

    /**
     * Epic-specific traversal. Only:
     *  - root epic (or epic-level type) itself
     *  - + execution-level children (tasks, stories, bugs, monitoring tasks, subtasks).
     */
    private void collectEpicTree(IssueRelations node,
                                 Set<Long> visited,
                                 List<Issue> out,
                                 Issue root) {
        if (node?.currentIssue == null) {
            return
        }
        if (!visited.add(node.currentIssue.id)) {
            return
        }

        Issue cur = node.currentIssue
        String typeName = (cur.issueType?.name ?: "")

        boolean isRoot = (cur.key == root.key)

        // Root is always included (Epic / Requirement / Project at epic level)
        if (isRoot) {
            out << cur
        } else {
            // For non-root nodes, only execution-level types are allowed under Epic.
            if (!ALLOWED_EXECUTION_TYPES.contains(typeName)) {
                return
            }
            out << cur
        }

        // Traverse children recursively, but only through allowed nodes.
        (node.children ?: [] as Set<IssueRelations>).each { IssueRelations ch ->
            collectEpicTree(ch, visited, out, root)
        }
    }
}
