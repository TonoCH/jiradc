package project_information

import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.web.bean.PagerFilter
import com.atlassian.mail.Email
import com.atlassian.mail.server.MailServerManager
import org.apache.log4j.Logger

/**
 * ProjectInformationConfig
 *
 * @author chabrecek.anton
 * Created on 26. 8. 2026.

 * Weekly read-only consistency audit.
 * Always sends a report. It does not repair issues and does not change PI_SYNC_REQUIRED.
 */
class ProjectInformationWeeklyAuditJob extends ProjectInformationConfig {
    private static final Logger log = Logger.getLogger("scriptrunner.job.project-information-weekly-audit")

    private static final String[] REPORT_RECIPIENTS = ["chabrecek.anton@scheidt-bachmann.sk"]
    private static final String AUDIT_JQL = "cf[18600] is not EMPTY OR cf[18700] is not EMPTY OR cf[18802] is not EMPTY"
    private static final int SEARCH_BATCH_SIZE = 500

    private final MailServerManager mailServerManager = ComponentAccessor.getComponent(MailServerManager)

    void run() {
        List<Map> errors = []
        int checked = 0
        int valid = 0
        try {
            ApplicationUser bot = userManager.getUserByName(JIRA_BOT)
            if (!bot) throw new IllegalStateException("User '${JIRA_BOT}' not found")
            validateConfiguration()

            searchAll(bot, AUDIT_JQL).each { Issue issue ->
                checked++
                String pi = selectValue(issue)
                String text = textValue(issue, cfTextMirror).trim()
                String override = textValue(issue, cfOverride).trim()
                String queue = textValue(issue, cfPiSyncRequired).trim()
                boolean ok = true

                if (normalize(pi) != normalize(text)) {
                    ok = false
                    errors << row(issue, "PI_TEXT_MISMATCH", pi, text,
                            "Project Information N and text mirror differ")
                }
                if (pi && !override) {
                    ok = false
                    errors << row(issue, "MISSING_OVERRIDE", pi, override,
                            "Project Information is set but Override Key is empty")
                }
                if (override && !issueManager.getIssueObject(override)) {
                    ok = false
                    errors << row(issue, "INVALID_OVERRIDE", pi, override,
                            "Override Key does not reference an existing issue")
                }
                if (queue.equalsIgnoreCase("PROCESSING")) {
                    ok = false
                    errors << row(issue, "STUCK_PROCESSING", pi, queue,
                            "PI_SYNC_REQUIRED is still PROCESSING during weekly audit")
                }
                if (ok) valid++
            }
        } catch (Exception e) {
            errors << [issue: "AUDIT", type: "JOB_FAILURE", pi: "", actual: "",
                       description: e.message ?: e.class.name]
            log.error("Weekly PI audit failed: ${e.message}", e)
        }
        sendReport(checked, valid, errors)
    }

    private Map row(Issue issue, String type, String pi, String actual, String description) {
        [issue: issue.key, type: type, pi: pi, actual: actual, description: description]
    }

    private List<Issue> searchAll(ApplicationUser user, String jql) {
        def parsed = searchService.parseQuery(user, jql)
        if (!parsed.valid) throw new IllegalArgumentException("Invalid audit JQL: ${parsed.errors}")
        List<Issue> result = []
        int start = 0
        while (true) {
            PagerFilter pager = new PagerFilter(SEARCH_BATCH_SIZE)
            pager.start = start
            def hits = searchService.search(user, parsed.query, pager).results
            if (!hits) break
            hits.each { hit ->
                Issue issue = issueManager.getIssueObject(hit.id)
                if (issue) result << issue
            }
            start += hits.size()
            if (hits.size() < SEARCH_BATCH_SIZE) break
        }
        return result
    }

    private String selectValue(Issue issue) {
        def raw = issue?.getCustomFieldValue(cfProjectInformation)
        if (!raw) return ""
        if (raw instanceof Collection) {
            Option first = raw.find { it instanceof Option } as Option
            return first?.value?.trim() ?: ""
        }
        if (raw instanceof Option) return raw.value?.trim() ?: ""
        return raw.toString()?.trim() ?: ""
    }

    private void sendReport(int checked, int valid, List<Map> errors) {
        def smtp = mailServerManager.defaultSMTPMailServer
        if (!smtp) {
            log.error("Weekly PI report not sent: default SMTP server is not configured")
            return
        }
        String rows = errors.collect { e ->
            "<tr><td>${html(e.issue)}</td><td>${html(e.type)}</td><td>${html(e.pi)}</td>" +
                    "<td>${html(e.actual)}</td><td>${html(e.description)}</td></tr>"
        }.join("\n")
        String body = """
          <h2>Weekly Project Information consistency report</h2>
          <p>Issues checked: ${checked}<br/>Valid issues: ${valid}<br/>Errors: ${errors.size()}</p>
          <table border="1" cellpadding="5" cellspacing="0">
            <tr><th>Issue</th><th>Error type</th><th>PI N</th><th>Actual value</th><th>Description</th></tr>
            ${rows ?: '<tr><td colspan="5">No errors found</td></tr>'}
          </table>
        """
        Email email = new Email(REPORT_RECIPIENTS.findAll { it }.join(","))
        email.setSubject(errors ? "[PI Audit] Errors found" : "[PI Audit] Successful")
        email.setMimeType("text/html; charset=UTF-8")
        email.setBody(body)
        smtp.send(email)
    }

    private void validateConfiguration() {
        List<String> missing = []
        if (!cfProjectInformation) missing << CF_PROJECT_INFORMATION_N
        if (!cfTextMirror) missing << CF_PROJECT_INFORMATION_TEXT
        if (!cfOverride) missing << CF_PROJECT_INFORMATION_OVERRIDE_KEY
        if (!cfPiSyncRequired) missing << CF_PI_SYNC_REQUIRED
        if (missing) throw new IllegalStateException("Missing custom fields: ${missing.join(', ')}")
    }

    private static String textValue(Issue issue, CustomField field) {
        issue?.getCustomFieldValue(field)?.toString() ?: ""
    }
    private static String normalize(String value) { value == null ? "" : value.trim() }
    private static String html(Object value) {
        (value?.toString() ?: "").replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace('"', "&quot;")
    }
}
