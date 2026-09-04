package project_information

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.ModifiedValue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder
import groovy.json.JsonOutput
import org.apache.log4j.Logger

/**
 * ProjectInformationCreateListener
 *
 * @author chabrecek.anton
 * Created on 26. 8. 2026.
 */

class ProjectInformationCreateListener extends ProjectInformationConfig{

    private static final Logger log =
            Logger.getLogger("scriptrunner.listener.project-information-create")

    private static final boolean ENABLE_OLD_PROJECT_INFORMATION_SYNC = false

    private final def customFieldManager = ComponentAccessor.customFieldManager
    private final def issueManager = ComponentAccessor.issueManager
    private final def optionsManager = ComponentAccessor.optionsManager
    private final def subTaskManager = ComponentAccessor.subTaskManager

    private final IssueIndexingService indexingService =
            ComponentAccessor.getComponent(IssueIndexingService)

    private final CustomField cfProjectInformation =
            customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_N)

    private final CustomField cfTextMirror =
            customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_TEXT)

    private final CustomField cfOverrideKey =
            customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_OVERRIDE_KEY)

    private final CustomField cfOldProjectInformation =
            customFieldManager.getCustomFieldObject(CF_OLD_PROJECT_INFORMATION)

    private final CustomField cfParentLink =
            customFieldManager.getCustomFieldObject(CF_PARENT_LINK)

    private final CustomField cfEpicLink =
            customFieldManager.getCustomFieldObject(CF_EPIC_LINK)

    void handle(IssueEvent event) {

        log.warn("=== PI INIT START ===")

        Issue created = event?.issue

        log.warn("Event issue=${created?.key}, " + "type=${created?.issueType?.name}, " + "typeIsSubTask=${created?.issueType?.subTask}, " + "issueIsSubTask=${created?.isSubTask()}, " + "parent=${created?.parentObject?.key}")

        if (!created) {
            log.warn("EXIT: no issue")
            return
        }

        MutableIssue issue = created as MutableIssue

        if (!issue) {
            log.warn("EXIT: event issue is not mutable")
            return
        }

        String validationError = validateConfiguration()
        if (validationError) {
            log.error("PI create listener disabled for ${issue.key}: ${validationError}")
            return
        }

        try {
            withIssueLock(issue.id) {
                MutableIssue latest = issueManager.getIssueObject(issue.id) as MutableIssue
                if (!latest) {
                    throw new IllegalStateException("Cannot reload ${issue.key}")
                }

                String suppliedValue = selectValue(latest)
                if (suppliedValue) {
                    // A value supplied on create is a deliberate local override, including on a root issue.
                    applyInitialValues(latest, suppliedValue, latest.key)
                    return
                }

                Issue parent = getParent(latest)
                log.warn("Parent resolved=${parent?.key}, issue=${latest.key}, type=${latest.issueType?.name}")
                if (!parent) {
                    log.warn("EXIT: parent not found for ${latest.key}")
                    return
                }

                Map authority = resolveEffectiveAuthority(parent)
                if (!authority?.value) {
                    log.warn("EXIT: authority value is empty")
                    return
                }

                log.warn("Authority for ${latest.key}: value='${authority.value}', " +
                        "override='${authority.overrideKey}'")
                applyInitialValues(latest,
                        authority.value as String,
                        authority.overrideKey as String)
            }
        } catch (Exception e) {
            log.error("PI initialization failed for ${issue.key}: ${e.message}", e)
        }
    }

    private void applyInitialValues(MutableIssue issue, String value, String overrideKey) {

        log.warn("Applying PI to ${issue.key}, " + "value='${value}', override='${overrideKey}'")
        Option option = findActiveOption(issue, value)

        if (!option) {
            log.error("${issue.key}: option '${value}' not found")
            return
        }

        inTransaction {
            DefaultIssueChangeHolder holder = new DefaultIssueChangeHolder()
            Object currentValue = issue.getCustomFieldValue(cfProjectInformation)
            Collection<Option> oldOptions =
                    currentValue instanceof Collection ? (Collection<Option>) currentValue : []
            Collection<Option> newOptions = [option]

            if (normalize(selectValue(issue)) != normalize(value)) {
                cfProjectInformation.updateValue(null, issue,
                        new ModifiedValue(oldOptions, newOptions), holder)
                issue.setCustomFieldValue(cfProjectInformation, newOptions)
            }

            Object currentText = issue.getCustomFieldValue(cfTextMirror)
            if (normalize(currentText?.toString()) != normalize(value)) {
                cfTextMirror.updateValue(null, issue,
                        new ModifiedValue(currentText, value), holder)
                issue.setCustomFieldValue(cfTextMirror, value)
            }

            Object currentOverride = issue.getCustomFieldValue(cfOverrideKey)
            if (normalize(currentOverride?.toString()) != normalize(overrideKey)) {
                cfOverrideKey.updateValue(null, issue,
                        new ModifiedValue(currentOverride, overrideKey ?: null), holder)
                issue.setCustomFieldValue(cfOverrideKey, overrideKey ?: null)
            }

            if (ENABLE_OLD_PROJECT_INFORMATION_SYNC) {
                synchronizeOldProjectInformation(issue, value, holder)
            }
            return null
        }

        indexingService.reIndex(issue)
    }

    private void synchronizeOldProjectInformation(MutableIssue issue,
                                                  String targetValue,
                                                  DefaultIssueChangeHolder changeHolder) {

        if (!ENABLE_OLD_PROJECT_INFORMATION_SYNC) {
            return
        }

        Object currentRaw =
                issue.getCustomFieldValue(cfOldProjectInformation)

        String serialized =
                targetValue ? JsonOutput.toJson([rows: [[targetValue]]]) : null

        cfOldProjectInformation.updateValue(null,
                issue,
                new ModifiedValue(currentRaw, serialized),
                changeHolder)

        issue.setCustomFieldValue(cfOldProjectInformation,
                serialized)
    }

    private Issue getParent(Issue issue) {

        log.warn("Resolving parent for ${issue?.key}")

        if (!issue) {
            log.warn("Parent resolve: issue is null")
            return null
        }

        log.warn("Parent diagnostics: " + "type=${issue.issueType?.name}, " + "typeIsSubTask=${issue.issueType?.subTask}, " + "issueIsSubTask=${issue.isSubTask()}, " + "parentObject=${issue.parentObject?.key}")

        /*
        * Do not depend on issue.isSubTask().
        * During Issue Created processing it may still return false,
        * even though the issue type is Sub-task.
        */
        Issue directParent = issue.parentObject

        if (directParent) {
            log.warn("Direct parentObject resolved=${directParent.key}")
            return directParent
        }

        /*
        * Fallback through SubTaskManager.
        */
        Long parentId = null

        try {
            parentId = subTaskManager.getParentIssueId(issue)
        } catch (Exception e) {
            log.warn("Cannot resolve parent ID through SubTaskManager for ${issue.key}",
                    e)
        }

        log.warn("SubTaskManager parentId=${parentId}")

        if (parentId) {
            Issue subTaskParent = issueManager.getIssueObject(parentId)

            log.warn("SubTaskManager resolved parent=${subTaskParent?.key}")

            if (subTaskParent) {
                return subTaskParent
            }
        }

        /*
        * Epic Link fallback.
        */
        Object epic = cfEpicLink ? issue.getCustomFieldValue(cfEpicLink) : null

        log.warn("EpicLink value=${epic}")

        if (epic) {
            Issue epicParent = resolveIssue(epic)

            log.warn("EpicLink resolved parent=${epicParent?.key}")

            if (epicParent) {
                return epicParent
            }
        }

        /*
        * Advanced Roadmaps Parent Link fallback.
        */
        Object parentLink = cfParentLink ? issue.getCustomFieldValue(cfParentLink) : null

        log.warn("ParentLink value=${parentLink}")

        if (parentLink) {
            Issue hierarchyParent = resolveIssue(parentLink)

            log.warn("ParentLink resolved parent=${hierarchyParent?.key}")

            if (hierarchyParent) {
                return hierarchyParent
            }
        }

        log.warn("No parent found for ${issue.key}")
        return null
    }

    private Issue resolveIssue(Object value) {

        if (!value) {
            return null
        }

        if (value instanceof Issue) {
            return value
        }

        try {
            if (value.hasProperty("key")) {

                Issue byProperty =
                        issueManager.getIssueObject(value.key?.toString())

                if (byProperty) {
                    return byProperty
                }
            }
        } catch (Exception ignored) {
        }

        String key =
                value.toString()?.trim()

        return key ? issueManager.getIssueObject(key) : null
    }

    private Map resolveEffectiveAuthority(Issue issue) {

        if (!issue) {
            return [value      : "",
                    overrideKey: "",
                    sourceKey  : ""]
        }

        Set<Long> seen = [] as Set<Long>

        Issue current = issue
        int depth = 0

        while (current && depth++ < MAX_HIERARCHY_DEPTH) {

            if (!seen.add(current.id)) {
                throw new IllegalStateException("Cycle detected at ${current.key}")
            }

            String value = selectValue(current)

            String overrideKey =
                    textValue(current, cfOverrideKey)?.trim()

            if (value) {

                log.warn("Authority walk: ${current.key}")
                log.warn("Value=${value}")
                log.warn("Override=${overrideKey}")

                if (overrideKey) {
                    return [value      : value,
                            overrideKey: overrideKey,
                            sourceKey  : overrideKey]
                }

                return [value      : value,
                        overrideKey: current.key,
                        sourceKey  : current.key]
            }

            current = getParent(current)
        }

        if (current) {
            throw new IllegalStateException(
                    "Hierarchy depth exceeds ${MAX_HIERARCHY_DEPTH}")
        }

        return [value      : "",
                overrideKey: "",
                sourceKey  : ""]
    }

    private Option findActiveOption(Issue issue,
                                    String value) {

        def config =
                cfProjectInformation.getRelevantConfig(issue)

        if (!config) {
            return null
        }

        List<Option> matches = optionsManager.getOptions(config)?.findAll {
                    normalize(it?.value?.toString()) == normalize(value)
                }?.findAll { !isDisabledOption(it) } as List<Option>
        if (matches?.size() > 1) {
            throw new IllegalStateException(
                    "${issue.key}: multiple active PI options named '${value}' exist in the field context")
        }
        return matches ? matches.first() : null
    }

    private String selectValue(Issue issue) {

        Object raw =
                issue?.getCustomFieldValue(cfProjectInformation)

        log.warn("selectValue issue=${issue?.key}, " + "rawClass=${raw?.getClass()?.name}, " + "rawValue=${raw}")

        if (!raw) {
            return ""
        }

        if (raw instanceof Collection) {
            List<Option> values = raw.findAll { it instanceof Option } as List<Option>
            if (values.size() > 1) {
                throw new IllegalStateException(
                        "${issue.key}: Project Information contains ${values.size()} values; exactly one is supported")
            }
            return values ? values.first().value?.trim() ?: "" : ""
        }

        if (raw instanceof Option) {
            return raw.value?.trim() ?: ""
        }

        return raw.toString()?.trim() ?: ""
    }

    private static String textValue(Issue issue,
                                    CustomField field) {

        return issue?.getCustomFieldValue(field)?.toString() ?: ""
    }

    private static boolean isDisabledOption(Option option) {

        if (!option) {
            return false
        }

        try {
            return option.disabled as boolean
        } catch (Exception ignored) {
            try {
                return option.isDisabled()
            } catch (Exception ignoredAgain) {
                return false
            }
        }
    }

    private static String normalize(String value) {

        return value == null ? "" : value.trim()
    }

    private String validateConfiguration() {
        List<String> missing = []
        if (!cfProjectInformation) missing << CF_PROJECT_INFORMATION_N
        if (!cfTextMirror) missing << CF_PROJECT_INFORMATION_TEXT
        if (!cfOverrideKey) missing << CF_PROJECT_INFORMATION_OVERRIDE_KEY
        if (ENABLE_OLD_PROJECT_INFORMATION_SYNC && !cfOldProjectInformation) missing << CF_OLD_PROJECT_INFORMATION
        if (!cfParentLink) missing << CF_PARENT_LINK
        if (!cfEpicLink) missing << CF_EPIC_LINK
        if (!subTaskManager) missing << "SubTaskManager"
        if (!clusterLockService) missing << "ClusterLockService"
        if (!transactionTemplate) missing << "TransactionTemplate"
        return missing ? "missing components or custom fields: ${missing.join(', ')}" : null
    }
}
