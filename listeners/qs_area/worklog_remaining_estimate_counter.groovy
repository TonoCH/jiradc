package listeners.qs_area

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.worklog.WorklogCreatedEvent
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.worklog.Worklog
import com.atlassian.jira.issue.Issue
import org.apache.log4j.Logger

/**
 * worklog_remainingEstimate
 *
 * @author chabrecek.anton
 * Created on 10. 6. 2025.
 */

def worklog = event.getWorklog()
def childIssue = worklog.getIssue()
Logger log = Logger.getLogger(this.class.name)

log.info("childIssue is: "+childIssue?.getKey())

def issueManager = ComponentAccessor.getIssueManager()
def worklogManager = ComponentAccessor.getWorklogManager()
def customFieldManager = ComponentAccessor.getCustomFieldManager()


Issue parent = childIssue.getParentObject()
if (!parent) {
    def epicLinkField = customFieldManager.getCustomFieldObjectByName("Epic Link")
    def epicKey = childIssue.getCustomFieldValue(epicLinkField) as String
    parent = issueManager.getIssueObject(epicKey)
}
if (!parent) return


/*def subTasks = parent.getSubTaskObjects()
def allChildren = subTasks ?: []*/
def allChildren 

//if (subTasks.isEmpty() && parent.issueType.name == "Epic") {
if(parent.issueType.name == "Epic") {
    def searchService = ComponentAccessor.getComponent(com.atlassian.jira.bc.issue.search.SearchService)
    def jqlQueryParser = ComponentAccessor.getComponent(com.atlassian.jira.jql.parser.JqlQueryParser)
    def query = jqlQueryParser.parseQuery("\"Epic Link\" = ${parent.key}")
    def results = searchService.search(ComponentAccessor.jiraAuthenticationContext.loggedInUser, query, com.atlassian.jira.web.bean.PagerFilter.getUnlimitedFilter())
    allChildren = results.results.collect {issueManager.getIssueObject(it.id)}
}

long totalTimeSpent = 0L
allChildren.each { child ->
    List<Worklog> worklogs = worklogManager.getByIssue(child)
    worklogs.each { wl ->
        totalTimeSpent += wl.getTimeSpent() ?: 0L
    }
}

MutableIssue mParent = (MutableIssue) parent

Long originalEstimate = mParent.getOriginalEstimate() ?: 0L
long remainingEstimate = Math.max(originalEstimate - totalTimeSpent, 0L)

mParent.setEstimate(remainingEstimate)
//mParent.setTimeSpent(totalTimeSpent)

issueManager.updateIssue(
        ComponentAccessor.jiraAuthenticationContext.loggedInUser,
        mParent,
        EventDispatchOption.ISSUE_UPDATED,
        false
)

log.info("${mParent.getKey()} is updated with remainingEstimate: $remainingEstimate")