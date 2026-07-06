package listeners.global_area.projectInformation

/**
 * projectInformationHandler
 *
 * @author chabrecek.anton
 * Created on 3. 7. 2026.
 */
import com.atlassian.jira.event.issue.IssueEvent

def issueEvent = event as IssueEvent
def handler = new ProjectInformationHandler2()

if (!handler.isProjectInformationChanged(issueEvent)) {
    return
}

try {
    handler.handleProjectInformationChange(issueEvent.issue, issueEvent.user)
} catch (Exception e) {
    handler.logFailure(issueEvent.issue, e)
    handler.sendFailureMail(issueEvent.issue, e)
    throw e
}