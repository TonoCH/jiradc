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

Logger log = Logger.getLogger("PF_globalComment")
log.setLevel(Level.INFO)

MutableIssue issue = issue

log.info("Start - ${issue.key}" )

String comment = (String) transientVars?.comment

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

CustomField cfCommentType = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.COMMENT_TYPE)

String commentType = issue.getCustomFieldValue(cfCommentType).value

issue.setCustomFieldValue(cfCommentType, null)

if (commentType == "Child to Parents") {
    addCommentToParent(issue, currentUser, comment, false)
} else if (commentType == "Parent to Children") {
    addCommentToChildren(issue, currentUser, comment, false)
}

static addCommentToParent(Issue issue, ApplicationUser currentUser, String comment, boolean copyComment) {
    CustomField cfParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PARENT_BUG)
    Issue parentIssue = (Issue) issue.getCustomFieldValue(cfParentBug)

    if (parentIssue) {
        String childComment = comment
        if(!copyComment) {
            childComment = "comment from child issue ${issue.key}:\n\n${comment}"
        }

        ComponentAccessor.getCommentManager().create(parentIssue, currentUser, childComment, false)

        if (parentIssue.getCustomFieldValue(cfParentBug)) {
            addCommentToParent(parentIssue, currentUser, childComment, true)
        }
    }
}

static addCommentToChildren(Issue issue, ApplicationUser currentUser, String comment, boolean copyComment) {
    CustomField cfChildBugs = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.CHILD_BUGS)
    List<Issue> childIssues = issue.getCustomFieldValue(cfChildBugs)?.findAll()

    childIssues.each {
        String childComment = comment
        if(!copyComment) {
            childComment = "comment from parent issue ${issue.key}:\n\n${comment}"
        }

        ComponentAccessor.getCommentManager().create(it, currentUser, childComment, false)
        if (it.getCustomFieldValue(cfChildBugs)) {
            addCommentToChildren(it, currentUser, childComment, true)
        }
    }
}