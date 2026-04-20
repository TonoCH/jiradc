package jobs.quality

/**
 * project evb recuring task
 * Epic "Recurring Task Moers" is permanent with key:
 * every monday new Task in this Epic "CW1 Tasks Moers"
 * estimate 4h per week
 * closed on sunday
 *
 * @author chabrecek.anton
 * Created on 4. 12. 2025.
 */

import com.atlassian.jira.bc.issue.worklog.WorklogService
import com.atlassian.jira.bc.issue.worklog.WorklogInputParametersImpl
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.IssueInputParameters
import com.atlassian.jira.issue.IssueInputParametersImpl
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.bc.issue.IssueService.CreateValidationResult
import com.atlassian.jira.workflow.WorkflowTransitionUtil
import com.atlassian.jira.workflow.WorkflowTransitionUtilFactory
import utils.CustomFieldUtil
import utils.MyBaseUtil
import com.atlassian.jira.bc.JiraServiceContextImpl
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields

def issueService = ComponentAccessor.getIssueService()
def worklogService = ComponentAccessor.getComponent(WorklogService)
//def loggedInUser = ComponentAccessor.jiraAuthenticationContext.getLoggedInUser()
def userManager = ComponentAccessor.getUserManager()
def projectManager = ComponentAccessor.getProjectManager()
def issueManager = ComponentAccessor.getIssueManager()

// ---- CONFIG ----
def user = userManager.getUserByName("jira.bot")
def assigneeAndReporter = userManager.getUserByName("Moers.Christian")//Moers.Christian
def PROJECT_KEY = "EVB"
def EPIC_KEY = "EVB-24"
def ESTIMATE_STR = "4h"
def ESTIMATE = 4 * 60;
def weekNumber = LocalDate.now().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
def summary = "CW${weekNumber} Tasks Moers"
def WORKFLOW_PROGRESS_TRANSITION_ID = 31
def WORKFLOW_DONE_TRANSITION_ID = 41
def today = LocalDate.now()
// -----------------

def project = projectManager.getProjectObjByKey(PROJECT_KEY)
if (!project) {
    log.warn("Project ${PROJECT_KEY} not found")
    return
}

if (today.dayOfWeek == DayOfWeek.MONDAY) {
// create issue parameters
    IssueInputParameters params = new IssueInputParametersImpl()
            .setProjectId(project.id)
            .setIssueTypeId("10100")
            .setSummary(summary)
            .setReporterId(assigneeAndReporter.name)
            .setAssigneeId(assigneeAndReporter.name)
            .setOriginalEstimate(ESTIMATE)
            .addCustomFieldValue(CustomFieldUtil.getCustomFieldByName("Epic Link").idAsLong, EPIC_KEY)

    CreateValidationResult validation = issueService.validateCreate(user, params)
    if (!validation.isValid()) {
        log.warn("Validation failed: ${validation.errorCollection}")
        return
    }

    def result = issueService.create(user, validation)
    if (!result.isValid()) {
        log.warn("Create error: ${result.errorCollection}")
        return
    }

    def createdIssue = result.issue
    log.info("Created weekly task ${createdIssue.key}")

    def ctx = new JiraServiceContextImpl(user)

    def worklogParams = WorklogInputParametersImpl.builder()
            .issue(createdIssue)
            .startDate(new Date())
            .timeSpent(ESTIMATE_STR)
            .comment("Auto-generated weekly capacity")
            .build()

    def wlResult = worklogService.validateCreate(ctx, worklogParams)

    if (!wlResult) {
        log.warn("Worklog validation failed: ${ctx.errorCollection}")
    } else {

        worklogService.createAndAutoAdjustRemainingEstimate(ctx, wlResult, true)
    }

    WorkflowTransitionUtil workflowTransitionUtil = ComponentAccessor.getComponent(WorkflowTransitionUtilFactory.class).create()
    workflowTransitionUtil.setIssue(createdIssue)
    workflowTransitionUtil.setUserkey(user.key)
    workflowTransitionUtil.setAction(WORKFLOW_PROGRESS_TRANSITION_ID)
// validate and transition issue
    workflowTransitionUtil.validate();
    workflowTransitionUtil.progress();

    log.warn("Weekly task ${createdIssue.key} created, also workflow transition to progress and worklog logged")
    return "END OF JOB"
} else if (today.dayOfWeek == DayOfWeek.SUNDAY) {
    log.warn("It's Sunday -> closing weekly issue")

    def jql = "project = ${PROJECT_KEY} AND summary ~ \"CW${weekNumber} Tasks Moers\" ORDER BY created DESC"
    MyBaseUtil myBaseUtil = new MyBaseUtil();
    List<Issue> tasksIssues = new ArrayList<>();

    try {
        tasksIssues = myBaseUtil.findIssues(jql);
    }
    catch (IllegalArgumentException argumentException){
        log.warn(argumentException.message.toString())
        return "ERROR"
    }

    if (tasksIssues.size() == 0) {
        log.warn("No weekly issue found for CW${weekNumber}, nothing to close.");
        return "NO_ISSUE"
    }

    for (Issue taskIssue : tasksIssues) {

        MutableIssue mutableTaskIssue = issueManager.getIssueObject(taskIssue.id) as MutableIssue

        if (mutableTaskIssue.status.statusCategory.key == "done") {
            log.warn("Task ${mutableTaskIssue.key} is already in status ${mutableTaskIssue.status.name}, nothing to do.\n")
            continue
        }
        log.warn("Closing weekly issue ${mutableTaskIssue.key} with transition ID ${WORKFLOW_DONE_TRANSITION_ID}\n")

        WorkflowTransitionUtil workflowTransitionUtil = ComponentAccessor.getComponent(WorkflowTransitionUtilFactory.class).create()
        workflowTransitionUtil.setIssue(mutableTaskIssue)
        workflowTransitionUtil.setUserkey(user.key)
        workflowTransitionUtil.setAction(WORKFLOW_DONE_TRANSITION_ID)

        workflowTransitionUtil.validate();
        workflowTransitionUtil.progress();

        log.warn("Weekly task ${mutableTaskIssue.key} was transitioned to Done\n")
    }

    return "END OF JOB"
} else {
    log.warn("Not Monday or Sunday, nothing to do.")
    return "END OF JOB - NOTHING TO DO"
}

return "END OF JOB"