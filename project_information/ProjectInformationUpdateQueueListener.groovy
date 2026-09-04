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

    private static final boolean ENABLE_OLD_PROJECT_INFORMATION_SYNC = false

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
                        fieldChanged(changedFields, cfEpicLink) ||
                        systemFieldChanged(changedFields, "parent", "Parent") ||
                        systemFieldChanged(changedFields, "issuetype", "Issue Type") ||
                        systemFieldChanged(changedFields, "project", "Project")

        // Internal silent writes have no matching user-field change and therefore do not queue again.
        //if (!newFieldChanged && !oldFieldChanged) return
        if (!newFieldChanged && !oldFieldChanged && !hierarchyChanged) return

        try {
            Map outcome = withIssueLock(eventIssue.id) {
                MutableIssue source = issueManager.getIssueObject(eventIssue.id) as MutableIssue
                if (!source) {
                    throw new IllegalStateException("Cannot reload ${eventIssue.key}")
                }

                boolean directChange = newFieldChanged || oldFieldChanged
                String requestedValue = directChange
                        ? resolveRequestedValue(event, source, newFieldChanged, oldFieldChanged)
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
                return [key: source.key, authority: authority, result: result]
            }
            log.info("PI synchronization queued for ${outcome.key}; result=${outcome.result}; " +
                    "value='${outcome.authority.value ?: ''}'; " +
                    "override='${outcome.authority.overrideKey ?: ''}'")
        } catch (Exception e) {
            log.error("PI update listener failed for ${eventIssue.key}: ${e.message}", e)
        }
    }

    private String resolveRequestedValue(IssueEvent event, MutableIssue source,
                                         boolean newFieldChanged, boolean oldFieldChanged) {
        if (newFieldChanged) {
            Map changedValue = getChangedFieldValue(event, cfProjectInformation)
            String value = changedValue.found
                    ? (changedValue.value?.toString()?.trim() ?: "")
                    : selectValue(source)
            if (oldFieldChanged) {
                Map changedOldValue = getChangedFieldValue(event, cfOldProjectInformation)
                String oldValue = changedOldValue.found
                        ? extractOldProjectInformation(changedOldValue.value)
                        : extractOldProjectInformation(source.getCustomFieldValue(cfOldProjectInformation))
                if (normalize(oldValue) != normalize(value)) {
                    log.warn("Both PI fields changed on ${source.key}; ${CF_PROJECT_INFORMATION_N} wins")
                }
            }
            return value
        }
        if (!oldFieldChanged) return ""
        Map changedOldValue = getChangedFieldValue(event, cfOldProjectInformation)
        return changedOldValue.found
                ? extractOldProjectInformation(changedOldValue.value)
                : extractOldProjectInformation(source.getCustomFieldValue(cfOldProjectInformation))
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
        Option targetOption = target ? findActiveOption(issue, target) : null
        if (target && !targetOption) {
            log.error("${issue.key}: active option '${target}' not found in relevant context")
            return "FAILED"
        }

        Object currentRaw = issue.getCustomFieldValue(cfProjectInformation)
        String currentValue = selectValue(issue)
        String currentText = textValue(issue, cfTextMirror)
        String currentOverride = textValue(issue, cfOverride).trim()
        String currentQueue = textValue(issue, cfPiSyncRequired).trim()

        boolean piChanged = normalize(currentValue) != normalize(target)
        boolean textChanged = normalize(currentText) != normalize(target)
        boolean overrideChanged = normalize(currentOverride) != normalize(targetOverride)
        boolean queueChanged = !"true".equalsIgnoreCase(currentQueue)
        if (!piChanged && !textChanged && !overrideChanged && !queueChanged) return "UNCHANGED"

        try {
            inTransaction {
                DefaultIssueChangeHolder holder = new DefaultIssueChangeHolder()
                if (piChanged) {
                    Collection<Option> oldOptions = currentRaw instanceof Collection
                            ? currentRaw as Collection<Option> : []
                    Collection<Option> newOptions = targetOption ? [targetOption] : []
                    cfProjectInformation.updateValue(null, issue,
                            new ModifiedValue(oldOptions, newOptions), holder)
                    issue.setCustomFieldValue(cfProjectInformation, newOptions)
                }
                if (textChanged) {
                    updateTextFieldSilently(cfTextMirror, issue, currentText, target, holder)
                    issue.setCustomFieldValue(cfTextMirror, target ?: null)
                }
                if (overrideChanged) {
                    updateTextFieldSilently(cfOverride, issue, currentOverride, targetOverride, holder)
                    issue.setCustomFieldValue(cfOverride, targetOverride ?: null)
                }
                if (queueChanged) {
                    updateTextFieldSilently(cfPiSyncRequired, issue, currentQueue, "true", holder)
                    issue.setCustomFieldValue(cfPiSyncRequired, "true")
                }
                return null
            }
            indexingService.reIndex(issue)
            return "UPDATED"
        } catch (Exception e) {
            log.error("${issue.key}: local PI queue update failed: ${e.message}", e)
            return "FAILED"
        }
    }

    private Issue getParent(Issue issue) {
        if (!issue) return null

        if (issue.parentObject) return issue.parentObject

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

    private Map getChangedFieldValue(IssueEvent event, CustomField field) {
        if (!field) return [found: false, value: null]
        Set<String> aliases = [field.id?.toLowerCase(Locale.ROOT),
                               field.name?.toLowerCase(Locale.ROOT),
                               field.idAsLong?.toString()].findAll { it } as Set<String>

        List matchingItems = event.changeLog?.getRelated("ChildChangeItem")?.findAll { item ->
            ["field", "fieldid"].any { String property ->
                try {
                    String token = item.getString(property)?.trim()?.toLowerCase(Locale.ROOT)
                    return token && aliases.contains(token)
                } catch (Exception ignored) {
                    return false
                }
            }
        } as List
        if (!matchingItems) return [found: false, value: null]

        List<String> displayValues = matchingItems.collect { item ->
            try { return item.getString("newstring") as String }
            catch (Exception ignored) { return null }
        }.findAll { it != null }.collect { it.trim() }.unique()
        if (displayValues) {
            List<String> nonEmptyDisplayValues = displayValues.findAll { it }
            return [found: true,
                    value: nonEmptyDisplayValues ? nonEmptyDisplayValues.join(",") : ""]
        }

        List<String> storedValues = matchingItems.collect { item ->
            try { return item.getString("newvalue") as String }
            catch (Exception ignored) { return null }
        }.findAll { it != null }.collect { it.trim() }.unique()
        return [found: true, value: storedValues ? storedValues.join(",") : null]
    }

    private static boolean fieldChanged(Set<String> changedFields, CustomField field) {
        if (!field) return false
        Set<String> aliases = [field.id?.toLowerCase(Locale.ROOT),
                               field.name?.toLowerCase(Locale.ROOT),
                               field.idAsLong?.toString()].findAll { it } as Set<String>
        return changedFields.any { aliases.contains(it) }
    }

    private static boolean systemFieldChanged(Set<String> changedFields, String... aliases) {
        Set<String> normalizedAliases = aliases.findAll { it }
                .collect { it.trim().toLowerCase(Locale.ROOT) } as Set<String>
        return changedFields.any { normalizedAliases.contains(it) }
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

    private Option findActiveOption(Issue issue, String value) {
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

    private static boolean isDisabledOption(Option option) {
        try { return option?.disabled as boolean }
        catch (Exception ignored) {
            try { return option?.isDisabled() as boolean }
            catch (Exception ignoredAgain) { return false }
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
        if (!clusterLockService) missing << "ClusterLockService"
        if (!transactionTemplate) missing << "TransactionTemplate"
        return missing ? "missing custom fields: ${missing.join(', ')}" : null
    }

    private static String normalize(String value) {
        value == null ? "" : value.trim()
    }
}
