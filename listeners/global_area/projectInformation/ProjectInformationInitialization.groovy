package listeners.global_area.prjinf

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

class ProjectInformationInitialization {

    private static final Logger log =
            Logger.getLogger("scriptrunner.listener.project-information-hierarchy")

    private static final String CF_PROJECT_INFORMATION_N = "customfield_18600"   //prod: customfield_18702
    private static final String CF_PROJECT_INFORMATION_TEXT = "customfield_18700" //prod: customfield_20200
    private static final String CF_PROJECT_INFORMATION_OVERRIDE_KEY = "customfield_18802" //prod: customfield_20201
    private static final String CF_OLD_PROJECT_INFORMATION = "customfield_15600"

    private static final String CF_PARENT_LINK = "customfield_10301"
    private static final String CF_EPIC_LINK = "customfield_10001"

    private static final String ISSUE_TYPE_INITIATIVE = "Initiative"

    private static final boolean ENABLE_OLD_PROJECT_INFORMATION_SYNC = false

    private static final int MAX_HIERARCHY_DEPTH = 50

    private final def customFieldManager = ComponentAccessor.customFieldManager
    private final def issueManager = ComponentAccessor.issueManager
    private final def optionsManager = ComponentAccessor.optionsManager

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

        Issue parent = getParent(issue)

        log.warn("Parent resolved=${parent?.key}, " + "issueIsSubTask=${issue.isSubTask()}, " + "typeIsSubTask=${issue.issueType?.subTask}")

        log.warn("Issue=${issue.key}, " + "type=${issue.issueType?.name}, " + "parent=${parent?.key}")

        if (!parent) {
            log.warn("EXIT: parent not found for ${issue.key}")
            return
        }

        if (selectValue(issue)) {
            log.warn("EXIT: issue already contains value '${selectValue(issue)}'")
            return
        }

        Map authority = resolveEffectiveAuthority(parent)

        if (!authority?.value) {
            log.warn("EXIT: authority value is empty")
            return
        }

        log.warn("Authority for ${issue.key}: " + "value='${authority.value}', " + "override='${authority.overrideKey}'")

        applyInitialValues(issue,
                authority.value as String,
                authority.overrideKey as String)
    }

    private void applyInitialValues(MutableIssue issue, String value, String overrideKey) {

        log.warn("Applying PI to ${issue.key}, " + "value='${value}', override='${overrideKey}'")
        Option option = findActiveOption(issue, value)

        if (!option) {
            log.error("${issue.key}: option '${value}' not found")
            return
        }

        DefaultIssueChangeHolder holder =
                new DefaultIssueChangeHolder()

        Object currentValue = issue.getCustomFieldValue(cfProjectInformation)

        Collection<Option> oldOptions =
                currentValue instanceof Collection ? (Collection<Option>) currentValue : []

        Collection<Option> newOptions =
                [option]

        cfProjectInformation.updateValue(null,
                issue,
                new ModifiedValue(oldOptions,
                        newOptions),
                holder)

        cfTextMirror.updateValue(null,
                issue,
                new ModifiedValue(issue.getCustomFieldValue(cfTextMirror),
                        value),
                holder)

        cfOverrideKey.updateValue(null,
                issue,
                new ModifiedValue(issue.getCustomFieldValue(cfOverrideKey),
                        overrideKey),
                holder)

        issue.setCustomFieldValue(cfProjectInformation,
                newOptions)

        issue.setCustomFieldValue(cfTextMirror,
                value)

        issue.setCustomFieldValue(cfOverrideKey,
                overrideKey)

        if (ENABLE_OLD_PROJECT_INFORMATION_SYNC) {
            synchronizeOldProjectInformation(issue,
                    value,
                    holder)
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

                if (isInitiative(current)) {
                    return [value      : value,
                            overrideKey: "",
                            sourceKey  : current.key]
                }

                return [value      : value,
                        overrideKey: current.key,
                        sourceKey  : current.key]
            }

            current = getParent(current)
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

        Option option =
                optionsManager.getOptions(config)?.find {
                    normalize(it?.value?.toString()) == normalize(value)
                }

        if (!option) {
            return null
        }

        if (isDisabledOption(option)) {
            return null
        }

        return option
    }

    private String selectValue(Issue issue) {

        Object raw =
                issue?.getCustomFieldValue(cfProjectInformation)

        log.warn("selectValue issue=${issue?.key}, " + "rawClass=${raw?.getClass()?.name}, " + "rawValue=${raw}")

        if (!raw) {
            return ""
        }

        if (raw instanceof Collection) {

            Option first =
                    raw.find { it instanceof Option } as Option

            return first?.value?.trim() ?: ""
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

    private static boolean isInitiative(Issue issue) {

        return issue?.issueType?.name == ISSUE_TYPE_INITIATIVE
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

        return value == null ? null : value.trim()
    }
}