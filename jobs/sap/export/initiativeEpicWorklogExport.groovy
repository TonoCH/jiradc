package jobs.sap.export

/**
 * initiativeEpicWorklogExport
 *
 * @author chabrecek.anton
 * Created on 3. 3. 2026.
 */

Long INITIATIVE_FILTER_ID = 39543L
Long EPIC_FILTER_ID = 39545L

def base = new BaseWorklogExport(log)
def jiraBaseUrl = base.jiraBaseUrl

def cfg = new BaseWorklogExport.Config(
        mailReceiverUserName: "Bohner.Bastian",
        mailSubject: "Automated Jira Worklog Export (Initiatives + Epics)",
        tempFilePrefix: "jira-worklog-initiative-epic",
        sendMailWhenErrors: false,
        emptyFilterResultMessage: "In filters there are 0 results",
        mailBody: """Results are attached for the applied filters:

${jiraBaseUrl}/issues/?filter=${INITIATIVE_FILTER_ID}
${jiraBaseUrl}/issues/?filter=${EPIC_FILTER_ID}

Important information and limitations:
- This export combines Initiative and Epic exports into a single CSV file.
- CSV header is included only once.
"""
)

def parts = [
        new BaseWorklogExport.ExportPart(
                savedFilterId: INITIATIVE_FILTER_ID,
                nameForLogs: "initiative",
                emptyResultMessage: "Initiative filter returned 0 issues.",
                exportFn: { topLevelIssues, baseUrl, messages, errors ->
                    def exporter = new InitiativeWorklogCsvExporter(messages, errors)
                    exporter.exportCsvForInitiatives(topLevelIssues, baseUrl)
                }
        ),
        new BaseWorklogExport.ExportPart(
                savedFilterId: EPIC_FILTER_ID,
                nameForLogs: "epic",
                emptyResultMessage: "Epic filter returned 0 issues.",
                exportFn: { topLevelIssues, baseUrl, messages, errors ->
                    def exporter = new EpicWorklogCsvExporter(messages, errors)
                    exporter.exportCsvForEpics(topLevelIssues, baseUrl)
                }
        )
]

return base.runMulti(cfg, parts)