package rest

/**
 * kvsChartsLive
 *
 * Live (on-the-fly) prototype for KVS charts. Returns the same shape as a
 * single snapshot payload, but computed against TODAY's Jira state instead of
 * the frozen Monday-morning snapshot. Intended for one scope, current week
 * only — do NOT call this in a loop over weeks (would be 10x slower).
 *
 * Mirrors KPIWeeklySnapshotJob.runForWeek for one scope:
 *   - statusCounts (group by Question Status CF)
 *   - performanceTotal + performanceByCategory (via KVSPerformanceCalculator)
 *
 * @author chabrecek.anton
 * Created on 2026-06-22.
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.transform.BaseScript
import groovy.json.JsonOutput
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.issueType.Question
import kvs_audits.reports.KPIWeeklySnapshotJob
import kvs_audits.reports.KVSPerformanceCalculator
import utils.CustomFieldUtil
import utils.MyBaseUtil

import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

@BaseScript CustomEndpointDelegate delegate

kvsChartsLive(httpMethod: "GET", groups: ["jira-administrators", "kvs-audit-admins"]) {
    MultivaluedMap queryParams ->

        long t0 = System.currentTimeMillis()

        String pcKey = (queryParams.getFirst("pcKey") ?: "overall").toString()

        Map<String, Map<String, String>> defs = KPIWeeklySnapshotJob.KPI_DEFS
        Map defn = defs[pcKey] ?: defs["overall"]
        String questionsJql = (defn.questionsJql as String)?.trim()

        def myBaseUtil = new MyBaseUtil()
        def cfUtil = new CustomFieldUtil()
        CustomField cfQuestionStatus = cfUtil.getCustomFieldByName(Question.QUESTION_STATUS_FIELD_NAME)

        // ---- load questions for scope (one JQL, no per-week fan-out)
        List<Issue> questions = []
        try {
            questions = myBaseUtil.findIssues(questionsJql) ?: []
        } catch (Exception ignored) {
        }

        // ---- current week anchor (this Monday)
        LocalDate today = LocalDate.now(ZoneId.systemDefault())
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        String weekNo = String.valueOf(weekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

        // ---- run the same calculator the snapshot job uses
        Map kpiData = [:] as Map
        try {
            kpiData = new KVSPerformanceCalculator().calculateKPI(questions, weekStart) as Map ?: [:]
        } catch (Exception e) {
            kpiData = [error: "calculateKPI failed: ${e.message}".toString()]
        }

        // ---- status counts (live)
        Map<String, Integer> statusCounts = questions.collect { Issue q ->
            def s = cfQuestionStatus ? q.getCustomFieldValue(cfQuestionStatus)?.toString() : null
            s ?: (q.status?.name ?: "UNKNOWN")
        }.groupBy { it }.collectEntries { k, v -> [(k): (v as List).size()] } as Map<String, Integer>

        // ---- shape result like a snapshot slice + add a live performance %
        BigDecimal perfPct = 0G
        try {
            perfPct = ((kpiData.performanceTotal ?: 0) as BigDecimal) * 100G
            perfPct = perfPct.setScale(2, BigDecimal.ROUND_HALF_UP)
        } catch (Exception ignored) {
        }

        List categories = []
        if (kpiData.performanceByCategory instanceof Map) {
            (kpiData.performanceByCategory as Map).each { cat, val ->
                categories << [
                        category: cat?.toString() ?: "Uncategorized",
                        percent : ((val ?: 0) as BigDecimal).multiply(100G).setScale(2, BigDecimal.ROUND_HALF_UP)
                ]
            }
            categories = categories.sort { -((it.percent ?: 0) as BigDecimal) }
        }

        long elapsedMs = System.currentTimeMillis() - t0

        def result = [
                scope         : pcKey,
                week          : weekNo,
                from          : weekStart.toString(),
                computedAt    : new Date().toString(),
                elapsedMs     : elapsedMs,
                questionsCount: questions.size(),
                performancePct: perfPct,
                statusCounts  : statusCounts,
                categories    : categories
        ]

        return Response.ok(JsonOutput.toJson(result))
                .type(MediaType.APPLICATION_JSON)
                .build()
}
