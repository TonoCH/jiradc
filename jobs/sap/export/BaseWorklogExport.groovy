package jobs.sap.export

/**
 * BaseWorklogExport
 *
 * @author chabrecek.anton
 * Created on 3. 3. 2026.
 */

import com.atlassian.jira.bc.JiraServiceContextImpl
import com.atlassian.jira.bc.filter.SearchRequestService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.search.SearchRequest
import com.atlassian.jira.user.ApplicationUser
import org.apache.log4j.Logger
import utils.MyBaseUtil
import utils.mail_notifiers.Mailer

import java.nio.file.Files

class BaseWorklogExport {

    static class Config {
        Long savedFilterId
        String mailReceiverUserName
        String mailSubject
        String mailBody
        String tempFilePrefix = "jira-worklog"
        boolean sendMailWhenErrors = false
        String emptyFilterResultMessage = "In filter there are 0 results"
    }

    static class ExportPart {
        Long savedFilterId
        String nameForLogs = "part"
        Closure<String> exportFn
        String emptyResultMessage = null
    }

    private final Logger log
    private final ApplicationUser currentUser
    private final SearchRequestService searchRequestService
    private final MyBaseUtil myBaseUtil
    private final String jiraBaseUrl = "https://sb-jira.ads.local";

    final List<String> messages = new ArrayList<>()
    final List<String> errors = new ArrayList<>()

    BaseWorklogExport(Logger log) {
        this.log = log
        this.currentUser = ComponentAccessor.jiraAuthenticationContext.loggedInUser
        this.searchRequestService = ComponentAccessor.getComponent(SearchRequestService)
        this.myBaseUtil = new MyBaseUtil()
        this.jiraBaseUrl = ComponentAccessor.getApplicationProperties().getString("jira.baseurl")
    }

    String getJiraBaseUrl() { return jiraBaseUrl }

    String run(Config cfg, Closure<String> exportFn) {
        if (!cfg?.savedFilterId) throw new IllegalArgumentException("Config.savedFilterId is required")
        if (!cfg?.mailReceiverUserName) throw new IllegalArgumentException("Config.mailReceiverUserName is required")
        if (!cfg?.mailSubject) throw new IllegalArgumentException("Config.mailSubject is required")
        if (!cfg?.mailBody) throw new IllegalArgumentException("Config.mailBody is required")
        if (exportFn == null) throw new IllegalArgumentException("exportFn is required")

        List<Issue> issues = loadIssuesFromSavedFilter(cfg.savedFilterId)
        if (issues.isEmpty()) return cfg.emptyFilterResultMessage

        String csv = exportFn.call(issues, jiraBaseUrl, messages, errors)

        messages.each { log.warn(it) }
        errors.each { log.error(it) }

        if (!errors.isEmpty() && !cfg.sendMailWhenErrors) {
            return csv
        }

        sendMailWithCsv(cfg, csv)
        return csv
    }

    String runMulti(Config cfg, List<ExportPart> parts) {
        if (!cfg?.mailReceiverUserName) throw new IllegalArgumentException("Config.mailReceiverUserName is required")
        if (!cfg?.mailSubject) throw new IllegalArgumentException("Config.mailSubject is required")
        if (!cfg?.mailBody) throw new IllegalArgumentException("Config.mailBody is required")
        if (!parts || parts.isEmpty()) throw new IllegalArgumentException("parts is required")

        List<String> csvParts = []

        parts.each { part ->
            if (!part?.savedFilterId) throw new IllegalArgumentException("ExportPart.savedFilterId is required")
            if (part.exportFn == null) throw new IllegalArgumentException("ExportPart.exportFn is required")

            List<Issue> issues = loadIssuesFromSavedFilter(part.savedFilterId)
            if (issues.isEmpty()) {
                String msg = part.emptyResultMessage ?: "Export part '${part.nameForLogs}' filter ${part.savedFilterId} returned 0 issues."
                messages.add(msg)
                return
            }

            String csv = part.exportFn.call(issues, jiraBaseUrl, messages, errors)
            if (csv?.trim()) csvParts.add(csv)
        }

        messages.each { log.warn(it) }
        errors.each { log.error(it) }

        if (csvParts.isEmpty()) {
            return cfg.emptyFilterResultMessage
        }

        String mergedCsv = mergeCsvWithSingleHeader(csvParts)

        if (!errors.isEmpty() && !cfg.sendMailWhenErrors) {
            return mergedCsv
        }

        sendMailWithCsv(cfg, mergedCsv)
        return mergedCsv
    }

    private void sendMailWithCsv(Config cfg, String csv) {
        File tmpCsv = Files.createTempFile("${cfg.tempFilePrefix}", ".csv").toFile()
        try {
            tmpCsv.write(csv, "UTF-8")

            Mailer mailer = new Mailer()
            boolean sent = mailer.sendMessageWithAttachment(cfg.mailReceiverUserName,cfg.mailSubject,cfg.mailBody,[tmpCsv])

            if (sent) {
                log.info("Worklog CSV email was successfully queued for sending.")
                //temp send also me:
                mailer.sendMessageWithAttachment("chabrecek.anton", cfg.mailSubject, cfg.mailBody,[tmpCsv])
            } else {
                log.error("Worklog CSV email was NOT sent.")
                log.error(String.valueOf(mailer.getErrorMessages()))
                //if error send me mail with this info:
                mailer.sendMessageWithAttachment("chabrecek.anton", cfg.mailSubject, cfg.mailBody,[tmpCsv])
            }
        } finally {
            tmpCsv.delete()
        }
    }


    static String mergeCsvWithSingleHeader(List<String> csvList) {
        if (!csvList || csvList.isEmpty()) return ""

        List<List<String>> allLines = csvList.collect { csv ->
            csv.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1).toList()
        }.collect { lines ->
            while (!lines.isEmpty() && lines.last().trim().isEmpty()) lines = lines[0..-2]
            lines
        }.findAll { !it.isEmpty() }

        if (allLines.isEmpty()) return ""

        String header = allLines[0][0]
        List<String> merged = [header]

        allLines.eachWithIndex { lines, idx ->
            if (lines.isEmpty()) return
            String h = lines[0]
            if (h != header) {
                throw new IllegalStateException("CSV header mismatch in part #${idx + 1}. Expected='${header}' but was='${h}'")
            }
            if (lines.size() > 1) {
                merged.addAll(lines.subList(1, lines.size()))
            }
        }

        return merged.join("\n") + "\n"
    }

    private List<Issue> loadIssuesFromSavedFilter(Long savedFilterId) {
        def serviceCtx = new JiraServiceContextImpl(currentUser)
        SearchRequest sr = searchRequestService.getFilter(serviceCtx, savedFilterId)

        if (!sr || serviceCtx.errorCollection.hasAnyErrors()) {
            throw new IllegalStateException(
                    "Saved filter ID ${savedFilterId} not found or no permission. " +
                            "Errors=${serviceCtx.errorCollection?.errors}, Messages=${serviceCtx.errorCollection?.errorMessages}"
            )
        }

        String savedJql = sr.query?.toString()
        savedJql = sanitizeJql(savedJql)

        if (!savedJql) {
            throw new IllegalStateException("Saved filter ${savedFilterId} has empty JQL.")
        }

        return myBaseUtil.findIssues(savedJql) ?: []
    }

    private static String sanitizeJql(String jql) {
        if (!jql) return jql
        return jql.replaceAll(/\{([^}]*)\}/, '$1')
    }
}
