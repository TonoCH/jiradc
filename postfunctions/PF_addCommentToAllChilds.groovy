package postfunctions

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.link.IssueLink
import com.atlassian.jira.user.ApplicationUser
import org.apache.log4j.Level
import org.apache.log4j.Logger

Logger log = Logger.getLogger("PF_addCommentToAllChilds")
log.setLevel(Level.INFO)

MutableIssue issue = issue

log.info("Start - ${issue.key}" )

String comment = (String) transientVars?.comment

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

List<IssueLink> allInIssueLink = ComponentAccessor.getIssueLinkManager().getInwardLinks(issue.id)

allInIssueLink.each { iLink ->
    Issue linkIssue = iLink.getSourceObject()
    String commentStr = "added comment from parent issue: ${issue.key}\n\n${comment}"
    ComponentAccessor.getCommentManager().create(linkIssue, currentUser, commentStr, false)
}