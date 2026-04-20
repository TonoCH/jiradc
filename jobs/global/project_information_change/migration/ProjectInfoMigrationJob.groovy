package jobs.global.project_information_change.migration

import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.*
import com.atlassian.jira.issue.customfields.manager.OptionsManager
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.fields.config.FieldConfig
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.user.util.UserManager
import com.atlassian.jira.web.bean.PagerFilter
import groovy.json.JsonSlurper
import jobs.global.project_information_change.ProjectInfoPropagator
import org.apache.log4j.Logger

/**
 * ProjectInfoMigrationJob — ONE-TIME migration.
 *
 * Phase 1:  Build option indexes
 * Phase 2a: SAP-Order-No (input)  → PI n  (jira.bot-temp = hard, user's explicit choice)
 * Phase 2b: SAP-Order-No (script) → PI n  (jira.bot = soft, computed/inherited value)
 * Phase 2c: Old Project Information → PI n (jira.bot = soft)
 * Phase 3:  Inherit from hierarchy (preserve authority from ancestor)
 * Phase 4:  Disable created options (history/Multiple)
 *
 * SAP resolution:
 *   1 match   → assign option
 *   N matches → disambiguate via old PI, or create "Multiple project with [SAP]"
 *   0 matches → create "history [SAP]"
 *
 * @author chabrecek.anton
 */
class ProjectInfoMigrationJob {

    // Constants from Propagator
    static final String CF_PROJ_INF_N_ID = ProjectInfoPropagator.CF_PROJ_INF_N_ID
    static final String CF_OLD_PROJ_INF  = ProjectInfoPropagator.CF_OLD_PROJ_INF
    static final String CF_SAP_INPUT     = ProjectInfoPropagator.CF_SAP_INPUT
    static final String CF_SAP_SCRIPTED  = ProjectInfoPropagator.CF_SAP_SCRIPTED
    static final String CF_PARENT_LINK   = ProjectInfoPropagator.CF_PARENT_LINK
    static final String CF_EPIC_LINK     = ProjectInfoPropagator.CF_EPIC_LINK
    static final String JIRA_BOT         = ProjectInfoPropagator.JIRA_BOT
    static final String JIRA_BOT_TEMP    = ProjectInfoPropagator.JIRA_BOT_TEMP

    private static final int BATCH_SIZE = 200

    // ── Services ──────────────────────────────────────────────
    private final Logger log = Logger.getLogger("scriptrunner.migration.project-info")

    private final def cfManager    = ComponentAccessor.getCustomFieldManager()
    private final def issueManager = ComponentAccessor.getIssueManager()
    private final def optManager   = ComponentAccessor.getOptionsManager()
    private final def userManager  = ComponentAccessor.getUserManager()
    private final def searchService= ComponentAccessor.getComponent(SearchService)
    private final def historyMgr   = ComponentAccessor.getChangeHistoryManager()

    private final def cfNewPI       = cfManager.getCustomFieldObject(CF_PROJ_INF_N_ID)
    private final def cfOldPI       = cfManager.getCustomFieldObject(CF_OLD_PROJ_INF)
    private final def cfSapInput    = cfManager.getCustomFieldObject(CF_SAP_INPUT)
    private final def cfSapScripted = cfManager.getCustomFieldObject(CF_SAP_SCRIPTED)
    private final def cfParentLink  = cfManager.getCustomFieldObject(CF_PARENT_LINK)
    private final def cfEpicLink    = cfManager.getCustomFieldObject(CF_EPIC_LINK)

    // ── Indexes & caches ──────────────────────────────────────
    private Map<String, List<Option>> sapToOptions = [:]
    private Map<String, Option> fullStringToOption = [:]
    private Map<String, Option> createdOptionsCache = [:]
    private List<FieldConfig> fieldConfigs = []
    private Map<Long, Issue> parentCache = [:]
    private Set<Long> optionsToDisable = new HashSet<>()

    // ── Counters ──────────────────────────────────────────────
    private int countFromSapInput = 0, countFromSapScripted = 0, countFromOldPI = 0
    private int countInherited = 0, countMultiple = 0, countHistory = 0
    private int countDisabled = 0, countSkipped = 0, countErrors = 0

    // ══════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ══════════════════════════════════════════════════════════

    void run() {
        log.info("=== MIGRATION START ===")

        def botUser     = userManager.getUserByName(JIRA_BOT)
        def botTempUser = userManager.getUserByName(JIRA_BOT_TEMP)

        if (!botUser)     { log.error("User '${JIRA_BOT}' not found.");      return }
        if (!botTempUser) { log.error("User '${JIRA_BOT_TEMP}' not found."); return }
        if (!cfNewPI)     { log.error("Field ${CF_PROJ_INF_N_ID} not found."); return }

        buildOptionIndexes()                        // Phase 1
        migrateSapInput(botTempUser)                // Phase 2a — user's choice (hard)
        migrateSapScripted(botUser)                 // Phase 2b — computed SAP (soft)
        migrateOldPI(botUser)                       // Phase 2c — old PI fallback (soft)
        inherit(botUser, botTempUser)               // Phase 3
        disableCreatedOptions()                     // Phase 4

        log.info("=== MIGRATION END ===")
        log.info("  SapInput:${countFromSapInput} SapScripted:${countFromSapScripted} OldPI:${countFromOldPI}")
        log.info("  Inherited:${countInherited} Multiple:${countMultiple} History:${countHistory}")
        log.info("  Disabled:${countDisabled} Skipped:${countSkipped} Errors:${countErrors}")
    }

    // ══════════════════════════════════════════════════════════
    //  PHASE 1: Build indexes
    // ══════════════════════════════════════════════════════════

    private void buildOptionIndexes() {
        log.info("-- Phase 1: indexing options --")

        fieldConfigs = (cfNewPI.configurationSchemes
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
    //  PHASE 2a: SAP-Order-No (input) → PI n
    //  User's explicit choice. Written as jira.bot-temp (hard).
    // ══════════════════════════════════════════════════════════

    private void migrateSapInput(ApplicationUser botTempUser) {
        log.info("-- Phase 2a: SAP-Order-No (input) --")
        process(jql(CF_SAP_INPUT, "not EMPTY", CF_PROJ_INF_N_ID, "EMPTY"), botTempUser) { issue ->

            String sapRaw = issue.getCustomFieldValue(cfSapInput)?.toString()?.trim()
            if (!sapRaw) { countSkipped++; return false }

            Option opt = resolveBySap(issue, sapRaw)
            if (opt) {
                set(issue, opt, botTempUser)
                countFromSapInput++
                return true
            }
            countSkipped++
            return false
        }
    }

    // ══════════════════════════════════════════════════════════
    //  PHASE 2b: SAP-Order-No (scripted) → PI n
    //  Computed/inherited SAP. Written as jira.bot (soft).
    // ══════════════════════════════════════════════════════════

    private void migrateSapScripted(ApplicationUser botUser) {
        log.info("-- Phase 2b: SAP-Order-No (scripted) --")
        process(jql(CF_SAP_SCRIPTED, "not EMPTY", CF_PROJ_INF_N_ID, "EMPTY"), botUser) { issue ->

            String sapRaw = issue.getCustomFieldValue(cfSapScripted)?.toString()?.trim()
            if (!sapRaw || sapRaw == "NOT Defined") { countSkipped++; return false }

            Option opt = resolveBySap(issue, sapRaw)
            if (opt) {
                set(issue, opt, botUser)
                countFromSapScripted++
                return true
            }
            countSkipped++
            return false
        }
    }

    // ══════════════════════════════════════════════════════════
    //  PHASE 2c: Old Project Information → PI n
    //  Fallback. Written as jira.bot (soft).
    // ══════════════════════════════════════════════════════════

    private void migrateOldPI(ApplicationUser botUser) {
        log.info("-- Phase 2c: old Project Information --")
        process(jql(CF_OLD_PROJ_INF, "not EMPTY", CF_PROJ_INF_N_ID, "EMPTY"), botUser) { issue ->

            String oldPI = extractDbRow(issue.getCustomFieldValue(cfOldPI))
            if (!oldPI) { countSkipped++; return false }

            // Exact match first
            Option opt = fullStringToOption[oldPI.trim()]

            // Fallback: extract SAP and resolve
            if (!opt) {
                String sap = extractSap(oldPI)
                if (sap) opt = resolveBySap(issue, sap)
            }

            if (opt) {
                set(issue, opt, botUser)
                countFromOldPI++
                return true
            }
            countSkipped++
            return false
        }
    }

    // ══════════════════════════════════════════════════════════
    //  PHASE 3: Inherit from hierarchy (preserve authority)
    // ══════════════════════════════════════════════════════════

    private void inherit(ApplicationUser botUser, ApplicationUser botTempUser) {
        log.info("-- Phase 3: hierarchical inheritance --")
        process(jql(CF_PROJ_INF_N_ID, "EMPTY"), botUser) { issue ->

            def result = walkUp(issue, new HashSet<Long>())
            if (!result) { countSkipped++; return false }

            Option opt = fullStringToOption[result.value]
            if (!opt) opt = new ProjectInfoPropagator().resolveOption(issue, result.value)
            if (!opt) { countSkipped++; return false }

            // Preserve authority: bot parent → bot child, temp parent → temp child
            ApplicationUser writeAs = isBot(result.author) ? botUser : botTempUser

            set(issue, opt, writeAs)
            countInherited++
            return true
        }
    }

    private Map walkUp(Issue issue, Set<Long> visited) {
        if (!issue || visited.contains(issue.id)) return null
        visited.add(issue.id)

        Issue parent = getParent(issue)
        if (!parent) return null

        String val = getValue(parent)
        if (val) return [value: val, author: getLastAuthor(parent)]

        return walkUp(parent, visited)
    }

    // ══════════════════════════════════════════════════════════
    //  PHASE 4: Disable created options
    // ══════════════════════════════════════════════════════════

    private void disableCreatedOptions() {
        if (!optionsToDisable) { log.info("-- Phase 4: nothing to disable --"); return }

        log.info("-- Phase 4: disabling ${optionsToDisable.size()} options --")
        optionsToDisable.each { optionId ->
            try {
                def opt = optManager.findByOptionId(optionId)
                if (opt) { optManager.disableOption(opt); countDisabled++ }
            } catch (Exception e) {
                log.error("  Failed to disable option ${optionId}: ${e.message}")
                countErrors++
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  SAP RESOLUTION (with disambiguácia)
    // ══════════════════════════════════════════════════════════

    /**
     * 1 match   → return it
     * N matches → disambiguate via old PI, or create "Multiple project with [SAP]"
     * 0 matches → create "history [SAP]"
     */
    private Option resolveBySap(Issue issue, String rawSap) {
        String sap = rawSap?.replaceAll("[^0-9]", "")
        if (!sap) return null

        def matches = sapToOptions[sap]

        if (matches?.size() == 1) return matches[0]

        if (matches?.size() > 1) {
            // Disambiguácia: old PI exact match?
            String oldPI = extractDbRow(issue.getCustomFieldValue(cfOldPI))?.trim()
            if (oldPI) {
                Option exact = matches.find { it.value?.toString()?.trim() == oldPI }
                if (exact) return exact
            }
            return getOrCreateOption(sap, String.format(ProjectInfoPropagator.LABEL_MULTIPLE, sap))
        }

        return getOrCreateOption(sap, String.format(ProjectInfoPropagator.LABEL_HISTORY, sap))
    }

    private Option getOrCreateOption(String cacheKey, String label) {
        if (createdOptionsCache.containsKey(cacheKey)) return createdOptionsCache[cacheKey]

        Option existing = fullStringToOption[label]
        if (existing) {
            createdOptionsCache[cacheKey] = existing
            optionsToDisable.add(existing.optionId)
            return existing
        }

        if (!fieldConfigs) { log.error("No field configs for '${label}'"); return null }

        def config = fieldConfigs[0]
        def opts = optManager.getOptions(config)
        long seq = (opts*.sequence.max() ?: 0) + 1
        def created = optManager.createOption(config, null, seq, label)

        fullStringToOption[label] = created
        createdOptionsCache[cacheKey] = created
        optionsToDisable.add(created.optionId)

        boolean isHistory = label.startsWith("history")
        if (isHistory) countHistory++ else countMultiple++
        log.info("  Created ${isHistory ? 'HISTORY' : 'MULTIPLE'}: '${label}'")

        return created
    }

    // ══════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════

    private Issue getParent(Issue issue) {
        if (parentCache.containsKey(issue.id)) return parentCache[issue.id]
        Issue p = null
        if (issue.isSubTask()) {
            p = issue.parentObject
        } else {
            def epic = issue.getCustomFieldValue(cfEpicLink)
            if (epic) p = issueManager.getIssueObject(epic.toString())
            else {
                def pl = issue.getCustomFieldValue(cfParentLink)
                if (pl) p = issueManager.getIssueObject(pl.toString())
            }
        }
        parentCache[issue.id] = p
        return p
    }

    private ApplicationUser getLastAuthor(Issue issue) {
        def histories = historyMgr.getChangeHistories(issue)
        if (!histories) return null
        def last = histories.reverse().find { h ->
            h.changeItemBeans?.any { it.field == ProjectInfoPropagator.CF_PROJ_INF_N_NAME }
        }
        if (!last) return null
        try { return last.authorObject }
        catch (Exception e) { return userManager.getUserByName(last.authorKey) }
    }

    private static boolean isBot(ApplicationUser user) {
        return user == null || user.name == JIRA_BOT || user.key == JIRA_BOT
    }

    private void set(Issue issue, Option opt, ApplicationUser user) {
        try {
            MutableIssue m = issueManager.getIssueObject(issue.id)
            m.setCustomFieldValue(cfNewPI, [opt])
            issueManager.updateIssue(user, m, EventDispatchOption.DO_NOT_DISPATCH, false)
        } catch (Exception e) {
            log.error("Failed to set '${opt?.value}' on ${issue?.key}: ${e.message}")
            countErrors++
        }
    }

    private String getValue(Issue issue) {
        def v = issue.getCustomFieldValue(cfNewPI)
        if (v instanceof Collection) return v?.find { it != null }?.value
        if (v instanceof Option) return v.value
        return null
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

    private static String cfId(String cf) { cf.replace("customfield_", "") }

    // ── JQL ───────────────────────────────────────────────────

    private static String jql(String cf1, String cond1, String cf2 = null, String cond2 = null) {
        String q = "cf[${cfId(cf1)}] is ${cond1}"
        if (cf2) q += " AND cf[${cfId(cf2)}] is ${cond2}"
        return q
    }

    // ── Batch processor ───────────────────────────────────────

    private void process(String jql, ApplicationUser user, Closure<Boolean> fn) {
        def parse = searchService.parseQuery(user, jql)
        if (!parse.valid) { log.error("Invalid JQL: ${jql}"); return }

        int start = 0, total = 0
        while (true) {
            def pager = new PagerFilter(BATCH_SIZE)
            pager.start = start
            def res = searchService.search(user, parse.query, pager)
            def issues = res.results
            if (!issues) break

            int skipped = 0, modified = 0
            issues.each {
                try {
                    Issue i = issueManager.getIssueObject(it.id)
                    if (i) { if (fn(i)) modified++ else skipped++ }
                } catch (Exception e) {
                    log.error("Error on ${it.id}: ${e.message}")
                    countErrors++; skipped++
                }
            }

            total += issues.size()
            log.info("  Batch: ${issues.size()} (mod:${modified} skip:${skipped}) Total:${total}")
            start += skipped
            if (modified == 0 && issues.size() < BATCH_SIZE) break
        }
    }
}
