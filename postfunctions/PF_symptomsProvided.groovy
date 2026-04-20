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

Logger log = Logger.getLogger("PF_symptomsProvided")
log.setLevel(Level.INFO)

MutableIssue issue = issue

log.info("Start - ${issue.key}" )

String comment = transientVars?.comment

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

symptomsProvided(issue, currentUser, comment, false)

static symptomsProvided(Issue issue, ApplicationUser currentUser, String comment, boolean copyComment) {
    TransitionIssueUtil.transitionIssue(issue, 11)
    CustomField cfChildBugs = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.CHILD_BUGS)
    List<Issue> childIssues = issue.getCustomFieldValue(cfChildBugs)?.findAll{it.status.name == "Waiting for Symptoms"}

    childIssues.each {
        String childComment = comment
        if(!copyComment) {
             childComment = "symptoms provided for ${issue.key}:\n\n${comment}"
        }

        ComponentAccessor.getCommentManager().create(it, currentUser, childComment, false)
        symptomsProvided(it, currentUser, childComment, true)
    }
}