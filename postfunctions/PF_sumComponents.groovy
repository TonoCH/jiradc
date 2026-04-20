package postfunctions

import com.atlassian.jira.bc.project.component.ProjectComponent
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger

log = Logger.getLogger("PF_sumComponents")
log.setLevel(Level.DEBUG)

MutableIssue issue = issue

log.debug("START - ${issue.key}")

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

CustomField cfComponentsCore = ComponentAccessor.getCustomFieldManager().getCustomFieldObjectsByName(CustomFieldNameConstants.COMPONENTS_FCS_CORE)?.first()

List<String> componentsCore = issue.getCustomFieldValue(cfComponentsCore).collect { return it.toString() }

log.debug("componentsCore - ${componentsCore}")

Set<ProjectComponent> sumComponents = new HashSet<ProjectComponent>()

componentsCore.each { compStr ->
    def component = ComponentAccessor.getProjectComponentManager().findByComponentName(issue.projectId, compStr)
    if (component != null) {
        sumComponents.add(component)
    }
}
issue.setComponent(sumComponents)
ComponentAccessor.getIssueManager().updateIssue(currentUser, issue, EventDispatchOption.ISSUE_UPDATED, false)