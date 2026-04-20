package utils.mail_notifiers

import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.event.issue.IssueEvent

/**
 * kvs_measureAssignee
 * name:kvs_measureAssignee
 *
 * @author chabrecek.anton
 * Created on 5. 11. 2025.
 * projects: KVS Audit Test, KVS Audit
 */

def changeLog = event?.getChangeLog()

if (issue.issueType.name == 'Measure') {
    def changedAssignee = changeLog?.getRelated("ChildChangeItem")?.any { it.field == "assignee" }
    if (changedAssignee || (issue.assignee != originalIssue.assignee)) {
        return true
    }
}

return false