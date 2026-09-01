package project_information

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.ModifiedValue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder
import groovy.json.JsonSlurper
import org.apache.log4j.Logger


/**
 * ProjectInformationConfig
 *
 * @author chabrecek.anton
 * Created on 26. 8. 2026.

 * UPDATE listener, Phase 1.
 *
 * On a direct change of Project Information N it updates only the source issue:
 *   - Project Information Text
 *   - Project Information Override Key
 *   - PI_SYNC_REQUIRED = true
 *
 * Descendants are processed by ProjectInformationIncrementalSyncJob.
 */
class ProjectInformationUpdateQueueListener extends ProjectInformationConfig {

    private static final Logger log = Logger.getLogger("scriptrunner.listener.project-information-update-queue")

    //private static final String ISSUE_TYPE_INITIATIVE = "Initiative"
    private static final boolean ENABLE_OLD_PROJECT_INFORMATION_SYNC = false
    private static final int MAX_HIERARCHY_DEPTH = 50

    //private final Map<Long, Issue> parentCache = [:]
    //private final Set<Long> resolvedParents = [] as Set<Long>

    void handle(IssueEvent event) {
        Issue eventIssue = event?.issue
        if (!eventIssue) return

        String validationError = validateConfiguration()
        if (validationError) {
            log.error("PI update listener disabled for ${eventIssue.key}: ${validationError}")
            return
        }

        Set<String> changedFields = getChangedFieldTokens(event)
        boolean newFieldChanged = fieldChanged(changedFields, cfProjectInformation)
        boolean oldFieldChanged = ENABLE_OLD_PROJECT_INFORMATION_SYNC &&
                fieldChanged(changedFields, cfOldProjectInformation)

        boolean hierarchyChanged =
                fieldChanged(changedFields, cfParentLink) ||
                        fieldChanged(changedFields, cfEpicLink)

        // Internal silent writes have no matching user-field change and therefore do not queue again.
        //if (!newFieldChanged && !oldFieldChanged) return
        if (!newFieldChanged && !oldFieldChanged && !hierarchyChanged) return

        MutableIssue source = issueManager.getIssueObject(eventIssue.id) as MutableIssue
        if (!source) {
            log.error("Cannot reload ${eventIssue.key}")
            return
        }

        try {
            boolean directChange = newFieldChanged || oldFieldChanged
            String requestedValue = directChange
                    ? resolveRequestedValue(source, newFieldChanged, oldFieldChanged)
                    : selectValue(source)

            Map authority = calculateSourceAuthority(source, requestedValue, directChange, hierarchyChanged)

            String result = applySourceValuesAndQueue(
                    source,
                    authority.value as String,
                    authority.overrideKey as String
            )
            if (result == "FAILED") {
                throw new IllegalStateException("Source issue could not be queued")
            }
            log.info("PI synchronization queued for ${source.key}; result=${result}; " +
                    "value='${authority.value ?: ''}'; override='${authority.overrideKey ?: ''}'")
        } catch (Exception e) {
            log.error("PI update listener failed for ${source.key}: ${e.message}", e)
        }
    }

    private String resolveRequestedValue(MutableIssue source, boolean newFieldChanged, boolean oldFieldChanged) {
        if (newFieldChanged) {
            String value = selectValue(source)
            if (oldFieldChanged) {
                String oldValue = extractOldProjectInformation(source.getCustomFieldValue(cfOldProjectInformation))
                if (normalize(oldValue) != normalize(value)) {
                    log.warn("Both PI fields changed on ${source.key}; ${CF_PROJECT_INFORMATION_N} wins")
                }
            }
            return value
        }
        return oldFieldChanged
                ? extractOldProjectInformation(source.getCustomFieldValue(cfOldProjectInformation))
                : ""
    }

    private Map calculateSourceAuthority(
            Issue source,
            String requestedValue,
            boolean directChange,
            boolean hierarchyChanged) {

        String requested = requestedValue?.trim() ?: ""
        String currentOverride = textValue(source, cfOverride).trim()

        if (directChange) {
            if (requested) {
                return [
                        value      : requested,
                        overrideKey: source.key,
                        sourceKey  : source.key
                ]
            }


            return resolveEffectiveAuthority(getParent(source))
        }

        if (hierarchyChanged &&
                normalize(currentOverride) == normalize(source.key)) {

            return [
                    value      : requested,
                    overrideKey: source.key,
                    sourceKey  : source.key
            ]
        }

        return resolveEffectiveAuthority(getParent(source))
    }

    private Map resolveEffectiveAuthority(Issue issue) {
        if (!issue) {
            return [value: "", overrideKey: "", sourceKey: ""]
        }

        Set<Long> seen = [] as Set<Long>
        Issue current = issue
        int depth = 0

        while (current && depth++ < MAX_HIERARCHY_DEPTH) {
            if (!seen.add(current.id)) {
                throw new IllegalStateException(
                        "Cycle detected while resolving authority at ${current.key}"
                )
            }

            String value = selectValue(current)
            String overrideKey = textValue(current, cfOverride).trim()

            if (value) {
                String authorityKey = overrideKey ?: current.key

                return [
                        value      : value,
                        overrideKey: authorityKey,
                        sourceKey  : authorityKey
                ]
            }

            current = getParent(current)
        }

        if (current) {
            throw new IllegalStateException(
                    "Hierarchy depth exceeds ${MAX_HIERARCHY_DEPTH}"
            )
        }

        return [value: "", overrideKey: "", sourceKey: ""]
    }

    private String applySourceValuesAndQueue(MutableIssue issue, String targetValue, String targetOverrideKey) {
        String target = targetValue?.trim() ?: ""
        String targetOverride = targetOverrideKey?.trim() ?: ""
        String currentText = textValue(issue, cfTextMirror)
        String currentOverride = textValue(issue, cfOverride).trim()
        String currentQueue = textValue(issue, cfPiSyncRequired).trim()

        boolean textChanged = normalize(currentText) != normalize(target)
        boolean overrideChanged = normalize(currentOverride) != normalize(targetOverride)
        boolean queueChanged = !"true".equalsIgnoreCase(currentQueue)
        if (!textChanged && !overrideChanged && !queueChanged) return "UNCHANGED"

        DefaultIssueChangeHolder holder = new DefaultIssueChangeHolder()
        try {
            if (textChanged) updateTextFieldSilently(cfTextMirror, issue, currentText, target, holder)
            if (overrideChanged) updateTextFieldSilently(cfOverride, issue, currentOverride, targetOverride, holder)
            if (queueChanged) updateTextFieldSilently(cfPiSyncRequired, issue, currentQueue, "true", holder)

            issue.setCustomFieldValue(cfTextMirror, target ?: null)
            issue.setCustomFieldValue(cfOverride, targetOverride ?: null)
            issue.setCustomFieldValue(cfPiSyncRequired, "true")
            indexingService.reIndex(issue)
            return "UPDATED"
        } catch (Exception e) {
            log.error("${issue.key}: local PI queue update failed: ${e.message}", e)
            return "FAILED"
        }
    }

    private Issue getParent(Issue issue) {
        if (!issue) return null

        if (issue.isSubTask()) {
            return issue.parentObject
        }

        Issue parent = resolveIssue(issue.getCustomFieldValue(cfEpicLink))

        if (!parent) {
            parent = resolveIssue(issue.getCustomFieldValue(cfParentLink))
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

    private Set<String> getChangedFieldTokens(IssueEvent event) {
        Set<String> result = [] as Set<String>
        event.changeLog?.getRelated("ChildChangeItem")?.each { item ->
            ["field", "fieldid"].each { String property ->
                try {
                    String token = item.getString(property)
                    if (token) result.add(token.trim().toLowerCase(Locale.ROOT))
                } catch (Exception ignored) { }
            }
        }
        return result
    }

    private static boolean fieldChanged(Set<String> changedFields, CustomField field) {
        if (!field) return false
        Set<String> aliases = [field.id?.toLowerCase(Locale.ROOT),
                               field.name?.toLowerCase(Locale.ROOT),
                               field.idAsLong?.toString()].findAll { it } as Set<String>
        return changedFields.any { aliases.contains(it) }
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

    private static void updateTextFieldSilently(CustomField field, MutableIssue issue,
                                                String oldValue, String newValue,
                                                DefaultIssueChangeHolder holder) {
        field.updateValue(null, issue,
                new ModifiedValue(oldValue ?: null, newValue ?: null), holder)
    }

    private static String textValue(Issue issue, CustomField field) {
        issue?.getCustomFieldValue(field)?.toString() ?: ""
    }

    static String extractOldProjectInformation(Object raw) {
        if (!raw?.toString()?.trim()) return ""
        String text = raw.toString().trim()
        try {
            def parsed = new JsonSlurper().parseText(text)
            return parsed?.rows?.getAt(0)?.getAt(0)?.toString()?.trim() ?: ""
        } catch (Exception ignored) {
            return (text.startsWith("{") || text.startsWith("[")) ? "" : text
        }
    }

    private String validateConfiguration() {
        List<String> missing = []
        if (!cfProjectInformation) missing << CF_PROJECT_INFORMATION_N
        if (!cfTextMirror) missing << CF_PROJECT_INFORMATION_TEXT
        if (!cfOverride) missing << CF_PROJECT_INFORMATION_OVERRIDE_KEY
        if (!cfPiSyncRequired) missing << CF_PI_SYNC_REQUIRED
        if (ENABLE_OLD_PROJECT_INFORMATION_SYNC && !cfOldProjectInformation) missing << CF_OLD_PROJECT_INFORMATION
        if (!cfParentLink) missing << CF_PARENT_LINK
        if (!cfEpicLink) missing << CF_EPIC_LINK
        return missing ? "missing custom fields: ${missing.join(', ')}" : null
    }

    /*private static boolean isInitiative(Issue issue) {
        issue?.issueType?.name == ISSUE_TYPE_INITIATIVE
    }*/

    private static String normalize(String value) {
        value == null ? "" : value.trim()
    }
}
