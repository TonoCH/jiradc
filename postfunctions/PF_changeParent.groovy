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

Logger log = Logger.getLogger("PF_changeParent")
log.setLevel(Level.INFO)

MutableIssue currentIssue = issue

log.info("Start - ${currentIssue.key}" )

String comment = (String) transientVars?.comment

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

CustomField cfTargetParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.TARGET_PARENT_BUG)
CustomField cfParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PARENT_BUG)
CustomField cfChildBugs = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.CHILD_BUGS)

// get current parent issue
MutableIssue currentParentIssue = (MutableIssue) currentIssue.getCustomFieldValue(cfParentBug)

// get target issue from current issue dialog
MutableIssue targetParentIssue = (MutableIssue) currentIssue.getCustomFieldValue(cfTargetParentBug)

// remove current issue on parent issue
if(currentParentIssue) {
	List<Issue> issues = (List<Issue>) currentParentIssue.getCustomFieldValue(cfChildBugs)
	issues = issues.minus(currentIssue)
	currentParentIssue.setCustomFieldValue(cfChildBugs, issues)
	ComponentAccessor.getIssueManager().updateIssue(currentUser, currentParentIssue, EventDispatchOption.ISSUE_UPDATED, false)
	ComponentAccessor.getComponent(IssueIndexingService.class).reIndex(currentParentIssue)
    
    if(targetParentIssue) {
        ComponentAccessor.getCommentManager().create(currentParentIssue, currentUser, "child ${issue.key} moved from this issue to ${targetParentIssue.key}", false)
    } else {
        ComponentAccessor.getCommentManager().create(currentParentIssue, currentUser, "child ${issue.key} removed from this issue", false)
    }
}


// add current issue to target issue
if(targetParentIssue) {
    issues = (List<Issue>) targetParentIssue.getCustomFieldValue(cfChildBugs)

    List<Issue> newChilds = new ArrayList<>()
    if (issues) {
        newChilds.addAll(issues)
    }
    newChilds.add(currentIssue)
    targetParentIssue.setCustomFieldValue(cfChildBugs, newChilds)
    ComponentAccessor.getIssueManager().updateIssue(currentUser, targetParentIssue, EventDispatchOption.ISSUE_UPDATED, false)
	ComponentAccessor.getComponent(IssueIndexingService.class).reIndex(targetParentIssue)
    
    if(currentParentIssue) {
        ComponentAccessor.getCommentManager().create(targetParentIssue, currentUser, "child ${issue.key} moved from ${currentParentIssue.key} into this issue", false)
    } else {
        ComponentAccessor.getCommentManager().create(targetParentIssue, currentUser, "child ${issue.key} added to this issue", false)
    }    
}

if (currentParentIssue) {
    if(targetParentIssue) {
		ComponentAccessor.getCommentManager().create(issue, currentUser, "parent changed from ${currentParentIssue.key} to ${targetParentIssue.key}", false)
    } else {
 		ComponentAccessor.getCommentManager().create(issue, currentUser, "parent changed from ${currentParentIssue.key} to EMPTY", false)  
    }
} else {
    if(targetParentIssue) {
		ComponentAccessor.getCommentManager().create(issue, currentUser, "parent set to ${targetParentIssue.key}", false)
    } else {
 		ComponentAccessor.getCommentManager().create(issue, currentUser, "parent reset to EMPTY", false)  
    }    
}

currentIssue.setCustomFieldValue(cfParentBug, targetParentIssue)
currentIssue.setCustomFieldValue(cfTargetParentBug, null) // reset dialog
ComponentAccessor.getIssueManager().updateIssue(currentUser, currentIssue, EventDispatchOption.ISSUE_UPDATED, false)
ComponentAccessor.getComponent(IssueIndexingService.class).reIndex(currentIssue)