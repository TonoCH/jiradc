package postfunctions

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil
import utils.TransitionIssueUtil

Logger log = Logger.getLogger("PF_psStartDevelopmentOnFeature")
log.setLevel(Level.INFO)

MutableIssue issue = issue

log.info("Start - ${issue.key}" )

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

// determine parent issue
CustomField cfParentLink = CustomFieldUtil.getCustomFieldByName("Parent Link")
def parentLink = issue.getCustomFieldValue(cfParentLink)

if(!parentLink) {
	return
}

log.info("Found parent - ${parentLink.key}" )
IssueManager issueManager = ComponentAccessor.getIssueManager()
MutableIssue parentIssue = (MutableIssue) issueManager.getIssueObject(parentLink.key)
TransitionIssueUtil.transitionIssueByTransitionName(parentIssue, "In Development")
String commentForParent = "started progress on child issue ${issue.key}"
ComponentAccessor.getCommentManager().create(parentIssue, currentUser, commentForParent, false)
