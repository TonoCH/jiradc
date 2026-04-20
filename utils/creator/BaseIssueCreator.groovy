package utils.creator;

/**
 * BaseIssueCreator
 *
 * @author chabrecek.anton
 * Created on 7. 7. 2025.
 */

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.issue.IssueInputParameters;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.user.ApplicationUser
import org.apache.log4j.Logger
import utils.IssueBatchImporter;

abstract class BaseIssueCreator {

    protected IssueService issueService = ComponentAccessor.getIssueService()
    protected IssueManager issueManager = ComponentAccessor.getIssueManager()
    protected ProjectManager projectManager = ComponentAccessor.getProjectManager()
    protected CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager()
    protected ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
    protected Logger log = Logger.getLogger(IssueBatchImporter)

    abstract String getProjectKey()

    abstract String getIssueTypeName()

    //abstract Set<String> getAllowedCsvHeaders()
    abstract Set<String> getOptionalHeaders();
    abstract Set<String> getMandatoryHeaders();

    abstract void fillFields(IssueInputParameters params, Map<String, String> data)

    MutableIssue createIssue(Map<String, String> data) {

        log.warn("create issue:$data")

        Project project = projectManager.getProjectByCurrentKey(getProjectKey())
        if (!project) {
            log.error("Project '${getProjectKey()}' not found")

            throw new IllegalStateException("Project '${getProjectKey()}' not found")
        }

        IssueType issueType = ComponentAccessor.getConstantsManager()
                .getAllIssueTypeObjects()
                .find {
                    it.name == getIssueTypeName()
                }

        if (!issueType) {
            log.error("Issue type '${getIssueTypeName()}' not found")

            throw new IllegalStateException("Issue type '${getIssueTypeName()}' not found")
        }

        String assignee = data["assignee"]
        String reporter = data["reporter"]

        IssueInputParameters inputParams = issueService.newIssueInputParameters()
        inputParams.setProjectId(project.getId())
        inputParams.setIssueTypeId(issueType.getId())
        inputParams.setReporterId(currentUser.getName())
        inputParams.setAssigneeId(currentUser.getName())
        inputParams.setSummary(data["summary"])

        if(data["assignee"] != null && !data["assignee"].equals("Unassigned"))
            inputParams.setAssigneeId(assignee)

        if(data["reporter"] != null && !data["reporter"].equals("Unassigned"))
            inputParams.setReporterId(reporter)

        /*if(data["assignee"] != null && !data["assignee"].equals("Unassigned")) {
            def assignee = ComponentAccessor.userManager.getUserByName(data["assignee"])
            if (assignee) {
                inputParams.setAssigneeId(assignee.getName())
            } else {
                log.warn("Assignee user not found for: ${data["assignee"]}")
            }
        }

        if(data["reporter"] != null && !data["reporter"].equals("Unassigned")) {
            def reporter = ComponentAccessor.userManager.getUserByName(data["reporter"])
            if (reporter) {
                inputParams.setReporterId(reporter.getName())
            } else {
                log.warn("Reporter user not found for: ${data["reporter"]}")
            }
        }*/

        fillFields(inputParams, data)

        def validationResult

        if (this instanceof ISubTaskLevel) {
            def subtask = this as ISubTaskLevel
            def parent = subtask.getParentIssue()
            if (!parent)
                throw new IllegalStateException("Parent issue not found:")
            validationResult = issueService.validateSubTaskCreate(currentUser, parent.id, inputParams)
        } else {
            validationResult = issueService.validateCreate(currentUser, inputParams)
        }

        if (!validationResult.isValid()) {
            log.error("Issue creation failed: " + validationResult.getErrorCollection());

            throw new IllegalStateException("Issue creation failed: " + validationResult.getErrorCollection())
        }

        def createResult = issueService.create(currentUser, validationResult)
        if (!createResult.isValid()) {
            log.error("Issue creation failed at create(): " + createResult.getErrorCollection())
            throw new IllegalStateException("Issue creation failed: " + createResult.getErrorCollection())
        }
        MutableIssue created = createResult.getIssue()
        log.warn("created issue was successful: ${created.getKey()}")

        return created;
    }

}