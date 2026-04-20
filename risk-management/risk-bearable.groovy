import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.UpdateIssueRequest
import java.sql.Timestamp;
 
issue.setCustomFieldValue(ComponentAccessor.getCustomFieldManager().getCustomFieldObject("customfield_12262"), new Timestamp(new Date().getTime()));