package jobs.global.export

import org.apache.commons.text.StringEscapeUtils
import com.atlassian.jira.bc.filter.SearchRequestService;
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.search.SearchRequest
import com.atlassian.jira.issue.search.SearchResults
import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.issue.search.util.SearchSortUtil
import com.atlassian.jira.issue.search.SearchException
import com.atlassian.jira.security.JiraAuthenticationContext
import com.atlassian.jira.bc.JiraServiceContextImpl
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.web.bean.PagerFilter
import com.atlassian.jira.issue.fields.layout.column.ColumnLayoutManager
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager
import com.atlassian.mail.Email
import org.slf4j.LoggerFactory

import javax.activation.DataHandler
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource
/**
 * SavedFilterCsvExporter
 *
 * @author chabrecek.anton
 * Created on 18. 8. 2026.
 */

class SavedFilterCsvExporter {

    static final String CSV_SEPARATOR = ';'
    static final String FILE_SUFFIX = '.csv'
    static final String SUBJECT_PREFIX = '[Jira] Saved filter CSV export'
    static final String BODY_MIME_TYPE = 'text/html; charset=UTF-8'
    static final String ATTACHMENT_MIME_TYPE = 'text/csv; charset=UTF-8'
    static final String FALLBACK_FILE_NAME_PREFIX = 'jira-filter-export'
    static final int PAGE_SIZE = 500
    static final boolean INCLUDE_UTF8_BOM = true
    static final boolean SEND_EMPTY_REPORT = true
    static final boolean INCLUDE_ASSIGNEES_DISTINCT = true

    private final log = LoggerFactory.getLogger(this.class)

    private final ApplicationUser runAs
    private final SearchService searchService
    private final SearchRequestService searchRequestService
    private final ColumnLayoutManager columnLayoutManager
    private final FieldLayoutManager fieldLayoutManager
    private final def userManager
    private final def mailServerManager
    private final def serviceContext

    SavedFilterCsvExporter(ApplicationUser runAs) {
        this.runAs = runAs
        this.serviceContext = new JiraServiceContextImpl(runAs)
        this.searchService = ComponentAccessor.getComponent(SearchService)
        this.searchRequestService = ComponentAccessor.getComponent(SearchRequestService)
        this.columnLayoutManager = ComponentAccessor.getComponent(ColumnLayoutManager)
        this.fieldLayoutManager = ComponentAccessor.getComponent(FieldLayoutManager)
        this.userManager = ComponentAccessor.userManager
        this.mailServerManager = ComponentAccessor.mailServerManager
    }

    String exportAndSend(String filterJql, Object recipientSpec) {
        Long filterId = extractFilterId(filterJql)
        if (!filterId) return "SKIPPED: Cannot extract filter id from '${filterJql}'"

        SearchRequest filter = searchRequestService.getFilter(serviceContext, filterId)
        if (!filter) return "SKIPPED: Filter ${filterId} not found or not visible for ${runAs?.name}"

        def parse = searchService.parseQuery(runAs, "filter = ${filterId}")
        if (!parse?.valid) return "SKIPPED: Invalid filter query for ${filterId}: ${parse?.errors}"

        def columns = columnLayoutManager.getColumnLayout(runAs, filter)?.columnLayoutItems ?: []
        if (!columns) return "SKIPPED: Filter ${filterId} has no stored columns"

        List<Issue> issues = collectIssues(filter)
        if (!issues && !SEND_EMPTY_REPORT) return "SKIPPED: Filter ${filterId} returned no issues"

        Set<ApplicationUser> recipients = resolveRecipients(recipientSpec, issues)
        if (!recipients) return "SKIPPED: No valid recipients for filter ${filterId}"

        String csv = renderCsv(columns, issues)
        String fileName = safeFileName("${filter.name ?: FALLBACK_FILE_NAME_PREFIX}-${filterId}${FILE_SUFFIX}")
        String subject = "${SUBJECT_PREFIX}: ${filter.name ?: filterId} (${issues.size()} issues)"
        String body = renderBody(filter, issues, recipients)

        sendMailWithAttachment(recipients, subject, body, fileName, csv)

        return "SENT: filter=${filterId}, name='${filter.name}', issues=${issues.size()}, recipients=${recipients*.name.join(', ')}"
    }

    private Long extractFilterId(String filterJql) {
        def m = filterJql =~ /(?i)\bfilter\s*=\s*(\d+)\b/
        return m.find() ? m.group(1) as Long : null
    }

    private List<Issue> collectIssues(SearchRequest filter) {
        List<Issue> issues = []
        int startAt = 0

        while (true) {
            SearchResults results = searchService.search(runAs, filter.query, PagerFilter.newPageAlignedFilter(startAt, PAGE_SIZE))
            List<Issue> page = readIssues(results)
            if (!page) break

            issues.addAll(page)
            startAt += page.size()

            Integer total = readTotal(results)
            if (total != null && startAt >= total) break
            if (page.size() < PAGE_SIZE) break
        }

        return issues
    }

    private static List<Issue> readIssues(SearchResults results) {
        if (results.respondsTo('getResults')) return results.results as List<Issue>
        if (results.respondsTo('getIssues')) return results.issues as List<Issue>
        return []
    }

    private static Integer readTotal(SearchResults results) {
        if (results.respondsTo('getTotal')) return results.total as Integer
        return null
    }

    private Set<ApplicationUser> resolveRecipients(Object spec, List<Issue> issues) {
        Set<ApplicationUser> users = [] as LinkedHashSet

        if (spec instanceof Map) {
            addUsers(users, spec.users)
            if (spec.assignees == true) addAssignees(users, issues)
        } else {
            addUsers(users, spec)
        }

        return users.findAll { it?.active && it.emailAddress } as LinkedHashSet
    }

    private void addUsers(Set<ApplicationUser> target, Object value) {
        if (!value) return

        if (value instanceof ApplicationUser) {
            target << value
            return
        }

        if (value instanceof Collection) {
            value.each { addUsers(target, it) }
            return
        }

        ApplicationUser user = userManager.getUserByName(value as String)
        if (user) target << user
        else log.warn("Recipient user not found: ${value}")
    }

    private void addAssignees(Set<ApplicationUser> target, List<Issue> issues) {
        issues.each { Issue issue ->
            if (issue.assignee) target << issue.assignee
        }
    }

    private String renderCsv(def columns, List<Issue> issues) {
        def out = new StringBuilder()
        if (INCLUDE_UTF8_BOM) out << '\uFEFF'

        out << columns.collect { csvCell(columnName(it)) }.join(CSV_SEPARATOR) << '\n'

        issues.each { Issue issue ->
            out << columns.collect { csvCell(columnValue(it, issue)) }.join(CSV_SEPARATOR) << '\n'
        }

        return out.toString()
    }

    private String columnName(def columnItem) {
        def field = columnItem.navigableField
        return field?.name ?: field?.id ?: ''
    }

    private String columnValue(def columnItem, Issue issue) {
        def field = columnItem.navigableField
        if (!field) return ''

        try {
            def fieldLayoutItem = fieldLayoutManager.getFieldLayout(issue)?.getFieldLayoutItem(field.id)
            def html = field.getColumnViewHtml(fieldLayoutItem, [:], issue)
            return cleanText(html)
        } catch (Throwable ignored) {
            return fallbackValue(field.id as String, issue)
        }
    }

    private static String fallbackValue(String fieldId, Issue issue) {
        switch (fieldId) {
            case 'issuekey': return issue.key
            case 'summary': return issue.summary ?: ''
            case 'issuetype': return issue.issueType?.name ?: ''
            case 'status': return issue.status?.name ?: ''
            case 'priority': return issue.priority?.name ?: ''
            case 'assignee': return issue.assignee?.displayName ?: ''
            case 'reporter': return issue.reporter?.displayName ?: ''
            case 'created': return issue.created?.toString() ?: ''
            case 'updated': return issue.updated?.toString() ?: ''
            default:
                return ''
        }
    }

    /*private static String cleanText(Object value) {
        if (value == null) return ''
        String s = value as String
        s = s.replaceAll(/<br\s*\/?>/, '\n')
        s = s.replaceAll(/<[^>]+>/, '')
        s = s.replace('&nbsp;', ' ')
                .replace('&amp;', '&')
                .replace('&lt;', '<')
                .replace('&gt;', '>')
                .replace('&quot;', '"')
                .replace('&#39;', "'")
        return s.replaceAll(/\r\n|\r|\n/, ' ').replaceAll(/\s+/, ' ').trim()
    }*/

    private static String cleanText(Object value) {
        if (value == null) return ''

        String s = value.toString()
        s = s.replaceAll(/<br\s*\/?>/, '\n')
        s = s.replaceAll(/<[^>]+>/, '')

        s = StringEscapeUtils.unescapeHtml4(s)

        return s.replaceAll(/\r\n|\r|\n/, ' ')
                .replaceAll(/\s+/, ' ')
                .trim()
    }

    private static String csvCell(Object value) {
        String s = value == null ? '' : value as String
        boolean quote = s.contains(CSV_SEPARATOR) || s.contains('"') || s.contains('\n') || s.contains('\r')
        s = s.replace('"', '""')
        return quote ? "\"${s}\"" : s
    }

    private void sendMailWithAttachment(Set<ApplicationUser> recipients, String subject, String htmlBody, String fileName, String csv) {
        def smtp = mailServerManager.defaultSMTPMailServer
        if (!smtp) throw new IllegalStateException('Default SMTP mail server is not configured')

        String to = recipients.collect { it.emailAddress }.findAll { it }.unique().join(',')

        def textPart = new MimeBodyPart()
        textPart.setContent(htmlBody, BODY_MIME_TYPE)

        def attachmentPart = new MimeBodyPart()
        attachmentPart.setDataHandler(new DataHandler(new ByteArrayDataSource(csv.getBytes('UTF-8'), ATTACHMENT_MIME_TYPE)))
        attachmentPart.setFileName(fileName)

        def multipart = new MimeMultipart()
        multipart.addBodyPart(textPart)
        multipart.addBodyPart(attachmentPart)

        def email = new Email(to)
        email.setSubject(subject)
        email.setMultipart(multipart)

        smtp.send(email)
    }

    private String renderBody(SearchRequest filter, List<Issue> issues, Set<ApplicationUser> recipients) {
        """
        <html>
          <body style="font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#172B4D;">
            <h3 style="margin-bottom:8px;">Saved filter CSV export</h3>
            <table style="border-collapse:collapse;">
              <tr><td style="padding:4px 10px;font-weight:bold;">Filter</td><td style="padding:4px 10px;">${esc(filter.name)}</td></tr>
              <tr><td style="padding:4px 10px;font-weight:bold;">Filter ID</td><td style="padding:4px 10px;">${filter.id}</td></tr>
              <tr><td style="padding:4px 10px;font-weight:bold;">Issues</td><td style="padding:4px 10px;">${issues.size()}</td></tr>
              <tr><td style="padding:4px 10px;font-weight:bold;">Recipients</td><td style="padding:4px 10px;">${esc(recipients*.name.join(', '))}</td></tr>
            </table>
            <p style="color:#5E6C84;">The attached CSV contains only columns configured in the saved Jira filter.</p>
          </body>
        </html>
        """
    }

    private static String safeFileName(String value) {
        value.replaceAll(/[\\/:*?"<>|]/, '_').replaceAll(/\s+/, '_')
    }

    private static String esc(Object value) {
        String s = value == null ? '' : value as String
        s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    }
}
