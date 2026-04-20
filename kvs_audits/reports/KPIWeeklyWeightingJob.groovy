/*
 * Scheduled Job: Weekly Delay Weighting increment
 * - Delay weighting = 1..N, +1 every week if measure is not done (unresolved).
 */

package kvs_audits.reports

import kvs_audits.KVSLogger
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.issueType.Question
import utils.CustomFieldUtil
import utils.MyBaseUtil

class KPIWeeklyWeightingJob {

    // ====== CONFIG ======
    static final String CF_DELAY = Question.DELAY_WEIGHTING_FIELD_NAME
    static final String MEASURES_OPEN_JQL = """project = "${CustomFieldsConstants.PROJECT_KVS_AUDIT}" AND issuetype = "${CustomFieldsConstants.MEASURE}" AND resolution = Unresolved"""
    // ====================

    private final KVSLogger logger = new KVSLogger()
    private final MyBaseUtil myBaseUtil = new MyBaseUtil()
    private final CustomFieldUtil cfUtil = new CustomFieldUtil()

    void execute() {
        def cfDelay = cfUtil.getCustomFieldByName(CF_DELAY)
        if (!cfDelay) throw new IllegalStateException("Custom field not found: ${CF_DELAY}")

        def issues = myBaseUtil.findIssues(MEASURES_OPEN_JQL)
        issues.each { issue ->
            def current = myBaseUtil.getCustomFieldValue(issue, CF_DELAY)
            Double val = (current instanceof Number) ? (current as Number).doubleValue() : null
            if (val == null) val = 0d

            myBaseUtil.setCustomFieldValue(issue, cfDelay, (val == 0d ? 1d : (val + 1d)))
        }

        logger.setInfoMessage("Delay weighting updated for ${issues.size()} measures.")
    }
}