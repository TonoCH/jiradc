package postfunctions

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.IssueFieldConstants
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil
import utils.TransitionIssueUtil
import com.atlassian.jira.user.ApplicationUser

Logger log = Logger.getLogger("PF_closeParent")
log.setLevel(Level.INFO)

MutableIssue issue = issue

log.info("Start - ${issue.key}" )

CustomField cfParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PARENT_BUG)
CustomField cfChildBugs = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.CHILD_BUGS)

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

MutableIssue parentIssue = (MutableIssue) issue.getCustomFieldValue(cfParentBug)

if (!parentIssue) return

if(parentIssue && parentIssue.projectId in [11834L, 12601L, 11505L]) {
    log.info("parent project: supported ${parentIssue.projectId}")
    List<Issue> childIssues = (List<Issue>) parentIssue.getCustomFieldValue(cfChildBugs)

    if (childIssues) {
        List<Issue> notClosedIssues = childIssues?.findAll {
            it.status.name != "Done"
        }

        log.info("not closed issues: ${notClosedIssues}")
        
        if (notClosedIssues.isEmpty()) {
            log.info("not closed issues: EMPTY --> transtion of ${parentIssue.key} with resolution: ${issue.resolution.name}")
            
            //parentIssue.setResolution(issue.getResolution())
            Map params = new HashMap()
            params.put(IssueFieldConstants.RESOLUTION, issue.resolution.id)
			//ComponentAccessor.getIssueManager().updateIssue(currentUser, parentIssue, EventDispatchOption.ISSUE_UPDATED, false)
            TransitionIssueUtil.transitionIssue(parentIssue, 31, params)
            ComponentAccessor.getCommentManager().create(parentIssue, currentUser, "state transition to \"Done\" initiated by status update of ${issue.key}", false)
        }
    }
} else {
    log.info("parent project: not supported ${parentIssue.projectId}" )    
}