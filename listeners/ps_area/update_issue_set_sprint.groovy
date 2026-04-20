package listeners.ps_area

/**
 * update_issue_set_sprint
 *
 * @author chabrecek.anton
 * Updated on 22/05/2025.
 *
 * name: PS empty Sprint when moving Support issues
 * events: issue moved
 * applied to: CLOUD1SUP, CLOUD2SUP
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.attachment.Attachment
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil

Logger logg = Logger.getLogger("LIS_psClearSprint")
logg.setLevel(Level.INFO)

Issue issue = event.issue

logg.info("Start - ${issue.key}" )

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
CustomField cfSprint = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.SPRINT)
issue.setCustomFieldValue(cfSprint,null)
ComponentAccessor.getIssueManager().updateIssue(currentUser, issue, EventDispatchOption.ISSUE_UPDATED, false)