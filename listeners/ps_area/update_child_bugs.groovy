package listeners.ps_area

/**
 * update_child_bugs
 *
 * @author chabrecek.anton
 * Created on 22/05/2025.
 *
 * name: Update child bugs
 * events: issue resolved, issue update, issue closed
 * applied to: PSBUGS, DEPLOY, PSCVE
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

Logger log = Logger.getLogger("LIS_psUpdateFieldsOnChilds")
log.setLevel(Level.INFO)

Issue issue = event.issue

log.info("Start - ${issue.key}" )

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

// determine child bugs that are 'Waiting for Symptoms'
CustomField cfChildBugs = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.CHILD_BUGS)
List<MutableIssue> childIssues = (List<MutableIssue>) issue.getCustomFieldValue(cfChildBugs)

if(!childIssues) {
    return
}

childIssues.each { childIssue ->
    log.info("Found child - ${childIssue.key}" )
    linkNewAttachmentsFromParentInChildComment(issue, childIssue, currentUser)

    CustomField cfAffectedComponentVersion = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.AFFECTED_COMPONENT_VERSION)
    if (!childIssue.getCustomFieldValue(cfAffectedComponentVersion) && issue.getCustomFieldValue(cfAffectedComponentVersion)) {
        childIssue.setCustomFieldValue(cfAffectedComponentVersion, issue.getCustomFieldValue(cfAffectedComponentVersion))
        String commentForChild = "Set affected component version \'${issue.getCustomFieldValue(cfAffectedComponentVersion)}\' from parent issue ${issue.key} automatically"
        ComponentAccessor.getCommentManager().create(childIssue, currentUser, commentForChild, false)
        ComponentAccessor.getIssueManager().updateIssue(currentUser, childIssue, EventDispatchOption.ISSUE_UPDATED, false)
    }
}


static void linkNewAttachmentsFromParentInChildComment(Issue parentIssue, MutableIssue childIssue, ApplicationUser currentUser) {
    Logger logSub = Logger.getLogger("LIS_psUpdateFieldsOnChilds.linkNewAttachmentsFromParentInChildComment")
    String baseUrl = ComponentAccessor.getApplicationProperties().getString("jira.baseurl")
    List<Attachment> parentAttachments = (ArrayList) ComponentAccessor.getAttachmentManager().getAttachments(parentIssue)
    parentAttachments.each { parentAttachment ->
        logSub.info("Found attachment - ${parentAttachment.getFilename()} w/ id ${parentAttachment.getId()}")
        if(parentIssue.getUpdated().getTime() - parentAttachment.getCreated().getTime() <= 3000) {
            logSub.info("Attachment createtime is within 3sec. timeframe to issue updatetime -> new link from child needs to be added")
            String commentForChild = "Attachment [${parentAttachment.getFilename()}|${baseUrl}/secure/attachment/${parentAttachment.getId()}/${parentAttachment.getFilename()}]"
            commentForChild += " has been uploaded to parent issue ${parentIssue.key}"
            ComponentAccessor.getCommentManager().create(childIssue, currentUser, commentForChild, false)
        }
    }
}