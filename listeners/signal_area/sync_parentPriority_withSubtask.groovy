package listeners.signal_area

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.event.type.EventType
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.event.type.EventDispatchOption

/**
 * sync_parentPriority_withSubtask
 *
 * @author chabrecek.anton
 * Created on 22/05/2025.
 *
 * name: SiS - Sync Parent Priority with SubTasks
 * events: issue created, issue updated
 * applied to: SiS ESTW CFL SE (CFL), SiS ESTW DBAG SE (DBAG), SiS ESTW ÖBB SE (OEBB), SiS TDSB SE (TDSB), SiS Test Playground (SISPLAY)
 */

def issueEvent = event as IssueEvent
def issue = issueEvent.issue as MutableIssue
def issueManager = ComponentAccessor.getIssueManager()

// Function to update sub-task priority
def updateSubTaskPriority(MutableIssue parentIssue, IssueManager issueManager) {
    def subTasks = parentIssue.getSubTaskObjects()
    def priority = parentIssue.getPriority()

    subTasks.each { subTask ->
        def mutableSubTask = issueManager.getIssueObject(subTask.id) as MutableIssue
        mutableSubTask.setPriority(priority)
        issueManager.updateIssue(ComponentAccessor.jiraAuthenticationContext.getLoggedInUser(), mutableSubTask, EventDispatchOption.DO_NOT_DISPATCH, false)
    }
}

// Check if the event is an update event and the priority field has changed
if (issueEvent.getEventTypeId() == EventType.ISSUE_UPDATED_ID) {
    def changeLog = issueEvent.getChangeLog()
    def priorityChangeItems = changeLog.getRelated("ChildChangeItem").findAll { it.field == "priority" }

    if (priorityChangeItems) {
        updateSubTaskPriority(issue, issueManager)
    }
}

// Check if the event is a sub-task creation event
if (issueEvent.getEventTypeId() == EventType.ISSUE_CREATED_ID && issue.getIssueType().isSubTask()) {
    def parentIssue = issue.getParentObject() as MutableIssue
    def priority = parentIssue.getPriority()

    // Set the priority of the sub-task to the priority of the parent issue
    issue.setPriority(priority)

    // Store the changes
    issueManager.updateIssue(ComponentAccessor.jiraAuthenticationContext.getLoggedInUser(), issue, EventDispatchOption.DO_NOT_DISPATCH, false)
}
