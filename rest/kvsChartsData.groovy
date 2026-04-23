package rest

/**
 * kvsChartsData
 *
 * @author chabrecek.anton
 * Created on 27. 2. 2026.
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.link.IssueLink
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.transform.BaseScript
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import utils.MyBaseUtil
import utils.CustomFieldUtil
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.issueType.Audit
import kvs_audits.issueType.AuditPreparation
import kvs_audits.issueType.Question

import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

@BaseScript CustomEndpointDelegate delegate

kvsChartsData(httpMethod: "GET", groups: ["jira-administrators", "kvs-audit-admins"]) {
    MultivaluedMap queryParams ->

        def myBaseUtil = new MyBaseUtil()
        def cfUtil = new CustomFieldUtil()
        def jsonSlurper = new JsonSlurper()

        // ---- params
        String pcKey = (queryParams.getFirst("pcKey") ?: "PC9").toString()
        int weeks = ((queryParams.getFirst("weeks") ?: "12").toString()).toInteger()
        int listLimit = ((queryParams.getFirst("listLimit") ?: "500").toString()).toInteger()

        boolean isOverall = "overall".equalsIgnoreCase(pcKey)

        // ---- resolve Profit Center issue (unless overall)
        Issue pcIssue = null
        String scopeIssueKey = null
        if (!isOverall) {
            String pcJql = """project = KVSPC AND resolution = Unresolved AND issuetype = "Profit Center" AND "Profit Center Key" = "${pcKey}" ORDER BY priority DESC, updated DESC"""
            def pcIssues = myBaseUtil.findIssues(pcJql)
            if (!pcIssues) {
                return Response.status(404).entity(JsonOutput.toJson([error: "Profit Center not found", pcKey: pcKey]))
                        .type(MediaType.APPLICATION_JSON).build()
            }
            pcIssue = pcIssues.first() as Issue
            scopeIssueKey = pcIssue.key
        }

        // ---- date window (ISO week; start on Monday of (today - weeks + 1))
        def today = LocalDate.now(ZoneId.systemDefault())
        def currentMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        def start = currentMonday.minusWeeks(weeks - 1)

        // ---- build list of Mondays we want to show (oldest -> newest)
        List<LocalDate> weekMondays = []
        LocalDate m = start
        while (!m.isAfter(currentMonday)) {
            weekMondays << m
            m = m.plusWeeks(1)
        }

        // ---- KPI snapshot issues in the window
        String scopeClause = isOverall
                ? """AND "KPI Scope" is EMPTY"""
                : """AND "KPI Scope" = ${pcIssue.key}"""

        String snapJql = """
        project = "${CustomFieldsConstants.PROJECT_KPI}"
        AND issuetype = "Task"
        AND resolution = Unresolved
        ${scopeClause}
        AND "KPI From Date" >= "${start}"
        ORDER BY "KPI From Date" ASC
    """.stripIndent().trim()

        def snaps = myBaseUtil.findIssues(snapJql)

        // ---- index snapshots by Monday date (yyyy-MM-dd) for quick lookup
        Map<String, Map> snapshotsByMonday = [:]
        Map latestSnapshotPayload = null
        String latestMondayKey = null

        snaps.each { relIssue ->
            try {
                def rawJson = myBaseUtil.getCustomFieldValue(relIssue, "KPI Performance JSON")
                if (!rawJson) return
                def payload = jsonSlurper.parseText(rawJson.toString()) as Map
                String fromDate = payload.from?.toString()
                if (!fromDate) return
                snapshotsByMonday[fromDate] = payload
                if (latestMondayKey == null || fromDate > latestMondayKey) {
                    latestMondayKey = fromDate
                    latestSnapshotPayload = payload
                }
            } catch (Exception ex) {
                log.warn("Failed to parse snapshot ${relIssue?.key}: ${ex.message}")
            }
        }

        // ---- PC -> measures JQL (for on-the-fly fallback + live lists)
        String measuresJqlBase = resolveMeasuresJql(pcKey, isOverall)
        String questionsJqlBase = resolveQuestionsJql(pcKey, isOverall)
        String auditJqlBase = resolveAuditsJql(pcIssue, isOverall)

        // ---- build trend array (one entry per visible week)
        List trend = []
        List weekLabels = []
        List keyFigure = []
        List scatter = []
        Map<String, Long> pieAgg = [:].withDefault { 0L }
        boolean anyOnTheFlyUsed = false
        boolean anySnapshotUsed = false

        weekMondays.each { LocalDate mon ->
            String mondayKey = mon.toString()
            LocalDate sun = mon.plusDays(6)
            Map slice = snapshotsByMonday[mondayKey]

            Map measures, questionsStats, audits, statusCounts
            BigDecimal perfPct = 0G
            String weekNo = String.valueOf(mon.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
            String source = "onthefly"

            // Measures / audits are ALWAYS computed on-the-fly so the chart
            // reflects today's Jira state (stored snapshot values freeze Monday
            // morning and miss activity happening during the week, including on
            // the snapshot day itself). Performance / statusCounts / categories
            // stay snapshot-based because those represent a frozen point-in-time.
            if (slice) {
                source = "snapshot"
                anySnapshotUsed = true

                perfPct = toPct(slice.performanceTotal)
                statusCounts = (slice.statusCounts ?: [:]) as Map
                weekNo = (slice.week ?: weekNo).toString()
            } else {
                anyOnTheFlyUsed = true
                statusCounts = [:]
                perfPct = 0G
            }

            // Always fresh counts for the per-week history charts
            measures = computeStatsOnTheFly(myBaseUtil, measuresJqlBase, mon, sun)
            questionsStats = computeStatsOnTheFly(myBaseUtil, questionsJqlBase, mon, sun)
            audits = computeAuditStatsOnTheFly(myBaseUtil, auditJqlBase, mon, sun)

            weekLabels << ("W" + weekNo)
            keyFigure << perfPct

            scatter << [
                    x: asInt(measures?.open),
                    y: asInt(measures?.done)
            ]

            statusCounts.each { k, v ->
                pieAgg[k.toString()] = (pieAgg[k.toString()] ?: 0L) + asLong(v)
            }

            double auditRatePct = 0.0
            int auditsOpen = asInt(audits?.open)
            int auditsClosed = asInt(audits?.closed)
            int auditsTotal = auditsOpen + auditsClosed
            if (audits?.rate != null) {
                auditRatePct = ((audits.rate as BigDecimal) * 100).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue()
            } else if (auditsTotal > 0) {
                auditRatePct = ((auditsClosed as double) / auditsTotal) * 100.0
            }

            trend << [
                    week            : weekNo,
                    from            : mondayKey,
                    dataSource      : source,
                    performancePct  : perfPct,
                    measuresOpen    : asInt(measures?.open),
                    measuresDone    : asInt(measures?.done),
                    measuresCreated : asInt(measures?.created),
                    measuresResolved: asInt(measures?.resolved),
                    questionsOpen   : asInt(questionsStats?.open),
                    questionsDone   : asInt(questionsStats?.done),
                    auditsOpen      : auditsOpen,
                    auditsClosed    : auditsClosed,
                    auditsTotal     : auditsTotal,
                    auditRatePct    : auditRatePct
            ]
        }

        // ---- 12w average (from keyFigure array, unchanged from legacy behavior)
        BigDecimal avg = 0G
        def nonZero = keyFigure.findAll { (it as BigDecimal) > 0G }
        if (nonZero) {
            avg = ((nonZero.sum() as BigDecimal) / (nonZero.size() as BigDecimal))
                    .setScale(2, BigDecimal.ROUND_HALF_UP)
        }

        // ---- per-category performance (from latest snapshot)
        List categories = []
        if (latestSnapshotPayload?.performanceByCategory instanceof Map) {
            Map pbc = latestSnapshotPayload.performanceByCategory as Map
            pbc.each { cat, val ->
                categories << [
                        category: cat?.toString() ?: "Uncategorized",
                        percent : ((val ?: 0) as BigDecimal).multiply(100G).setScale(2, BigDecimal.ROUND_HALF_UP)
                ]
            }
            categories = categories.sort { -((it.percent ?: 0) as BigDecimal) }
        }

        // ---- overall / status breakdown (from latest snapshot's statusCounts)
        Map overall = buildOverallSection(latestSnapshotPayload, avg, weekLabels, keyFigure)

        // ---- LIVE LISTS: open measures, closed measures (30d), open audits
        List openMeasures = fetchOpenMeasures(myBaseUtil, measuresJqlBase, listLimit)
        List closedMeasures = fetchClosedMeasures(myBaseUtil, measuresJqlBase, 30, listLimit)
        List openAudits = fetchOpenAudits(myBaseUtil, auditJqlBase, listLimit, pcIssue, isOverall)

        // ---- SOURCE DATA DESCRIPTORS — one per chart / table, rendered in the UI
        String closedMeasuresLiveJql = "(${measuresJqlBase}) AND resolution != Unresolved AND resolutiondate >= \"${LocalDate.now().minusDays(30)}\" ORDER BY resolutiondate DESC"
        String openMeasuresLiveJql = "(${measuresJqlBase}) AND resolution = Unresolved ORDER BY created DESC"
        String openAuditsLiveJql = "(${auditJqlBase}) AND resolution = Unresolved ORDER BY \"${Audit.TARGET_END_FIELD_NAME}\" ASC"

        Map sources = [
                overallGauge        : [
                        type        : "formula",
                        description : "Rolling KPI across the last " + weeks + " weekly snapshots. Formula: sum of weighted positive statuses divided by sum of weighted positive + weighted NOK aging penalties. Values averaged across the window (non-zero weeks only). Zones: Red < 75 pct, Yellow 75-90 pct, Green >= 90 pct.",
                        questionsJql: questionsJqlBase
                ],
                categoryGrid        : [
                        type        : "snapshot",
                        description : "Per-category KPI from the LATEST available weekly snapshot for this scope. Categories come from the Category EN custom field on Question issues (free-text). Empty values fall back to Uncategorized.",
                        questionsJql: questionsJqlBase
                ],
                trendPerf           : [
                        type        : "snapshot",
                        description : "Weekly KPI value per scope. Values come from KPI snapshots in project " + CustomFieldsConstants.PROJECT_KPI + ". Weeks without a snapshot show 0 pct.",
                        questionsJql: questionsJqlBase
                ],
                historyOpen         : [
                        type        : "jql",
                        description : "Number of measures that were OPEN at the end of each week: created on/before that Sunday AND (still unresolved OR resolutiondate is after that Sunday). Counted live per week.",
                        jqlTemplate : "(" + measuresJqlBase + ") AND created < [week+1 Monday] AND (resolution = Unresolved OR resolutiondate >= [week+1 Monday])"
                ],
                historyClosed       : [
                        type        : "jql",
                        description : "Number of measures RESOLVED in each week (resolutiondate in Mon-Sun range). Counted live per week.",
                        jqlTemplate : "(" + measuresJqlBase + ") AND resolutiondate >= [Monday] AND resolutiondate < [next Monday]"
                ],
                historyCreated      : [
                        type        : "jql",
                        description : "Number of measures CREATED in each week (created date in Mon-Sun range). Counted live per week.",
                        jqlTemplate : "(" + measuresJqlBase + ") AND created >= [Monday] AND created < [next Monday]"
                ],
                auditExecution      : [
                        type             : "jql",
                        description      : "Weekly audit execution. OPEN = audits targeted for that week still unresolved (Target end in week). CLOSED = audits resolved in that week (resolutiondate in week). RATE = closed / (open + closed).",
                        jqlTemplateOpen  : "(" + auditJqlBase + ") AND resolution = Unresolved AND \"" + Audit.TARGET_END_FIELD_NAME + "\" >= [Monday] AND \"" + Audit.TARGET_END_FIELD_NAME + "\" < [next Monday]",
                        jqlTemplateClosed: "(" + auditJqlBase + ") AND resolution != Unresolved AND resolutiondate >= [Monday] AND resolutiondate < [next Monday]"
                ],
                statusDonut         : [
                        type        : "snapshot",
                        description : "Question statuses aggregated across all visible weekly snapshots for this scope. Numbers are sums across the window (a question appearing in multiple weekly snapshots is counted multiple times).",
                        questionsJql: questionsJqlBase
                ],
                openMeasuresTable   : [
                        type        : "jql",
                        description : "Live list of currently OPEN measures in this scope (resolution = Unresolved).",
                        jql         : openMeasuresLiveJql
                ],
                closedMeasuresTable : [
                        type        : "jql",
                        description : "Live list of measures CLOSED within the last 30 days (resolution != Unresolved AND resolutiondate within the last 30 days). If empty, no measures have been resolved in that window for this scope.",
                        jql         : closedMeasuresLiveJql
                ],
                openAuditsTable     : [
                        type        : "jql",
                        description : "Live list of currently OPEN audits in this scope, sorted by Target end ASC.",
                        jql         : openAuditsLiveJql
                ]
        ]

        // ---- assemble result
        def result = [
                pcKey           : pcKey,
                scopeIssueKey   : scopeIssueKey,
                from            : start.toString(),
                to              : currentMonday.plusDays(6).toString(),
                weeksRequested  : weeks,
                dataSource      : [
                        anySnapshot : anySnapshotUsed,
                        anyOnTheFly : anyOnTheFlyUsed,
                        strategy    : anySnapshotUsed && anyOnTheFlyUsed ? "mixed" :
                                (anySnapshotUsed ? "snapshot" : "onthefly")
                ],

                // --- NEW sections ---
                overall         : overall,
                categories      : categories,
                openMeasures    : openMeasures,
                closedMeasures  : closedMeasures,
                openAudits      : openAudits,
                sources         : sources,

                // --- LEGACY fields kept for backward compatibility ---
                weeks           : weekLabels,
                keyFigure       : keyFigure,
                keyFigureAvg12  : avg,
                pie             : [
                        labels: pieAgg.keySet().toList(),
                        values: pieAgg.values().toList()
                ],
                scatter         : scatter,
                trend           : trend
        ]

        return Response.ok(JsonOutput.toJson(result))
                .type(MediaType.APPLICATION_JSON)
                .build()
}


// ═══════════════════════════════════════════════════════════════════════════════
//  Helpers
// ═══════════════════════════════════════════════════════════════════════════════

/** Build "overall" section for the main KPI card. */
Map buildOverallSection(Map latestSnapshotPayload, BigDecimal avg, List weekLabels, List keyFigure) {
    String latestWeekLabel = weekLabels ? weekLabels.last() : "-"
    BigDecimal latestWeekPct = keyFigure ? (keyFigure.last() as BigDecimal) : 0G

    List breakdown = []
    long totalQuestions = 0
    Map sc = (latestSnapshotPayload?.statusCounts ?: [:]) as Map
    totalQuestions = sc.values().collect { (it as Number)?.longValue() ?: 0L }.sum() as long ?: 0L

    sc.each { k, v ->
        long n = (v as Number)?.longValue() ?: 0L
        double pct = totalQuestions > 0 ? ((n as double) / totalQuestions * 100.0) : 0.0
        breakdown << [
                status : k?.toString() ?: "Unknown",
                count  : n,
                percent: Math.round(pct * 100.0) / 100.0
        ]
    }
    breakdown = breakdown.sort { -((it.count ?: 0) as long) }

    return [
            averagePct     : avg,
            latestWeek     : latestWeekLabel,
            latestWeekPct  : latestWeekPct,
            totalQuestions : totalQuestions,
            statusBreakdown: breakdown
    ]
}

String kvsOverallQuestionsJql() {
    return "filter in (40422, 40423, 40424, 40425, 40426)"
}

/** Resolve questions JQL (used for stats + on-the-fly fallback). */
String resolveQuestionsJql(String pcKey, boolean isOverall) {
    if (isOverall) {
        // NO "resolution = Unresolved" — the saved filters include resolved issues (OK / I.O.N.M. / Duplicate).
        return kvsOverallQuestionsJql()
    }
    // Mirror filters defined in KPIWeeklySnapshotJob
    switch (pcKey) {
        case "PC2": return "filter=40423"
        case "PC3": return "filter=40424"
        case "PC4": return "filter=40425"
        case "PC6": return "filter=40426"
        case "PC9": return "filter=40422"
        default:
            return """project = "${CustomFieldsConstants.PROJECT_KVS_AUDIT}" AND issuetype = "${CustomFieldsConstants.QUESTION}" AND resolution = Unresolved"""
    }
}

/** Resolve measures JQL. */
String resolveMeasuresJql(String pcKey, boolean isOverall) {
    String baseMeasure = """project = "${CustomFieldsConstants.PROJECT_KVS_AUDIT}" AND issuetype = "${CustomFieldsConstants.MEASURE}" """
    if (isOverall) {
        // Measures that relate to any question in the 5 main PC filters
        return """${baseMeasure} AND issueFunction in linkedIssuesOf('${kvsOverallQuestionsJql()}', 'relates to')"""
    }
    String qFilter = null
    switch (pcKey) {
        case "PC2": qFilter = "filter=40423"; break
        case "PC3": qFilter = "filter=40424"; break
        case "PC4": qFilter = "filter=40425"; break
        case "PC6": qFilter = "filter=40426"; break
        case "PC9": qFilter = "filter=40422"; break
    }
    if (qFilter) {
        return """${baseMeasure} AND issueFunction in linkedIssuesOf('${qFilter}', 'relates to')"""
    }
    return baseMeasure
}

/** Resolve audits JQL. */
String resolveAuditsJql(Issue pcIssue, boolean isOverall) {
    String base = """project = "${CustomFieldsConstants.PROJECT_KVS_AUDIT}" AND issuetype = "${CustomFieldsConstants.AUDIT}" """
    if (isOverall) {
        // Option B overall: audits matching the 5 main PCs via Audit Description,
        // same semantic as the saved question filters.
        return """${base} AND ("Audit Description" ~ "PC2" OR "Audit Description" ~ "PC3" OR "Audit Description" ~ "PC4" OR "Audit Description" ~ "PC6" OR "Audit Description" ~ "PC9")"""
    }
    if (!pcIssue) return base
    return """${base} AND "${Audit.PROFIT_CENTER_FIELD_NAME}" = ${pcIssue.key}"""
}

/** Mirrors KPIWeeklySnapshotJob.computeStats() for fallback use. */
Map computeStatsOnTheFly(MyBaseUtil myBaseUtil, String baseJql, LocalDate weekStart, LocalDate weekEnd) {
    if (!baseJql) return [open: 0, done: 0, created: 0, resolved: 0]
    String ws = weekStart.toString()
    String weExcl = weekEnd.plusDays(1).toString()
    int open = 0, done = 0, created = 0, resolved = 0
    // "Open at end of target week" = created on/before week end AND still unresolved (or resolved after week end)
    try {
        open = myBaseUtil.findIssues(
                """(${baseJql}) AND created < "${weExcl}" AND (resolution = Unresolved OR resolutiondate >= "${weExcl}")"""
        ).size()
    } catch (e) {}
    try { done = myBaseUtil.findIssues("(${baseJql}) AND resolution != Unresolved").size() } catch (e) {}
    try { created = myBaseUtil.findIssues("(${baseJql}) AND created >= \"${ws}\" AND created < \"${weExcl}\"").size() } catch (e) {}
    try { resolved = myBaseUtil.findIssues("(${baseJql}) AND resolutiondate >= \"${ws}\" AND resolutiondate < \"${weExcl}\"").size() } catch (e) {}
    return [open: open, done: done, created: created, resolved: resolved]
}

/** Mirrors KPIWeeklySnapshotJob.computeAuditStats() for fallback use. */
Map computeAuditStatsOnTheFly(MyBaseUtil myBaseUtil, String auditJqlBase, LocalDate weekStart, LocalDate weekEnd) {
    if (!auditJqlBase) return [open: 0, closed: 0, created: 0, total: 0, rate: 0.0]
    String ws = weekStart.toString()
    String weExcl = weekEnd.plusDays(1).toString()

    int open = 0, closed = 0, created = 0
    try {
        open = myBaseUtil.findIssues(
                """(${auditJqlBase}) AND resolution = Unresolved AND "${Audit.TARGET_END_FIELD_NAME}" >= "${ws}" AND "${Audit.TARGET_END_FIELD_NAME}" < "${weExcl}" """
        ).size()
    } catch (Exception e) {}
    try {
        closed = myBaseUtil.findIssues(
                """(${auditJqlBase}) AND resolution != Unresolved AND resolutiondate >= "${ws}" AND resolutiondate < "${weExcl}" """
        ).size()
    } catch (Exception e) {}
    try {
        created = myBaseUtil.findIssues(
                """(${auditJqlBase}) AND created >= "${ws}" AND created < "${weExcl}" """
        ).size()
    } catch (Exception e) {}

    int total = open + closed
    double rate = total > 0 ? ((double) closed / total) : 0.0
    return [open: open, closed: closed, created: created, total: total, rate: rate]
}

/** Live list of OPEN measures with enriched fields. */
List fetchOpenMeasures(MyBaseUtil myBaseUtil, String measuresJqlBase, int limit) {
    if (!measuresJqlBase) return []
    String jql = "(${measuresJqlBase}) AND resolution = Unresolved ORDER BY created DESC"
    def issues = safeFindIssues(myBaseUtil, jql)
    return issues.take(limit).collect { Issue m -> measureToRow(m, false) }
}

/** Live list of CLOSED measures resolved within the last N days. */
List fetchClosedMeasures(MyBaseUtil myBaseUtil, String measuresJqlBase, int daysBack, int limit) {
    if (!measuresJqlBase) return []
    String since = LocalDate.now().minusDays(daysBack).toString()
    String jql = "(${measuresJqlBase}) AND resolution != Unresolved AND resolutiondate >= \"${since}\" ORDER BY resolutiondate DESC"
    def issues = safeFindIssues(myBaseUtil, jql)
    return issues.take(limit).collect { Issue m -> measureToRow(m, true) }
}

/** Convert a Measure issue into a row for the UI, pulling data from the linked Question. */
Map measureToRow(Issue measure, boolean closed) {
    def myBase = new MyBaseUtil()
    def linkManager = ComponentAccessor.issueLinkManager

    Issue linkedQuestion = null
    try {
        def links = []
        links.addAll(linkManager.getOutwardLinks(measure.id) ?: [])
        links.addAll(linkManager.getInwardLinks(measure.id) ?: [])
        def relates = links.find { IssueLink il -> il.issueLinkType?.name?.toLowerCase()?.contains("relate") }
        if (relates) {
            Issue src = relates.sourceObject
            Issue dst = relates.destinationObject
            Issue other = (src?.id == measure.id) ? dst : src
            if (other && other.issueType?.name == CustomFieldsConstants.QUESTION) {
                linkedQuestion = other
            }
        }
    } catch (Exception e) { /* best-effort */ }

    String dateStr = null
    try {
        def d = closed ? measure.resolutionDate : measure.created
        if (d) dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd").format(d)
    } catch (Exception e) {}

    String deviation = null
    String auditLocation = null
    String pcKey = null
    String level = null
    String questionKey = linkedQuestion?.key
    String personResp = null
    try {
        if (linkedQuestion) {
            deviation = asString(myBase.getCustomFieldValue(linkedQuestion, Question.DEVIATION_FIELD_NAME))
            def locRaw = myBase.getCustomFieldValue(linkedQuestion, Question.AUDIT_LOCATION_FIELD_NAME)
            auditLocation = formatIssuePickerList(locRaw)
            personResp = formatUsers(myBase.getCustomFieldValue(linkedQuestion, Question.PERSON_RESPONSIBILITY_FIELD_NAME))

            // Walk parent chain: Question -> Audit -> AuditPreparation
            Issue parentAudit = linkedQuestion.parentObject
            if (parentAudit) {
                def pc = myBase.getCustomFieldValue(parentAudit, Audit.PROFIT_CENTER_FIELD_NAME)
                if (pc instanceof Issue) pcKey = (pc as Issue).summary ?: (pc as Issue).key
                level = asString(myBase.getCustomFieldValue(parentAudit, AuditPreparation.AUDIT_LEVEL_FIELD_NAME))
            }
        }
    } catch (Exception e) { /* best-effort */ }

    return [
            date         : dateStr,
            measureKey   : measure.key,
            measure      : measure.summary,
            questionKey  : questionKey,
            deviation    : deviation ?: "",
            auditLocation: auditLocation ?: "",
            profitCenter : pcKey ?: "",
            responsible  : measure.assignee?.displayName ?: "",
            personResponsibility: personResp ?: "",
            status       : measure.status?.name ?: "",
            level        : level ?: ""
    ]
}

/** Live list of OPEN audits with Jira + CF fields. */
List fetchOpenAudits(MyBaseUtil myBaseUtil, String auditJqlBase, int limit, Issue pcIssue, boolean isOverall) {
    if (!auditJqlBase) return []
    String jql = "(${auditJqlBase}) AND resolution = Unresolved ORDER BY \"${Audit.TARGET_END_FIELD_NAME}\" ASC"
    def issues = safeFindIssues(myBaseUtil, jql)

    return issues.take(limit).collect { Issue a -> auditToRow(a) }
}

Map auditToRow(Issue audit) {
    def myBase = new MyBaseUtil()

    def targetEnd = null
    String targetEndStr = null
    Integer weekNo = null
    try {
        def raw = myBase.getCustomFieldValue(audit, Audit.TARGET_END_FIELD_NAME)
        if (raw) {
            LocalDate ld
            if (raw instanceof java.sql.Timestamp) {
                ld = raw.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            } else if (raw instanceof java.util.Date) {
                ld = raw.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            } else if (raw instanceof LocalDate) {
                ld = raw
            } else {
                ld = LocalDate.parse(raw.toString().substring(0, 10))
            }
            targetEnd = ld
            targetEndStr = ld.toString()
            weekNo = ld.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        }
    } catch (Exception e) {}

    String auditId = asString(myBase.getCustomFieldValue(audit, Audit.AUDIT_ID_FIELD_NAME))
    String auditLevel = asString(myBase.getCustomFieldValue(audit, AuditPreparation.AUDIT_LEVEL_FIELD_NAME))
    String auditType = asString(myBase.getCustomFieldValue(audit, Audit.AUDIT_TYPE_FIELD_NAME))

    String pcName = ""
    try {
        def pc = myBase.getCustomFieldValue(audit, Audit.PROFIT_CENTER_FIELD_NAME)
        if (pc instanceof Issue) pcName = ((Issue) pc).summary ?: ((Issue) pc).key
    } catch (Exception e) {}

    String faName = ""
    try {
        def fa = myBase.getCustomFieldValue(audit, Audit.FUNCTIONAL_AREA_FIELD_NAME)
        if (fa instanceof Issue) faName = ((Issue) fa).summary ?: ((Issue) fa).key
    } catch (Exception e) {}

    String workplaces = ""
    try {
        def wp = myBase.getCustomFieldValue(audit, Audit.WORKPLACES_FIELD_NAME)
        workplaces = formatIssuePickerList(wp)
    } catch (Exception e) {}

    return [
            auditKey       : audit.key,
            auditId        : auditId ?: "",
            targetEnd      : targetEndStr ?: "",
            week           : weekNo != null ? weekNo.toString() : "",
            level          : auditLevel ?: "",
            profitCenter   : pcName,
            functionalArea : faName,
            workplaces     : workplaces,
            assignee       : audit.assignee?.displayName ?: "",
            auditType      : auditType ?: ""
    ]
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Tiny utility helpers
// ═══════════════════════════════════════════════════════════════════════════════

List safeFindIssues(MyBaseUtil myBaseUtil, String jql) {
    try {
        return myBaseUtil.findIssues(jql) ?: []
    } catch (Exception e) {
        return []
    }
}

int asInt(Object v) {
    if (v == null) return 0
    if (v instanceof Number) return ((Number) v).intValue()
    try { return Integer.parseInt(v.toString()) } catch (e) { return 0 }
}

long asLong(Object v) {
    if (v == null) return 0L
    if (v instanceof Number) return ((Number) v).longValue()
    try { return Long.parseLong(v.toString()) } catch (e) { return 0L }
}

String asString(Object v) {
    if (v == null) return null
    return v.toString()
}

BigDecimal toPct(Object raw) {
    if (raw == null) return 0G
    try {
        BigDecimal bd = (raw as BigDecimal) * 100G
        return bd.setScale(2, BigDecimal.ROUND_HALF_UP)
    } catch (Exception e) {
        return 0G
    }
}

/** Format a Jira "Issue Picker" value (Issue or Collection<Issue>) as a comma-separated string. */
String formatIssuePickerList(Object raw) {
    if (raw == null) return ""
    if (raw instanceof Issue) return ((Issue) raw).summary ?: ((Issue) raw).key
    if (raw instanceof Collection) {
        return ((Collection) raw).collect { it instanceof Issue ? (((Issue) it).summary ?: ((Issue) it).key) : it.toString() }
                .findAll { it != null && !it.toString().isEmpty() }
                .join(", ")
    }
    return raw.toString()
}

String formatUsers(Object raw) {
    if (raw == null) return ""
    if (raw instanceof Collection) {
        return ((Collection) raw).collect { u -> userDisplay(u) }.findAll { it }.join(", ")
    }
    return userDisplay(raw)
}

String userDisplay(Object u) {
    if (u == null) return ""
    try {
        if (u.hasProperty("displayName")) return u.displayName ?: ""
        if (u.hasProperty("name")) return u.name ?: ""
    } catch (Exception e) {}
    return u.toString()
}