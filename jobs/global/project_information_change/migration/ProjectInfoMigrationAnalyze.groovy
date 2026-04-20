package jobs.global.project_information_change.migration

import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.web.bean.PagerFilter
import groovy.json.JsonSlurper
import jobs.global.project_information_change.ProjectInfoPropagator
import org.apache.log4j.Logger

import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat

/**
 * ProjectInfoMigrationAnalyze — READ-ONLY analysis of all issues.
 *
 * Simulates the full migration logic (SAP input → SAP scripted → old PI → inherit)
 * and writes the predicted result to CSV WITHOUT modifying any issue.
 *
 * CSV columns:
 *   issueKey; summary; Project Information; Project Information n (current);
 *   Project Information n (simulated); Simulated Source;
 *   Project Reference; SAP Order No (input); SAP Order No (scripted)
 *
 * Output: {jira.home}/export/project-info-analyze/project-info-analyze-{timestamp}.csv
 *
 * Two-pass approach:
 *   Pass 1 — resolve each issue individually (SAP input/scripted, old PI)
 *   Pass 2 — inherit from hierarchy for unresolved issues
 *
 * @author chabrecek.anton
 */
class ProjectInfoMigrationAnalyze {

    private static final int BATCH_SIZE = 200
    private static final String SEPARATOR = ";"
    private static final String JQL = "order by key ASC"

    private static final String[] CSV_HEADER = [
            "issueKey",
            "summary",
            "Project Information",
            "Project Information n (current)",
            "Project Information n (simulated)",
            "Simulated Source",
            "Project Reference",
            "SAP Order No (input)",
            "SAP Order No (scripted)"
    ]

    // ── Services ──────────────────────────────────────────────
    private final Logger log = Logger.getLogger("scriptrunner.analyze.project-info")

    private final def cfManager     = ComponentAccessor.getCustomFieldManager()
    private final def issueManager  = ComponentAccessor.getIssueManager()
    private final def optManager    = ComponentAccessor.getOptionsManager()
    private final def searchService = ComponentAccessor.getComponent(SearchService)
    private final def userManager   = ComponentAccessor.getUserManager()
    private final def historyMgr    = ComponentAccessor.getChangeHistoryManager()

    private final def cfNewPI       = cfManager.getCustomFieldObject(ProjectInfoPropagator.CF_PROJ_INF_N_ID)
    private final def cfOldPI       = cfManager.getCustomFieldObject(ProjectInfoPropagator.CF_OLD_PROJ_INF)
    private final def cfSapInput    = cfManager.getCustomFieldObject(ProjectInfoPropagator.CF_SAP_INPUT)
    private final def cfSapScripted = cfManager.getCustomFieldObject(ProjectInfoPropagator.CF_SAP_SCRIPTED)
    private final def cfParentLink  = cfManager.getCustomFieldObject(ProjectInfoPropagator.CF_PARENT_LINK)
    private final def cfEpicLink    = cfManager.getCustomFieldObject(ProjectInfoPropagator.CF_EPIC_LINK)

    // ── Option indexes (same as MigrationJob) ────────────────
    private Map<String, List<Option>> sapToOptions = [:]
    private Map<String, Option> fullStringToOption = [:]

    // ── Simulation results cache  ────────────────────────────
    // key = issue.id → [simulated: "value", source: "SAP input | SAP scripted | old PI | inherited | already set | -"]
    private Map<Long, Map> simulationCache = [:]

    // ── Counters ──────────────────────────────────────────────
    private int totalProcessed = 0
    private int totalErrors = 0

    // ══════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ══════════════════════════════════════════════════════════

    String run() {
        log.info("=== ANALYZE START ===")

        def botUser = userManager.getUserByName(ProjectInfoPropagator.JIRA_BOT)
        if (!botUser) {
            log.error("User '${ProjectInfoPropagator.JIRA_BOT}' not found.")
            return "FAILED: bot user not found"
        }
        if (!cfNewPI) {
            log.error("Field ${ProjectInfoPropagator.CF_PROJ_INF_N_ID} not found.")
            return "FAILED: PI n field not found"
        }

        buildOptionIndexes()

        File outputFile = prepareOutputFile()
        if (!outputFile) return "FAILED: could not create output file"

        log.info("Output: ${outputFile.absolutePath}")

        // ── Pass 1: simulate direct resolution for every issue ──
        log.info("-- Pass 1: direct resolution (SAP input → SAP scripted → old PI) --")
        simulateAllDirect(botUser)

        // ── Pass 2: inherit from hierarchy for unresolved ───────
        log.info("-- Pass 2: hierarchical inheritance --")
        simulateInheritance(botUser)

        // ── Write CSV ───────────────────────────────────────────
        log.info("-- Writing CSV --")
        outputFile.withWriter(StandardCharsets.UTF_8.name()) { writer ->
            writer.write("\uFEFF")  // BOM for Excel
            writer.writeLine(CSV_HEADER.join(SEPARATOR))
            writeAllIssues(botUser, writer)
        }

        log.info("=== ANALYZE END ===")
        log.info("  Total processed: ${totalProcessed}, Errors: ${totalErrors}")
        log.info("  Output: ${outputFile.absolutePath}")

        return "Done. ${totalProcessed} issues, ${totalErrors} errors. File: ${outputFile.absolutePath}"
    }

    // ══════════════════════════════════════════════════════════
    //  PHASE 1: Build option indexes (same as MigrationJob)
    // ══════════════════════════════════════════════════════════

    private void buildOptionIndexes() {
        log.info("-- Building option indexes --")

        def fieldConfigs = (cfNewPI.configurationSchemes
                ?.collectMany { it.configs?.values() ?: [] }
                ?.unique { it.id }) ?: []

        fieldConfigs.each { config ->
            optManager.getOptions(config)?.each { opt ->
                String val = opt.value?.toString()?.trim()
                if (!val) return
                fullStringToOption[val] = opt
                String sap = extractSap(val)
                if (sap) sapToOptions.computeIfAbsent(sap) { [] }.add(opt)
            }
        }

        log.info("  ${fullStringToOption.size()} options, ${sapToOptions.size()} SAP numbers, " +
                "${sapToOptions.count { k, v -> v.size() > 1 }} ambiguous")
    }

    // ══════════════════════════════════════════════════════════
    //  PASS 1: Direct resolution (no write, just cache results)
    // ══════════════════════════════════════════════════════════

    private void simulateAllDirect(ApplicationUser user) {
        processIssues(user, JQL) { Issue issue ->
            simulateDirect(issue)
        }
    }

    /**
     * Simulates migration phases 2a → 2b → 2c for a single issue.
     * Stores result in simulationCache.
     */
    private void simulateDirect(Issue issue) {
        // If PI n already has a value, migration would skip it
        String currentVal = getCurrentPIn(issue)
        if (currentVal) {
            simulationCache[issue.id] = [simulated: currentVal, source: "already set"]
            return
        }

        // Phase 2a: SAP-Order-No (input) — user's explicit choice
        String sapInput = issue.getCustomFieldValue(cfSapInput)?.toString()?.trim()
        if (sapInput) {
            String resolved = resolveBySap(issue, sapInput)
            if (resolved) {
                simulationCache[issue.id] = [simulated: resolved, source: "SAP input"]
                return
            }
        }

        // Phase 2b: SAP-Order-No (scripted) — computed
        String sapScripted = issue.getCustomFieldValue(cfSapScripted)?.toString()?.trim()
        if (sapScripted && sapScripted != "NOT Defined") {
            String resolved = resolveBySap(issue, sapScripted)
            if (resolved) {
                simulationCache[issue.id] = [simulated: resolved, source: "SAP scripted"]
                return
            }
        }

        // Phase 2c: Old Project Information — fallback
        String oldPI = extractDbRow(issue.getCustomFieldValue(cfOldPI))
        if (oldPI) {
            // Exact match first
            Option opt = fullStringToOption[oldPI.trim()]
            if (opt) {
                simulationCache[issue.id] = [simulated: opt.value, source: "old PI (exact)"]
                return
            }

            // Fallback: extract SAP from old PI
            String sap = extractSap(oldPI)
            if (sap) {
                String resolved = resolveBySap(issue, sap)
                if (resolved) {
                    simulationCache[issue.id] = [simulated: resolved, source: "old PI (SAP)"]
                    return
                }
            }
        }

        // Not resolved yet — will try inheritance in pass 2
        simulationCache[issue.id] = [simulated: "", source: "-"]
    }

    // ══════════════════════════════════════════════════════════
    //  PASS 2: Hierarchical inheritance (read-only)
    // ══════════════════════════════════════════════════════════

    private void simulateInheritance(ApplicationUser user) {
        int inherited = 0
        simulationCache.each { issueId, result ->
            if (result.source != "-") return  // already resolved

            try {
                Issue issue = issueManager.getIssueObject(issueId)
                if (!issue) return

                def parentResult = walkUpSimulated(issue, new HashSet<Long>())
                if (parentResult) {
                    result.simulated = parentResult
                    result.source = "inherited"
                    inherited++
                }
            } catch (Exception e) {
                log.error("Inheritance error for issue ${issueId}: ${e.message}")
            }
        }
        log.info("  Inherited: ${inherited}")
    }

    /**
     * Walk up the hierarchy looking for a simulated (or current) value.
     */
    private String walkUpSimulated(Issue issue, Set<Long> visited) {
        if (!issue || visited.contains(issue.id)) return null
        visited.add(issue.id)

        Issue parent = getParent(issue)
        if (!parent) return null

        // Check if parent has a simulated result
        def parentResult = simulationCache[parent.id]
        if (parentResult && parentResult.simulated) {
            return parentResult.simulated
        }

        // Parent might not be in cache (shouldn't happen, but fallback)
        String currentVal = getCurrentPIn(parent)
        if (currentVal) return currentVal

        return walkUpSimulated(parent, visited)
    }

    // ══════════════════════════════════════════════════════════
    //  CSV WRITE (final pass — all issues)
    // ══════════════════════════════════════════════════════════

    private void writeAllIssues(ApplicationUser user, Writer writer) {
        processIssues(user, JQL) { Issue issue ->
            writer.writeLine(buildCsvLine(issue))
            totalProcessed++
        }
        writer.flush()
    }

    // ══════════════════════════════════════════════════════════
    //  BATCH PROCESSOR (generic, read-only)
    // ══════════════════════════════════════════════════════════

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
                    log.error("Error processing issue ${hit?.key}: ${e.message}")
                    totalErrors++
                }
            }

            start += hits.size()
            log.info("  Batch: ${hits.size()}, Total so far: ${start}")

            if (hits.size() < BATCH_SIZE) break
        }
    }

    // ══════════════════════════════════════════════════════════
    //  CSV LINE BUILDER
    // ══════════════════════════════════════════════════════════

    private String buildCsvLine(Issue issue) {
        def sim = simulationCache[issue.id] ?: [simulated: "", source: "-"]

        def values = [
                escapeCsv(issue.key),
                escapeCsv(issue.summary ?: ""),
                escapeCsv(cfValue(issue, "Project Information")),
                escapeCsv(getCurrentPIn(issue)),
                escapeCsv(sim.simulated ?: ""),
                escapeCsv(sim.source ?: "-"),
                escapeCsv(cfValue(issue, "Project Reference")),
                escapeCsv(cfValue(issue, "SAP-Order-No (input)")),
                escapeCsv(cfValue(issue, "SAP-Order-No"))
        ]
        return values.join(SEPARATOR)
    }

    // ══════════════════════════════════════════════════════════
    //  SAP RESOLUTION (same logic as MigrationJob, read-only)
    // ══════════════════════════════════════════════════════════

    /**
     * Returns the option VALUE string (not the Option object) — we only need
     * the label for the CSV, no writes happen.
     *
     * 1 match   → option value
     * N matches → disambiguate via old PI, or "Multiple project with [SAP]"
     * 0 matches → "history [SAP]"
     */
    private String resolveBySap(Issue issue, String rawSap) {
        String sap = rawSap?.replaceAll("[^0-9]", "")
        if (!sap) return null

        def matches = sapToOptions[sap]

        if (matches?.size() == 1) return matches[0].value

        if (matches?.size() > 1) {
            // Disambiguate via old PI
            String oldPI = extractDbRow(issue.getCustomFieldValue(cfOldPI))?.trim()
            if (oldPI) {
                Option exact = matches.find { it.value?.toString()?.trim() == oldPI }
                if (exact) return exact.value
            }
            return String.format(ProjectInfoPropagator.LABEL_MULTIPLE, sap)
        }

        // 0 matches
        return String.format(ProjectInfoPropagator.LABEL_HISTORY, sap)
    }

    // ══════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════

    private String getCurrentPIn(Issue issue) {
        def v = issue.getCustomFieldValue(cfNewPI)
        if (v instanceof Collection) return v?.find { it != null }?.value ?: ""
        if (v instanceof Option) return v.value ?: ""
        return ""
    }

    private Issue getParent(Issue issue) {
        if (issue.isSubTask()) return issue.parentObject

        def epic = issue.getCustomFieldValue(cfEpicLink)
        if (epic) return issueManager.getIssueObject(epic.toString())

        def pl = issue.getCustomFieldValue(cfParentLink)
        if (pl) return issueManager.getIssueObject(pl.toString())

        return null
    }

    private String cfValue(Issue issue, String cfName) {
        def cf = ComponentAccessor.customFieldManager.getCustomFieldObjectByName(cfName)
        def v = cf ? issue.getCustomFieldValue(cf) : null
        if (!v) return ""
        if (v instanceof Collection) {
            return v.findResults {
                it?.toString()
            }.join(",")
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
        def m = (val =~ /\[(\d+)\]/)
        return m.find() ? m.group(1) : null
    }

    private File prepareOutputFile() {
        try {
            def jiraHome = ComponentAccessor.getComponent(
                    com.atlassian.jira.config.util.JiraHome)?.homePath
            if (!jiraHome) {
                jiraHome = System.getProperty("jira.home", "/tmp")
            }

            def dir = new File(jiraHome, "export/project-info-analyze")
            if (!dir.exists()) dir.mkdirs()

            def timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date())
            def file = new File(dir, "project-info-analyze-${timestamp}.csv")
            file.createNewFile()
            return file
        } catch (Exception e) {
            log.error("Failed to create output file: ${e.message}")
            return null
        }
    }

    /**
     * Escape value for CSV: wrap in quotes if it contains separator, quotes, or newlines.
     */
    private static String escapeCsv(String value) {
        if (!value) return ""
        if (value.contains(SEPARATOR) || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\""
        }
        return value
    }
}
