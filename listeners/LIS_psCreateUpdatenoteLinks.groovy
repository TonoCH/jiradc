package listeners

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.label.Label 
import com.atlassian.jira.issue.link.RemoteIssueLinkBuilder
import com.atlassian.jira.issue.link.RemoteIssueLink
import com.atlassian.jira.issue.link.RemoteIssueLinkManager
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil

Logger log = Logger.getLogger("LIS_psCreateUpdatenoteLinks")
log.setLevel(Level.INFO)

Issue issue = event.issue

log.info("Start - ${issue.key}" )

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

CustomField cfUpdatenotes = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.UPDATENOTES)
Set<Label> updatenotes = (HashSet<Label>) issue.getCustomFieldValue(cfUpdatenotes)
List<RemoteIssueLink> remoteIssueLinks = ComponentAccessor.getComponent(RemoteIssueLinkManager.class).getRemoteIssueLinksForIssue(issue)

updatenotes.each { updatenote ->
	String updatenoteLabel = updatenote.getLabel()
	if(updatenoteLabel == "N/A") {
		return
	}
	String updatenoteId = updatenoteLabel.substring(updatenoteLabel.lastIndexOf("_") + 1)
	log.info("Found updatenote: ${updatenoteLabel} with Id: ${updatenoteId}" )
	updatenoteId = updatenoteId.replace("A","10")
	updatenoteId = updatenoteId.replace("B","11")
	updatenoteId = updatenoteId.replace("C","12")
	if(!remoteIssueLinks.findAll{it.getTitle().equals(updatenoteLabel)}) {
		RemoteIssueLink newLink = new RemoteIssueLinkBuilder().issueId(issue.getId())
														   .url("http://bugzilla.gsph:8084/UpdateNote_WEB/ShowUP.jsp?systemId=12&id="+updatenoteId)
														   .title(updatenoteLabel)
														   .iconUrl("http://bugzilla.gsph:8084/UpdateNote_WEB/images/favicon.ico")
														   .iconTitle("Updatenote-Tool")
														   .build();
		ComponentAccessor.getComponent(RemoteIssueLinkManager.class).createRemoteIssueLink(newLink, currentUser);
	}
}