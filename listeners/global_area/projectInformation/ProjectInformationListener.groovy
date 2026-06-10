import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.event.type.EventType
import org.apache.log4j.Logger

Logger log = Logger.getLogger('ProjectInformationListener')

final String FUEL_CATEGORY_NAME = 'FUEL Projects'
final String SERVICE_USER_NAME = 'jira.bot'

IssueEvent issueEvent = event as IssueEvent
def issue = issueEvent.issue
def project = issue?.projectObject
if (project == null) return

// Ignore the service user's own writes (the script writes with DO_NOT_DISPATCH,
// but moves/other operations performed as the bot must not loop either).
if (issueEvent.user?.name == SERVICE_USER_NAME) return

def categoryName = project.projectCategoryObject?.name
def handlerType = (categoryName == FUEL_CATEGORY_NAME) ?
        ProjectInformationHandler.Type.FUEL :
        ProjectInformationHandler.Type.OTHER

def handler = ProjectInformationHandler.create(handlerType)

switch (issueEvent.eventTypeId) {
    case EventType.ISSUE_CREATED_ID:
        handler.handleCreate(issue)
        break

    case EventType.ISSUE_UPDATED_ID:
    case EventType.ISSUE_MOVED_ID:
        handler.handleUpdate(issueEvent)
        break

    case EventType.ISSUE_DELETED_ID:
        handler.handleDelete(issue)
        break
}
