package postfunctions

import com.atlassian.jira.bc.project.component.ProjectComponent
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.link.IssueLink
import com.atlassian.jira.project.Project
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.user.UserUtils
import constants.GeneralConstants
import constants.IssueTypeIdConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.LinkUtil
import utils.TransitionIssueUtil

Logger log = Logger.getLogger("PF_createBugFromComponent")
log.setLevel(Level.DEBUG)

MutableIssue issue = issue

log.info("START - ${issue.key}")

def comment = transientVars?.comment

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

List<IssueLink> allInIssueLink = ComponentAccessor.getIssueLinkManager().getInwardLinks(issue.id)

Project projectOfIssue = ComponentAccessor.getProjectManager().getProjectByCurrentKey(issue.getProjectObject().key)
def components = projectOfIssue.getProjectComponents().findAll { it.name in issue.getComponents()*.name }

components.each { comp ->

    Issue existedIssue = null
    allInIssueLink.each { iLink ->
        Issue linkIssue = iLink.getSourceObject()
        Project projectOfLinkIssue = ComponentAccessor.getProjectManager().getProjectByCurrentKey(linkIssue.getProjectObject().key)
        def componentsOfLinkIssue = projectOfLinkIssue.getProjectComponents().findAll {
            it.name in linkIssue.getComponents()*.name
        }

        componentsOfLinkIssue.each { componentOfLinkIssue ->
            if (comp.name == componentOfLinkIssue.name) {
                existedIssue = linkIssue
            }
        }
    }

    if (!existedIssue) {
        log.debug("Not exist will be created")
        Project targetProject = findProjectByTeam(comp.name, 10100)
        MutableIssue newIssue = ComponentAccessor.getIssueFactory().getIssue()
        newIssue.setProjectObject(targetProject)
        newIssue.setIssueTypeId(IssueTypeIdConstants.BUG)
        newIssue.setSummary("${issue.getSummary()} - ${comp.name}")
        newIssue.setAssignee(currentUser)
        newIssue.setReporter(currentUser)
        ProjectComponent componentOfTargetProject = ComponentAccessor.getProjectComponentManager().findByComponentName(targetProject.id, comp.name)
        if (componentOfTargetProject) {
            newIssue.setComponent(Arrays.asList(componentOfTargetProject))

            // Create new Issue
            newIssue = (MutableIssue) ComponentAccessor.getIssueManager().createIssueObject(currentUser, newIssue)

            // Create comment
            if (comment) {
                ComponentAccessor.getCommentManager().create(newIssue, currentUser, comment?.toString(), false)
            }
            LinkUtil.createLinkBetweenIssues(newIssue, issue, 10001L, currentUser)
        }
    } else {
        log.debug("Exist issue: ${existedIssue.key} in status ${existedIssue.statusId}")

        if ("10001" == existedIssue.statusId) {
            log.debug("Issue: ${existedIssue.key} will be Reopened")
            TransitionIssueUtil.transitionIssueWithComment(existedIssue.key, 41, "Auto Reopened")
        }
    }
}

allInIssueLink.each { iLink ->

    boolean searched = false
    Issue linkIssue = iLink.getSourceObject()

    if ("10001" == linkIssue.statusId) {
        return
    }

    Project projectOfLinkIssue = ComponentAccessor.getProjectManager().getProjectByCurrentKey(linkIssue.getProjectObject().key)
    def componentsOfLinkIssue = projectOfLinkIssue.getProjectComponents().findAll {
        it.name in linkIssue.getComponents()*.name
    }

    componentsOfLinkIssue.each { componentOfLinkIssue ->
        components.each { component ->
            if (component.name.equalsIgnoreCase(componentOfLinkIssue.name)) {
                searched = true
            }
        }
    }

    if (!searched) {
        log.debug("Issue: ${linkIssue.key} will be set to DONE state")
        TransitionIssueUtil.transitionIssueWithComment(linkIssue.key, 71, "Auto done")
    }
}

static Project findProjectByTeam(String team, long projectCategoryId) {
    def projects = ComponentAccessor.getProjectManager().getProjectObjectsFromProjectCategory(projectCategoryId)
    Project projectOfTeam = null
    int numberOfTeams = 0

    projects.each { p ->
        p.getProjectComponents().each { c ->
            if (c.name.equalsIgnoreCase(team)) {
                projectOfTeam = p
                numberOfTeams++
            }
        }
    }
    if (numberOfTeams != 1) return null
    return projectOfTeam
}