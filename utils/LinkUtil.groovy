package utils

import com.atlassian.jira.JiraException
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.user.ApplicationUser
import org.apache.log4j.Level
import org.apache.log4j.Logger

class LinkUtil {

    private static final Logger log = Logger.getLogger(TransitionIssueUtil.class)

    static {
        log.setLevel(Level.ERROR)
    }

    static void createLinkBetweenIssues(Issue sourceIssue, Issue destinationIssue, Long linkTypeId, ApplicationUser user) {

        if (!(sourceIssue?.getId()) || !(destinationIssue?.getId()) || !linkTypeId) {
            log.error("Trying to create link(typeID)=" + linkTypeId + " between " + sourceIssue + "(" + sourceIssue?.getId() + ") and " + destinationIssue + "(" + sourceIssue?.getId() + "). SOMETHING IS NULL. Script will not create link.")
            return
        }
        //log.setLevel(org.apache.log4j.Level.DEBUG)
        log.debug("createLinkBetweenIssues - sourceIssue: " + sourceIssue + " destIssue: " + destinationIssue + " linkTypId: " + linkTypeId + " user: " + user)

        try {
            if (ComponentAccessor.getIssueLinkManager().getIssueLink(sourceIssue.id, destinationIssue.id, linkTypeId) == null)
                ComponentAccessor.getIssueLinkManager().createIssueLink(sourceIssue.id, destinationIssue.id, linkTypeId, 0L, user)
            else
                log.debug("createLinkBetweenIssues - link already exists, skipping creation (sourceIssue: " + sourceIssue + " destIssue: " + destinationIssue + " linkTypId: " + linkTypeId)

            ReindexUtil.performReIndex(sourceIssue)
            ReindexUtil.performReIndex(destinationIssue)

        } catch (JiraException e) {
            log.error("Error in create link - sourceIssue: " + sourceIssue + " destIssue: " + destinationIssue + " linkTypeId: " + linkTypeId)
            e.printStackTrace()
        }
    }
}
