package scriptfields

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import org.apache.log4j.Level
import org.apache.log4j.Logger

MutableIssue issue = issue

Logger log = Logger.getLogger(this.getClass().getCanonicalName())
log.setLevel(Level.DEBUG)

log.info("START - ${issue.key}")

CustomField cfParentProject = ComponentAccessor.getCustomFieldManager().getCustomFieldObjectsByName("Parent Project")?.first()
CustomField cfParentIssue = ComponentAccessor.getCustomFieldManager().getCustomFieldObjectsByName("Parent Bug")?.first()

if (!cfParentIssue) {
    log.error("CF cfParentIssue not found, please add it")
    return
}

Issue parentIssue = cfParentIssue.getValue(issue)

issue.setCustomFieldValue(cfParentProject, parentIssue.projectObject.key)