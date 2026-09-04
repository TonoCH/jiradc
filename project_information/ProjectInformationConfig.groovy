package project_information

import com.atlassian.beehive.ClusterLockService
import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.ModifiedValue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.web.bean.PagerFilter
import com.atlassian.mail.Email
import com.atlassian.mail.server.MailServerManager
import com.atlassian.sal.api.transaction.TransactionTemplate
import org.apache.log4j.Logger

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * ProjectInformationConfig
 *
 * Shared base for the Project Information synchronization: configuration, Jira components,
 * cluster locks, hierarchy resolution, the single field-write routine and the JQL/mail helpers.
 *
 * Everything that more than one component needs lives here, so the four entry points stay thin
 * and cannot drift apart:
 *   ProjectInformationCreateListener       - initializes a newly created issue
 *   ProjectInformationUpdateQueueListener  - reacts to a Project Information change
 *   ProjectInformationIncrementalSyncJob   - propagates queued sources to their descendants
 *   ProjectInformationWeeklyAuditJob       - read-only consistency report, optional repair
 *
 * @author chabrecek.anton
 * Created on 26. 8. 2026.
 */
class ProjectInformationConfig {

    // ---------------------------------------------------------------- configuration

    // Override these with JVM -D properties on every DC node; defaults preserve the current environment.
    public static final String CF_PROJECT_INFORMATION_N = configured(
            "pi.cf.projectInformation", "customfield_18600") // prod example: customfield_18702
    public static final String CF_PROJECT_INFORMATION_TEXT = configured(
            "pi.cf.textMirror", "customfield_18700")         // prod example: customfield_20200
    public static final String CF_PROJECT_INFORMATION_OVERRIDE_KEY = configured(
            "pi.cf.overrideKey", "customfield_18802")        // prod example: customfield_20201
    public static final String CF_PI_SYNC_REQUIRED = configured(
            "pi.cf.syncRequired", "customfield_19003")       // prod: confirm before deployment
    public static final String CF_PARENT_LINK = configured(
            "pi.cf.parentLink", "customfield_10301")
    public static final String CF_EPIC_LINK = configured(
            "pi.cf.epicLink", "customfield_10001")

    public static final String JIRA_BOT = configured("pi.bot.username", "jira.bot")
    public static final String[] ERROR_RECIPIENTS = configuredList(
            "pi.errorRecipients", "chabrecek.anton@scheidt-bachmann.sk")

    /** Empty means the whole instance. Set during a staged rollout, e.g. -Dpi.scope.projects=TEST,SBOX */
    public static final String[] SCOPE_PROJECTS = configuredList("pi.scope.projects", "")

    /** Guards against a stale field ID that happens to exist in the target environment. */
    public static final String EXPECTED_QUEUE_FIELD_NAME = configured("pi.cf.syncRequired.name", "PI_SYNC_REQUIRED")

    public static final int SEARCH_BATCH_SIZE = 500
    public static final int MAX_HIERARCHY_DEPTH = 50
    public static final int MAX_SYNC_ATTEMPTS = configuredInt("pi.sync.maxAttempts", 3)
    public static final int HIERARCHY_CACHE_LIMIT = 20000
    public static final int MAX_MAIL_ROWS = 200
    public static final String LOCK_NAMESPACE = "sk-scheidt-bachmann-project-information-sync"

    /** PI_SYNC_REQUIRED state machine: empty -> true -> PROCESSING -> empty, or "FAILED n" on error. */
    public static final String QUEUE_PENDING = "true"
    public static final String QUEUE_PROCESSING = "PROCESSING"
    public static final String QUEUE_FAILED = "FAILED"

    // ---------------------------------------------------------------- components

    public final def customFieldManager = ComponentAccessor.customFieldManager
    public final def issueManager = ComponentAccessor.issueManager
    public final def optionsManager = ComponentAccessor.optionsManager
    public final def userManager = ComponentAccessor.userManager
    public final def subTaskManager = ComponentAccessor.subTaskManager
    public final SearchService searchService = ComponentAccessor.getComponent(SearchService)
    public final IssueIndexingService indexingService = ComponentAccessor.getComponent(IssueIndexingService)
    public final MailServerManager mailServerManager = ComponentAccessor.getComponent(MailServerManager)
    public final ClusterLockService clusterLockService = ComponentAccessor.getComponent(ClusterLockService)
    public final TransactionTemplate transactionTemplate = ComponentAccessor.getComponent(TransactionTemplate)

    public final CustomField cfProjectInformation = customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_N)
    public final CustomField cfTextMirror = customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_TEXT)
    public final CustomField cfOverride = customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_OVERRIDE_KEY)
    public final CustomField cfPiSyncRequired = customFieldManager.getCustomFieldObject(CF_PI_SYNC_REQUIRED)
    public final CustomField cfParentLink = customFieldManager.getCustomFieldObject(CF_PARENT_LINK)
    public final CustomField cfEpicLink = customFieldManager.getCustomFieldObject(CF_EPIC_LINK)

    protected final Logger log = Logger.getLogger("scriptrunner.project-information." + getClass().simpleName)

    /** Parent lookups are expensive; one instance serves one event or one job execution. */
    private final Map<Long, Issue> parentCache = new HashMap<Long, Issue>()

    // ---------------------------------------------------------------- validation

    /**
     * Verifies every component and custom field this integration writes to. The queue field is
     * additionally checked by name, because a stale default ID that exists in the target
     * environment would otherwise be silently overwritten.
     */
    protected String validateConfiguration() {
        List<String> problems = []
        if (!cfProjectInformation) problems << "custom field ${CF_PROJECT_INFORMATION_N} (Project Information N)"
        if (!cfTextMirror) problems << "custom field ${CF_PROJECT_INFORMATION_TEXT} (text mirror)"
        if (!cfOverride) problems << "custom field ${CF_PROJECT_INFORMATION_OVERRIDE_KEY} (Override Key)"
        if (!cfPiSyncRequired) {
            problems << "custom field ${CF_PI_SYNC_REQUIRED} (PI_SYNC_REQUIRED)"
        } else if (EXPECTED_QUEUE_FIELD_NAME &&
                !EXPECTED_QUEUE_FIELD_NAME.equalsIgnoreCase(cfPiSyncRequired.name?.trim())) {
            problems << "custom field ${CF_PI_SYNC_REQUIRED} is named '${cfPiSyncRequired.name}' but " +
                    "'${EXPECTED_QUEUE_FIELD_NAME}' was expected; refusing to write into a foreign field"
        }
        if (!cfParentLink) problems << "custom field ${CF_PARENT_LINK} (Parent Link)"
        if (!cfEpicLink) problems << "custom field ${CF_EPIC_LINK} (Epic Link)"
        if (!clusterLockService) problems << "ClusterLockService"
        if (!transactionTemplate) problems << "TransactionTemplate"
        if (!indexingService) problems << "IssueIndexingService"
        if (!subTaskManager) problems << "SubTaskManager"
        return problems ? "misconfigured: ${problems.join('; ')}" : null
    }

    // ---------------------------------------------------------------- scope

    protected boolean inScope(Issue issue) {
        if (!SCOPE_PROJECTS) return true
        String key = issue?.projectObject?.key
        return key && SCOPE_PROJECTS.any { it.equalsIgnoreCase(key) }
    }

    protected static String scopedJql(String inner) {
        if (!SCOPE_PROJECTS) return inner
        return "project in (${SCOPE_PROJECTS.join(', ')}) AND (${inner})"
    }

    // ---------------------------------------------------------------- hierarchy

    /**
     * Sub-task parent first, then Epic Link, then Advanced Roadmaps Parent Link. Every component
     * uses this one implementation, so authority resolution and child discovery cannot disagree
     * about who the parent is.
     */
    protected Issue getParent(Issue issue) {
        if (!issue?.id) return null
        if (parentCache.containsKey(issue.id)) return parentCache[issue.id]

        Issue parent = issue.parentObject
        if (!parent) parent = resolveSubTaskParent(issue)
        if (!parent && cfEpicLink) parent = resolveIssue(issue.getCustomFieldValue(cfEpicLink))
        if (!parent && cfParentLink) parent = resolveIssue(issue.getCustomFieldValue(cfParentLink))

        if (parentCache.size() >= HIERARCHY_CACHE_LIMIT) parentCache.clear()
        parentCache[issue.id] = parent
        return parent
    }

    /**
     * During Issue Created processing parentObject can still be null on an issue whose type is
     * Sub-task, so the sub-task relation is resolved explicitly before the link fields are tried.
     * Without this the create listener reports "no parent" and inheritance never happens.
     */
    private Issue resolveSubTaskParent(Issue issue) {
        if (!subTaskManager) return null
        try {
            Long parentId = subTaskManager.getParentIssueId(issue) as Long
            return parentId ? issueManager.getIssueObject(parentId) : null
        } catch (Exception e) {
            log.debug("Cannot resolve the sub-task parent of ${issue.key}: ${e.message}")
            return null
        }
    }

    protected void clearHierarchyCache() {
        parentCache.clear()
    }

    protected Issue resolveIssue(Object value) {
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

    /**
     * Walks up from the given issue and returns the first ancestor that carries a value. The
     * returned Override Key is that ancestor's own Override Key when present, otherwise its key.
     * Returns empty values when no ancestor carries a value.
     */
    protected Map resolveEffectiveAuthority(Issue issue) {
        Set<Long> seen = [] as Set<Long>
        Issue current = issue
        int depth = 0

        while (current && depth++ < MAX_HIERARCHY_DEPTH) {
            if (!seen.add(current.id)) {
                throw new IllegalStateException("Hierarchy cycle detected at ${current.key}")
            }
            String value = selectValue(current)
            if (value) {
                String override = normalize(textValue(current, cfOverride))
                return [value: value, overrideKey: override ?: current.key]
            }
            current = getParent(current)
        }
        if (current) throw new IllegalStateException("Hierarchy exceeds ${MAX_HIERARCHY_DEPTH} levels")
        return [value: "", overrideKey: ""]
    }

    /** One JQL per chunk of parents instead of one per issue; candidates are confirmed with getParent. */
    protected List<Issue> findDirectChildren(Collection<Issue> parents, ApplicationUser user) {
        if (!parents) return []
        Map<Long, Issue> expected = parents.findAll { it?.id }.collectEntries { [(it.id): it] }
        Map<Long, Issue> children = [:]

        expected.values().toList().collate(100).each { List<Issue> chunk ->
            String keys = chunk.collect { it.key }.join(",")
            String jql = scopedJql("parent in (${keys}) OR " +
                    "cf[${numericId(CF_EPIC_LINK)}] in (${keys}) OR " +
                    "cf[${numericId(CF_PARENT_LINK)}] in (${keys})")
            searchIssueIds(user, jql).each { Long id ->
                Issue candidate = issueManager.getIssueObject(id)
                Issue actualParent = candidate ? getParent(candidate) : null
                if (actualParent && expected.containsKey(actualParent.id)) children[id] = candidate
            }
        }
        return children.values() as List<Issue>
    }

    // ---------------------------------------------------------------- field access

    protected String selectValue(Issue issue) {
        def raw = issue?.getCustomFieldValue(cfProjectInformation)
        if (!raw) return ""
        if (raw instanceof Collection) {
            List<Option> values = raw.findAll { it instanceof Option } as List<Option>
            if (values.size() > 1) {
                throw new IllegalStateException("${issue.key}: Project Information contains " +
                        "${values.size()} values; exactly one is supported")
            }
            return values ? (values.first().value?.trim() ?: "") : ""
        }
        if (raw instanceof Option) return raw.value?.trim() ?: ""
        return raw.toString()?.trim() ?: ""
    }

    protected String textValue(Issue issue, CustomField field) {
        if (!issue || !field) return ""
        return issue.getCustomFieldValue(field)?.toString() ?: ""
    }

    protected Option findActiveOption(Issue issue, String value) {
        def config = cfProjectInformation.getRelevantConfig(issue)
        if (!config) return null
        List<Option> matches = optionsManager.getOptions(config)?.findAll {
            normalize(it?.value?.toString()) == normalize(value)
        }?.findAll { !isDisabledOption(it) } as List<Option>
        if (matches?.size() > 1) {
            throw new IllegalStateException(
                    "${issue.key}: multiple active PI options named '${value}' exist in the field context")
        }
        return matches ? matches.first() : null
    }

    // ---------------------------------------------------------------- writing

    /**
     * The single place that writes the data invariant on one issue.
     *
     * @param queueState value to write into PI_SYNC_REQUIRED, or null to leave that field untouched
     * @return UNCHANGED, UPDATED or FAILED
     */
    protected String applyValues(MutableIssue issue, String targetValue, String targetOverrideKey, String queueState) {
        String target = normalize(targetValue)
        String targetOverride = normalize(targetOverrideKey)

        Option targetOption = target ? findActiveOption(issue, target) : null
        if (target && !targetOption) {
            log.error("${issue.key}: active option '${target}' is not available in the relevant field context")
            return "FAILED"
        }

        Object currentRaw = issue.getCustomFieldValue(cfProjectInformation)
        String currentValue = selectValue(issue)
        String currentText = normalize(textValue(issue, cfTextMirror))
        String currentOverride = normalize(textValue(issue, cfOverride))
        String currentQueue = normalize(textValue(issue, cfPiSyncRequired))

        boolean piChanged = currentValue != target
        boolean textChanged = currentText != target
        boolean overrideChanged = currentOverride != targetOverride
        boolean queueChanged = queueState != null && currentQueue != normalize(queueState)
        if (!piChanged && !textChanged && !overrideChanged && !queueChanged) return "UNCHANGED"

        try {
            inTransaction {
                DefaultIssueChangeHolder holder = new DefaultIssueChangeHolder()
                if (piChanged) {
                    Collection<Option> oldOptions =
                            currentRaw instanceof Collection ? (Collection<Option>) currentRaw : null
                    Collection<Option> newOptions = targetOption ? [targetOption] : null
                    cfProjectInformation.updateValue(null, issue,
                            new ModifiedValue(oldOptions ?: null, newOptions), holder)
                    issue.setCustomFieldValue(cfProjectInformation, newOptions)
                }
                if (textChanged) updateText(cfTextMirror, issue, currentText, target, holder)
                if (overrideChanged) updateText(cfOverride, issue, currentOverride, targetOverride, holder)
                if (queueChanged) updateText(cfPiSyncRequired, issue, currentQueue, normalize(queueState), holder)
                return null
            }
            indexingService.reIndex(issue)
            return "UPDATED"
        } catch (Exception e) {
            log.error("${issue.key}: Project Information write failed: ${e.message}", e)
            return "FAILED"
        }
    }

    /**
     * Marks an issue as a source for the incremental job, without disturbing an entry that is
     * already pending or currently being processed.
     *
     * Only an authority may be queued. Queueing a descendant instead would make the job treat that
     * descendant's own (possibly empty) value as the truth and propagate it downwards, and the
     * original problem would never be retried.
     *
     * Call this only when no other issue lock is held, so two issue locks are never nested.
     */
    protected boolean queueForSync(Long issueId) {
        if (!issueId) return false
        return withIssueLock(issueId) {
            MutableIssue latest = issueManager.getIssueObject(issueId) as MutableIssue
            if (!latest) return false
            String state = normalize(textValue(latest, cfPiSyncRequired))
            if (QUEUE_PENDING.equalsIgnoreCase(state) || QUEUE_PROCESSING.equalsIgnoreCase(state)) return false
            setQueueState(latest, QUEUE_PENDING)
            return true
        }
    }

    /** Writes PI_SYNC_REQUIRED only. An empty state clears the field. */
    protected void setQueueState(MutableIssue issue, String state) {
        String current = normalize(textValue(issue, cfPiSyncRequired))
        String target = normalize(state)
        if (current == target) return
        inTransaction {
            updateText(cfPiSyncRequired, issue, current, target, new DefaultIssueChangeHolder())
            return null
        }
        indexingService.reIndex(issue)
    }

    /** Writes without producing change history; the issue object is kept in sync for later reads. */
    protected static void updateText(CustomField field, MutableIssue issue,
                                     String oldValue, String newValue, DefaultIssueChangeHolder holder) {
        String value = newValue?.trim() ?: null
        field.updateValue(null, issue, new ModifiedValue(oldValue ?: null, value), holder)
        issue.setCustomFieldValue(field, value)
    }

    // ---------------------------------------------------------------- queue state machine

    private static final Pattern FAILED_STATE = Pattern.compile("(?i)^FAILED\\s+(\\d+)\$")

    /** Attempts recorded in a "FAILED n" state, or -1 when the state is not a failure state. */
    protected static int failedAttempts(String queueState) {
        Matcher matcher = FAILED_STATE.matcher(normalize(queueState))
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1
    }

    /**
     * Attempts already spent on this issue, or -1 when it must not be claimed. "true" is a fresh
     * entry, "FAILED n" is a retry until MAX_SYNC_ATTEMPTS is reached; after that the issue is left
     * alone so a permanently broken item cannot loop every five minutes.
     */
    protected static int claimableAttempts(String queueState) {
        String state = normalize(queueState)
        if (QUEUE_PENDING.equalsIgnoreCase(state)) return 0
        int attempts = failedAttempts(state)
        return (attempts >= 0 && attempts < MAX_SYNC_ATTEMPTS) ? attempts : -1
    }

    protected static boolean isExhausted(String queueState) {
        return failedAttempts(queueState) >= MAX_SYNC_ATTEMPTS
    }

    protected static String failedState(int attempts) {
        return "${QUEUE_FAILED} ${attempts}"
    }

    /** Matches every claimable and exhausted state; exact filtering happens in code. */
    protected static String queueSearchJql() {
        String field = "cf[${numericId(CF_PI_SYNC_REQUIRED)}]"
        return "${field} ~ \"${QUEUE_PENDING}\" OR ${field} ~ \"${QUEUE_FAILED}\""
    }

    // ---------------------------------------------------------------- search

    /**
     * Returns issue IDs only. Callers reload issues one at a time, so a query matching hundreds of
     * thousands of issues cannot exhaust the heap.
     */
    protected List<Long> searchIssueIds(ApplicationUser user, String jql) {
        def parsed = searchService.parseQuery(user, jql)
        if (!parsed.valid) throw new IllegalArgumentException("Invalid JQL '${jql}': ${parsed.errors}")

        List<Long> ids = []
        int start = 0
        while (true) {
            PagerFilter pager = new PagerFilter(SEARCH_BATCH_SIZE)
            pager.start = start
            def hits = searchService.searchOverrideSecurity(user, parsed.query, pager).results
            if (!hits) break
            hits.each { if (it?.id != null) ids << (it.id as Long) }
            start += hits.size()
            if (hits.size() < SEARCH_BATCH_SIZE) break
        }
        return ids
    }

    // ---------------------------------------------------------------- locking and transactions

    protected <T> T withIssueLock(Long issueId, Closure<T> work) {
        if (!issueId) throw new IllegalArgumentException("Issue ID is required for the PI synchronization lock")
        return withNamedLock("${LOCK_NAMESPACE}-${issueId}", work)
    }

    protected <T> T withNamedLock(String lockName, Closure<T> work) {
        if (!clusterLockService) throw new IllegalStateException("ClusterLockService is not available")
        def lock = clusterLockService.getLockForName(lockName)
        lock.lock()
        try {
            return work.call()
        } finally {
            lock.unlock()
        }
    }

    protected boolean tryWithNamedLock(String lockName, Closure work) {
        if (!clusterLockService) throw new IllegalStateException("ClusterLockService is not available")
        def lock = clusterLockService.getLockForName(lockName)
        if (!lock.tryLock()) return false
        try {
            work.call()
            return true
        } finally {
            lock.unlock()
        }
    }

    protected <T> T inTransaction(Closure<T> work) {
        if (!transactionTemplate) throw new IllegalStateException("TransactionTemplate is not available")
        return transactionTemplate.execute { work.call() } as T
    }

    // ---------------------------------------------------------------- mail

    protected void sendMail(String subject, String body) {
        try {
            def smtp = mailServerManager?.defaultSMTPMailServer
            if (!smtp) throw new IllegalStateException("Default SMTP mail server is not configured")
            String recipients = ERROR_RECIPIENTS.findAll { it }.join(",")
            if (!recipients) throw new IllegalStateException("No mail recipients are configured")
            Email email = new Email(recipients)
            email.setSubject(subject)
            email.setMimeType("text/html; charset=UTF-8")
            email.setBody(body)
            smtp.send(email)
        } catch (Exception e) {
            log.error("Could not send mail '${subject}': ${e.message}", e)
        }
    }

    /** Truncated, because one failing subtree can produce thousands of rows. */
    protected static String htmlTable(List<String> headers, List<List<Object>> rows, String emptyText) {
        String head = headers.collect { "<th>${html(it)}</th>" }.join("")
        String body = rows.take(MAX_MAIL_ROWS).collect { List<Object> row ->
            "<tr>" + row.collect { "<td>${html(it)}</td>" }.join("") + "</tr>"
        }.join("\n")
        if (rows.size() > MAX_MAIL_ROWS) {
            body += "\n<tr><td colspan=\"${headers.size()}\">" +
                    "... ${rows.size() - MAX_MAIL_ROWS} further rows omitted; see the log</td></tr>"
        }
        String fallback = "<tr><td colspan=\"${headers.size()}\">${html(emptyText)}</td></tr>"
        return "<table border=\"1\" cellpadding=\"5\" cellspacing=\"0\">\n" +
                "<tr>${head}</tr>\n${body ?: fallback}\n</table>"
    }

    // ---------------------------------------------------------------- small helpers

    protected static String normalize(String value) { value == null ? "" : value.trim() }

    protected static String numericId(String id) { id.replace("customfield_", "") }

    protected static boolean isDisabledOption(Option option) {
        if (!option) return false
        try { return option.disabled as boolean }
        catch (Exception ignored) {
            try { return option.isDisabled() as boolean }
            catch (Exception ignoredAgain) { return false }
        }
    }

    protected static String html(Object value) {
        (value?.toString() ?: "").replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace('"', "&quot;")
    }

    private static String configured(String propertyName, String defaultValue) {
        String value = System.getProperty(propertyName)
        return value?.trim() ?: defaultValue
    }

    private static int configuredInt(String propertyName, int defaultValue) {
        try { return Integer.parseInt(configured(propertyName, defaultValue.toString())) }
        catch (Exception ignored) { return defaultValue }
    }

    private static String[] configuredList(String propertyName, String defaultValue) {
        return configured(propertyName, defaultValue)
                .split(",").collect { it.trim() }.findAll { it } as String[]
    }
}
