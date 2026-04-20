package listeners.fuel_area.worklogs_reporting

/*
 * Fuel reporting listener — ESU-style hierarchy via HierarchyProvider.
 * Triggers: Issue Created, Issue Updated, Worklog Created, Worklog Updated, Worklog Deleted
 *
 * Levels updated: Epic, Feature, LPM Initiative/Initiative
 * Units:
 *   - Epic & Feature: HOURS (2 dp stored as Double)
 *   - LPM: MANDAYS (hours / 8, 2 dp stored as Double)
 *
 * Signal Ratio (3 dp) rule:
 *   - If budget is null/empty/<= 0 => SR = 1.000
 *   - Else 20% threshold logic on same units as the level’s N-fields
 *
 * LPM vs Epic Budget Difference (mandays):
 *   diffD = (LPM_Budget_sec - SUM(Epic_Budget_sec_under_this_LPM)) / 28800, 2 dp
 *
 * Budget Usage (%):
 *   Epic/Feature: 100 * (spentH / budgetH) ; LPM: 100 * (spentD / budgetD)
 *   If the relevant budget is null/<=0 => set null (configurable to 0%)
 *
 * Only calls updateIssue + reIndex for targets where fields actually changed.
 * Updated: 2025-08-26
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.ModifiedValue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder
import com.atlassian.jira.user.ApplicationUser
import org.apache.log4j.Logger

import com.onresolve.scriptrunner.runner.customisers.WithPlugin
import com.onresolve.scriptrunner.runner.customisers.PluginModule
import aptis.plugins.epicSumUp.api.HierarchyProvider

@WithPlugin("aptis.plugins.epicSumUp")
Logger log = Logger.getLogger("fuel.reporting.listener")

@PluginModule
HierarchyProvider hierarchyProvider

if (!(event instanceof IssueEvent)) return

def ev = event as IssueEvent
MutableIssue changed = ev.issue as MutableIssue
if (!changed) return

ApplicationUser user = ComponentAccessor.jiraAuthenticationContext?.loggedInUser
if (!user) return

def issueManager    = ComponentAccessor.issueManager
def indexingService = ComponentAccessor.getComponent(IssueIndexingService)

// Worklog event IDs: 10=created, 15=updated, 16=deleted
boolean isWorklogEvent = [10L, 15L, 16L].contains(ev?.eventTypeId as Long)

/* ---------- Resolve CustomFields via WorklogConstants ---------- */
// Sources: budgets (seconds)
CustomField lpmTimeBudgetField     = WorklogConstants.getCF(WorklogConstants.LPM_TIME_BUDGET_FIELD)
CustomField epicTimeBudgetField    = WorklogConstants.getCF(WorklogConstants.EPIC_TIME_BUDGET_FIELD)
CustomField featureTimeBudgetField = WorklogConstants.getCF(WorklogConstants.FEATURE_TIME_BUDGET_FIELD)

// Targets: numeric N fields (Double)
// Epic (HOURS)
CustomField epicBudgetN        = WorklogConstants.getCF(WorklogConstants.EPIC_TIME_BUDGET_N_FIELD)
CustomField epicSignalRatio    = WorklogConstants.getCF(WorklogConstants.EPIC_SIGNAL_RATIO_FIELD)
CustomField epicTimeRemainingN = WorklogConstants.getCF(WorklogConstants.EPIC_TIME_REMAINING_FIELD)
CustomField epicTimeSpentN     = WorklogConstants.getCF(WorklogConstants.EPIC_TIME_SPENT_FIELD)

// Feature (HOURS)
CustomField featureSignalRatio    = WorklogConstants.getCF(WorklogConstants.FEATURE_SIGNAL_RATIO_FIELD)
CustomField featureTimeRemainingN = WorklogConstants.getCF(WorklogConstants.FEATURE_TIME_REMAINING_FIELD)
CustomField featureTimeSpentN     = WorklogConstants.getCF(WorklogConstants.FEATURE_TIME_SPENT_FIELD)
CustomField featureBudgetN        = WorklogConstants.getCF(WorklogConstants.FEATURE_TIME_BUDGET_N_FIELD)

// LPM (MANDAYS)
CustomField lpmSignalRatio     = WorklogConstants.getCF(WorklogConstants.LPM_SIGNAL_RATIO_FIELD)
CustomField lpmBudgetN         = WorklogConstants.getCF(WorklogConstants.LPM_TIME_BUDGET_N_FIELD)
CustomField lpmTimeRemainingN  = WorklogConstants.getCF(WorklogConstants.LPM_TIME_REMAINING_FIELD)
CustomField lpmTimeSpentN      = WorklogConstants.getCF(WorklogConstants.LPM_TIME_SPENT_FIELD)

//LPM vs Epic Budget Difference (mandays, Number)
CustomField lpmEpicBudgetDiffMandaysN = WorklogConstants.getCF(WorklogConstants.LPM_EPIC_BUDGET_DIFF_MANDAYS)

//Budget Usage (%) across Epic/Feature/LPM
CustomField budgetUsagePercent = WorklogConstants.getCF(WorklogConstants.BUDGET_USAGE_PERCENT_FIELD)
if (!budgetUsagePercent) {
    log.warn("Budget Usage (%) custom field not found (id=${WorklogConstants.BUDGET_USAGE_PERCENT_FIELD?.id}). Skipping writes for this field.")
}

/* ---------- helpers ---------- */
def ch = new DefaultIssueChangeHolder()
final double EPS = 1e-9
// flip to false if you prefer 0% when no budget
final boolean TREAT_NO_BUDGET_AS_NULL = true

def getCFSeconds = { Issue i, CustomField cf ->
    if (!cf || !i) return 0L
    def v = i.getCustomFieldValue(cf)
    (v instanceof Number) ? ((v as Number).longValue()) : 0L
}
def round2 = { Double d -> d == null ? null : (d as Double).round(2) }
def toDouble2dp = { Double d -> d == null ? null : (d as Double).round(2) }

// SR: budget<=0 -> 1.000; else 20% threshold; 3 dp
def ratio3 = { Double budget, Double remaining ->
    if (budget == null || !(budget instanceof Number) || Math.abs(budget) < EPS) {
        return 1.000d
    }
    Double threshold = 0.2 * budget
    double sr
    if (remaining != null && remaining > threshold) {
        sr = (remaining - threshold) / budget
    } else if (remaining != null && remaining < 0) {
        sr = remaining / threshold
    } else {
        sr = 0.0
    }
    (sr as Double).round(3)
}

def pctOrNull = { Double spent, Double budget ->//used helper (keeps null when no/zero budget by default)
    if (budget == null || budget <= 0.0d) {
        return TREAT_NO_BUDGET_AS_NULL ? null : 0.0d
    }
    round2((spent ?: 0.0d) / budget * 100.0d)
}

def nearlyEqual = { Object a, Object b ->
    if (a == b) return true
    if (a == null || b == null) return false
    if (a instanceof Number && b instanceof Number) {
        return Math.abs(((a as Number).doubleValue() - (b as Number).doubleValue())) < EPS
    }
    a == b
}

def writeCF = { Issue tgt, CustomField cf, Object newVal ->
    if (!cf || !tgt) return false
    def oldVal = tgt.getCustomFieldValue(cf)
    if (nearlyEqual(oldVal, newVal)) return false
    cf.updateValue(null, tgt as MutableIssue, new ModifiedValue(oldVal, newVal), ch)
    true
}

/* ---------- ESU-style scope ---------- */
def esuChildren = { Issue root ->
    List<Issue> kids = hierarchyProvider?.getChildIssues(root, user) ?: []
    LinkedHashSet<Issue> set = new LinkedHashSet<>()
    set.add(root)
    kids.each { if (it) set.add(it) }
    set as List<Issue>
}
def esuParents = { Issue leaf ->
    Issue[] arr = hierarchyProvider?.getParents(leaf, user)
    (arr ? arr.toList() : [])
}
def sumTimeSpentSeconds = { Issue root ->
    long total = 0L
    esuChildren(root).each { Issue i -> total += (i?.getTimeSpent() ?: 0L) }
    total
}

/* ---- Collect targets: changed + ESU parents; keep only Epic/Feature/LPM ---- */
def isEpic    = { Issue i -> i?.issueType?.name?.equalsIgnoreCase("Epic") }
def isFeature = { Issue i -> i?.issueType?.name?.equalsIgnoreCase("Feature") }
def isLpm     = { Issue i -> i?.issueType?.name?.equalsIgnoreCase("LPM Initiative") || i?.issueType?.name?.equalsIgnoreCase("Initiative") }

Set<Issue> allTargets = new LinkedHashSet<>()
allTargets.add(changed)
esuParents(changed)?.each { if (it) allTargets.add(it) }
allTargets = allTargets.findAll { isEpic(it) || isFeature(it) || isLpm(it) } as Set

// Track changes per target; update/reindex only those
Map<String, Boolean> changedMap = [:].withDefault { false }

/* ---------- helper: sum Epic budgets under a given LPM (via ESU children) ---------- */
def sumEpicBudgetsSecondsUnderLpm = { Issue lpmIssue ->
    long total = 0L
    // First level children -> Features
    esuChildren(lpmIssue).findAll { it?.id != lpmIssue?.id && isFeature(it) }.each { Issue feature ->
        // Children of Feature -> Epics
        esuChildren(feature).findAll { isEpic(it) }.each { Issue epic ->
            total += getCFSeconds(epic, epicTimeBudgetField)
        }
    }
    total
}

allTargets.each { Issue tgt ->
    if (isEpic(tgt)) {
        long budgetSec = getCFSeconds(tgt, epicTimeBudgetField)
        long spentSec  = sumTimeSpentSeconds(tgt)

        Double budgetH = round2(budgetSec / 3600.0)
        Double spentH  = round2(spentSec  / 3600.0)
        Double remainH = round2((budgetSec - spentSec) / 3600.0)

        Double sr      = ratio3(budgetH, remainH)
        Double pctUsed = pctOrNull(spentH, budgetH)

        boolean c = false
        c |= writeCF(tgt, epicTimeSpentN,     toDouble2dp(spentH))
        c |= writeCF(tgt, epicTimeRemainingN, toDouble2dp(remainH))
        c |= writeCF(tgt, epicBudgetN,        budgetH)
        c |= writeCF(tgt, epicSignalRatio,    sr)
        c |= writeCF(tgt, budgetUsagePercent, toDouble2dp(pctUsed))

        changedMap[tgt.key] = changedMap[tgt.key] || c
    }
    else if (isFeature(tgt)) {
        long budgetSec = getCFSeconds(tgt, featureTimeBudgetField)
        long spentSec  = sumTimeSpentSeconds(tgt)

        Double budgetH = round2(budgetSec / 3600.0)
        Double spentH  = round2(spentSec  / 3600.0)
        Double remainH = round2((budgetSec - spentSec) / 3600.0)

        Double sr      = ratio3(budgetH, remainH)
        Double pctUsed = pctOrNull(spentH, budgetH)

        boolean c = false
        c |= writeCF(tgt, featureTimeSpentN,     toDouble2dp(spentH))
        c |= writeCF(tgt, featureTimeRemainingN, toDouble2dp(remainH))
        c |= writeCF(tgt, featureBudgetN,        budgetH)
        c |= writeCF(tgt, featureSignalRatio,    sr)
        c |= writeCF(tgt, budgetUsagePercent,    toDouble2dp(pctUsed))

        changedMap[tgt.key] = changedMap[tgt.key] || c
    }
    else if (isLpm(tgt)) {
        long lpmBudgetSec = getCFSeconds(tgt, lpmTimeBudgetField)
        long spentSec     = sumTimeSpentSeconds(tgt)

        Double budgetH = lpmBudgetSec / 3600.0
        Double spentH  = spentSec     / 3600.0
        Double remainH = (lpmBudgetSec - spentSec) / 3600.0

        Double budgetD = round2(budgetH / 8.0)
        Double spentD  = round2(spentH  / 8.0)
        Double remainD = round2(remainH / 8.0)

        Double sr      = ratio3(budgetD, remainD)
        Double pctUsed = pctOrNull(spentD, budgetD)

        boolean c = false
        c |= writeCF(tgt, lpmTimeSpentN,     toDouble2dp(spentD))
        c |= writeCF(tgt, lpmTimeRemainingN, toDouble2dp(remainD))
        c |= writeCF(tgt, lpmBudgetN,        budgetD)
        c |= writeCF(tgt, lpmSignalRatio,    sr)
        c |= writeCF(tgt, budgetUsagePercent, toDouble2dp(pctUsed))

        // LPM vs Epic Budget Difference (mandays), optional target
        if (lpmEpicBudgetDiffMandaysN) {
            long sumEpicBudgetsSec = sumEpicBudgetsSecondsUnderLpm(tgt)
            Double diffD = round2((lpmBudgetSec - sumEpicBudgetsSec) / 28800.0d)  // 28800 = 8h * 3600
            c |= writeCF(tgt, lpmEpicBudgetDiffMandaysN, toDouble2dp(diffD))
        }

        changedMap[tgt.key] = changedMap[tgt.key] || c
    }
}

/* ---- Persist + reindex ONLY for changed targets ---- */
def changedTargets = allTargets.findAll { Issue t -> (changedMap[t.key] ?: false) }
if (!changedTargets.isEmpty()) {
    changedTargets.each { Issue tgt ->
        def dispatch = isWorklogEvent ? EventDispatchOption.ISSUE_UPDATED : EventDispatchOption.DO_NOT_DISPATCH
        issueManager.updateIssue(user, tgt as MutableIssue, dispatch, false)
        try {
            indexingService?.reIndex(tgt)
        } catch (Throwable t) {
            log.warn("Reindex failed for ${tgt.key}: ${t.message}")
        }
    }
}