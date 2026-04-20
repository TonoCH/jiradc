package kvs_audits.reports


import kvs_audits.KVSLogger
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.common.JqlSearcher
import kvs_audits.issueType.AuditPreparation
import utils.MyBaseUtil
import com.atlassian.jira.issue.Issue

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * KPIJob
 *
 * @author chabrecek.anton
 * Created on 13/05/2025.
 */
class KPIJob {

    JqlSearcher jqlSearcher = new JqlSearcher(CustomFieldsConstants.DEFAULT_PROJECT_FOR_JOBS)

    public void execute() {
        def myBaseUtil = new MyBaseUtil()
        def logger = new KVSLogger()

        List<Issue> prepIssues = jqlSearcher.getAllAuditsPreparation()//myBaseUtil.findIssues("project = ${PROJECT_KEY} AND issuetype = '${AUDIT_PREP_TYPE}' AND resolution = Unresolved")
        logger.setInfoMessage("Found ${prepIssues.size()} Audit Preparation issues for KPI calculation")

        def calculator = new KVSPerformanceCalculator()
        def exporter = new KVSExportHandler()

        prepIssues.each { prepIssue ->
            AuditPreparation auditPreparation = new AuditPreparation(prepIssue)
            def usageList = auditPreparation.getQuestionUsage()
            if (!usageList) {
                logger.setWarnMessage("No Question Usage defined for ${prepIssue.key}, skipping")
                return
            }

            // Fetch questions for this preparation issue
            List<Issue> questionIssues = jqlSearcher.getQuestions(auditPreparation)//"project = ${Question.PROJECT_KVS_AUDIT_QUESTION} AND resolution = Unresolved AND 'Question Usage' in (${usageList.collect { "\"${it}\"" }.join(", ")})"
            if (!questionIssues) {
                logger.setWarnMessage("No questions found for ${prepIssue.key}, skipping")
                return
            }

            def kpiData = calculator.calculateKPI(questionIssues, LocalDate.now())
            exporter.exportToCustomField(auditPreparation.getMutableIssue(), kpiData, AuditPreparation.KVS_KPI_JSON_FIELD_NAME)
            logger.setInfoMessage("Exported KPI JSON to ${prepIssue.key}")
        }

        def today = LocalDate.now()
        def week = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

        if (today.dayOfWeek == DayOfWeek.MONDAY) {
            new KPIWeeklySnapshotJob().execute();//runWeeklySnapshots(week, today)
            new KPIWeeklyWeightingJob().execute();
        }
    }
}
