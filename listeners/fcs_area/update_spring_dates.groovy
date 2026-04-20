package listeners.fcs_area

/**
 * update_spring_dates
 *
 * @author chabrecek.anton
 * Created on 22/05/2025.
 *
 * name: update sprint dates
 * events: issue update
 * applied to: IDBT
 */

import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.user.UserUtils
import com.atlassian.jira.user.util.UserUtil
import org.joda.time.format.DateTimeFormat

import java.util.regex.Matcher
import java.util.regex.Pattern
import org.joda.time.DateTime
import com.atlassian.greenhopper.service.rapid.view.RapidViewService
import com.atlassian.greenhopper.service.sprint.SprintIssueService
import com.atlassian.greenhopper.service.sprint.SprintManager
import com.atlassian.greenhopper.service.sprint.Sprint
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.onresolve.scriptrunner.runner.customisers.JiraAgileBean
import com.onresolve.scriptrunner.runner.customisers.WithPlugin
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil

Logger log = Logger.getLogger("sprint")
log.setLevel(Level.INFO)

@WithPlugin("com.pyxis.greenhopper.jira")

@JiraAgileBean
RapidViewService rapidViewService

@JiraAgileBean
SprintIssueService sprintIssueService

@JiraAgileBean
SprintManager sprintManager

MutableIssue issue = event.getIssue()

log.info("START - ${issue.key}")

CustomField cfSprint = CustomFieldUtil.getCustomFieldByName("Sprint")
CustomField cfSprintStart = CustomFieldUtil.getCustomFieldByName("Target start")
CustomField cfSpringEnd = CustomFieldUtil.getCustomFieldByName("Target end")

def loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

List<Sprint> allSprints = issue.getCustomFieldValue(cfSprint)

log.info("allSprints: ${allSprints}")

Sprint firstSprint = allSprints.findAll {it.startDate != null}.sort { a,b -> a.startDate <=> b.startDate }.find { true }
Sprint lastSprint = allSprints.findAll {it.endDate != null}.sort { a,b -> b.endDate <=> a.endDate }.find { true }

Sprint future = allSprints.find { it.state == Sprint.State.FUTURE }

log.info("future: ${future}")

DateTime startDate = null
DateTime endDate = null

if (firstSprint && lastSprint) {
    startDate = firstSprint.startDate
    endDate = lastSprint.endDate
}

if (future) {
    endDate = getDate(future.name)
}

log.info("startD: ${startDate}")
log.info("endD: ${endDate}")

if (startDate && endDate) {
    issue.setCustomFieldValue(cfSprintStart, startDate.toDate())
    issue.setCustomFieldValue(cfSpringEnd, endDate.toDate())
} else if (endDate) {
    issue.setCustomFieldValue(cfSprintStart, endDate.minusDays(19).toDate())
    issue.setCustomFieldValue(cfSpringEnd, endDate.plusHours(2).toDate())
} else {
    issue.setCustomFieldValue(cfSprintStart, null)
    issue.setCustomFieldValue(cfSpringEnd, null)
}

log.info("start: ${issue.getCustomFieldValue(cfSprintStart)}")
log.info("end: ${issue.getCustomFieldValue(cfSpringEnd)}")

ComponentAccessor.getIssueManager().updateIssue(UserUtils.getUser("lineup20"), issue, EventDispatchOption.DO_NOT_DISPATCH, false)
ComponentAccessor.getComponent(IssueIndexingService.class).reIndex(issue)

static DateTime getDate(String value) {
    def dateStr = ""
    Pattern pattern = Pattern.compile("\\(([^)]+)\\)")
    Matcher matcher = pattern.matcher(value)
    if (matcher.find()) {
        dateStr = matcher.group(1)
    }

    return DateTime.parse(dateStr, DateTimeFormat.forPattern("yyyy.MM.dd"))
}