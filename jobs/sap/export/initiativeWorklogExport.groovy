package jobs.sap.export

Long SAVED_FILTER_ID = 39543L

def base = new BaseWorklogExport(log)
def jiraBaseUrl = base.jiraBaseUrl

def cfg = new BaseWorklogExport.Config(
        savedFilterId: SAVED_FILTER_ID,
        mailReceiverUserName: "Bohner.Bastian",
        mailSubject: "Automated Jira Worklog Export",
        tempFilePrefix: "jira-worklog",
        sendMailWhenErrors: false,
        emptyFilterResultMessage: "In filter there is 0 results",
        mailBody: """Results are attached for the applied filter:

${jiraBaseUrl}/issues/?filter=${SAVED_FILTER_ID}

Important information and limitations:
- Only Initiatives returned by the filter are processed.
- For each Initiative, the entire hierarchy is collected (Features → Epics → Tasks → Sub-tasks).
- The hierarchy is resolved using JQL queries.
  - If an issue is not indexed at the time of execution, it will not appear in the result.
  - Jira reindexing runs daily; this job is currently scheduled every Monday at 01:00, when all issues should already be indexed.
- The output contains only issues with at least one registered worklog.
  - Issues without worklogs are intentionally excluded.
"""
)

return base.run(cfg) { topLevelIssues, baseUrl, messages, errors ->
    def exporter = new InitiativeWorklogCsvExporter(messages, errors)
    exporter.exportCsvForInitiatives(topLevelIssues, baseUrl)
}