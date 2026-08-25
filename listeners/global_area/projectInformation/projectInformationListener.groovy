package listeners.global_area.projectInformation

import com.atlassian.jira.event.issue.IssueEvent

/**
 * projectInformationListener
 *
 * @author chabrecek.anton
 * Created on 24. 8. 2026.
 */

new ProjectInformationHierarchyListener().handle(event as IssueEvent)