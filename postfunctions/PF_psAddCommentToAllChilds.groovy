package postfunctions

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.comments.Comment
import com.atlassian.jira.issue.link.IssueLink
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil

Logger log = Logger.getLogger("PF_psAddCommentToAllChilds")
log.setLevel(Level.INFO)

MutableIssue issue = issue

log.info("Start - ${issue.key}" )

String comment = (String) transientVars?.comment
ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

// determine child bugs
CustomField cfChildBugs = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.CHILD_BUGS)
List<Issue> childIssues = (List<Issue>) issue.getCustomFieldValue(cfChildBugs)

if(!childIssues) {
	return
}

// add and save comments on child issues	
childIssues.each { childIssue ->
	String commentStr = "added comment on parent issue ${issue.key}:\n\n${comment}"
	ComponentAccessor.getCommentManager().create(childIssue, currentUser, commentStr, false)
}
