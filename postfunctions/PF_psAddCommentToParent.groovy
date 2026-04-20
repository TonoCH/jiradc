package postfunctions

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil

Logger log = Logger.getLogger("PF_psAddCommentToParent")
log.setLevel(Level.INFO)

Issue issue = issue

log.info("Start - ${issue.key}" )

String comment = transientVars?.comment
ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

// determine parent bug
CustomField cfParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PARENT_BUG)
Issue parentIssue = (Issue) issue.getCustomFieldValue(cfParentBug)


if(!parentIssue) {
	return
}

// copy and save comment for parent
log.info("Found parent - ${parentIssue.key}" )
String commentForParent = "added comment on child issue ${issue.key}:\n\n${comment}"
ComponentAccessor.getCommentManager().create(parentIssue, currentUser, commentForParent, false)
