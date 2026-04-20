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

Logger log = Logger.getLogger("PF_psWaitForSymptomsOnParent")
log.setLevel(Level.INFO)

MutableIssue issue = issue

log.info("Start - ${issue.key}" )

String commentFromDialog = transientVars?.comment
ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

// determine parent issue
CustomField cfParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PARENT_BUG)
MutableIssue parentIssue = (MutableIssue) issue.getCustomFieldValue(cfParentBug)

if(!parentIssue) {
	return
}
    
log.info("Found parent - ${parentIssue.key}" )
TransitionIssueUtil.transitionIssueByTransitionName(parentIssue, "Waiting for Symptoms")
String commentForParent = "waiting for symptoms on child issue ${issue.key}"
if(commentFromDialog) {
	commentForParent += " with comment:\n\n${commentFromDialog}"
}
ComponentAccessor.getCommentManager().create(parentIssue, currentUser, commentForParent, false)
String commentForChild = "wait for symptoms on parent issue ${parentIssue.key} automatically"
ComponentAccessor.getCommentManager().create(issue, currentUser, commentForChild, false)
