package postfunctions

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil
import utils.TransitionIssueUtil

Logger log = Logger.getLogger("PF_psReopenParent")
log.setLevel(Level.INFO)

MutableIssue issue = issue

log.info("Start - ${issue.key}" )

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

// determine parent issue
CustomField cfParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PARENT_BUG)
MutableIssue parentIssue = (MutableIssue) issue.getCustomFieldValue(cfParentBug)

if(!parentIssue) {
	return
}

log.info("Found parent - ${parentIssue.key}" )
String commentForParent

// if parent has no resolution, just leave a comment (no reopening is possible/needed)
if(!parentIssue.getResolution()) {
	commentForParent = "child issue ${issue.key} set back to \'to do\'"
	ComponentAccessor.getCommentManager().create(parentIssue, currentUser, commentForParent, false)
	return
}

TransitionIssueUtil.transitionIssueByTransitionName(parentIssue, "Reopen")
commentForParent = "parent reopened automatically because child issue ${issue.key} has been set back to \'to do\'"
ComponentAccessor.getCommentManager().create(parentIssue, currentUser, commentForParent, false)
String commentForChild = "reopened parent issue ${parentIssue.key} automatically"
ComponentAccessor.getCommentManager().create(issue, currentUser, commentForChild, false)