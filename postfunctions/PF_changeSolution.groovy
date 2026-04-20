package postfunctions

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil

Logger log = Logger.getLogger("PF_changeSolution")
log.setLevel(Level.INFO)

MutableIssue currentIssue = issue

log.info("Start - ${currentIssue.key}" )

String comment = (String) transientVars?.comment

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

CustomField cfTargetSolution = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.TARGET_SOLUTION)
CustomField cfSolution = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.SOLUTION)
CustomField cfRequirements = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.REQUIREMENTS)

// get current parent issue
MutableIssue currentSolution = (MutableIssue) currentIssue.getCustomFieldValue(cfSolution)

// get target issue from current issue dialog
MutableIssue targetSolution = (MutableIssue) currentIssue.getCustomFieldValue(cfTargetSolution)

// remove current issue on parent issue
if(currentSolution) {
	List<Issue> issues = (List<Issue>) currentSolution.getCustomFieldValue(cfRequirements)
	issues = issues.minus(currentIssue)
	currentSolution.setCustomFieldValue(cfRequirements, issues)
	ComponentAccessor.getIssueManager().updateIssue(currentUser, currentSolution, EventDispatchOption.ISSUE_UPDATED, false)
	ComponentAccessor.getComponent(IssueIndexingService.class).reIndex(currentSolution)
    
    if(targetSolution) {
        ComponentAccessor.getCommentManager().create(currentSolution, currentUser, "requirement ${issue.key} assignment changed from this issue to ${targetSolution.key}", false)
    } else {
        ComponentAccessor.getCommentManager().create(currentSolution, currentUser, "requirement ${issue.key} assignment removed from this issue", false)
    }
}


// add current issue to target issue
if(targetSolution) {
    issues = (List<Issue>) targetSolution.getCustomFieldValue(cfRequirements)

    List<Issue> newRequirements = new ArrayList<>()
    if (issues) {
        newRequirements.addAll(issues)
    }
    newRequirements.add(currentIssue)
    targetSolution.setCustomFieldValue(cfRequirements, newRequirements)
    ComponentAccessor.getIssueManager().updateIssue(currentUser, targetSolution, EventDispatchOption.ISSUE_UPDATED, false)
	ComponentAccessor.getComponent(IssueIndexingService.class).reIndex(targetSolution)
    
    if(currentSolution) {
        ComponentAccessor.getCommentManager().create(targetSolution, currentUser, "requirement ${issue.key} assignment changed from ${currentSolution.key} into this issue", false)
    } else {
        ComponentAccessor.getCommentManager().create(targetSolution, currentUser, "requirement ${issue.key} assignment added to this issue", false)
    }    
}

if (currentSolution) {
    if(targetSolution) {
		ComponentAccessor.getCommentManager().create(issue, currentUser, "solution changed from ${currentSolution.key} to ${targetSolution.key}", false)
    } else {
 		ComponentAccessor.getCommentManager().create(issue, currentUser, "solution changed from ${currentSolution.key} to EMPTY", false)  
    }
} else {
    if(targetSolution) {
		ComponentAccessor.getCommentManager().create(issue, currentUser, "solution set to ${targetSolution.key}", false)
    } else {
 		ComponentAccessor.getCommentManager().create(issue, currentUser, "solution reset to EMPTY", false)  
    }    
}

currentIssue.setCustomFieldValue(cfSolution, targetSolution)
currentIssue.setCustomFieldValue(cfTargetSolution, null) // reset dialog
ComponentAccessor.getIssueManager().updateIssue(currentUser, currentIssue, EventDispatchOption.ISSUE_UPDATED, false)
ComponentAccessor.getComponent(IssueIndexingService.class).reIndex(currentIssue)