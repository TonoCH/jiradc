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

Logger log = Logger.getLogger("PF_psFinishChilds")
log.setLevel(Level.INFO)

MutableIssue issue = issue

log.info("Start - ${issue.key}" )

String commentFromDialog = transientVars?.comment
ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

// determine child bugs
CustomField cfChildBugs = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.CHILD_BUGS)
List<MutableIssue> childIssues = (List<MutableIssue>) issue.getCustomFieldValue(cfChildBugs)

if(!childIssues) {
	return
}

String commentForChild = "parent issue ${issue.key} done"
if(commentFromDialog) {
	commentForChild += " with comment:\n\n${commentFromDialog}"
}

// add comment and finish all child issues
childIssues.each { childIssue ->
	log.info("Found child - ${childIssue.key}" )	
	ComponentAccessor.getCommentManager().create(childIssue, currentUser, commentForChild, false)
	
	// if child is resolved already, continue with next child
	if (childIssue.getResolution()) {
		return
	}
	
	// create needed parameter map for transition
	Map params = new HashMap()
	params.put("resolution", issue.getResolutionId())
	params.put("comment", "issue finished automatically because parent issue has been finished")
	
	TransitionIssueUtil.transitionIssueByTransitionName(childIssue, "Resolve", params)
}