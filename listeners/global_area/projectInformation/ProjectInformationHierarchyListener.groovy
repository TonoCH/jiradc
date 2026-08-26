package listeners.global_area.prjinf

import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.ModifiedValue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.web.bean.PagerFilter
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.log4j.Logger

class ProjectInformationHierarchyListener {

    private static final Logger log = Logger.getLogger("scriptrunner.listener.project-information-hierarchy")

    private static final String CF_PROJECT_INFORMATION_N = "customfield_18600"   //prod: "customfield_18702"
    private static final String CF_PROJECT_INFORMATION_TEXT = "customfield_18700" //prod: "customfield_20200"
    private static final String CF_PROJECT_INFORMATION_OVERRIDE_KEY = "customfield_18802" //prod: "customfield_20201"
    private static final String CF_OLD_PROJECT_INFORMATION = "customfield_15600"

    private static final String CF_PARENT_LINK = "customfield_10301"
    private static final String CF_EPIC_LINK = "customfield_10001"
    private static final String ISSUE_TYPE_INITIATIVE = "Initiative"
    private static final String JIRA_BOT = "jira.bot"

    private static final boolean ENABLE_OLD_PROJECT_INFORMATION_SYNC = false

    private static final int SEARCH_BATCH_SIZE = 200
    private static final int MAX_HIERARCHY_DEPTH = 50

    private final def customFieldManager = ComponentAccessor.customFieldManager
    private final def issueManager = ComponentAccessor.issueManager
    private final def optionsManager = ComponentAccessor.optionsManager
    private final def userManager = ComponentAccessor.userManager
    private final SearchService searchService = ComponentAccessor.getComponent(SearchService)
    private final IssueIndexingService indexingService = ComponentAccessor.getComponent(IssueIndexingService)

    private final CustomField cfProjectInformation = customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_N)
    private final CustomField cfTextMirror = customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_TEXT)
    private final CustomField cfOverrideKey = customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_OVERRIDE_KEY)
    private final CustomField cfOldProjectInformation = customFieldManager.getCustomFieldObject(CF_OLD_PROJECT_INFORMATION)
    private final CustomField cfParentLink = customFieldManager.getCustomFieldObject(CF_PARENT_LINK)
    private final CustomField cfEpicLink = customFieldManager.getCustomFieldObject(CF_EPIC_LINK)

    private final Map<Long, Issue> parentCache = [:]
    private final Set<Long> resolvedParents = [] as Set<Long>

    void handle(IssueEvent event) {

        log.warn("PI listener received event: issue=${eventIssue.key}, " +
                "eventTypeId=${event.eventTypeId}, " + "eventClass=${event.class.name}, " + "changeLogId=${event.changeLog?.id}")

        Issue eventIssue = event?.issue
        if (!eventIssue) {
            return
        }

        String validationError = validateConfiguration()
        if (validationError) {
            log.error("Project Information listener disabled for ${eventIssue.key}: ${validationError}")
            return
        }

        Set<String> changedFields = getChangedFieldTokens(event)
        log.warn("PI listener changed fields for ${eventIssue.key}: ${changedFields}")

        boolean newFieldChanged = fieldChanged(changedFields, cfProjectInformation)
        boolean oldFieldChanged = ENABLE_OLD_PROJECT_INFORMATION_SYNC &&
                fieldChanged(changedFields, cfOldProjectInformation)

        if (!newFieldChanged && !oldFieldChanged) {
            return
        }

        ApplicationUser bot = userManager.getUserByName(JIRA_BOT)
        if (!bot) {
            log.error("Project Information listener cannot process ${eventIssue.key}: user '${JIRA_BOT}' not found")
            return
        }

        MutableIssue source = issueManager.getIssueObject(eventIssue.id) as MutableIssue
        if (!source) {
            log.error("Project Information listener cannot reload ${eventIssue.key}")
            return
        }

        try {
            String requestedValue = resolveRequestedValue(source, newFieldChanged, oldFieldChanged)
            Map authority = calculateSourceAuthority(source, requestedValue)

            log.info("Project Information distribution started from ${source.key}; " +
                    "trigger=${newFieldChanged ? CF_PROJECT_INFORMATION_N : CF_OLD_PROJECT_INFORMATION}; " +
                    "value='${authority.value ?: ''}'; override='${authority.overrideKey ?: ''}'")

            Set<Long> visited = [] as Set<Long>
            int updated = distributeFromSource(source, authority, bot, visited)

            log.info("Project Information distribution finished for ${source.key}; " +
                    "visited=${visited.size()}; updated=${updated}")
        } catch (Exception e) {
            log.error("Project Information distribution failed for ${source.key}: ${e.message}", e)
        }
    }


    private String resolveRequestedValue(MutableIssue source,
                                         boolean newFieldChanged,
                                         boolean oldFieldChanged) {
        if (newFieldChanged) {
            String value = selectValue(source)
            if (oldFieldChanged) {
                String oldValue = extractOldProjectInformation(source.getCustomFieldValue(cfOldProjectInformation))
                if (normalize(oldValue) != normalize(value)) {
                    log.warn("Both Project Information fields changed on ${source.key}; " +
                            "${CF_PROJECT_INFORMATION_N} wins over ${CF_OLD_PROJECT_INFORMATION}")
                }
            }
            return value
        }

        return oldFieldChanged
                ? extractOldProjectInformation(source.getCustomFieldValue(cfOldProjectInformation))
                : ""
    }

    private Map calculateSourceAuthority(Issue source, String requestedValue) {
        String requested = requestedValue?.trim() ?: ""

        if (isInitiative(source)) {
            return [value: requested, overrideKey: source.key, sourceKey: requested ? source.key : ""]
        }

        Issue parent = getParent(source)
        Map parentAuthority = resolveEffectiveAuthority(parent)
        String parentValue = parentAuthority.value ?: ""
        String parentOverride = parentAuthority.overrideKey ?: ""

        if (!requested) {
            return [
                    value: parentValue,
                    overrideKey: parentOverride,
                    sourceKey: parentAuthority.sourceKey ?: ""
            ]
        }

        if (parentValue && normalize(requested) == normalize(parentValue)) {
            return [
                    value: parentValue,
                    overrideKey: parentOverride,
                    sourceKey: parentAuthority.sourceKey ?: ""
            ]
        }

        return [value: requested, overrideKey: source.key, sourceKey: source.key]
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
                throw new IllegalStateException("Cycle detected while resolving authority at ${current.key}")
            }

            String value = selectValue(current)
            String override = textValue(current, cfOverrideKey).trim()

            if (value) {
                if (override) {
                    return [value: value, overrideKey: override, sourceKey: override]
                }
                if (isInitiative(current)) {
                    return [value: value, overrideKey: "", sourceKey: current.key]
                }

                return [value: value, overrideKey: current.key, sourceKey: current.key]
            }

            current = getParent(current)
        }

        if (depth >= MAX_HIERARCHY_DEPTH) {
            throw new IllegalStateException("Hierarchy depth exceeds ${MAX_HIERARCHY_DEPTH}")
        }

        return [value: "", overrideKey: "", sourceKey: ""]
    }

    private int distributeFromSource(MutableIssue source,
                                     Map authority,
                                     ApplicationUser bot,
                                     Set<Long> visited) {
        int updated = 0

        String sourceResult = applyValuesSilently(source, authority.value as String,
                authority.overrideKey as String)
        if (sourceResult == "UPDATED") {
            updated++
        } else if (sourceResult == "FAILED") {
            throw new IllegalStateException("Source ${source.key} could not be synchronized")
        }
        visited.add(source.id)

        List<Issue> frontier = findDirectChildren(source, bot)
        int depth = 0

        while (frontier && depth++ < MAX_HIERARCHY_DEPTH) {
            List<Issue> next = []

            frontier.sort { it.key }.each { Issue child ->
                if (!child || !visited.add(child.id)) {
                    return
                }

                String childOverride = textValue(child, cfOverrideKey).trim()
                boolean independentOverrideRoot = childOverride == child.key

                if (independentOverrideRoot) {
                    log.debug("Boundary ${child.key}: existing override root; subtree skipped")
                    return
                }

                MutableIssue mutableChild = issueManager.getIssueObject(child.id) as MutableIssue
                if (!mutableChild) {
                    log.error("Cannot reload descendant ${child.key}; subtree skipped")
                    return
                }

                String childResult = applyValuesSilently(
                        mutableChild,
                        authority.value as String,
                        authority.overrideKey as String
                )

                if (childResult != "FAILED") {
                    if (childResult == "UPDATED") {
                        updated++
                    }
                    next.addAll(findDirectChildren(mutableChild, bot))
                } else {
                    // Do not continue below an issue that could not be made consistent.
                    log.error("Subtree below ${child.key} skipped because its values could not be synchronized")
                }
            }

            frontier = next
        }

        if (frontier) {
            log.error("Distribution from ${source.key} stopped after ${MAX_HIERARCHY_DEPTH} levels")
        }

        return updated
    }

    private String applyValuesSilently(MutableIssue issue,
                                       String targetValue,
                                       String targetOverrideKey) {
        String target = targetValue?.trim() ?: ""
        String targetOverride = targetOverrideKey?.trim() ?: ""

        Option targetOption = target ? findActiveOption(issue, target) : null
        if (target && !targetOption) {
            log.error("${issue.key}: active option '${target}' not found in relevant context; issue not changed")
            return "FAILED"
        }

        Object currentOption = issue.getCustomFieldValue(cfProjectInformation)
        String currentValue = selectValue(issue)
        String currentText = textValue(issue, cfTextMirror)
        String currentOverride = textValue(issue, cfOverrideKey).trim()
        String currentOld = ENABLE_OLD_PROJECT_INFORMATION_SYNC
                ? extractOldProjectInformation(issue.getCustomFieldValue(cfOldProjectInformation))
                : ""

        boolean newChanged = normalize(currentValue) != normalize(target)
        boolean textChanged = normalize(currentText) != normalize(target)
        boolean overrideChanged = normalize(currentOverride) != normalize(targetOverride)
        boolean oldChanged = ENABLE_OLD_PROJECT_INFORMATION_SYNC &&
                normalize(currentOld) != normalize(target)

        if (!newChanged && !textChanged && !overrideChanged && !oldChanged) {
            return "UNCHANGED"
        }

        DefaultIssueChangeHolder changeHolder = new DefaultIssueChangeHolder()

        try {
            if (newChanged) {
                Collection<Option> oldOptions =
                        currentOption instanceof Collection
                                ? currentOption as Collection<Option>
                                : []

                Collection<Option> newOptions =
                        targetOption
                                ? [targetOption]
                                : []

                cfProjectInformation.updateValue(
                        null,
                        issue,
                        new ModifiedValue(oldOptions, newOptions),
                        changeHolder
                )

                issue.setCustomFieldValue(
                        cfProjectInformation,
                        newOptions
                )
            }

            if (textChanged) {
                updateTextFieldSilently(cfTextMirror, issue, currentText, target, changeHolder)
                issue.setCustomFieldValue(cfTextMirror, target ?: null)
            }

            if (overrideChanged) {
                updateTextFieldSilently(cfOverrideKey, issue, currentOverride, targetOverride, changeHolder)
                issue.setCustomFieldValue(cfOverrideKey, targetOverride ?: null)
            }

            if (oldChanged) {
                synchronizeOldProjectInformation(issue, target, changeHolder)
            }

            //due to performace disabled
            //indexingService.reIndex(issue)
            log.debug("${issue.key}: synchronized value='${target}', override='${targetOverride}', oldSync=${ENABLE_OLD_PROJECT_INFORMATION_SYNC}")
            return "UPDATED"
        } catch (Exception e) {
            log.error("${issue.key}: silent Project Information update failed: ${e.message}", e)
            return "FAILED"
        }
    }

    private static void updateTextFieldSilently(CustomField field,
                                                MutableIssue issue,
                                                String oldValue,
                                                String newValue,
                                                DefaultIssueChangeHolder changeHolder) {
        field.updateValue(
                null,
                issue,
                new ModifiedValue(oldValue ?: null, newValue ?: null),
                changeHolder
        )
    }

    private void synchronizeOldProjectInformation(MutableIssue issue,
                                                  String targetValue,
                                                  DefaultIssueChangeHolder changeHolder) {
        if (!ENABLE_OLD_PROJECT_INFORMATION_SYNC) {
            return
        }

        Object currentRaw = issue.getCustomFieldValue(cfOldProjectInformation)
        String serialized = targetValue
                ? JsonOutput.toJson([rows: [[targetValue]]])
                : null

        cfOldProjectInformation.updateValue(
                null,
                issue,
                new ModifiedValue(currentRaw, serialized),
                changeHolder
        )
        issue.setCustomFieldValue(cfOldProjectInformation, serialized)
    }

    private Option findActiveOption(Issue issue, String value) {
        def config = cfProjectInformation.getRelevantConfig(issue)
        if (!config) {
            return null
        }

        Option option = optionsManager.getOptions(config)?.find {
            normalize(it?.value?.toString()) == normalize(value)
        }

        if (option && isDisabledOption(option)) {
            return null
        }
        return option
    }

    private List<Issue> findDirectChildren(Issue parent, ApplicationUser user) {
        if (!parent) {
            return []
        }

        String key = parent.key
        String jql = "parent = ${key} OR cf[${numericId(CF_EPIC_LINK)}] = ${key} OR cf[${numericId(CF_PARENT_LINK)}] = ${key}"
        def parsed = searchService.parseQuery(user, jql)
        if (!parsed.valid) {
            throw new IllegalArgumentException("Invalid child JQL for ${key}: ${parsed.errors}")
        }

        Map<Long, Issue> children = [:]
        int start = 0

        while (true) {
            PagerFilter pager = new PagerFilter(SEARCH_BATCH_SIZE)
            pager.start = start
            def hits = searchService.search(user, parsed.query, pager).results
            if (!hits) {
                break
            }

            hits.each { hit ->
                Issue candidate = issueManager.getIssueObject(hit.id)
                if (candidate && getParent(candidate)?.id == parent.id) {
                    children[candidate.id] = candidate
                }
            }

            start += hits.size()
            if (hits.size() < SEARCH_BATCH_SIZE) {
                break
            }
        }

        return children.values() as List<Issue>
    }

    private Issue getParent(Issue issue) {
        if (!issue) {
            return null
        }
        if (resolvedParents.contains(issue.id)) {
            return parentCache[issue.id]
        }

        Issue parent = null
        try {
            if (issue.isSubTask()) {
                parent = issue.parentObject
            } else {
                parent = resolveIssue(issue.getCustomFieldValue(cfEpicLink))
                if (!parent) {
                    parent = resolveIssue(issue.getCustomFieldValue(cfParentLink))
                }
            }
        } finally {
            resolvedParents.add(issue.id)
            parentCache[issue.id] = parent
        }
        return parent
    }

    private Issue resolveIssue(Object value) {
        if (!value) {
            return null
        }
        if (value instanceof Issue) {
            return value as Issue
        }
        try {
            if (value.hasProperty("key")) {
                Issue byProperty = issueManager.getIssueObject(value.key?.toString())
                if (byProperty) {
                    return byProperty
                }
            }
        } catch (Exception ignored) {
            // Continue with string fallback.
        }
        String key = value.toString()?.trim()
        return key ? issueManager.getIssueObject(key) : null
    }

    private Set<String> getChangedFieldTokens(IssueEvent event) {
        Set<String> result = [] as Set<String>
        event.changeLog?.getRelated("ChildChangeItem")?.each { item ->
            ["field", "fieldid"].each { String property ->
                try {
                    String token = item.getString(property)
                    if (token) {
                        result.add(token.trim().toLowerCase(Locale.ROOT))
                    }
                } catch (Exception ignored) {
                    // Jira versions differ in exposed change-item properties.
                }
            }
        }
        return result
    }

    private static boolean fieldChanged(Set<String> changedFields, CustomField field) {
        if (!field) {
            return false
        }
        Set<String> aliases = [
                field.id?.toLowerCase(Locale.ROOT),
                field.name?.toLowerCase(Locale.ROOT),
                field.idAsLong?.toString()
        ].findAll { it } as Set<String>

        return changedFields.any { aliases.contains(it) }
    }

    private String selectValue(Issue issue) {
        def raw = issue?.getCustomFieldValue(cfProjectInformation)

        if (!raw) {
            return ""
        }

        if (raw instanceof Collection) {
            Option first = raw.find { it instanceof Option } as Option
            return first?.value?.trim() ?: ""
        }

        if (raw instanceof Option) {
            return raw.value?.trim() ?: ""
        }

        return raw.toString()?.trim() ?: ""
    }

    private static String textValue(Issue issue, CustomField field) {
        return issue?.getCustomFieldValue(field)?.toString() ?: ""
    }

    static String extractOldProjectInformation(Object raw) {
        if (!raw?.toString()?.trim()) {
            return ""
        }

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
        if (!cfProjectInformation) missing.add(CF_PROJECT_INFORMATION_N)
        if (!cfTextMirror) missing.add(CF_PROJECT_INFORMATION_TEXT)
        if (!cfOverrideKey) missing.add(CF_PROJECT_INFORMATION_OVERRIDE_KEY)
        if (ENABLE_OLD_PROJECT_INFORMATION_SYNC && !cfOldProjectInformation) missing.add(CF_OLD_PROJECT_INFORMATION)
        if (!cfParentLink) missing.add(CF_PARENT_LINK)
        if (!cfEpicLink) missing.add(CF_EPIC_LINK)
        return missing ? "missing custom fields: ${missing.join(', ')}" : null
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

    private static boolean isInitiative(Issue issue) {
        return issue?.issueType?.name == ISSUE_TYPE_INITIATIVE
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim()
    }

    private static String numericId(String customFieldId) {
        return customFieldId.replace("customfield_", "")
    }
}
