package postfunctions

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.user.ApplicationUser
import org.apache.log4j.Level
import org.apache.log4j.Logger

Logger log = Logger.getLogger("PF_addCommentToSubTasks")
log.setLevel(Level.WARN)

Issue issue = issue

log.info("Start - ${issue.key}" )

def comment = transientVars?.comment

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

issue.getSubTaskObjects().each {
    child ->
        if (comment) {
            ComponentAccessor.getCommentManager().create(child, currentUser, comment?.toString(), false)
        }
}