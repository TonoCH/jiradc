package jobs.global.prjfinal

import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.fields.config.FieldConfig
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.web.bean.PagerFilter
import groovy.json.JsonSlurper
import org.apache.log4j.Logger

import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat

/**
 * Write migration for Project Information.
 *
 * Safety rules:
 * - loads the complete JQL scope before any write
 * - simulates the complete parent authority chain before any write
 * - skips conflicts, unresolved items and cycles
 * - creates missing options only in the issue's relevant field context
 * - temporarily enables disabled options and restores them after migration
 * - disables options created by this migration after all issue writes
 * - updates without events/mail and reindexes changed issues in batches
 * - writes a detailed CSV audit file
 */
class ProjectInfoMigrationExecute {

    private static final int SEARCH_BATCH_SIZE = 200
    private static final int REINDEX_BATCH_SIZE = 200
    private static final String SEPARATOR = ";"

    private static final String CF_PROJECT_INFORMATION_N = "customfield_18702"
    private static final String CF_OLD_PROJECT_INFORMATION = "customfield_15600"
    private static final String CF_PROJECT_INFORMATION_TEXT = "customfield_20200"
    private static final String CF_PROJECT_INFORMATION_OVERRIDE_KEY = "customfield_20201"
    private static final String CF_SAP_INPUT = "customfield_10225"
    private static final String CF_SAP_SCRIPTED = "customfield_10217"
    private static final String CF_PARENT_LINK = "customfield_10301"
    private static final String CF_EPIC_LINK = "customfield_10001"

    private static final String ISSUE_TYPE_INITIATIVE = "Initiative"
    private static final String LABEL_MULTIPLE = "Multiple project with [%s]"
    private static final String LABEL_HISTORY = "history [%s]"
    private static final String JIRA_BOT = "jira.bot"
    private static final String JQL_SCOPE = "cf[10217] is not EMPTY order by key ASC"

    // Keep true for the first controlled run. Set false only after reviewing the generated CSV.
    private static final boolean DRY_RUN = true

    private static final String[] CSV_HEADER = [
            "issueKey", "projectKey", "issueType", "summary", "parentKey",
            "currentProjectInformationN", "targetProjectInformationN",
            "currentProjectInformationText", "targetProjectInformationText",
            "currentOverrideKey", "targetOverrideKey",
            "directResolutionSource", "branch", "sourceIssue",
            "optionContextId", "optionAction", "conflictType",
            "result", "changed", "detail"
    ]

    private final Logger log = Logger.getLogger("scriptrunner.execute.project-info")
    private final def cfManager = ComponentAccessor.customFieldManager
    private final def issueManager = ComponentAccessor.issueManager
    private final def optManager = ComponentAccessor.optionsManager
    private final def userManager = ComponentAccessor.userManager
    private final SearchService searchService = ComponentAccessor.getComponent(SearchService)
    private final IssueIndexingService indexingService = ComponentAccessor.getComponent(IssueIndexingService)
    private final def jiraHomeComponent = ComponentAccessor.getComponent(com.atlassian.jira.config.util.JiraHome)

    private final def cfNewPI = cfManager.getCustomFieldObject(CF_PROJECT_INFORMATION_N)
    private final def cfOldPI = cfManager.getCustomFieldObject(CF_OLD_PROJECT_INFORMATION)
    private final def cfTextMirror = cfManager.getCustomFieldObject(CF_PROJECT_INFORMATION_TEXT)
    private final def cfOverrideKey = cfManager.getCustomFieldObject(CF_PROJECT_INFORMATION_OVERRIDE_KEY)
    private final def cfSapInput = cfManager.getCustomFieldObject(CF_SAP_INPUT)
    private final def cfSapScripted = cfManager.getCustomFieldObject(CF_SAP_SCRIPTED)
    private final def cfParentLink = cfManager.getCustomFieldObject(CF_PARENT_LINK)
    private final def cfEpicLink = cfManager.getCustomFieldObject(CF_EPIC_LINK)

    private final Map<Long, Map<String, Option>> optionsByConfig = [:]
    private final Map<String, List<Option>> sapToOptions = [:]
    private final Map<String, List<Map>> optionOccurrencesByValue = [:]
    private final Map<Long, Issue> outputIssuesById = [:]
    private final Map<Long, Map> directCache = [:]
    private final Map<Long, Map> simulationCache = [:]
    private final Map<Long, Issue> parentCache = [:]
    private final Set<Long> parentResolved = [] as Set
    private final Set<Long> recursionStack = [] as Set

    private final Set<Long> createdOptionIds = [] as Set
    private final Set<Long> temporarilyEnabledOptionIds = [] as Set
    private final List<Issue> changedIssues = []
    private final List<Map> rows = []

    private int conflictCount
    private int unresolvedCount
    private int errorCount
    private int changedCount
    private int unchangedCount
    private int dryRunChangeCount

    String run() {
        log.info("=== PROJECT INFORMATION EXECUTION START; DRY_RUN=${DRY_RUN} ===")
        ApplicationUser bot = userManager.getUserByName(JIRA_BOT)
        if (!bot) return fail("User '${JIRA_BOT}' not found")

        String validation = validateConfiguration()
        if (validation) return fail(validation)

        File csv = prepareOutputFile()
        if (!csv) return fail("Could not create output file")

        try {
            buildOptionIndexes()
            loadOutputIssues(bot)

            // Freeze all target decisions before touching any issue or option.
            outputIssuesById.values().sort { it.key }.each { Issue issue ->
                try {
                    simulateIssue(issue)
                } catch (Exception e) {
                    errorCount++
                    simulationCache[issue.id] = errorSimulation(e)
                    log.error("Simulation failed for ${issue.key}: ${e.message}", e)
                }
            }

            outputIssuesById.values().sort { it.key }.each { Issue issue ->
                executeOne(issue, bot)
            }

            if (!DRY_RUN) {
                reindexChangedIssues()
            }
        } finally {
            // Never disable newly created/enabled options before issue writes are finished.
            if (!DRY_RUN) {
                restoreOptionStates()
            }
            writeCsv(csv)
        }

        log.info("=== PROJECT INFORMATION EXECUTION END ===")
        log.info("Scope=${outputIssuesById.size()}, changed=${changedCount}, dryRunChanges=${dryRunChangeCount}, unchanged=${unchangedCount}, conflicts=${conflictCount}, unresolved=${unresolvedCount}, errors=${errorCount}")
        log.info("CSV=${csv.absolutePath}")

        return "Done. DRY_RUN=${DRY_RUN}, scope=${outputIssuesById.size()}, changed=${changedCount}, wouldChange=${dryRunChangeCount}, conflicts=${conflictCount}, unresolved=${unresolvedCount}, errors=${errorCount}. File: ${csv.absolutePath}"
    }

    private void executeOne(Issue issue, ApplicationUser bot) {
        Map sim = simulationCache[issue.id] ?: unresolvedSimulation(issue, "NOT_SIMULATED", "Issue was not simulated")
        Issue parent = getParent(issue)
        String currentPi = getProjectInformationNValue(issue)
        String currentText = textValue(issue, cfTextMirror)
        String currentOverride = textValue(issue, cfOverrideKey).trim()
        String targetPi = sim.simulatedPi ?: ""
        String targetText = sim.simulatedText ?: ""
        String targetOverride = sim.simulatedOverrideKey ?: ""

        Map conflict = detectConflict(currentPi, targetPi, currentText, targetText, currentOverride, targetOverride)
        boolean requiresChange = normalize(currentPi) != normalize(targetPi) ||
                normalize(currentText) != normalize(targetText) ||
                normalize(currentOverride) != normalize(targetOverride)

        Map row = baseRow(issue, parent, currentPi, targetPi, currentText, targetText, currentOverride, targetOverride, sim, conflict)

        if (sim.result == "ERROR") {
            row.result = "SKIPPED_ERROR"
            row.detail = sim.detail
            rows << row
            return
        }
        if ((sim.branch ?: "").startsWith("UNRESOLVED") || sim.branch in ["CYCLE_DETECTED", "NOT_SIMULATED"]) {
            unresolvedCount++
            row.result = "SKIPPED_${sim.branch}"
            row.detail = sim.detail
            rows << row
            return
        }
        if (conflict.conflict) {
            conflictCount++
            row.result = "SKIPPED_CONFLICT"
            row.detail = conflict.detail
            rows << row
            return
        }
        if (!requiresChange) {
            unchangedCount++
            row.result = "UNCHANGED"
            rows << row
            return
        }
        if (!targetPi) {
            unresolvedCount++
            row.result = "SKIPPED_EMPTY_TARGET"
            row.detail = "Target select-list value is empty"
            rows << row
            return
        }

        FieldConfig config = cfNewPI.getRelevantConfig(issue)
        if (!config) {
            errorCount++
            row.result = "SKIPPED_NO_FIELD_CONTEXT"
            row.detail = "No relevant context for ${CF_PROJECT_INFORMATION_N}"
            rows << row
            return
        }

        try {
            def targetOption = findOrPrepareOption(config, targetPi, row)
            if (!targetOption) throw new IllegalStateException("Could not resolve or create option '${targetPi}' in context ${config.id}")

            if (DRY_RUN) {
                dryRunChangeCount++
                row.result = "DRY_RUN_WOULD_CHANGE"
                row.changed = "false"
                rows << row
                return
            }

            MutableIssue mutable = issueManager.getIssueObject(issue.id) as MutableIssue
            mutable.setCustomFieldValue(cfNewPI, targetOption as Option)
            mutable.setCustomFieldValue(cfTextMirror, targetText ?: null)
            mutable.setCustomFieldValue(cfOverrideKey, targetOverride ?: null)
            issueManager.updateIssue(bot, mutable, EventDispatchOption.DO_NOT_DISPATCH, false)

            changedIssues << mutable
            changedCount++
            row.result = "UPDATED"
            row.changed = "true"
            rows << row
        } catch (Exception e) {
            errorCount++
            row.result = "ERROR"
            row.detail = "${e.class.simpleName}: ${e.message}"
            rows << row
            log.error("Update failed for ${issue.key}: ${e.message}", e)
        }
    }

    private Object findOrPrepareOption(FieldConfig config, String value, Map row) {
        Long configId = config.id as Long
        String norm = normalize(value)
        Option option = optionsByConfig[configId]?.get(norm)

        if (option) {
            row.optionContextId = configId.toString()
            if (isDisabledOption(option)) {
                row.optionAction = "ENABLE_TEMPORARILY"
                if (!DRY_RUN) {
                    optManager.enableOption(option)
                    temporarilyEnabledOptionIds << (option.optionId as Long)
                }
            } else {
                row.optionAction = "USE_EXISTING"
            }
            return option
        }

        row.optionContextId = configId.toString()
        row.optionAction = "CREATE_IN_CONTEXT_THEN_DISABLE"
        if (DRY_RUN) {
            // In dry-run there is deliberately no synthetic Option object and no write follows.
            return new Object()
        }

        Long nextSequence = ((optManager.getOptions(config)*.sequence.findAll { it != null }.max() ?: -1L) as Long) + 1L
        option = optManager.createOption(config, null, nextSequence, value)
        if (!option) return null

        createdOptionIds << (option.optionId as Long)
        optionsByConfig.computeIfAbsent(configId) { [:] }[norm] = option
        optionOccurrencesByValue.computeIfAbsent(norm) { [] }.add([configId: configId, optionId: option.optionId, value: value])
        String sap = extractSap(value)
        if (sap) sapToOptions.computeIfAbsent(sap) { [] }.add(option)
        return option
    }

    private void restoreOptionStates() {
        createdOptionIds.each { Long id ->
            try {
                Option option = optManager.findByOptionId(id)
                if (option && !isDisabledOption(option)) optManager.disableOption(option)
            } catch (Exception e) {
                errorCount++
                log.error("Could not disable newly created option ${id}: ${e.message}", e)
            }
        }
        temporarilyEnabledOptionIds.each { Long id ->
            try {
                Option option = optManager.findByOptionId(id)
                if (option && !isDisabledOption(option)) optManager.disableOption(option)
            } catch (Exception e) {
                errorCount++
                log.error("Could not restore disabled state for option ${id}: ${e.message}", e)
            }
        }
    }

    private void reindexChangedIssues() {
        changedIssues.collate(REINDEX_BATCH_SIZE).each { List<Issue> batch ->
            try {
                indexingService.reIndexIssueObjects(batch)
                log.info("Reindexed ${batch.size()} changed issues")
            } catch (Exception batchError) {
                log.warn("Batch reindex failed; retrying individually: ${batchError.message}")
                batch.each { Issue issue ->
                    try {
                        indexingService.reIndex(issue)
                    } catch (Exception issueError) {
                        errorCount++
                        log.error("Reindex failed for ${issue.key}: ${issueError.message}", issueError)
                    }
                }
            }
        }
    }

    private Map simulateIssue(Issue issue) {
        if (!issue) return syntheticUnresolved("NO_ISSUE", "Issue object is null")
        if (simulationCache.containsKey(issue.id)) return simulationCache[issue.id]
        if (recursionStack.contains(issue.id)) {
            Map cycle = unresolvedSimulation(issue, "CYCLE_DETECTED", "Cycle detected in parent hierarchy")
            simulationCache[issue.id] = cycle
            return cycle
        }

        recursionStack << issue.id
        try {
            Map direct = getDirectResolution(issue)
            Issue parent = getParent(issue)
            Map parentSim = parent ? simulateIssue(parent) : null
            Map sim

            if (isInitiative(issue)) {
                sim = direct.value ? simulation(issue, direct.value as String, "", "INITIATIVE", issue.key, direct) :
                        unresolvedSimulation(issue, "UNRESOLVED_INITIATIVE_VALUE", "Initiative has no old Project Information value and no SAP fallback")
                simulationCache[issue.id] = sim
                return sim
            }

            boolean parentHasAuthority = parentSim?.simulatedPi as boolean
            String inheritedValue = parentHasAuthority ? parentSim.simulatedPi as String : null
            String inheritedSource = parentHasAuthority ? parentSim.sourceIssue as String : ""
            String inheritedOverride = parentHasAuthority ? (parentSim.simulatedOverrideKey ?: "") as String : ""

            if (!parentHasAuthority) {
                sim = direct.value ? simulation(issue, direct.value as String, issue.key, "ROOT_OVERRIDE_OR_STANDALONE_AUTHORITY", issue.key, direct) :
                        unresolvedSimulation(issue, "UNRESOLVED_NO_OLD_VALUE_NO_AUTHORITY", "No direct value and no parent authority")
                simulationCache[issue.id] = sim
                return sim
            }

            if (direct.value && normalize(direct.value as String) != normalize(inheritedValue)) {
                sim = simulation(issue, direct.value as String, issue.key,
                        inheritedOverride ? "NESTED_OVERRIDE_ROOT" : "OVERRIDE_ROOT", issue.key, direct)
                sim.detail = joinDetails(sim.detail, "Own value differs from parent authority; Variant A creates an override root")
                simulationCache[issue.id] = sim
                return sim
            }

            String branch = inheritedOverride ?
                    (direct.value ? "INHERITED_FROM_OVERRIDE_SAME_VALUE" : "INHERITED_FROM_OVERRIDE") :
                    (direct.value ? "INHERITED_FROM_INITIATIVE_SAME_VALUE" : "INHERITED_FROM_INITIATIVE")
            Map source = direct.value ? direct : [source: "INHERITED", detail: "Inherited from parent authority"]
            sim = simulation(issue, inheritedValue, inheritedOverride, branch, inheritedSource, source)
            simulationCache[issue.id] = sim
            return sim
        } finally {
            recursionStack.remove(issue.id)
        }
    }

    private Map getDirectResolution(Issue issue) {
        if (directCache.containsKey(issue.id)) return directCache[issue.id]

        Object rawOld = issue.getCustomFieldValue(cfOldPI)
        String oldValue = extractDbRow(rawOld)?.trim()
        String sapInput = textValue(issue, cfSapInput).trim()
        String sapScripted = textValue(issue, cfSapScripted).trim()
        Map result

        if (oldValue) {
            result = [value: oldValue, source: "OLD_PI", detail: "Old Project Information preserved exactly"]
        } else if (sapInput) {
            result = resolveBySap(sapInput, "SAP_INPUT")
        } else if (sapScripted && !sapScripted.equalsIgnoreCase("NOT Defined")) {
            result = resolveBySap(sapScripted, "SAP_SCRIPTED")
        } else {
            result = [value: "", source: "NO_DIRECT_VALUE", detail: "No old Project Information and no usable SAP fallback"]
        }
        directCache[issue.id] = result
        return result
    }

    private Map resolveBySap(String raw, String prefix) {
        String sap = raw?.replaceAll("[^0-9]", "")
        if (!sap) return [value: "", source: "${prefix}_INVALID", detail: "SAP value contains no digits"]

        // The same logical value can exist in several field contexts. That is not an ambiguity.
        List<Option> matches = sapToOptions[sap] ?: []
        Map<String, List<Option>> matchesByValue = matches.groupBy { normalize(it.value?.toString()) }
        if (matchesByValue.size() == 1) {
            String resolvedValue = matchesByValue.values().first().first().value?.toString()
            return [value: resolvedValue, source: "${prefix}_EXISTING_OPTION", detail: "SAP resolved to one logical option value"]
        }
        if (matchesByValue.size() > 1) {
            String label = String.format(LABEL_MULTIPLE, sap)
            return [value: label, source: "${prefix}_MULTIPLE_REQUIRED", detail: "SAP matched multiple options"]
        }
        String label = String.format(LABEL_HISTORY, sap)
        return [value: label, source: "${prefix}_HISTORY_REQUIRED", detail: "SAP matched no option"]
    }

    private Map simulation(Issue issue, String target, String overrideKey, String branch, String sourceIssue, Map direct) {
        [simulatedPi: target ?: "", simulatedText: target ?: "", simulatedOverrideKey: overrideKey ?: "",
         branch: branch, sourceIssue: sourceIssue ?: "", directResolutionSource: direct.source ?: "UNKNOWN",
         result: "SIMULATED", detail: direct.detail ?: ""]
    }

    private Map unresolvedSimulation(Issue issue, String branch, String detail) {
        [simulatedPi: "", simulatedText: "", simulatedOverrideKey: "", branch: branch, sourceIssue: "",
         directResolutionSource: getDirectResolution(issue)?.source ?: "NO_DIRECT_VALUE", result: branch, detail: detail]
    }

    private static Map syntheticUnresolved(String branch, String detail) {
        [simulatedPi: "", simulatedText: "", simulatedOverrideKey: "", branch: branch, sourceIssue: "",
         directResolutionSource: "NO_DIRECT_VALUE", result: branch, detail: detail]
    }

    private static Map errorSimulation(Exception e) {
        [simulatedPi: "", simulatedText: "", simulatedOverrideKey: "", branch: "ERROR", sourceIssue: "",
         directResolutionSource: "ERROR", result: "ERROR", detail: "${e.class.simpleName}: ${e.message}"]
    }

    private void buildOptionIndexes() {
        List<FieldConfig> configs = (cfNewPI.configurationSchemes?.collectMany { it.configs?.values() ?: [] }?.unique { it.id }) ?: []
        configs.each { FieldConfig config ->
            Long configId = config.id as Long
            optionsByConfig.computeIfAbsent(configId) { [:] }
            optManager.getOptions(config)?.each { Option option ->
                String value = option.value?.toString()?.trim()
                if (value) {
                    String norm = normalize(value)
                    optionsByConfig[configId][norm] = option
                    optionOccurrencesByValue.computeIfAbsent(norm) { [] }.add([configId: configId, optionId: option.optionId, value: value])
                    String sap = extractSap(value)
                    if (sap) sapToOptions.computeIfAbsent(sap) { [] }.add(option)
                }
            }
        }
    }

    private void loadOutputIssues(ApplicationUser user) {
        def parsed = searchService.parseQuery(user, JQL_SCOPE)
        if (!parsed.valid) throw new IllegalArgumentException("Invalid JQL '${JQL_SCOPE}': ${parsed.errors}")
        int start = 0
        while (true) {
            PagerFilter pager = new PagerFilter(SEARCH_BATCH_SIZE)
            pager.start = start
            def hits = searchService.search(user, parsed.query, pager).results
            if (!hits) break
            hits.each { hit ->
                Issue issue = issueManager.getIssueObject(hit.id)
                if (issue) outputIssuesById[issue.id] = issue
            }
            start += hits.size()
            if (hits.size() < SEARCH_BATCH_SIZE) break
        }
        log.info("Frozen scope contains ${outputIssuesById.size()} issues")
    }

    private Issue getParent(Issue issue) {
        if (!issue) return null
        if (parentResolved.contains(issue.id)) return parentCache[issue.id]
        Issue parent
        try {
            if (issue.isSubTask()) {
                parent = issue.parentObject
            } else {
                parent = resolveIssueFromFieldValue(issue.getCustomFieldValue(cfEpicLink))
                if (!parent) parent = resolveIssueFromFieldValue(issue.getCustomFieldValue(cfParentLink))
            }
        } finally {
            parentResolved << issue.id
            parentCache[issue.id] = parent
        }
        parent
    }

    private Issue resolveIssueFromFieldValue(Object value) {
        if (!value) return null
        if (value instanceof Issue) return value as Issue
        try {
            if (value.hasProperty("key")) {
                Issue byProperty = issueManager.getIssueObject(value.key?.toString())
                if (byProperty) return byProperty
            }
        } catch (Exception ignored) { }
        String key = value.toString()?.trim()
        key ? issueManager.getIssueObject(key) : null
    }

    private Map detectConflict(String currentPi, String targetPi, String currentText, String targetText,
                               String currentOverride, String targetOverride) {
        boolean pi = hasValue(currentPi) && normalize(currentPi) != normalize(targetPi)
        boolean text = hasValue(currentText) && normalize(currentText) != normalize(targetText)
        boolean override = hasValue(currentOverride) && normalize(currentOverride) != normalize(targetOverride)
        List<String> fields = []
        if (pi) fields << "Project Information n"
        if (text) fields << "text mirror"
        if (override) fields << "override key"
        [conflict: pi || text || override,
         type: fields ? "CONFLICT_${fields.collect { it.toUpperCase().replace(' ', '_') }.join('_AND_')}" : "NO_CONFLICT",
         detail: fields ? "Current non-empty ${fields.join(', ')} differs from target; manual review required" : ""]
    }

    private Map baseRow(Issue issue, Issue parent, String currentPi, String targetPi, String currentText,
                        String targetText, String currentOverride, String targetOverride, Map sim, Map conflict) {
        [issueKey: issue.key, projectKey: issue.projectObject?.key ?: "", issueType: issue.issueType?.name ?: "",
         summary: issue.summary ?: "", parentKey: parent?.key ?: "",
         currentProjectInformationN: currentPi, targetProjectInformationN: targetPi,
         currentProjectInformationText: currentText, targetProjectInformationText: targetText,
         currentOverrideKey: currentOverride, targetOverrideKey: targetOverride,
         directResolutionSource: sim.directResolutionSource ?: "", branch: sim.branch ?: "", sourceIssue: sim.sourceIssue ?: "",
         optionContextId: "", optionAction: "NONE", conflictType: conflict.type ?: "NO_CONFLICT",
         result: "", changed: "false", detail: ""]
    }

    private String validateConfiguration() {
        Map<String, Object> required = [
                (CF_PROJECT_INFORMATION_N): cfNewPI, (CF_OLD_PROJECT_INFORMATION): cfOldPI,
                (CF_PROJECT_INFORMATION_TEXT): cfTextMirror, (CF_PROJECT_INFORMATION_OVERRIDE_KEY): cfOverrideKey,
                (CF_SAP_INPUT): cfSapInput, (CF_SAP_SCRIPTED): cfSapScripted,
                (CF_PARENT_LINK): cfParentLink, (CF_EPIC_LINK): cfEpicLink
        ]
        List<String> missing = required.findAll { !it.value }.collect { it.key }
        missing ? "Missing custom fields: ${missing.join(', ')}" : null
    }

    private String getProjectInformationNValue(Issue issue) {
        def value = issue?.getCustomFieldValue(cfNewPI)
        if (!value) return ""
        if (value instanceof Collection) value = value.find { it != null }
        if (value instanceof Option) return value.value?.toString() ?: ""
        try {
            if (value.hasProperty("value")) return value.value?.toString() ?: ""
        } catch (Exception ignored) { }
        value.toString()
    }

    private static String textValue(Issue issue, def field) {
        issue?.getCustomFieldValue(field)?.toString() ?: ""
    }

    static String extractDbRow(Object raw) {
        if (!raw?.toString()?.trim()) return null
        String text = raw.toString()
        try {
            def parsed = new JsonSlurper().parseText(text)
            return parsed?.rows?.getAt(0)?.getAt(0)?.toString()
        } catch (Exception ignored) {
            String trimmed = text.trim()
            return (trimmed.startsWith("{") || trimmed.startsWith("[")) ? null : trimmed
        }
    }

    static String extractSap(String value) {
        if (!value) return null
        def matcher = value =~ /\[(\d+)\]/
        matcher.find() ? matcher.group(1) : null
    }

    private static String normalize(String value) { value == null ? null : value.trim() }
    private static boolean hasValue(String value) { value?.trim() as boolean }
    private static String joinDetails(Object a, Object b) { [a, b].findAll { it?.toString()?.trim() }.join(". ") }
    private boolean isInitiative(Issue issue) { issue?.issueType?.name == ISSUE_TYPE_INITIATIVE }

    private static boolean isDisabledOption(Option option) {
        if (!option) return false
        try { return option.disabled as boolean } catch (Exception ignored) { }
        try { return option.isDisabled() } catch (Exception ignored) { return false }
    }

    private File prepareOutputFile() {
        try {
            String home = jiraHomeComponent?.homePath ?: System.getProperty("jira.home", "/tmp")
            File dir = new File(home, "export/project-info-execute")
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create ${dir.absolutePath}")
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date())
            new File(dir, "project-info-execute-${timestamp}.csv")
        } catch (Exception e) {
            errorCount++
            log.error("Output preparation failed: ${e.message}", e)
            null
        }
    }

    private void writeCsv(File file) {
        if (!file) return
        file.withWriter(StandardCharsets.UTF_8.name()) { Writer writer ->
            writer.write("\uFEFF")
            writer.writeLine(CSV_HEADER.join(SEPARATOR))
            rows.each { Map row ->
                writer.writeLine(CSV_HEADER.collect { String key -> escapeCsv(row[key]?.toString() ?: "") }.join(SEPARATOR))
            }
        }
    }

    private static String escapeCsv(String value) {
        if (!value) return ""
        (value.contains(SEPARATOR) || value.contains('"') || value.contains('\n') || value.contains('\r')) ?
                '"' + value.replace('"', '""') + '"' : value
    }

    private String fail(String message) {
        log.error(message)
        "FAILED: ${message}"
    }
}
