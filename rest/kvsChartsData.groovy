package rest

/**
 * kvsChartsHtml
 *
 * @author chabrecek.anton
 * Created on 27. 2. 2026.
 */

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.transform.BaseScript
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import utils.MyBaseUtil
import utils.CustomFieldUtil
import kvs_audits.common.CustomFieldsConstants

import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@BaseScript CustomEndpointDelegate delegate

kvsChartsData(httpMethod: "GET", groups: ["jira-administrators", "kvs-audit-admins"]) { MultivaluedMap queryParams ->

    def myBaseUtil = new MyBaseUtil()
    def cfUtil = new CustomFieldUtil()
    def jsonSlurper = new JsonSlurper()

    // ---- params
    String pcKey = (queryParams.getFirst("pcKey") ?: "PC9").toString()
    int weeks = ((queryParams.getFirst("weeks") ?: "12").toString()).toInteger()

    // ---- resolve Profit Center issue for KPI Scope (Multiple Issue Picker)
    String pcJql = """project = KVSPC AND resolution = Unresolved AND issuetype = "Profit Center" AND "Profit Center Key" = "${pcKey}" ORDER BY priority DESC, updated DESC"""
    def pcIssues = myBaseUtil.findIssues(pcJql)
    if (!pcIssues) {
        return Response.status(404).entity(JsonOutput.toJson([error: "Profit Center not found", pcKey: pcKey]))
                .type(MediaType.APPLICATION_JSON).build()
    }
    def pcIssue = pcIssues.first()

    // ---- date window (avoid week number wrap)
    def today = LocalDate.now(ZoneId.systemDefault())
    def start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(weeks - 1)

    // ---- KPI snapshot issues
    String snapJql = """
        project = "${CustomFieldsConstants.PROJECT_KPI}"
        AND issuetype = "Task"
        AND resolution = Unresolved
        AND "KPI Scope" = ${pcIssue.key}
        AND "KPI From Date" >= "${start}"
        ORDER BY "KPI From Date" ASC
    """.stripIndent().trim()

    def snaps = myBaseUtil.findIssues(snapJql)

    // ---- parse JSON + build series
    def weekLabels = []
    def keyFigure = [] // % per week
    def scatter = []   // open vs done points
    def pieAgg = [:].withDefault { 0L }

    def trend = []

    snaps.each { relIssue ->
        def rawJson = myBaseUtil.getCustomFieldValue(relIssue, "KPI Performance JSON")
        if (!rawJson) return

        def payload = jsonSlurper.parseText(rawJson.toString())

        def weekNo = payload.week?.toString() ?: "?"
        def fromDate = payload.from?.toString()
        def perf = ((payload.performanceTotal ?: 0) as BigDecimal) * 100

        def m = payload.measures ?: [:]
        def q = payload.questions ?: [:]
        def sc = payload.statusCounts ?: [:]

        weekLabels << ("W" + weekNo)
        keyFigure << perf.setScale(2, BigDecimal.ROUND_HALF_UP)

        scatter << [
                x: (m.open ?: 0) as Integer,
                y: (m.done ?: 0) as Integer
        ]

        sc.each { k, v ->
            pieAgg[k.toString()] = (pieAgg[k.toString()] ?: 0L) + ((v ?: 0) as Long)
        }

        trend << [
                week            : weekNo,
                from            : fromDate,
                performancePct  : perf.setScale(2, BigDecimal.ROUND_HALF_UP),
                measuresOpen    : (m.open ?: 0) as Integer,
                measuresDone    : (m.done ?: 0) as Integer,
                measuresCreated : (m.created ?: 0) as Integer,
                measuresResolved: (m.resolved ?: 0) as Integer,
                questionsOpen   : (q.open ?: 0) as Integer,
                questionsDone   : (q.done ?: 0) as Integer
        ]
    }

    BigDecimal avg = 0
    if (keyFigure) {
        avg = (keyFigure.sum() as BigDecimal) / (keyFigure.size() as BigDecimal)
        avg = avg.setScale(2, BigDecimal.ROUND_HALF_UP)
    }

    def result = [
            pcKey         : pcKey,
            scopeIssueKey : pcIssue.key,
            from          : start.toString(),
            weeksRequested: weeks,
            weeks         : weekLabels,
            keyFigure     : keyFigure,
            keyFigureAvg12: avg,
            pie           : [
                    labels: pieAgg.keySet().toList(),
                    values: pieAgg.values().toList()
            ],
            scatter       : scatter,
            trend         : trend
    ]

    log.error(result)

    return Response.ok(JsonOutput.toJson(result))
            .type(MediaType.APPLICATION_JSON)
            .build()
}