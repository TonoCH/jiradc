package workflows

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.CustomFieldManager
import java.sql.Timestamp 
 
CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager();

//corrected on 5.3.2024 add if statement
if (issue) { 
    issue.setCustomFieldValue(
        customFieldManager.getCustomFieldObjectByName("Termin ist"), new Timestamp((new Date()).time)
    )
    log.info("\"Termin ist\" auf heutiges Datum setzen...")

    IssueManager issueManager = ComponentAccessor.getIssueManager();

    issueManager.updateIssue(
        ComponentAccessor.getJiraAuthenticationContext().getUser()
        ,issue
        , EventDispatchOption.ISSUE_UPDATED
        , false
    )
}
else {
    log.error("Issue not found")
}