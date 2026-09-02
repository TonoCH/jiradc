package rest

/**
 * kvsChartsLive
 *
 * Live (on-the-fly) recalculation for the KVS charts. Recomputes, against
 * TODAY's Jira state:
 *
 *   - Overall Performance   (current week gauge)
 *   - Trend                 (every week of the selected window + current week)
 *   - Performance per Category
 *   - Question status counts
 *
 * PERFORMANCE NOTE
 * ----------------
 * The questions are loaded ONCE and every per-issue attribute needed by the KPI
 * formula (workflow status, Category EN, the parent audit's Target end) is
 * extracted ONCE into a lightweight in-memory row. All weeks are then derived
 * from those rows without touching Jira again - the only thing that changes
 * between weeks is the age of an open NOK, so there is nothing to re-read.
 *
 * Two further micro-optimisations vs. the naive approach:
 *   - CustomField objects are resolved once (MyBaseUtil.getCustomFieldValue(name)
 *     re-scans every custom field of the issue on each call),
 *   - the parent audit's Target end is cached per audit id (many questions
 *     share one audit).
 *
 * The arithmetic mirrors KVSPerformanceCalculator.calculateKPI() exactly:
 *   numerator   = (iO + FIXED + I.O.N.M.) * Gm * Gz
 *   denominator = numerator + (NOK * Gm * Gz * ageWeeks)
 *   performance = sum(numerator) / sum(denominator)
 * (the calculator buckets by ISO week of the audit date and then sums all
 *  buckets, which is arithmetically identical to summing directly.)
 *
 * @author chabrecek.anton
 * Created on 2026-06-22.
 */

import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.transform.BaseScript
import groovy.json.JsonOutput
import kvs_audits.issueType.Audit
import kvs_audits.issueType.Question
import kvs_audits.reports.KPIWeeklySnapshotJob
import utils.CustomFieldUtil
import utils.MyBaseUtil

import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

@BaseScript CustomEndpointDelegate delegate

kvsChartsLive(httpMethod: "GET", groups: ["jira-administrators", "kvs-audit-admins"]) {
    MultivaluedMap queryParams ->

        long t0 = System.currentTimeMillis()

        // Status -> KPI meaning. Mirrors KVSPerformanceCalculator.isIO/isBehoben/isFixedLater/isNIO.
        List<String> positiveStatuses = ["OK", "FIXED", "I.O.N.M."]
        String nokStatus = "NOK"

        String pcKey = (queryParams.getFirst("pcKey") ?: "overall").toString()
        int weeks = ((queryParams.getFirst("weeks") ?: "12").toString()).toInteger()
        if (weeks < 1) weeks = 1
        if (weeks > 104) weeks = 104

        Map<String, Map<String, String>> defs = KPIWeeklySnapshotJob.KPI_DEFS
        Map defn = defs[pcKey] ?: defs["overall"]
        boolean scopeResolved = defs.containsKey(pcKey)
        String questionsJql = (defn.questionsJql as String)?.trim()

        def myBaseUtil = new MyBaseUtil()

        // ---- resolve custom fields ONCE (see performance note above)
        CustomField cfQuestionStatus = CustomFieldUtil.getCustomFieldByName(Question.QUESTION_STATUS_FIELD_NAME)
        CustomField cfCategoryEn = CustomFieldUtil.getCustomFieldByName(Question.CATEGORY_EN_FIELD_NAME)
        CustomField cfTargetEnd = Audit.TARGET_END_FIELD

        // ---- load questions for the scope (one JQL, no per-week fan-out)
        List<Issue> questions = []
        String loadError = null
        try {
            questions = myBaseUtil.findIssues(questionsJql) ?: []
        } catch (Exception e) {
            loadError = "Question search failed: ${e.message}".toString()
        }
        long tLoaded = System.currentTimeMillis()

        // ---- flatten every question into an in-memory row (single pass over Jira)
        Map<Long, LocalDate> auditDateCache = [:]
        List<Map> rows = new ArrayList<Map>(questions.size())
        Map<String, Integer> statusCounts = [:]

        questions.each { Issue q ->
            // Reported breakdown: "Question Status" CF, falling back to the workflow status.
            String reportedStatus = null
            try {
                reportedStatus = cfQuestionStatus ? q.getCustomFieldValue(cfQuestionStatus)?.toString() : null
            } catch (Exception ignored) {
            }
            reportedStatus = reportedStatus ?: (q.status?.name ?: "UNKNOWN")
            statusCounts[reportedStatus] = (statusCounts[reportedStatus] ?: 0) + 1

            // KPI arithmetic uses the workflow status (same as KVSPerformanceCalculator).
            String kpiStatus = q.status?.name ?: ""
            int positive = positiveStatuses.contains(kpiStatus) ? 1 : 0
            int nok = (kpiStatus == nokStatus) ? 1 : 0
            if (positive == 0 && nok == 0) return   // TO DO / Not Checked / Duplicate -> ignored

            String category = "Uncategorized"
            try {
                String raw = cfCategoryEn ? q.getCustomFieldValue(cfCategoryEn)?.toString()?.trim() : null
                if (raw) category = raw
            } catch (Exception ignored) {
            }

            rows << [
                    positive   : positive,
                    nok        : nok,
                    category   : category,
                    auditMonday: resolveAuditMonday(q, cfTargetEnd, auditDateCache)
            ]
        }
        long tFlattened = System.currentTimeMillis()

        // ---- week window: identical to kvsChartsData (start .. last CLOSED week),
        //      plus the current running week as the final "today" point.
        LocalDate today = LocalDate.now(ZoneId.systemDefault())
        LocalDate currentMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        LocalDate windowEnd = currentMonday.minusWeeks(1)
        LocalDate start = windowEnd.minusWeeks(weeks - 1)

        List<LocalDate> weekMondays = []
        LocalDate m = start
        while (!m.isAfter(currentMonday)) {
            weekMondays << m
            m = m.plusWeeks(1)
        }

        // ---- all weeks derived from the in-memory rows
        List trend = weekMondays.collect { LocalDate mon ->
            Map agg = aggregate(rows, mon)
            return [
                    week          : String.valueOf(mon.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)),
                    from          : mon.toString(),
                    performancePct: pct(agg.numerator as double, agg.denominator as double),
                    currentWeek   : (mon == currentMonday)
            ]
        }

        // ---- current week detail: gauge + categories + the numbers behind the formula
        Map current = aggregate(rows, currentMonday)
        BigDecimal perfPct = pct(current.numerator as double, current.denominator as double)

        List categories = []
        (current.byCategory as Map).each { String cat, Map v ->
            categories << [
                    category    : cat,
                    percent     : pct(v.numerator as double, v.denominator as double),
                    positives   : v.positives,
                    nokCount    : v.nokCount,
                    penaltyWeeks: v.penaltyWeeks
            ]
        }
        categories = categories.sort { -((it.percent ?: 0) as BigDecimal) }

        long elapsedMs = System.currentTimeMillis() - t0

        def result = [
                scope         : pcKey,
                scopeResolved : scopeResolved,
                weeksRequested: weeks,
                questionsJql  : questionsJql,
                computedAt    : new Date().toString(),
                elapsedMs     : elapsedMs,
                timings       : [
                        searchMs : tLoaded - t0,
                        extractMs: tFlattened - tLoaded,
                        computeMs: System.currentTimeMillis() - tFlattened
                ],
                questionsCount: questions.size(),
                evaluatedCount: rows.size(),

                // current week (gauge)
                week          : String.valueOf(currentMonday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)),
                from          : currentMonday.toString(),
                performancePct: perfPct,

                // the raw ingredients of the formula, so the UI can show
                // "7158 / (7158 + 85) = 98.83 %" with the auditor's own numbers
                explain       : [
                        positives   : current.positives,
                        nokCount    : current.nokCount,
                        penaltyWeeks: current.penaltyWeeks,
                        numerator   : round2(current.numerator as double),
                        denominator : round2(current.denominator as double),
                        ignored     : questions.size() - ((rows.size()) as int)
                ],

                statusCounts  : statusCounts,
                categories    : categories,
                trend         : trend
        ]
        if (loadError) result.error = loadError

        return Response.ok(JsonOutput.toJson(result))
                .type(MediaType.APPLICATION_JSON)
                .build()
}


// ═══════════════════════════════════════════════════════════════════════════════
//  Helpers
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Sum numerator / denominator over every evaluated question for one reference
 * week. Only the NOK aging term depends on the week, everything else is fixed.
 */
Map aggregate(List<Map> rows, LocalDate weekMonday) {
    double numerator = 0.0d
    double denominator = 0.0d
    int positives = 0
    int nokCount = 0
    long penaltyWeeks = 0L
    Map<String, Map> byCategory = [:]

    rows.each { Map r ->
        int isPositive = (int) r.positive
        int isNok = (int) r.nok

        double num = isPositive * 1.0d * 1.0d          // Gm * Gz, both 1.0 today
        int age = (isNok == 1) ? ageWeeks(r.auditMonday as LocalDate, weekMonday) : 0
        double den = num + (isNok * 1.0d * 1.0d * age)

        numerator += num
        denominator += den
        positives += isPositive
        nokCount += isNok
        penaltyWeeks += age

        Map c = byCategory.get(r.category as String)
        if (c == null) {
            c = [numerator: 0.0d, denominator: 0.0d, positives: 0, nokCount: 0, penaltyWeeks: 0L]
            byCategory.put(r.category as String, c)
        }
        c.numerator = (c.numerator as double) + num
        c.denominator = (c.denominator as double) + den
        c.positives = (c.positives as int) + isPositive
        c.nokCount = (c.nokCount as int) + isNok
        c.penaltyWeeks = (c.penaltyWeeks as long) + age
    }

    return [
            numerator   : numerator,
            denominator : denominator,
            positives   : positives,
            nokCount    : nokCount,
            penaltyWeeks: penaltyWeeks,
            byCategory  : byCategory
    ]
}

/** Whole ISO weeks between the audit week and the reference week, never negative. */
int ageWeeks(LocalDate auditMonday, LocalDate weekMonday) {
    if (auditMonday == null) return 0
    return Math.max(0, (int) ChronoUnit.WEEKS.between(auditMonday, weekMonday))
}

/**
 * Monday of the week the question was audited in - the parent audit's "Target end",
 * falling back to the question's created date (same rule as
 * KVSPerformanceCalculator.getAuditDate). Cached per parent audit.
 */
LocalDate resolveAuditMonday(Issue question, CustomField cfTargetEnd, Map<Long, LocalDate> cache) {
    LocalDate d = null
    try {
        Issue parent = question.parentObject
        if (parent && cfTargetEnd) {
            if (cache.containsKey(parent.id)) {
                d = cache.get(parent.id)
            } else {
                d = toLocalDate(parent.getCustomFieldValue(cfTargetEnd))
                cache.put(parent.id, d)
            }
        }
    } catch (Exception ignored) {
    }
    if (d == null) {
        try {
            d = question.created.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        } catch (Exception ignored) {
            return null
        }
    }
    return d?.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

LocalDate toLocalDate(Object raw) {
    if (raw == null) return null
    try {
        if (raw instanceof LocalDate) return (LocalDate) raw
        if (raw instanceof Timestamp) return ((Timestamp) raw).toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        if (raw instanceof java.util.Date) return ((java.util.Date) raw).toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        return LocalDate.parse(raw.toString().substring(0, 10))
    } catch (Exception ignored) {
        return null
    }
}

BigDecimal pct(double numerator, double denominator) {
    if (denominator <= 0.0d) return 0G
    return (((numerator / denominator) * 100.0d) as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP)
}

BigDecimal round2(double v) {
    return (v as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP)
}
