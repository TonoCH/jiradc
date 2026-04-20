package postfunctions

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil

Logger log = Logger.getLogger("PF_addCommentToParent")
log.setLevel(Level.INFO)

Issue issue = issue

log.info("Start - ${issue.key}" )

String comment = transientVars?.comment
ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
addCommentToParent(issue, currentUser, comment, false)

static addCommentToParent(Issue issue, ApplicationUser currentUser, String comment, boolean copyComment) {
    CustomField cfParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PARENT_BUG)
    Issue parentIssue = (Issue) issue.getCustomFieldValue(cfParentBug)

    String childComment = comment  
    if(!copyComment) {
         childComment = "comment from child issue ${issue.key}:\n\n${comment}"
    }

    ComponentAccessor.getCommentManager().create(parentIssue, currentUser, childComment, false)

    if (parentIssue.getCustomFieldValue(cfParentBug)) {
        addCommentToParent(parentIssue, currentUser, childComment, true)
    }
}