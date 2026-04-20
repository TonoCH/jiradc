package jobs.sap.export

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.worklog.WorklogManager

import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * BaseWorklogCsvExporter
 *
 * @author chabrecek.anton
 * Created on 23. 2. 2026.
 */
abstract class BaseWorklogCsvExporter {

    protected final WorklogManager worklogManager = ComponentAccessor.getWorklogManager()
    protected final def customFieldManager = ComponentAccessor.getCustomFieldManager()

    protected final IssueRelationsResolver issueHierarchyResolver
    protected final CustomField cfTargetStart
    protected final CustomField cfTargetEnd

    protected final DateTimeFormatter d = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    protected final List<String> messages
    protected final List<String> errors
    protected final boolean debug = false

    BaseWorklogCsvExporter(List<String> messages, List<String> errors) {
        this.messages = messages
        this.errors = errors
        this.issueHierarchyResolver = new IssueRelationsResolver(messages, errors)
        this.cfTargetStart = customFieldManager.getCustomFieldObject("customfield_10700")
        this.cfTargetEnd = customFieldManager.getCustomFieldObject("customfield_10701")
    }

    /**
     * Human label of the root level (e.g. "Initiative", "Epic") for messages/logs.
     */
    protected abstract String getRootLabel()

    /**
     * Allowed root issue type names (e.g. Initiative / Objective, or Epic / Requirement etc.).
     */
    protected abstract Set<String> getAllowedRootTypes()

    /**
     * Generic entry point: export CSV for a collection of root issues.
     */
    String exportCsv(Collection<Issue> roots, String jiraBaseUrl) {
        def rows = [] as List<WorklogExportRow>

        if (!roots) {
            messages << "No ${rootLabel}s provided"
            return toCsv(rows)
        }

        roots.each { Issue root ->
            try {
                if (!root) {
                    errors << "Null issue in ${rootLabel.toLowerCase()} collection"
                    return
                }

                def typeName = root.issueType?.name
                if (!getAllowedRootTypes().contains(typeName)) {
                    messages << "${root.key} skipped (issueType=${typeName} not in ${getAllowedRootTypes()})"
                    return
                }

                def allIssues = collectIssuesHierarchyDown(root)
                if (!allIssues) {
                    messages << "${root.key} has no issues in hierarchy"
                    return
                }

                def uniqueIssues = allIssues.findAll { it != null }.unique { it.id }

                for (Issue issue in uniqueIssues) {
                    if (!shouldExport(issue)) continue
                    try {
                        rows << toExportRow(root, issue, jiraBaseUrl)
                    } catch (Exception e) {
                        errors << "Row build failed for ${root.key} -> ${issue?.key}: ${e.class.simpleName}: ${e.message}"
                    }
                }
            } catch (Exception e) {
                errors << "${rootLabel} processing failed for ${root?.key}: ${e.class.simpleName}: ${e.message}"
            }
        }

        if (!rows) {
            messages << "No issues matched export condition for provided ${rootLabel.toLowerCase()}s"
        }
        toCsv(rows)
    }

    protected boolean shouldExport(Issue issue) {
        if (!issue) return false

        def statusCategoryKey = issue.status?.statusCategory?.key
        if ((statusCategoryKey ?: "").equalsIgnoreCase("done")) return false

        long originalEstimate = (issue.originalEstimate ?: 0L)
        long remainingEstimate = (issue.estimate ?: 0L)
        long timespent = (issue.timeSpent ?: 0L)

        return (originalEstimate != 0L || remainingEstimate != 0L || timespent != 0L)
    }

    protected List<Issue> collectIssuesHierarchyDown(Issue root) {
        if (!root) return []

        IssueRelations tree
        try {
            tree = issueHierarchyResolver.resolve(root)
        } catch (Exception e) {
            errors << "Hierarchy resolve failed for ${root?.key}: ${e.class.simpleName}: ${e.message}"
            return []
        }

        if (debug) debugHierarchyTree(tree, messages, errors)

        def out = [] as List<Issue>
        def visited = new LinkedHashSet<Long>()
        collectIssuesFromTree(tree, visited, out)
        out
    }

    protected void collectIssuesFromTree(IssueRelations node, Set<Long> visited, List<Issue> out) {
        if (node?.currentIssue == null) return
        if (!visited.add(node.currentIssue.id)) return

        out << node.currentIssue
        (node.children ?: []).each { IssueRelations ch -> collectIssuesFromTree(ch, visited, out) }
    }

    protected WorklogExportRow toExportRow(Issue root, Issue issue, String jiraBaseUrl) {
        def assignee = issue.assignee?.displayName ?: issue.assignee?.name

        long timeSpent = issue.timeSpent ?: 0L
        long originalEstimate = issue.originalEstimate ?: 0L
        long remainingEstimate = issue.estimate ?: 0L

        def targetStart = formatDateField(issue, cfTargetStart)
        def targetEnd = formatDateField(issue, cfTargetEnd)

        new WorklogExportRow(
                (WorklogExportRow.PROP_RELATED_INITIATIVE_KEY): root.key, // top-level node (Initiative or Epic)
                (WorklogExportRow.PROP_ISSUE_LINK): jiraBaseUrl?.endsWith("/") ?
                        (jiraBaseUrl + "browse/" + issue.key) :
                        (jiraBaseUrl + "/browse/" + issue.key),
                (WorklogExportRow.PROP_ISSUE_KEY): issue.key,
                (WorklogExportRow.PROP_ISSUE_TYPE): issue.issueType?.name,
                (WorklogExportRow.PROP_ASSIGNEE): assignee,
                (WorklogExportRow.PROP_ORIGINAL_ESTIMATE_SECONDS): originalEstimate,
                (WorklogExportRow.PROP_REMAINING_ESTIMATE_SECONDS): remainingEstimate,
                (WorklogExportRow.PROP_TIME_SPENT_SECONDS): timeSpent,
                (WorklogExportRow.PROP_TARGET_START): targetStart,
                (WorklogExportRow.PROP_TARGET_END): targetEnd
        )
    }

    protected String formatDateField(Issue issue, CustomField customField) {
        if (!customField) return null
        def v = issue.getCustomFieldValue(customField)
        if (v == null) return null

        if (v instanceof Date) {
            def zone = ZoneId.systemDefault()
            return d.format(((Date) v).toInstant().atZone(zone).toLocalDate())
        }
        v.toString()
    }

    protected String toCsv(List<WorklogExportRow> rows) {
        def sb = new StringBuilder()
        sb.append(line(WorklogExportRow.csvHeader()))
        rows.each { sb.append(line(it.toCsvValues())) }
        sb.toString()
    }

    protected String line(List<String> values) {
        values.collect { csv(it) }.join(";") + "\n"
    }

    protected String csv(String v) {
        if (v == null) return ""
        def s = v.toString()
        def needs = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") || s.contains(";")
        if (!needs) return s
        "\"" + s.replace("\"", "\"\"") + "\""
    }

    // -------- debug region (copied from your Initiative exporter) --------

    protected void debugHierarchyTree(IssueRelations rootNode, List<String> messages, List<String> errors, int maxNodes = 5000) {
        if (rootNode == null || rootNode.currentIssue == null) {
            messages << "DEBUG: root node is null"
            return
        }

        messages << "DEBUG: issueKey, issueType, children, linked, worklogs, parentIssueKey"

        try {
            def path = new LinkedHashSet<Long>()
            def counter = [n: 0]
            dumpNode(rootNode, 0, path, counter, maxNodes, messages)
            if (counter.n >= maxNodes) {
                messages << ("DEBUG: stopped at maxNodes=${maxNodes}" as String)
            }
        } catch (Exception e) {
            errors << ("DEBUG: tree dump failed for ${rootNode?.currentIssue?.key}: ${e.class.simpleName}: ${e.message}" as String)
        }
    }

    protected void dumpNode(IssueRelations node, int depth, Set<Long> path, Map counter, int maxNodes, List<String> messages) {
        if (node == null || node.currentIssue == null) return
        if (counter.n >= maxNodes) return

        def issue = node.currentIssue

        if (path.contains(issue.id)) {
            def indent = "\t" * Math.min(depth, 20)
            messages << ("DEBUG: ${indent}${issue.key}, ${issue.issueType?.name ?: ''}, CYCLE, , , ${node.parent?.key ?: ''}" as String)
            return
        }

        counter.n++

        def indent = "\t" * Math.min(depth, 20)
        def wlCount = safeWorklogCount(issue)
        def chCount = (node.children ?: []).size()
        def lCount = (node.linked ?: []).size()

        messages << ("DEBUG: ${indent}${issue.key}, ${issue.issueType?.name ?: ''}, ${chCount}, ${lCount}, ${wlCount}, ${node.parent?.key ?: ''}" as String)

        path.add(issue.id)

        def kids = sortChildNodes(node.children ?: [] as Set<IssueRelations>)
        kids.each { IssueRelations ch ->
            dumpNode(ch, depth + 1, path, counter, maxNodes, messages)
        }

        path.remove(issue.id)
    }

    protected List<IssueRelations> sortChildNodes(Set<IssueRelations> kids) {
        def list = (kids ?: [] as Set<IssueRelations>).toList()

        def rank = { IssueRelations n ->
            def t = (n?.currentIssue?.issueType?.name ?: "").toLowerCase()
            if (t.contains("initiative")) return 10
            if (t.contains("feature")) return 20
            if (t.contains("epic")) return 30
            if (t.contains("story") || t.contains("task") || t.contains("bug")) return 40
            if (t.contains("sub-task") || t.contains("subtask")) return 50
            return 60
        }

        list.sort { a, b ->
            def ra = rank(a); def rb = rank(b)
            if (ra != rb) return ra <=> rb
            return (a?.currentIssue?.key ?: "") <=> (b?.currentIssue?.key ?: "")
        }
        return list
    }

    protected int safeWorklogCount(Issue issue) {
        try {
            def wls = worklogManager.getByIssue(issue) ?: []
            return wls.size()
        } catch (Exception ignored) {
            return -1
        }
    }
}
