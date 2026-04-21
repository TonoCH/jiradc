package kvs_audits.reports

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.IssueInputParameters
import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.issue.fields.CustomField
import groovy.json.JsonOutput
import kvs_audits.KVSLogger
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.issueType.Audit
import utils.CustomFieldUtil
import utils.MyBaseUtil
import kvs_audits.issueType.Question

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import com.atlassian.jira.user.ApplicationUser

class KPIWeeklySnapshotJob {

    // ====== CONFIG ======
    static final String SNAPSHOT_ISSUE_TYPE = "Task"
    static final double CALC_VERSION = 1.0

    static final String CF_SCOPE = "KPI Scope"
    static final String CF_WEEK = "KPI Week"
    static final String CF_CALC_VERSION = "KPI Calculation Version"
    static final String CF_JSON = "KPI Performance JSON"
    static final String CF_FROM = "KPI From Date"
    static final String CF_TO = "KPI To Date"
    static final String PCKEY2 = "PC2"
    static final String PCKEY3 = "PC3"
    static final String PCKEY4 = "PC4"
    static final String PCKEY6 = "PC6"
    static final String PCKEY9 = "PC9"
    static final String KVS_PC2_QUESTIONS = "filter=40423";
    static final String KVS_PC3_QUESTIONS = "filter=40424";
    static final String KVS_PC4_QUESTIONS = "filter=40425";
    static final String KVS_PC6_QUESTIONS = "filter=40426";
    static final String KVS_PC9_QUESTIONS = "filter=40422";

    static final String KVS_OVERALL_QUESTIONS = "filter in (40422, 40423, 40424, 40425, 40426)"


    // Static KPI definitions
    static final Map<String, Map<String, String>> KPI_DEFS = [
            "overall"  : [
                    pcKey       : null,
                    // NOTE: NO "resolution = Unresolved" here — PC-specific filters also include
                    // Resolved issues (OK / I.O.N.M. / Duplicate). Without those statuses the
                    // rolling KPI collapses from ~95 % to ~2 %.
                    questionsJql: """${KVS_OVERALL_QUESTIONS}""",
                    measuresJql : """project = "${CustomFieldsConstants.PROJECT_KVS_AUDIT}" AND issuetype = "${CustomFieldsConstants.MEASURE}" AND issueFunction in linkedIssuesOf('${KVS_OVERALL_QUESTIONS}', 'relates to')"""
            ],
            "${PCKEY2}": [
                    pcKey       : PCKEY2,
                    questionsJql: """$KVS_PC2_QUESTIONS """,
                    measuresJql : """project = "${CustomFieldsConstants.PROJECT_KVS_AUDIT}" AND issuetype = "${CustomFieldsConstants.MEASURE}" AND issueFunction in linkedIssuesOf('$KVS_PC2_QUESTIONS', 'relates to')"""
            ],
            "${PCKEY3}": [
                    pcKey       : PCKEY3,
                    questionsJql: """$KVS_PC3_QUESTIONS """,
                    measuresJql : """project = "${CustomFieldsConstants.PROJECT_KVS_AUDIT}" AND issuetype = "${CustomFieldsConstants.MEASURE}" AND issueFunction in linkedIssuesOf('$KVS_PC3_QUESTIONS', 'relates to')"""
            ],
            "${PCKEY4}": [
                    pcKey       : PCKEY4,
                    questionsJql: """$KVS_PC4_QUESTIONS """,
                    measuresJql : """project = "${CustomFieldsConstants.PROJECT_KVS_AUDIT}" AND issuetype = "${CustomFieldsConstants.MEASURE}" AND issueFunction in linkedIssuesOf('$KVS_PC4_QUESTIONS', 'relates to')"""
            ],
            "${PCKEY6}": [
                    pcKey       : PCKEY6,
                    questionsJql: """$KVS_PC6_QUESTIONS """,
                    measuresJql : """project = "${CustomFieldsConstants.PROJECT_KVS_AUDIT}" AND issuetype = "${CustomFieldsConstants.MEASURE}" AND issueFunction in linkedIssuesOf('$KVS_PC6_QUESTIONS', 'relates to')"""
            ],
            "${PCKEY9}": [
                    pcKey       : PCKEY9,
                    questionsJql: """$KVS_PC9_QUESTIONS """,
                    measuresJql : """project = "${CustomFieldsConstants.PROJECT_KVS_AUDIT}" AND issuetype = "${CustomFieldsConstants.MEASURE}" AND issueFunction in linkedIssuesOf('$KVS_PC9_QUESTIONS', 'relates to')"""
            ]
    ]
    // ====================

    private final KVSLogger logger = new KVSLogger()
    private final MyBaseUtil myBaseUtil = new MyBaseUtil()
    private final CustomFieldUtil cfUtil = new CustomFieldUtil()

    void execute() {
        // Weekly scheduled run: always targets the current ISO week (Monday).
        LocalDate monday = LocalDate.now(ZoneId.systemDefault())
                .with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        runForWeek(monday, null)
    }

    void runForWeek(LocalDate weekMonday, Collection<String> scopeFilter) {

        def authCtx = ComponentAccessor.jiraAuthenticationContext
        def userMgr = ComponentAccessor.userManager

        ApplicationUser runAs = userMgr.getUserByName("jira.bot")
        if (!runAs) throw new IllegalStateException("Run-as user not found: jira.bot")

        authCtx.setLoggedInUser(runAs)
        def user = runAs
        def issueService = ComponentAccessor.getIssueService()
        def constants = ComponentAccessor.constantsManager
        def project = ComponentAccessor.projectManager.getProjectObjByKey(CustomFieldsConstants.PROJECT_KPI)
        CustomField cfQuestionStatus = cfUtil.getCustomFieldByName(Question.QUESTION_STATUS_FIELD_NAME)

        if (!project) throw new IllegalStateException("Project not found: ${CustomFieldsConstants.PROJECT_KPI}")

        CustomField cfScope = cfUtil.getCustomFieldByName(CF_SCOPE)
        CustomField cfWeek = cfUtil.getCustomFieldByName(CF_WEEK)
        CustomField cfCalc = cfUtil.getCustomFieldByName(CF_CALC_VERSION)
        CustomField cfJson = cfUtil.getCustomFieldByName(CF_JSON)
        CustomField cfFrom = cfUtil.getCustomFieldByName(CF_FROM)
        CustomField cfTo = cfUtil.getCustomFieldByName(CF_TO)

        if (!cfScope || !cfWeek || !cfCalc || !cfJson) {
            throw new IllegalStateException("Missing KPI CFs (scope/week/calc/json). Check names.")
        }

        LocalDate weekStart = weekMonday.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        LocalDate weekEnd = weekStart.plusDays(6)
        String weekNo = weekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString()

        def fromDate = java.sql.Date.valueOf(weekStart)
        def toDate = java.sql.Date.valueOf(weekEnd)

        def issueType = constants.allIssueTypeObjects.find { it.name == SNAPSHOT_ISSUE_TYPE }
        if (!issueType) throw new IllegalStateException("IssueType not found: ${SNAPSHOT_ISSUE_TYPE}")

        def calculator = new KVSPerformanceCalculator()

        KPI_DEFS.each { String scopeKey, Map<String, String> defn ->
            if (scopeFilter != null && !scopeFilter.contains(scopeKey)) return

            String questionsJql = defn.questionsJql
            String measuresJql = defn.measuresJql
            String pcKeyDefined = defn.pcKey

            // Find questions for KPI calculation
            List<Issue> questions = myBaseUtil.findIssues(questionsJql)
            if (!questions) {
                logger.setWarnMessage("No questions for scope=${scopeKey}, week=${weekNo}. JQL=${questionsJql}")
            }

            def kpiData = calculator.calculateKPI(questions ?: [], weekStart)

            // Add weekly context and extra aggregates for charts
            Map payload = [
                    scope      : scopeKey,
                    week       : weekNo,
                    from       : weekStart.toString(),
                    to         : weekEnd.toString(),
                    calcVersion: CALC_VERSION
            ] + kpiData

            payload.statusCounts = (questions ?: [])
                    .collect { Issue q ->
                        def qStatus = cfQuestionStatus ? q.getCustomFieldValue(cfQuestionStatus)?.toString() : null
                        qStatus ?: (q.status?.name ?: "UNKNOWN")
                    }
                    .groupBy { it }
                    .collectEntries { k, v -> [(k): v.size()] }

            // Add measure stats (optional but recommended)
            if (measuresJql) {
                payload.measures = computeStats(measuresJql, weekStart, weekEnd)
            }

            if (questionsJql) {
                payload.questions = computeStats(questionsJql, weekStart, weekEnd)
            }

            // Audit execution stats per PC.
            // Resolve PC issue first (needed for both snapshot CF link and audit JQL).
            Issue pcIssue = findProfitCenterByKey(pcKeyDefined)
            payload.audits = computeAuditStats(pcIssue, weekStart, weekEnd)

            String json = JsonOutput.prettyPrint(JsonOutput.toJson(payload))

            MutableIssue snap = findExistingSnapshot(pcIssue, weekNo, CALC_VERSION)

            if (!snap) {
                snap = createSnapshot(issueService, user, project.id, issueType.id, scopeKey, weekNo)
                logger.setInfoMessage("Created snapshot ${snap.key} for scope=${scopeKey}, week=${weekNo}")
            } else {
                logger.setInfoMessage("Updating snapshot ${snap.key} for scope=${scopeKey}, week=${weekNo}")
            }

            if (pcIssue) {
                myBaseUtil.setCustomFieldValue(snap, cfScope, [pcIssue])
                // Multiple Issue Picker expects Collection<Issue>
            }
            myBaseUtil.setCustomFieldValue(snap, cfWeek, weekNo)
            myBaseUtil.setCustomFieldValue(snap, cfCalc, CALC_VERSION)
            myBaseUtil.setCustomFieldValue(snap, cfJson, json)
            if (cfFrom) myBaseUtil.setCustomFieldValue(snap, cfFrom, fromDate)
            if (cfTo) myBaseUtil.setCustomFieldValue(snap, cfTo, toDate)

            ComponentAccessor.issueManager.updateIssue(user, snap, EventDispatchOption.ISSUE_UPDATED, false)
        }
    }

    void backfillOverall(List<LocalDate> mondays) {
        mondays.each { LocalDate m -> runForWeek(m, ["overall"]) }
    }

    // Finds existing snapshot for (scope + week + calcVersion)
    private MutableIssue findExistingSnapshot(Issue pcIssue, String weekNo, double calcVersion) {
        def scopeClause = pcIssue ? """AND "${CF_SCOPE}" = ${pcIssue.key}""" : """AND "${CF_SCOPE}" is EMPTY"""

        String jql = """
        project = "${CustomFieldsConstants.PROJECT_KPI}"
        AND issuetype = "${SNAPSHOT_ISSUE_TYPE}"
        AND resolution = Unresolved
        ${scopeClause}
        AND "${CF_WEEK}" = ${weekNo}
        AND "${CF_CALC_VERSION}" = ${calcVersion}
        ORDER BY created DESC
    """.stripIndent().trim()

        def issues = myBaseUtil.findIssues(jql)
        return issues ? (issues.first() as MutableIssue) : null
    }

    private Issue findProfitCenterByKey(String pcKey) {
        if (!pcKey) return null
        String jql = """project = KVSPC AND resolution = Unresolved AND issuetype = "Profit Center" AND "Profit Center Key" = "${pcKey}" ORDER BY priority DESC, updated DESC""".trim()
        def pcs = myBaseUtil.findIssues(jql)
        return pcs ? (pcs.first() as Issue) : null
    }

    private MutableIssue createSnapshot(IssueService issueService, def user, Long projectId, String issueTypeId,
                                        String scopeKey, String weekNo) {
        IssueInputParameters ip = issueService.newIssueInputParameters()
        ip.setProjectId(projectId)
        ip.setIssueTypeId(issueTypeId)
        ip.setSummary("Snapshot taken on Week ${weekNo} (${scopeKey})")
        ip.setDescription("Auto weekly snapshot. scope=${scopeKey}, week=${weekNo}, calcVersion=${CALC_VERSION}")

        def validation = issueService.validateCreate(user, ip)
        if (!validation.valid) throw new IllegalStateException("Snapshot create validation failed: ${validation.errorCollection}")

        def created = issueService.create(user, validation)
        if (!created.valid) throw new IllegalStateException("Snapshot create failed: ${created.errorCollection}")

        return ComponentAccessor.issueManager.getIssueObject(created.issue.id) as MutableIssue
    }

    // Computes measures open/done/created/resolved for the week
    private Map computeStats(String baseJql, LocalDate weekStart, LocalDate weekEnd) {
        String ws = weekStart.toString()
        String weExcl = weekEnd.plusDays(1).toString()

        int open = myBaseUtil.findIssues("(${baseJql}) AND resolution = Unresolved").size()
        int done = myBaseUtil.findIssues("(${baseJql}) AND resolution != Unresolved").size()
        int created = myBaseUtil.findIssues("(${baseJql}) AND created >= \"${ws}\" AND created < \"${weExcl}\"").size()
        int resolved = 0
        try {
            resolved = myBaseUtil.findIssues("(${baseJql}) AND resolutiondate >= \"${ws}\" AND resolutiondate < \"${weExcl}\"").size()
        } catch (Exception e) {
            resolved = 0
        }

        logger.setWarnMessage(baseJql + "  -  " + [open: open, done: done, created: created, resolved: resolved])
        return [open: open, done: done, created: created, resolved: resolved]
    }

    private Map computeAuditStats(Issue pcIssue, LocalDate weekStart, LocalDate weekEnd) {
        String ws = weekStart.toString()
        String weExcl = weekEnd.plusDays(1).toString()

        String pcFilter
        if (pcIssue) {
            pcFilter = """AND "${Audit.PROFIT_CENTER_FIELD_NAME}" = ${pcIssue.key}"""
        } else {
            // Option B overall: audits matching the same five PCs as the saved question filters
            pcFilter = """AND ("Audit Description" ~ "PC2" OR "Audit Description" ~ "PC3" OR "Audit Description" ~ "PC4" OR "Audit Description" ~ "PC6" OR "Audit Description" ~ "PC9")"""
        }
        String base = """project = "${CustomFieldsConstants.PROJECT_KVS_AUDIT}" AND issuetype = "${CustomFieldsConstants.AUDIT}" ${pcFilter}""".trim()

        int open = 0, closed = 0, created = 0
        try {
            open = myBaseUtil.findIssues(
                    """(${base}) AND resolution = Unresolved AND "${Audit.TARGET_END_FIELD_NAME}" >= "${ws}" AND "${Audit.TARGET_END_FIELD_NAME}" < "${weExcl}" """
            ).size()
        } catch (Exception e) {
            logger.setWarnMessage("Audit open-count JQL failed: ${e.message}")
        }
        try {
            closed = myBaseUtil.findIssues(
                    """(${base}) AND resolution != Unresolved AND resolutiondate >= "${ws}" AND resolutiondate < "${weExcl}" """
            ).size()
        } catch (Exception e) {
            logger.setWarnMessage("Audit closed-count JQL failed: ${e.message}")
        }
        try {
            created = myBaseUtil.findIssues(
                    """(${base}) AND created >= "${ws}" AND created < "${weExcl}" """
            ).size()
        } catch (Exception e) {
            created = 0
        }

        int total = open + closed
        double rate = total > 0 ? ((double) closed / (double) total) : 0.0

        return [
                open   : open,
                closed : closed,
                created: created,
                total  : total,
                rate   : rate
        ]
    }
}