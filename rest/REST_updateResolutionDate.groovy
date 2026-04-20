package rest

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.index.IssueIndexingService
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.json.JsonSlurper
import groovy.transform.BaseScript
import org.apache.log4j.Level
import org.apache.log4j.Logger

import javax.servlet.http.HttpServletRequest
import java.sql.Timestamp
import java.text.SimpleDateFormat

Logger log = Logger.getLogger("REST_updateResolutionDate")
log.setLevel(Level.INFO)

@BaseScript CustomEndpointDelegate delegate
updateResolutionDate(httpMethod: "POST", groups: ["jira-administrators"]) { queryParams, body, HttpServletRequest request ->
    def jsonSlurper = new JsonSlurper()
    def dto = jsonSlurper.parseText(body as String)

    MutableIssue issue = ComponentAccessor.issueManager.getIssueByCurrentKey(dto.issueKey)
    String dateString = dto.resolutionDate

    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd hh:mm")
    Date date = sdf.parse(dateString)
    Timestamp timestamp = new Timestamp(date.time)
    if (issue && issue.resolution) {
        issue.setResolutionDate(timestamp)
        log.info(issue.resolutionDate)
        issue.store()
        ComponentAccessor.getComponent(IssueIndexingService.class).reIndex(issue)

    }
}