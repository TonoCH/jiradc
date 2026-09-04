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

    private static final String[] REPORT_RECIPIENTS = ERROR_RECIPIENTS
    private static final int SEARCH_BATCH_SIZE = 500

    private final MailServerManager mailServerManager = ComponentAccessor.getComponent(MailServerManager)

    void run() {
        List<Map> errors = []
        int checked = 0
        int valid = 0
        int pending = 0
        int processing = 0
        try {
            ApplicationUser bot = userManager.getUserByName(JIRA_BOT)
            if (!bot) throw new IllegalStateException("User '${JIRA_BOT}' not found")
            validateConfiguration()

            searchAll(bot, auditJql()).each { Issue issue ->
                checked++
                try {
                    String pi = selectValue(issue)
                    String text = textValue(issue, cfTextMirror).trim()
                    String override = textValue(issue, cfOverride).trim()
                    String queue = textValue(issue, cfPiSyncRequired).trim()
                    boolean ok = true

                    if (queue.equalsIgnoreCase("true")) pending++
                    if (queue.equalsIgnoreCase("PROCESSING")) processing++

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
                    if (!pi && override == issue.key) {
                        ok = false
                        errors << row(issue, "EMPTY_SELF_OVERRIDE", pi, override,
                                "Issue is marked as its own authority but has no Project Information value")
                    }

                    Issue authorityIssue = override ? issueManager.getIssueObject(override) : null
                    if (override && !authorityIssue) {
                        ok = false
                        errors << row(issue, "INVALID_OVERRIDE", pi, override,
                                "Override Key does not reference an existing issue")
                    } else if (override && override != issue.key && !isAncestor(issue, authorityIssue.id)) {
                        ok = false
                        errors << row(issue, "OVERRIDE_NOT_ANCESTOR", pi, override,
                                "Override Key exists but is not an ancestor")
                    }

                    if (override != issue.key) {
                        Map expected = resolveEffectiveAuthority(getParent(issue))
                        String expectedValue = expected.value as String
                        String expectedOverride = expected.overrideKey as String
                        if (normalize(pi) != normalize(expectedValue)) {
                            ok = false
                            errors << row(issue, "INHERITED_VALUE_MISMATCH", pi, expectedValue,
                                    "Value differs from the effective parent authority")
                        }
                        if (normalize(override) != normalize(expectedOverride)) {
                            ok = false
                            errors << row(issue, "INHERITED_OVERRIDE_MISMATCH", pi, override,
                                    "Expected Override Key '${expectedOverride}'")
                        }
                    }
                    if (ok) valid++
                } catch (Exception issueError) {
                    errors << row(issue, "ISSUE_AUDIT_FAILURE", "", "",
                            issueError.message ?: issueError.class.name)
                    log.error("Weekly PI audit failed for ${issue.key}: ${issueError.message}", issueError)
                }
            }
        } catch (Exception e) {
            errors << [issue: "AUDIT", type: "JOB_FAILURE", pi: "", actual: "",
                       description: e.message ?: e.class.name]
            log.error("Weekly PI audit failed: ${e.message}", e)
        }
        try {
            sendReport(checked, valid, pending, processing, errors)
        } catch (Exception mailError) {
            log.error("Weekly PI report could not be sent: ${mailError.message}", mailError)
        }
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
            def hits = searchService.searchOverrideSecurity(user, parsed.query, pager).results
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
            List<Option> values = raw.findAll { it instanceof Option } as List<Option>
            if (values.size() > 1) {
                throw new IllegalStateException(
                        "${issue.key}: Project Information contains ${values.size()} values; exactly one is supported")
            }
            return values ? values.first().value?.trim() ?: "" : ""
        }
        if (raw instanceof Option) return raw.value?.trim() ?: ""
        return raw.toString()?.trim() ?: ""
    }

    private void sendReport(int checked, int valid, int pending, int processing, List<Map> errors) {
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
          <p>Issues checked: ${checked}<br/>Valid issues: ${valid}<br/>
             Pending queue entries: ${pending}<br/>Currently processing: ${processing}<br/>
             Errors: ${errors.size()}</p>
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
        if (!cfParentLink) missing << CF_PARENT_LINK
        if (!cfEpicLink) missing << CF_EPIC_LINK
        if (missing) throw new IllegalStateException("Missing custom fields: ${missing.join(', ')}")
    }

    private String auditJql() {
        "cf[${numericId(CF_PROJECT_INFORMATION_N)}] is not EMPTY OR " +
                "cf[${numericId(CF_PROJECT_INFORMATION_TEXT)}] is not EMPTY OR " +
                "cf[${numericId(CF_PROJECT_INFORMATION_OVERRIDE_KEY)}] is not EMPTY OR " +
                "cf[${numericId(CF_PI_SYNC_REQUIRED)}] is not EMPTY"
    }

    private Map resolveEffectiveAuthority(Issue issue) {
        Set<Long> seen = [] as Set<Long>
        Issue current = issue
        int depth = 0
        while (current && depth++ < MAX_HIERARCHY_DEPTH) {
            if (!seen.add(current.id)) {
                throw new IllegalStateException("Hierarchy cycle detected at ${current.key}")
            }
            String value = selectValue(current)
            if (value) {
                String override = textValue(current, cfOverride).trim()
                return [value: value, overrideKey: override ?: current.key]
            }
            current = getParent(current)
        }
        if (current) throw new IllegalStateException("Hierarchy exceeds ${MAX_HIERARCHY_DEPTH} levels")
        return [value: "", overrideKey: ""]
    }

    private boolean isAncestor(Issue issue, Long expectedAncestorId) {
        Set<Long> seen = [] as Set<Long>
        Issue current = getParent(issue)
        int depth = 0
        while (current && depth++ < MAX_HIERARCHY_DEPTH) {
            if (!seen.add(current.id)) {
                throw new IllegalStateException("Hierarchy cycle detected at ${current.key}")
            }
            if (current.id == expectedAncestorId) return true
            current = getParent(current)
        }
        if (current) throw new IllegalStateException("Hierarchy exceeds ${MAX_HIERARCHY_DEPTH} levels")
        return false
    }

    private Issue getParent(Issue issue) {
        if (!issue) return null
        if (issue.parentObject) return issue.parentObject
        Issue parent = resolveIssue(issue.getCustomFieldValue(cfEpicLink))
        if (!parent) parent = resolveIssue(issue.getCustomFieldValue(cfParentLink))
        return parent
    }

    private Issue resolveIssue(Object value) {
        if (!value) return null
        if (value instanceof Issue) return value as Issue
        try {
            if (value.hasProperty("key")) {
                Issue byProperty = issueManager.getIssueObject(value.key?.toString())
                if (byProperty) return byProperty
            }
        } catch (Exception ignored) { }
        String key = value.toString()?.trim()
        return key ? issueManager.getIssueObject(key) : null
    }

    private static String textValue(Issue issue, CustomField field) {
        issue?.getCustomFieldValue(field)?.toString() ?: ""
    }
    private static String normalize(String value) { value == null ? "" : value.trim() }
    private static String numericId(String id) { id.replace("customfield_", "") }
    private static String html(Object value) {
        (value?.toString() ?: "").replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace('"', "&quot;")
    }
}
