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

Logger log = Logger.getLogger("PF_psSymptomsProvidedOnParent")
log.setLevel(Level.INFO)

MutableIssue issue = issue

log.info("Start - ${issue.key}" )

String commentFromDialog = transientVars?.comment
ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

// determine parent issue
CustomField cfParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PARENT_BUG)
MutableIssue parentIssue = (MutableIssue) issue.getCustomFieldValue(cfParentBug)
log.info("Found parent - ${parentIssue.key}" )

if(parentIssue.status.name != "Waiting for Symptoms") {
	return
}

String commentForParent = "Symptoms provided on child issue ${issue.key}"
if(commentFromDialog) {
	commentForParent += " with comment:\n\n${commentFromDialog}"
}
ComponentAccessor.getCommentManager().create(parentIssue, currentUser, commentForParent, false)

// if there are other child bugs for the parent bug, which are waiting for symptoms, no further action is required
CustomField cfChildBugs = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.CHILD_BUGS)
List<MutableIssue> childIssues = (List<MutableIssue>) parentIssue.getCustomFieldValue(cfChildBugs)
if (childIssues.findAll{it.status.name == "Waiting for Symptoms"}) {
	log.info("Found child issues, which are waiting for symptoms: ${childIssues.size()}")
	return
}

// create needed parameter map for automated transition of parent
Map params = new HashMap()
params.put("comment", "symptoms provided automatically because it has been done on child issue")
TransitionIssueUtil.transitionIssueByTransitionName(parentIssue, "Symptoms Provided", params)
	
String commentForChild = "provided symptoms on parent issue ${parentIssue.key} automatically"
ComponentAccessor.getCommentManager().create(issue, currentUser, commentForChild, false)
