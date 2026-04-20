package postfunctions

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.attachment.Attachment
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.comments.Comment
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.project.Project
import com.atlassian.jira.project.version.Version
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import constants.IssueTypeIdConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil
import utils.OptionUtil

Logger log = Logger.getLogger("PF_psCloneIssue")
log.setLevel(Level.INFO)

MutableIssue currentIssue = issue

log.info("Start - ${currentIssue.key}" )

String comment = (String) transientVars?.comment

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

CustomField cfTargetProject = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.TARGET_PROJECT)
CustomField cfChildBugs = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.CHILD_BUGS)

// get target project from current issue dialog
Project targetProject = (Project) currentIssue.getCustomFieldValue(cfTargetProject)
log.info("targetProject - ${targetProject}" )

MutableIssue parentIssue = currentIssue

MutableIssue newIssue = createChildFromParent(parentIssue, targetProject, currentUser)

// Add comment to child issue
String commentForChild = addComment("issue cloned from parent ${parentIssue.key}", comment)
ComponentAccessor.getCommentManager().create(newIssue, currentUser, commentForChild, false)

// Add child issue to parent
List<Issue> childsIssues = (List<Issue>) parentIssue.getCustomFieldValue(cfChildBugs)
if (!childsIssues) {
    childsIssues = new ArrayList<Issue>()
}
childsIssues.add(newIssue)
parentIssue.setCustomFieldValue(cfChildBugs, childsIssues)
String commentForParent = "issue cloned to child ${newIssue.key}"
ComponentAccessor.getCommentManager().create(parentIssue, currentUser, commentForParent, false)

//clean up and save current issue
currentIssue.setCustomFieldValue(cfTargetProject, null)
ComponentAccessor.getIssueManager().updateIssue(currentUser, currentIssue, EventDispatchOption.ISSUE_UPDATED, false)


static MutableIssue createChildFromParent(MutableIssue parentIssue, Project targetProject, ApplicationUser currentUser) {
	MutableIssue newIssue = ComponentAccessor.issueFactory.getIssue()

	newIssue.setProjectObject(targetProject)
	newIssue.setIssueTypeId(IssueTypeIdConstants.BUG)
	newIssue.setSummary(parentIssue.getSummary())
	String description = parentIssue.getDescription()
	description = addReporterToDescription(description,parentIssue.getReporter())
	description = addAffectedVersionsToDescription(description,parentIssue.getAffectedVersions())
	List<Attachment> parentAttachments = (ArrayList) ComponentAccessor.getAttachmentManager().getAttachments(parentIssue)
	description = addAttachmentsToDescription(description,parentAttachments)
	newIssue.setDescription(description)
	newIssue.setPriority(parentIssue.getPriority())
	newIssue.setReporter(currentUser)
	newIssue.setEnvironment(parentIssue.getEnvironment())

	CustomField cfAffectedComponentVersion = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.AFFECTED_COMPONENT_VERSION)
	newIssue.setCustomFieldValue(cfAffectedComponentVersion, parentIssue.getCustomFieldValue(cfAffectedComponentVersion))

	// add parent bug
	CustomField cfParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PARENT_BUG)
	newIssue.setCustomFieldValue(cfParentBug, parentIssue)

	// store in DB
	newIssue = (MutableIssue) ComponentAccessor.getIssueManager().createIssueObject(currentUser, newIssue)

	// copy comments from parent to child
	List<Comment> parentComments = ComponentAccessor.getCommentManager().getComments(parentIssue)
	if (parentComments){
		parentComments.each { comment ->
			ComponentAccessor.getCommentManager().create(newIssue, 
														comment.getAuthorApplicationUser(), 
														comment.getUpdateAuthorApplicationUser(), 
														comment.getBody(), 
														comment.getGroupLevel(), 
														comment.getRoleLevelId(), 
														comment.getCreated(), 
														comment.getUpdated(), 
														false)
		}
	}
	
	return newIssue;
} 

static String addComment(String defaultComment, String commentFromDialog) {
    if (commentFromDialog) {
        defaultComment += " with comment:\n\n${commentFromDialog}"
    }
    return defaultComment
}

static String addReporterToDescription(String description, ApplicationUser reporter) {
	if (reporter) {
		description += "\n\nQA Reporter: ${reporter.getName()}"
	}
	return description
}

static String addAffectedVersionsToDescription(String description, Collection<Version> affectedVersions) {
	if (!affectedVersions) {
		return description
	}
	description += "\n\nParent affected versions: "
	affectedVersions.each { version ->
		description += "${version.getName()} / "
	}
	return description
}

static String addAttachmentsToDescription(String description, List<Attachment> attachments) {
	if (!attachments) {
		return description
	}
	String baseUrl = ComponentAccessor.getApplicationProperties().getString("jira.baseurl")
	description += "\n\nParent attachments at child creation:\n"
	attachments.each { attachment ->
		description += "[${attachment.getFilename()}|${baseUrl}/secure/attachment/${attachment.getId()}/${attachment.getFilename()}] \n "
	}
	return description
}

