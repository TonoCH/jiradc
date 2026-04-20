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

Logger log = Logger.getLogger("PF_waitingForSymptoms")
log.setLevel(Level.INFO)

MutableIssue issue = issue

log.info("Start - ${issue.key}" )

String comment = transientVars?.comment

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

CustomField cfParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PARENT_BUG)

MutableIssue parentIssue = (MutableIssue) issue.getCustomFieldValue(cfParentBug)

if (parentIssue) {
    TransitionIssueUtil.transitionIssue(parentIssue, 41)
    addCommentToParent(issue, cfParentBug, currentUser, comment, false)
}

static addCommentToParent(Issue issue, CustomField cfParentBug, ApplicationUser currentUser, String comment, boolean copyComment) {
    Issue parentIssue = issue.getCustomFieldValue(cfParentBug) as Issue

    String childComment = comment  
    if(!copyComment) {
         childComment = "symptoms requested by issue ${issue.key}:\n\n${comment}"
    }

    ComponentAccessor.getCommentManager().create(parentIssue, currentUser, childComment, false)

    if (parentIssue.getCustomFieldValue(cfParentBug)) {
        addCommentToParent(parentIssue, cfParentBug, currentUser, childComment, true)
    }
}


