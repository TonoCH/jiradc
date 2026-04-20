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

Logger log = Logger.getLogger("PF_psResolveParent")
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
String commentForParent = "child issue ${issue.key} resolved with resolution \'${issue.getResolution()?.getName()}\'"
if(commentFromDialog) {
	commentForParent += " with comment:\n\n${commentFromDialog}"
}
ComponentAccessor.getCommentManager().create(parentIssue, currentUser, commentForParent, false)

// if parent issue is resolved already, no further action is required
if (parentIssue.getResolution()) {
	log.info("Parent has already been resolved")
	return
}

// if there are open child bugs for the parent bug, no further action is required
CustomField cfChildBugs = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.CHILD_BUGS)
List<MutableIssue> childIssues = (List<MutableIssue>) parentIssue.getCustomFieldValue(cfChildBugs)
if (childIssues.findAll{!it.resolution}) {
	log.info("Found open child issues: ${childIssues.size()}")
	return
}

// check if one child but has resolution 'FE fix it'. If yes, this resolution has to be taken.
String resolutionId = issue.getResolutionId();
childIssues.each { childIssue ->
	log.info("Found child - ${childIssue.key}" )
	if ("FE fix it".equals(childIssue.getResolution()?.getName())) {
		resolutionId = childIssue.getResolutionId()
	}
}

// create needed parameter map for automated transition of parent
Map params = new HashMap()
params.put("resolution", resolutionId)
params.put("comment", "issue resolved automatically because all child issues are done")

TransitionIssueUtil.transitionIssueByTransitionName(parentIssue, "Resolve", params)
