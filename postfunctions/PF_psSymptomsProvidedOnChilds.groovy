package postfunctions

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil
import utils.TransitionIssueUtil

Logger log = Logger.getLogger("PF_psSymptomsProvidedOnChilds")
log.setLevel(Level.INFO)

MutableIssue issue = issue

log.info("Start - ${issue.key}" )

String commentFromDialog = transientVars?.comment
ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

// determine child bugs that are 'Waiting for Symptoms'
CustomField cfChildBugs = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.CHILD_BUGS)
List<MutableIssue> childIssues = (List<MutableIssue>) issue.getCustomFieldValue(cfChildBugs)?.findAll{it.status.name == "Waiting for Symptoms"}

if(!childIssues) {
	return
}

String commentForChild = "Symptoms provided on parent issue ${issue.key}"
if(commentFromDialog) {
	commentForChild += " with comment:\n\n${commentFromDialog}"
}

// add comment and provide symptoms to found child issues
childIssues.each { childIssue ->
	log.info("Found child - ${childIssue.key}" )
	ComponentAccessor.getCommentManager().create(childIssue, currentUser, commentForChild, false)
	
	// create needed parameter map for transition
	Map params = new HashMap()
	params.put("comment", "symptoms provided automatically because it has been done on parent issue")
	
	TransitionIssueUtil.transitionIssueByTransitionName(childIssue, "Symptoms Provided", params)
	String commentForParent = "provided symptoms on child issue ${childIssue.key} automatically"
	ComponentAccessor.getCommentManager().create(issue, currentUser, commentForParent, false)
}