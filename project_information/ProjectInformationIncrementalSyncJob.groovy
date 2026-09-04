package project_information

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
import org.apache.log4j.Logger

/**
 * ProjectInformationConfig
 *
 * @author chabrecek.anton
 * Created on 26. 8. 2026.

 * Run every 5 minutes.
 * Sends mail only if an error occurs.
 * Queue race protection: true -> PROCESSING -> null.
 * If the listener changes PROCESSING back to true during processing, the job does not clear it.
 */
class ProjectInformationIncrementalSyncJob extends ProjectInformationConfig {
    private static final Logger log = Logger.getLogger("scriptrunner.job.project-information-incremental")

    private final Map<Long, Issue> parentCache = [:]
    private final Set<Long> resolvedParents = [] as Set<Long>

    void run() {
        List<Map> errors = []
        int rootsFound = 0
        int rootsCompleted = 0
        int visitedCount = 0
        int updatedCount = 0

        String configError = validateConfiguration()
        ApplicationUser bot = userManager.getUserByName(JIRA_BOT)
        if (configError || !bot) {
            String message = configError ?: "User '${JIRA_BOT}' not found"
            errors << [root: "CONFIG", issue: "", error: message]
            sendErrorMail(rootsFound, rootsCompleted, visitedCount, updatedCount, errors)
            return
        }

        List<Issue> roots
        try {
            roots = searchAll(bot, "cf[${numericId(CF_PI_SYNC_REQUIRED)}] ~ \\\"true\\\"")
            roots = roots.findAll { "true".equalsIgnoreCase(textValue(it, cfPiSyncRequired).trim()) }
                    .sort { it.key }
            rootsFound = roots.size()
        } catch (Exception e) {
            errors << [root: "SEARCH", issue: "", error: e.message ?: e.class.name]
            sendErrorMail(rootsFound, rootsCompleted, visitedCount, updatedCount, errors)
            return
        }

        roots.each { Issue rootHit ->
            MutableIssue root = issueManager.getIssueObject(rootHit.id) as MutableIssue
            if (!root || !"true".equalsIgnoreCase(textValue(root, cfPiSyncRequired).trim())) return

            try {
                setQueueState(root, "PROCESSING")
                // Authority is the value and override already calculated by the update listener.
                String value = selectValue(root)
                String override = textValue(root, cfOverride).trim()
                if (value && !override) {
                    throw new IllegalStateException("Root has value '${value}' but no Override Key")
                }

                Set<Long> visited = [] as Set<Long>
                Map result = distribute(root, value, override, bot, visited, errors)
                visitedCount += visited.size()
                updatedCount += result.updated as int
                if (!(result.failed as boolean)) {
                    // Clear only if nobody queued a newer change while this root was processing.
                    MutableIssue latest = issueManager.getIssueObject(root.id) as MutableIssue
                    if (latest && "PROCESSING".equalsIgnoreCase(textValue(latest, cfPiSyncRequired).trim())) {
                        setQueueState(latest, null)
                        rootsCompleted++
                    } else {
                        log.info("${root.key}: queue was changed during processing; retained for next run")
                    }
                } else {
                    resetToTrueIfStillProcessing(root.id)
                }
            } catch (Exception e) {
                errors << [root: root.key, issue: root.key, error: e.message ?: e.class.name]
                resetToTrueIfStillProcessing(root.id)
                log.error("PI incremental synchronization failed for ${root.key}: ${e.message}", e)
            }
        }

        log.info("PI incremental synchronization finished; rootsFound=${rootsFound}; " +
                "rootsCompleted=${rootsCompleted}; visited=${visitedCount}; updated=${updatedCount}; errors=${errors.size()}")
        if (errors) sendErrorMail(rootsFound, rootsCompleted, visitedCount, updatedCount, errors)
    }

    private Map distribute(MutableIssue root, String value, String override,
                           ApplicationUser bot, Set<Long> visited, List<Map> errors) {
        int updated = 0
        boolean failed = false
        visited.add(root.id)
        List<Issue> frontier = findDirectChildren(root, bot)
        int depth = 0

        while (frontier && depth++ < MAX_HIERARCHY_DEPTH) {
            List<Issue> next = []
            frontier.sort { it.key }.each { Issue child ->
                if (!child || !visited.add(child.id)) return

                String childOverride = textValue(child, cfOverride).trim()
                if (childOverride == child.key) {
                    log.debug("Boundary ${child.key}: independent override root; subtree skipped")
                    return
                }

                MutableIssue mutableChild = issueManager.getIssueObject(child.id) as MutableIssue
                if (!mutableChild) {
                    failed = true
                    errors << [root: root.key, issue: child.key, error: "Cannot reload descendant"]
                    return
                }

                String result = applyValues(mutableChild, value, override)
                if (result == "FAILED") {
                    failed = true
                    errors << [root: root.key, issue: child.key, error: "Values could not be synchronized"]
                    return
                }
                if (result == "UPDATED") updated++
                next.addAll(findDirectChildren(mutableChild, bot))
            }
            frontier = next
        }
        if (frontier) {
            failed = true
            errors << [root: root.key, issue: root.key,
                       error: "Hierarchy exceeds ${MAX_HIERARCHY_DEPTH} levels"]
        }
        return [updated: updated, failed: failed]
    }

    private String applyValues(MutableIssue issue, String targetValue, String targetOverrideKey) {
        String target = targetValue?.trim() ?: ""
        String targetOverride = targetOverrideKey?.trim() ?: ""
        Option targetOption = target ? findActiveOption(issue, target) : null
        if (target && !targetOption) {
            log.error("${issue.key}: active option '${target}' not found in relevant context")
            return "FAILED"
        }

        Object currentRaw = issue.getCustomFieldValue(cfProjectInformation)
        String currentValue = selectValue(issue)
        String currentText = textValue(issue, cfTextMirror)
        String currentOverride = textValue(issue, cfOverride).trim()
        boolean piChanged = normalize(currentValue) != normalize(target)
        boolean textChanged = normalize(currentText) != normalize(target)
        boolean overrideChanged = normalize(currentOverride) != normalize(targetOverride)
        if (!piChanged && !textChanged && !overrideChanged) return "UNCHANGED"

        DefaultIssueChangeHolder holder = new DefaultIssueChangeHolder()
        try {
            if (piChanged) {
                Collection<Option> oldOptions = currentRaw instanceof Collection
                        ? currentRaw as Collection<Option> : []
                Collection<Option> newOptions = targetOption ? [targetOption] : []
                cfProjectInformation.updateValue(null, issue, new ModifiedValue(oldOptions, newOptions), holder)
                issue.setCustomFieldValue(cfProjectInformation, newOptions)
            }
            if (textChanged) {
                updateText(cfTextMirror, issue, currentText, target, holder)
                issue.setCustomFieldValue(cfTextMirror, target ?: null)
            }
            if (overrideChanged) {
                updateText(cfOverride, issue, currentOverride, targetOverride, holder)
                issue.setCustomFieldValue(cfOverride, targetOverride ?: null)
            }
            indexingService.reIndex(issue)
            return "UPDATED"
        } catch (Exception e) {
            log.error("${issue.key}: PI synchronization write failed: ${e.message}", e)
            return "FAILED"
        }
    }

    private List<Issue> findDirectChildren(Issue parent, ApplicationUser user) {
        String key = parent.key
        String jql = "parent = ${key} OR cf[${numericId(CF_EPIC_LINK)}] = ${key} " +
                "OR cf[${numericId(CF_PARENT_LINK)}] = ${key}"
        List<Issue> candidates = searchAll(user, jql)
        Map<Long, Issue> children = [:]
        candidates.each { Issue candidate ->
            if (candidate && getParent(candidate)?.id == parent.id) children[candidate.id] = candidate
        }
        return children.values() as List<Issue>
    }

    private List<Issue> searchAll(ApplicationUser user, String jql) {
        def parsed = searchService.parseQuery(user, jql)
        if (!parsed.valid) throw new IllegalArgumentException("Invalid JQL '${jql}': ${parsed.errors}")
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

    private Issue getParent(Issue issue) {
        if (!issue) return null
        if (resolvedParents.contains(issue.id)) return parentCache[issue.id]
        Issue parent = null
        try {
            if (issue.isSubTask()) parent = issue.parentObject
            else {
                parent = resolveIssue(issue.getCustomFieldValue(cfEpicLink))
                if (!parent) parent = resolveIssue(issue.getCustomFieldValue(cfParentLink))
            }
        } finally {
            resolvedParents.add(issue.id)
            parentCache[issue.id] = parent
        }
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

    private Option findActiveOption(Issue issue, String value) {
        def config = cfProjectInformation.getRelevantConfig(issue)
        if (!config) return null
        Option option = optionsManager.getOptions(config)?.find {
            normalize(it?.value?.toString()) == normalize(value)
        }
        return option && isDisabledOption(option) ? null : option
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

    private void setQueueState(MutableIssue issue, String value) {
        String current = textValue(issue, cfPiSyncRequired)
        DefaultIssueChangeHolder holder = new DefaultIssueChangeHolder()
        updateText(cfPiSyncRequired, issue, current, value ?: "", holder)
        issue.setCustomFieldValue(cfPiSyncRequired, value ?: null)
        indexingService.reIndex(issue)
    }

    private void resetToTrueIfStillProcessing(Long issueId) {
        MutableIssue latest = issueManager.getIssueObject(issueId) as MutableIssue
        if (latest && "PROCESSING".equalsIgnoreCase(textValue(latest, cfPiSyncRequired).trim())) {
            setQueueState(latest, "true")
        }
    }

    private static void updateText(CustomField field, MutableIssue issue, String oldValue,
                                   String newValue, DefaultIssueChangeHolder holder) {
        field.updateValue(null, issue,
                new ModifiedValue(oldValue ?: null, newValue ?: null), holder)
    }

    private void sendErrorMail(int rootsFound, int rootsCompleted, int visited,
                               int updated, List<Map> errors) {
        try {
            def smtp = mailServerManager.defaultSMTPMailServer
            if (!smtp) throw new IllegalStateException("Default SMTP mail server is not configured")
            String rows = errors.collect { e ->
                "<tr><td>${html(e.root)}</td><td>${html(e.issue)}</td><td>${html(e.error)}</td></tr>"
            }.join("\n")
            String body = """
                <h2>Project Information incremental synchronization errors</h2>
                <p>Roots found: ${rootsFound}<br/>
                   Roots completed: ${rootsCompleted}<br/>
                   Issues visited: ${visited}<br/>
                   Issues updated: ${updated}<br/>
                   Errors: ${errors.size()}</p>
                <table border="1" cellpadding="5" cellspacing="0">
                  <tr><th>Root</th><th>Issue</th><th>Error</th></tr>${rows}
                </table>
            """
            Email email = new Email(ERROR_RECIPIENTS.findAll { it }.join(","))
            email.setSubject("[PI Sync] Errors found")
            email.setMimeType("text/html; charset=UTF-8")
            email.setBody(body)
            smtp.send(email)
        } catch (Exception e) {
            log.error("Could not send PI synchronization error email: ${e.message}", e)
        }
    }

    private String validateConfiguration() {
        List<String> missing = []
        if (!cfProjectInformation) missing << CF_PROJECT_INFORMATION_N
        if (!cfTextMirror) missing << CF_PROJECT_INFORMATION_TEXT
        if (!cfOverride) missing << CF_PROJECT_INFORMATION_OVERRIDE_KEY
        if (!cfPiSyncRequired) missing << CF_PI_SYNC_REQUIRED
        if (!cfParentLink) missing << CF_PARENT_LINK
        if (!cfEpicLink) missing << CF_EPIC_LINK
        return missing ? "Missing custom fields: ${missing.join(', ')}" : null
    }

    private static String textValue(Issue issue, CustomField field) {
        issue?.getCustomFieldValue(field)?.toString() ?: ""
    }
    private static String normalize(String value) { value == null ? "" : value.trim() }
    private static String numericId(String id) { id.replace("customfield_", "") }
    private static boolean isDisabledOption(Option option) {
        try { return option?.disabled as boolean }
        catch (Exception ignored) {
            try { return option?.isDisabled() as boolean }
            catch (Exception ignoredAgain) { return false }
        }
    }
    private static String html(Object value) {
        (value?.toString() ?: "").replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace('"', "&quot;")
    }
}
