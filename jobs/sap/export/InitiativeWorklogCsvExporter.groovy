package jobs.sap.export

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.worklog.WorklogManager
import com.atlassian.jira.issue.CustomFieldManager
import utils.IssueTypeHierarchy

import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * InitiativeWorklogCsvExporter
 *
 * @author chabrecek.anton
 * Created on 28. 1. 2026.
 */
class InitiativeWorklogCsvExporter extends BaseWorklogCsvExporter {

    InitiativeWorklogCsvExporter(List<String> messages, List<String> errors) {
        super(messages, errors)
    }

    @Override
    protected String getRootLabel() {
        "Initiative"
    }

    @Override
    protected Set<String> getAllowedRootTypes() {
        [
                IssueTypeHierarchy.INITIATIVE,
                IssueTypeHierarchy.LPM_INITIATIVE,
                IssueTypeHierarchy.BID_PROJECT,
                IssueTypeHierarchy.OBJECTIVE
        ] as Set<String>
    }

    /**
     * Backward-compatible entry point used by initiativeWorklogExport.groovy.
     */
    String exportCsvForInitiatives(Collection<Issue> initiatives, String jiraBaseUrl) {
        return exportCsv(initiatives, jiraBaseUrl)
    }
}