package listeners

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.attachment.Attachment
import com.atlassian.jira.issue.label.Label 
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil

Logger log = Logger.getLogger("LIS_psUpdateFieldsOnParent")
log.setLevel(Level.INFO)

Issue issue = event.issue

log.info("Start - ${issue.key}" )

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

// determine parent issue
CustomField cfParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PARENT_BUG)
MutableIssue parentIssue = (MutableIssue) issue.getCustomFieldValue(cfParentBug)

if(!parentIssue) {
	return
}
log.info("Found parent - ${parentIssue.key}" )

Set<Label> parentUpdatenotes = createParentUpdatenotesFromChild(issue, parentIssue, currentUser)
CustomField cfUpdatenotes = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.UPDATENOTES)
parentIssue.setCustomFieldValue(cfUpdatenotes, parentUpdatenotes)

linkNewAttachmentsFromChildInParentComment(issue, parentIssue, currentUser)

addCommentsForFixVersionChanges(event, issue, parentIssue, currentUser)

ComponentAccessor.getIssueManager().updateIssue(currentUser, parentIssue, EventDispatchOption.ISSUE_UPDATED, false)


static Set<Label> createParentUpdatenotesFromChild(Issue childIssue, MutableIssue parentIssue, ApplicationUser currentUser) {
	CustomField cfUpdatenotes = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.UPDATENOTES)
	Set<Label> childUpdatenotes = (HashSet<Label>) childIssue.getCustomFieldValue(cfUpdatenotes)
	Set<Label> parentUpdatenotes = (HashSet<Label>) parentIssue.getCustomFieldValue(cfUpdatenotes)
	childUpdatenotes.each { childUpdatenote ->
		if(!parentUpdatenotes.findAll{it.getLabel()==childUpdatenote.getLabel()}) {
			Label newUpdatenote = new Label(childUpdatenote.getId(), parentIssue.getId(), childUpdatenote.getLabel())
			if(!parentUpdatenotes) {
				parentUpdatenotes = new HashSet<Label>();
			}
			parentUpdatenotes.add(newUpdatenote)
			String commentForParent = "Added updatenote \'${childUpdatenote.getLabel()}\' from child issue ${childIssue.key} automatically"
			ComponentAccessor.getCommentManager().create(parentIssue, currentUser, commentForParent, false)
		}
	}
	return parentUpdatenotes
}

static void linkNewAttachmentsFromChildInParentComment(Issue childIssue, MutableIssue parentIssue, ApplicationUser currentUser) {
	Logger logSub = Logger.getLogger("LIS_psUpdateFieldsOnParent.linkNewAttachmentsFromChildInParentComment")
	String baseUrl = ComponentAccessor.getApplicationProperties().getString("jira.baseurl")
	List<Attachment> childAttachments = (ArrayList) ComponentAccessor.getAttachmentManager().getAttachments(childIssue)
	childAttachments.each { childAttachment ->
		logSub.info("Found attachment - ${childAttachment.getFilename()} w/ id ${childAttachment.getId()}")
		if(childIssue.getUpdated().getTime() - childAttachment.getCreated().getTime() <= 3000) {
			logSub.info("Attachment createtime is within 3sec. timeframe to issue updatetime -> new link from parent needs to be added")
			String commentForParent = "Attachment [${childAttachment.getFilename()}|${baseUrl}/secure/attachment/${childAttachment.getId()}/${childAttachment.getFilename()}]"
			commentForParent += " has been uploaded to child issue ${childIssue.key}"
			ComponentAccessor.getCommentManager().create(parentIssue, currentUser, commentForParent, false)
		}
	}
}

static void addCommentsForFixVersionChanges(IssueEvent event, Issue childIssue, MutableIssue parentIssue, ApplicationUser currentUser) {
	Logger logSub = Logger.getLogger("LIS_psUpdateFieldsOnParent.addCommentsForFixVersionChanges")
	def fixVersionChanges = event?.getChangeLog()?.getRelated("ChildChangeItem")?.findAll {it.field == "Fix Version"}
	fixVersionChanges.each { fixVersionChange ->
		logSub.info("Found Fix Version change from ${fixVersionChange.oldstring} to ${fixVersionChange.newstring}")
		String commentForParent
		if(!fixVersionChange.oldstring) {
			commentForParent = "Fix for child issue ${childIssue.key} provided with child project version ${fixVersionChange.newstring}"
		} else {
			commentForParent = "Fix for child issue ${childIssue.key} no longer contained in child project version ${fixVersionChange.oldstring}"
		}
		ComponentAccessor.getCommentManager().create(parentIssue, currentUser, commentForParent, false)
	}
}