package kvs_audits.reports

import com.atlassian.jira.issue.Issue
import kvs_audits.KVSLogger
import kvs_audits.issueType.Audit

import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import utils.MyBaseUtil
import java.time.temporal.ChronoUnit

import java.util.logging.Logger;

/**
 * KVSPerformanceCalculator
 *
 * Computes the KVS-Performance KPI based on audit results.
 *
 * Legend:
 *
 * Sets & Indices:
 *  - J   : number of calendar weeks in the evaluation window (from first audit to current week)
 *  - j   : specific calendar week (1 ≤ j ≤ J)
 *  - M   : total number of audit criteria evaluated each week
 *  - m   : specific audit criterion (1 ≤ m ≤ M)
 *  - Mz  : number of criteria belonging to category z
 *  - mz  : specific criterion within category z
 *
 * Binary Indicators (values 0 or 1):
 *  - iO_jm      : 1 if criterion m was “in order” (compliant) in week j, else 0
 *  - behoben_jm : 1 if criterion m was fixed directly in week j, else 0
 *  - iOnM_jm    : 1 if m was non-compliant earlier but later remedied by a measure (counted as compliant for week j), else 0
 *  - niO_jm     : 1 if m is still non-compliant in week j (open deviation), else 0
 *
 * Weights & Aging:
 *  - Gm         : weighting factor for criterion m (range 0–1)
 *  - Gz         : weighting factor for category z (range 0–1)
 *  - (J - j)    : number of weeks a deviation found in week j has remained open
 *
 * Per-criterion fraction:
 *  numerator   = (iO + behoben + iOnM) * Gm * Gz
 *  denominator = numerator + (niO * Gm * Gz * (J - j))
 *  weeklyContribution = numerator / denominator
 *
 * Aggregation:
 *  - TotalPerformance    = sum(numerators) / sum(denominators) across all weeks j and criteria m
 *  - CategoryPerformance = sum(numerators) / sum(denominators) across all j, for criteria m in category z
 *
 * Status Mapping:
 *  - "OK"       → iO_jm = 1 (criterion is compliant)
 *  - "FIXED"    → behoben_jm = 1 (criterion was fixed directly that week)
 *  - "I.O.N.M." → iOnM_jm = 1 (fixed by later measure, originally NOK)
 *  - "NOK"      → niO_jm = 1 (criterion still non-compliant)
 *  - "TO DO"    → ignored (not evaluated yet)
 *
 * @author chabrecek.anton
 * @created     2025-05-13
 */
class KVSPerformanceCalculator {

    protected MyBaseUtil myBaseUtil = new MyBaseUtil();
    protected KVSLogger logger = new KVSLogger()
    /**
     * Calculates overall and per-category KVS performance metrics.
     *
     * @param questions  list of Jira Issue objects representing audit criteria
     * @param currentDate     LocalDate used to derive the current calendar week
     * @return map with keys:
     *         - performanceTotal       : overall KPI (Double)
     *         - performanceByCategory  : Map<String,Double> of category → KPI
     *         - evaluatedWeeks        : Sorted List<Integer> of weeks evaluated
     *         - lastUpdated           : String date of calculation (YYYY-MM-DD)
     */
    Map<String,Object> calculateKPI(List<Issue> questions, LocalDate currentDate) {
        Map<Integer,Map<String,Double>> weekResults = [:]
        Map<String,Map<String,Double>> categoryResults = [:]

        questions.each { Issue question ->
            LocalDate auditDate = getAuditDate(question)
            int ageWeeks = Math.max(0, (int) ChronoUnit.WEEKS.between(
                    auditDate.with(java.time.DayOfWeek.MONDAY),
                    currentDate.with(java.time.DayOfWeek.MONDAY)
            ))

            int j = auditDate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            String category = getCategory(question)
            double Gm = getWeightGm(question)
            double Gz = getWeightGz(category)

            int iO      = isIO(question) ? 1 : 0
            int behoben = isBehoben(question) ? 1 : 0
            int iOnM    = isFixedLater(question) ? 1 : 0
            int niO     = isNIO(question) ? 1 : 0

            double numerator   = (iO + behoben + iOnM) * Gm * Gz
            double denominator = numerator + (niO * Gm * Gz * ageWeeks)

            weekResults[j] = weekResults.get(j, [num:0.0, den:0.0])
            weekResults[j].num += numerator
            weekResults[j].den += denominator

            categoryResults[category] = categoryResults.get(category, [num:0.0, den:0.0])
            categoryResults[category].num += numerator
            categoryResults[category].den += denominator
        }

        double totalNumerator = weekResults.values().sum { it.num } ?: 0.0
        double totalDenominator = weekResults.values().sum { it.den } ?: 0.0
        double performanceTotal = totalDenominator > 0 ? (totalNumerator / totalDenominator) : 0.0

        Map<String,Double> performanceByCategory = categoryResults.collectEntries { cat, vals ->
            double num = vals.num ?: 0.0
            double den = vals.den ?: 0.0
            [(cat): (den > 0 ? (num / den) : 0.0)]
        }

        return [
                performanceTotal      : performanceTotal,
                performanceByCategory : performanceByCategory,
                evaluatedWeeks        : weekResults.keySet().sort(),
                lastUpdated           : LocalDate.now().toString()
        ]
    }

    LocalDate getAuditDate(Issue question) {
        Issue parentAudit = question.parentObject
        if (parentAudit) {
            def raw = myBaseUtil.getCustomFieldValue(parentAudit, Audit.TARGET_END_FIELD_NAME)
            if (raw) {
                return raw instanceof Timestamp
                        ? raw.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                        : (raw instanceof LocalDate ? raw : raw.toLocalDate())
            }
        }

        return question.created.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
    }

     //Retrieves the KVS category from a custom field (falls back to "Uncategorized").
    //TODO will we have category??
    String getCategory(Issue q) {
        //TODO - enable if we have category for KVS
        //return q.getCustomFieldValue("KVS Category") ?: "Uncategorized"
        return "Uncategorized";
    }

    // Weighting factor Gm (criterion weight).
    double getWeightGm(Issue q) { return 1.0 }

    // Weighting factor Gz (category weight).
    double getWeightGz(String category) { return 1.0 }

     // Status "OK" maps to iO_jm = 1 (compliant criterion)
    boolean isIO(Issue q)        { return q.getStatus().name == "OK" }

     // Status "FIXED" maps to behoben_jm = 1 (fixed in the same week)
    boolean isBehoben(Issue q)   { return q.getStatus().name == "FIXED" }

     // Status "I.O.N.M." maps to iOnM_jm = 1 (fixed retrospectively by a measure)
    boolean isFixedLater(Issue q){ return q.getStatus().name == "I.O.N.M." }

     // Status "NOK" maps to niO_jm = 1 (still open deviation)
    boolean isNIO(Issue q)       { return q.getStatus().name == "NOK" }
}