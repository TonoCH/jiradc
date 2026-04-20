package listeners

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil

Logger logg = Logger.getLogger("LIS_psUpdatePriorityOnChilds")
logg.setLevel(Level.INFO)

Issue issue = event.issue

logg.info("Start - ${issue.key}" )

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

// determine child bugs that are 'Waiting for Symptoms'
CustomField cfChildBugs = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.CHILD_BUGS)
List<MutableIssue> childIssues = (List<MutableIssue>) issue.getCustomFieldValue(cfChildBugs)

if(!childIssues) {
	return
}

childIssues.each { childIssue ->
	logg.info("Found child - ${childIssue.key}" )

	if (childIssue.getPriority() != issue.getPriority()) {
        childIssue.setPriority(issue.getPriority())
		ComponentAccessor.getIssueManager().updateIssue(currentUser, childIssue, EventDispatchOption.ISSUE_UPDATED, false)
	}
}
