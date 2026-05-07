package jobs


import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.ModifiedValue
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.index.IssueIndexingParams
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.web.bean.PagerFilter
import groovy.json.JsonSlurper
import org.apache.log4j.Logger

import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat

class ProjectInfoMigrationJob {

    private static final boolean DRY_RUN = false
    private static final boolean CREATE_MISSING_OPTIONS = true
    private static final boolean SINGLE_PASS_WRITE = true

    private static final int BATCH_SIZE = 200
    private static final String SEPARATOR = ";"
    private static final String JQL = "order by key ASC"

    private static final String[] CSV_HEADER = [
            "issueKey",
            "summary",
            "Project Information",
            "Project Information n (before)",
            "Project Information n (written)",
            "Source",
            "Action",
            "Project Reference",
            "SAP Order No (input)",
            "SAP Order No (scripted)"
            ]

    private final Logger log = Logger.getLogger("scriptrunner.migrate.project-info")

    private final def cfManager       = ComponentAccessor.getCustomFieldManager()
    private final def issueManager    = ComponentAccessor.getIssueManager()
    private final def optManager      = ComponentAccessor.getOptionsManager()
    private final def searchService   = ComponentAccessor.getComponent(SearchService)
    private final def userManager     = ComponentAccessor.getUserManager()
    private final def indexingService = ComponentAccessor.getComponent(IssueIndexingService)

    static final String CF_PROJ_INF_N_ID = "customfield_18600"
    static final String CF_OLD_PROJ_INF  = "customfield_15600"
    static final String CF_SAP_INPUT     = "customfield_10225"
    static final String CF_SAP_SCRIPTED  = "customfield_10217"

    static final String JIRA_BOT = "jira.bot"

    static final String LABEL_MULTIPLE = "Multiple project with [%s]"
    static final String LABEL_HISTORY  = "history [%s]"

    private final def cfNewPI       = cfManager.getCustomFieldObject(CF_PROJ_INF_N_ID)
    private final def cfOldPI       = cfManager.getCustomFieldObject(CF_OLD_PROJ_INF)
    private final def cfSapInput    = cfManager.getCustomFieldObject(CF_SAP_INPUT)
    private final def cfSapScripted = cfManager.getCustomFieldObject(CF_SAP_SCRIPTED)

    private Map<String, List<Option>> sapToOptions = [:]
    private Map<String, Option> fullStringToOption = [:]

    private Map<Long, Map> simulationCache = [:]

    private int totalProcessed      = 0
    private int totalUpdated        = 0
    private int totalSkippedAlready = 0
    private int totalSkippedEmpty   = 0
    private int totalErrors         = 0
    private int totalOptionsCreated = 0
    private final List<Issue> issuesToReindex = []
    private final Set<String> stackTraceLogged = new HashSet<>()

    String run() {
        log.warn("=== MIGRATION START${DRY_RUN ? ' (DRY-RUN)' : ''} ===")

        def botUser = userManager.getUserByName(JIRA_BOT)
        if (!botUser) {
            log.error("User '${JIRA_BOT}' not found.")
            return "FAILED: bot user not found"
        }
        if (!cfNewPI) {
            log.error("Field ${CF_PROJ_INF_N_ID} not found.")
            return "FAILED: PI n field not found"
        }

        buildOptionIndexes()

        File outputFile = prepareOutputFile()
        if (!outputFile) return "FAILED: could not create output file"
        log.warn("CSV: ${outputFile.absolutePath}")

        if (SINGLE_PASS_WRITE) {
            log.warn("-- Single pass: resolve + write + CSV${DRY_RUN ? ' (DRY-RUN)' : ''} --")
            outputFile.withWriter(StandardCharsets.UTF_8.name()) { writer ->
                    writer.write("\uFEFF")
                writer.writeLine(CSV_HEADER.join(SEPARATOR))
                writeAllIssuesSinglePass(botUser, writer)
            }
        } else {
            log.warn("-- Pass 1: direct resolution --")
            simulateAllDirect(botUser)

            if (!DRY_RUN && CREATE_MISSING_OPTIONS) {
                log.warn("-- Pass 2: ensuring option set is complete --")
                ensureOptionsExist()
            }

            log.warn("-- Pass 3: writing values ${DRY_RUN ? '(DRY-RUN)' : ''}--")
            outputFile.withWriter(StandardCharsets.UTF_8.name()) { writer ->
                    writer.write("\uFEFF")
                writer.writeLine(CSV_HEADER.join(SEPARATOR))
                writeAllIssues(botUser, writer)
            }
        }

        if (!DRY_RUN) {
            log.warn("-- Bulk reindex (${issuesToReindex.size()} issues) --")
            bulkReindex()
        }

        log.warn("=== MIGRATION END ===")
        log.warn("  processed=${totalProcessed}, updated=${totalUpdated}, " +
                "skippedAlready=${totalSkippedAlready}, skippedEmpty=${totalSkippedEmpty}, " +
                "optionsCreated=${totalOptionsCreated}, errors=${totalErrors}")
        log.warn("  CSV: ${outputFile.absolutePath}")

        return "Done${DRY_RUN ? ' (DRY-RUN)' : ''}. " +
                "processed=${totalProcessed}, updated=${totalUpdated}, " +
                "skippedAlready=${totalSkippedAlready}, skippedEmpty=${totalSkippedEmpty}, " +
                "optionsCreated=${totalOptionsCreated}, errors=${totalErrors}. " +
                "CSV: ${outputFile.absolutePath}"
    }

    private void buildOptionIndexes() {
        log.warn("-- Building option indexes --")

        def fieldConfigs = (cfNewPI.configurationSchemes
                ?.collectMany { it.configs?.values() ?: [] }
                ?.unique { it.id }) ?: []

        fieldConfigs.each { config ->
                optManager.getOptions(config)?.each { opt ->
                String val = opt.value?.toString()?.trim()
            if (!val) return
                    fullStringToOption[val] = opt
            String sap = normalizeSap(extractSap(val))
            if (sap) sapToOptions.computeIfAbsent(sap) { [] }.add(opt)
        }
        }

        log.warn("  ${fullStringToOption.size()} options, ${sapToOptions.size()} SAP numbers, " +
                "${sapToOptions.count { k, v -> v.size() > 1 }} ambiguous")
    }

    private void simulateAllDirect(ApplicationUser user) {
        processIssues(user, JQL) { Issue issue ->
            simulationCache[issue.id] = resolveFor(issue)
        }
    }

    private Map resolveFor(Issue issue) {
        String currentVal = getCurrentPIn(issue)
        if (currentVal) return [simulated: currentVal, source: "already set"]

        String sapInput = issue.getCustomFieldValue(cfSapInput)?.toString()?.trim()
        if (sapInput) {
            String resolved = resolveBySap(issue, sapInput)
            if (resolved) return [simulated: resolved, source: "SAP input"]
        }

        String sapScripted = issue.getCustomFieldValue(cfSapScripted)?.toString()?.trim()
        if (sapScripted && sapScripted != "NOT Defined") {
            String resolved = resolveBySap(issue, sapScripted)
            if (resolved) return [simulated: resolved, source: "SAP scripted"]
        }

        String oldPI = extractDbRow(issue.getCustomFieldValue(cfOldPI))
        if (oldPI) {
            Option opt = fullStringToOption[oldPI.trim()]
            if (opt) return [simulated: opt.value, source: "old PI (exact)"]
            String sap = extractSap(oldPI)
            if (sap) {
                String resolved = resolveBySap(issue, sap)
                if (resolved) return [simulated: resolved, source: "old PI (SAP)"]
            }
        }

        return [simulated: "", source: "-"]
    }

    private String resolveBySap(Issue issue, String rawSap) {
        String sap = normalizeSap(rawSap)
        if (!sap) return null

        def matches = sapToOptions[sap]

        if (matches?.size() == 1)
        return matches[0].value

        if (matches?.size() > 1) {
            String oldPI = extractDbRow(issue.getCustomFieldValue(cfOldPI))?.trim()
            if (oldPI) {
                Option exact = matches.find { it.value?.toString()?.trim() == oldPI }
                if (exact) return exact.value
            }
            return String.format(LABEL_MULTIPLE, sap)
        }

        String oldPI = extractDbRow(issue.getCustomFieldValue(cfOldPI))?.trim()
        if (oldPI) {
            Option exactOpt = fullStringToOption[oldPI]
            if (exactOpt) return exactOpt.value

            if (oldPIContainsSap(oldPI, rawSap)) {
                String candidate = oldPI + " disabled"
                Option existing = fullStringToOption[candidate]
                if (existing) return existing.value
                return candidate
            }
        }

        String historyLabel = String.format(LABEL_HISTORY, rawSap ?: sap)
        Option existingHistory = fullStringToOption[historyLabel]
        if (existingHistory) return existingHistory.value
        return historyLabel
    }

    private static boolean oldPIContainsSap(String oldPI, String rawSap) {
        if (!oldPI || !rawSap) return false
        if (oldPI.contains(rawSap)) return true
        String norm = normalizeSap(rawSap)
        if (!norm) return false
        String normOldPI = normalizeSap(oldPI)
        return normOldPI != null && normOldPI.contains(norm)
    }

    private void ensureOptionsExist() {
        def requiredLabels = new LinkedHashSet<String>()
        simulationCache.values().each { r ->
            if (r.simulated && !(r.source in ["already set", "-"])) {
                requiredLabels.add(r.simulated.toString())
            }
        }

        def missing = requiredLabels.findAll { !fullStringToOption[it] }
        if (missing.isEmpty()) {
            log.warn("  All ${requiredLabels.size()} required options already exist")
            return
        }
        log.warn("  Required: ${requiredLabels.size()}, missing: ${missing.size()}")

        missing.each { createDisabledOption(it) }
    }

    private void writeAllIssues(ApplicationUser user, Writer writer) {
        processIssues(user, JQL) { Issue issue ->
            String action
            String writtenLabel = ""
            try {
                action = writeIssue(issue)
                if (action == "UPDATED" || action == "DRY-RUN") {
                    writtenLabel = simulationCache[issue.id]?.simulated ?: ""
                }
            } catch (Exception e) {
                logErrorOnce("write-${e.class.simpleName}",
                        "Write error on ${issue.key}: ${e.message}", e)
                totalErrors++
                action = "ERROR"
            }
            writer.writeLine(buildCsvLine(issue, action, writtenLabel))
            totalProcessed++
        }
        writer.flush()
    }

    private void writeAllIssuesSinglePass(ApplicationUser user, Writer writer) {
        processIssues(user, JQL) { Issue issue ->
            String action
            String writtenLabel = ""
            Map sim = null
            try {
                sim = resolveFor(issue)
                simulationCache[issue.id] = sim
                action = writeIssueWithResolution(issue, sim)
                if (action == "UPDATED" || action == "DRY-RUN") {
                    writtenLabel = sim.simulated ?: ""
                }
            } catch (Exception e) {
                logErrorOnce("write-${e.class.simpleName}",
                        "Write error on ${issue.key}: ${e.message}", e)
                totalErrors++
                action = "ERROR"
            }
            writer.writeLine(buildCsvLine(issue, action, writtenLabel))
            totalProcessed++
        }
        writer.flush()
    }

    private String writeIssue(Issue issue) {
        def sim = simulationCache[issue.id]
        if (!sim) { totalSkippedEmpty++; return "SKIPPED-no-value" }
        return writeIssueWithResolution(issue, sim)
    }

    private String writeIssueWithResolution(Issue issue, Map sim) {
        if (sim.source == "already set") {
            totalSkippedAlready++
            return "SKIPPED-already-set"
        }
        String targetLabel = sim.simulated?.toString()?.trim()
        if (!targetLabel) {
            totalSkippedEmpty++
            return "SKIPPED-no-value"
        }

        Option targetOpt = fullStringToOption[targetLabel]
        if (!targetOpt) {
            if (!DRY_RUN && CREATE_MISSING_OPTIONS) {
                targetOpt = createDisabledOption(targetLabel)
            }
            if (!targetOpt) {
                logErrorOnce("missing-option",
                        "  ${issue.key}: target option '${targetLabel}' not found and could not be created (source=${sim.source})",
                        null)
                totalErrors++
                return "ERROR"
            }
        }

        def oldVal = issue.getCustomFieldValue(cfNewPI)

        if (DRY_RUN) {
            totalUpdated++
            return "DRY-RUN"
        }

        cfNewPI.updateValue(
                null,
                issue,
                new ModifiedValue(oldVal, [targetOpt]),
                new DefaultIssueChangeHolder()
        )
        issuesToReindex.add(issue)
        totalUpdated++
        return "UPDATED"
    }

    private Option createDisabledOption(String label) {
        def fieldConfigs = (cfNewPI.configurationSchemes
                ?.collectMany { it.configs?.values() ?: [] }
                ?.unique { it.id }) ?: []
        if (!fieldConfigs) return null

        Option firstCreated = null
        fieldConfigs.each { config ->
            try {
                def existingOpts = optManager.getOptions(config) ?: []
                def existingInConfig = existingOpts.find { it.value?.toString()?.trim() == label }
                if (existingInConfig) {
                    if (!firstCreated) firstCreated = existingInConfig
                    fullStringToOption[label] = existingInConfig
                    String sap = normalizeSap(extractSap(label))
                    if (sap) sapToOptions.computeIfAbsent(sap) { [] }.add(existingInConfig)
                    return
                }
                long nextSeq = 1L + (existingOpts.findResults { it.sequence }?.max() ?: 0L)
                Option opt = optManager.createOption(config, null, nextSeq, label)
                opt.setDisabled(true)
                optManager.updateOptions([opt])
                fullStringToOption[label] = opt
                String sap = normalizeSap(extractSap(label))
                if (sap) sapToOptions.computeIfAbsent(sap) { [] }.add(opt)
                totalOptionsCreated++
                log.warn("  + created disabled option: '${label}' (config ${config.id})")
                if (!firstCreated) firstCreated = opt
            } catch (Exception e) {
                logErrorOnce("create-option-${e.class.simpleName}",
                        "  Failed to create option '${label}' in config ${config.id}: ${e.message}", e)
                totalErrors++
            }
        }
        return firstCreated
    }

    private void bulkReindex() {
        if (issuesToReindex.isEmpty()) {
            log.warn("  nothing to reindex")
            return
        }
        try {
            indexingService.reIndexIssueObjects(issuesToReindex, IssueIndexingParams.INDEX_ALL)
            log.warn("  reindexed ${issuesToReindex.size()} issues")
        } catch (Exception e) {
            logErrorOnce("reindex", "  bulk reindex failed: ${e.message}", e)
            totalErrors++
        }
    }

    private void processIssues(ApplicationUser user, String jql, Closure fn) {
        def parseResult = searchService.parseQuery(user, jql)
        if (!parseResult.valid) {
            log.error("Invalid JQL: ${jql}")
            return
        }

        int start = 0
        while (true) {
            def pager = new PagerFilter(BATCH_SIZE)
            pager.start = start

            def searchResult = searchService.search(user, parseResult.query, pager)
            def hits = searchResult.results
            if (!hits) break

            hits.each { hit ->
                try {
                    Issue issue = issueManager.getIssueObject(hit.id)
                    if (issue) fn(issue)
                } catch (Exception e) {
                    logErrorOnce("process-${e.class.simpleName}",
                            "Error processing issue ${hit?.key}: ${e.message}", e)
                    totalErrors++
                }
            }

            start += hits.size()
            log.warn("  batch=${hits.size()}, total=${start}")

            if (hits.size() < BATCH_SIZE) break
        }
    }

    private String buildCsvLine(Issue issue, String action, String writtenLabel) {
        def sim = simulationCache[issue.id] ?: [simulated: "", source: "-"]

        def values = [
        escapeCsv(issue.key),
                escapeCsv(issue.summary ?: ""),
        escapeCsv(cfValue(issue, "Project Information")),
                escapeCsv(getCurrentPIn(issue)),
                escapeCsv(writtenLabel ?: ""),
        escapeCsv(sim.source ?: "-"),
        escapeCsv(action ?: ""),
        escapeCsv(cfValue(issue, "Project Reference")),
                escapeCsv(cfValue(issue, "SAP-Order-No (input)")),
                escapeCsv(cfValue(issue, "SAP-Order-No"))
        ]
        return values.join(SEPARATOR)
    }

    private void logErrorOnce(String key, String message, Throwable t) {
        if (t != null && stackTraceLogged.add(key)) {
            log.error(message + " [stack trace below — further identical errors will be logged one-line]", t)
        } else {
            log.error(message)
        }
    }

    private String getCurrentPIn(Issue issue) {
        def v = issue.getCustomFieldValue(cfNewPI)
        if (v instanceof Collection) return v?.find { it != null }?.value ?: ""
        if (v instanceof Option) return v.value ?: ""
        return ""
    }

    private String cfValue(Issue issue, String cfName) {
        def cf = ComponentAccessor.customFieldManager.getCustomFieldObjectByName(cfName)
        def v = cf ? issue.getCustomFieldValue(cf) : null
        if (!v) return ""
        if (v instanceof Collection) {
            return v.findResults { it?.toString() }.join(",")
        }
        return v.toString()
    }

    static String extractDbRow(Object raw) {
        if (!raw) return null
        try { return new JsonSlurper().parseText(raw.toString())?.rows?.getAt(0)?.getAt(0)?.toString() }
        catch (Exception e) { return null }
    }

    static String extractSap(String val) {
        if (!val) return null
        def m = (val =~ /\[([A-Za-z0-9]+)\]/)
        return m.find() ? m.group(1) : null
    }

    static String normalizeSap(String s) {
        if (!s) return null
        String n = s.replaceAll("[^A-Za-z0-9]", "").toUpperCase()
        return n ?: null
    }

    private File prepareOutputFile() {
        try {
            def jiraHome = ComponentAccessor.getComponent(
                    com.atlassian.jira.config.util.JiraHome)?.homePath
            if (!jiraHome) {
                jiraHome = System.getProperty("jira.home", "/tmp")
            }

            def dir = new File(jiraHome, "export/project-info-migration")
            if (!dir.exists()) dir.mkdirs()

            def timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date())
            def file = new File(dir, "project-info-migration-${timestamp}.csv")
            file.createNewFile()
            return file
        } catch (Exception e) {
            log.error("Failed to create output file: ${e.message}")
            return null
        }
    }

    private static String escapeCsv(String value) {
        if (!value) return ""
        if (value.contains(SEPARATOR) || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\""
        }
        return value
    }
}