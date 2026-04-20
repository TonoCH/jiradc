package jobs.sap.export

Long SAVED_FILTER_ID = 39545L

def base = new BaseWorklogExport(log)
def jiraBaseUrl = base.jiraBaseUrl

def cfg = new BaseWorklogExport.Config(
        savedFilterId: SAVED_FILTER_ID,
        mailReceiverUserName: "Bohner.Bastian",
        mailSubject: "Automated Jira Worklog Export (Epics / Minor requirements)",
        tempFilePrefix: "jira-worklog-epic",
        sendMailWhenErrors: false,
        emptyFilterResultMessage: "In filter there are 0 results",
        mailBody: """Results are attached for the applied filter (Minor requirements):

${jiraBaseUrl}/issues/?filter=${SAVED_FILTER_ID}

Important information and limitations:
- Only Epics (Minor requirements) returned by the filter are processed.
- For each Epic, the entire hierarchy is collected (Stories / Tasks / Bugs / Sub-tasks).
- The hierarchy is resolved using JQL.
  - If an issue is not indexed at the time of execution, it will not appear in the result.
- The output contains only issues with relevant time values
  (original estimate, remaining estimate, or time spent not equal to 0).

The first column contains the top-level issue key (Epic in this export).
"""
)

return base.run(cfg) { topLevelIssues, baseUrl, messages, errors ->
    def exporter = new EpicWorklogCsvExporter(messages, errors)
    exporter.exportCsvForEpics(topLevelIssues, baseUrl)
}