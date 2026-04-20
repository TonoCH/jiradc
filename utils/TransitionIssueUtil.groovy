package utils

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.user.UserUtils
import com.atlassian.jira.util.JiraUtils
import com.atlassian.jira.util.ErrorCollection
import com.atlassian.jira.workflow.IssueWorkflowManager
import com.atlassian.jira.workflow.WorkflowTransitionUtil
import com.atlassian.jira.workflow.WorkflowTransitionUtilImpl
import com.opensymphony.workflow.loader.ActionDescriptor
import constants.GeneralConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.bc.issue.IssueService.TransitionValidationResult
import com.atlassian.jira.bc.issue.IssueService.IssueResult
import com.atlassian.jira.issue.IssueInputParameters
import com.atlassian.jira.issue.comments.CommentManager;
import com.atlassian.jira.issue.resolution.Resolution;

class TransitionIssueUtil {

    private static final Logger log = Logger.getLogger(TransitionIssueUtil.class)

    static {
        log.setLevel(Level.DEBUG)
    }

    static void transitionIssueWithComment(String issueKey, int transitionId, String comment) {
        Map params = new HashMap()
        params.put(WorkflowTransitionUtil.FIELD_COMMENT, comment)
        transitionIssueByIssueKey(issueKey, transitionId, params)
    }

    static void transitionIssue(MutableIssue issue, int transitionId) {
        transitionIssue(issue, transitionId, null)
    }

    static void transitionIssue(MutableIssue issue, int transitionId, Map params) {
        ApplicationUser user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
		log.info("Transition ${transitionId} for issue ${issue.key}" )
        // check if transition is valid in this time for user, if not try superuser
        boolean validTransition = ComponentAccessor.getComponent(IssueWorkflowManager.class).isValidAction(issue, transitionId, user)
		
        if(!validTransition) {
            log.error("User: ${user.name} don't have permission to run transition with id: ${transitionId} on issue: ${issue.key}, or: is not valid for in issue state: ${issue.status.name}")
            log.error("Trying use superuser account...")
            user = UserUtils.getUser(GeneralConstants.ADMIN)
            validTransition = ComponentAccessor.getComponent(IssueWorkflowManager.class).isValidAction(issue, transitionId, user)
            if(!validTransition) {
                log.error("User admin: ${user.name} don't have permission to run transition with id: ${transitionId} on issue: ${issue.key}, or: is not valid for in issue state: ${issue.status.name}")
                return
            }
        }

        //temporary update
        // transition an issue *** NOTE CODE UPDATE ON 29.2.2024 depending on changes in Jira 9.4 ***   
        IssueService issueService = ComponentAccessor.getComponent(IssueService.class);
        IssueInputParameters issueInputParameters = issueService.newIssueInputParameters();

        if(params != null && !params.isEmpty()){
            // Set resolution if provided
            if (params.containsKey("resolution")) {
                String resolutionId = (String) params.get("resolution");
                Resolution resolution = ComponentAccessor.getConstantsManager().getResolution(resolutionId);
                issueInputParameters.setResolutionId(resolution.getId());
            }

            // Set comment if provided
            if (params.containsKey("comment")) {
                String comment = (String) params.get("comment");
                CommentManager commentManager = ComponentAccessor.getCommentManager();
                commentManager.create(issue, user, comment, true);
            }
        }

        // Validate the transition (check permissions, required fields, etc.)
        IssueService.TransitionValidationResult validationResult = issueService.validateTransition(user, issue.getId(), transitionId, issueInputParameters);

        if (validationResult.isValid()) {
            // Perform the transition
            IssueService.IssueResult transitionResult = issueService.transition(user, validationResult);

            if (!transitionResult.isValid()) {
                //System.err.println("Error transitioning issue: " + transitionResult.getErrorCollection());
                log.error("Error transitioning issue: " + transitionResult.getErrorCollection());
            }
        } 
        else {
            // Handle validation errors
            //System.err.println("Transition validation failed: " + validationResult.getErrorCollection());   
            log.error("Transition validation failed: " + validationResult.getErrorCollection());     
        }

        // Old code
        //WorkflowTransitionUtil workflowTransitionUtil = JiraUtils.loadComponent(WorkflowTransitionUtilImpl.class)
        /*WorkflowTransitionUtil workflowTransitionUtil = ComponentAccessor.getComponent(WorkflowTransitionUtilImpl) as WorkflowTransitionUtil
        workflowTransitionUtil.setIssue(issue)
        workflowTransitionUtil.setUserkey(user.key)
        workflowTransitionUtil.setAction(transitionId)

        if (params != null && !params.isEmpty()) {
            workflowTransitionUtil.setParams(params)
        }

        // validate and transition issue
		ErrorCollection validationResult = workflowTransitionUtil.validate()
        if (!validationResult.hasAnyErrors()) {
            workflowTransitionUtil.progress()
        } else {
			log.info("Transition not valid" )
            for(String error: validationResult.getErrorMessages()) {
                log.error("ERROR: ${error}")
            }
			validationResult.getErrors().each { error -> 
			    log.error("ERROR: ${error.key} - ${error.value}")
			}
        }*/
    }

    private static Resolution getResolutionByName(String resolutionName) {
        Collection<Resolution> resolutions = ComponentAccessor.getConstantsManager() .getResolutions();
        for (Resolution resolution : resolutions) {
            if (resolution.getName().equalsIgnoreCase(resolutionName)) {
                return resolution;
            }
        }
        return null;
    }

    static void transitionIssueByIssueKey(String issueKey, int transitionId) {
        transitionIssueByIssueKey(issueKey, transitionId, null)
    }

    static void transitionIssueByIssueKey(String issueKey, int transitionId, Map params) {
        IssueManager issueManager = ComponentAccessor.getIssueManager()
        MutableIssue issue = issueManager.getIssueObject(issueKey)
        transitionIssue(issue, transitionId, params)
    }
	
	static void transitionIssueByTransitionName(MutableIssue issue, String transitionName) {
        transitionIssueByTransitionName(issue, transitionName, null)
    }

    static void transitionIssueByTransitionName(MutableIssue issue, String transitionName, Map params) {
		ApplicationUser user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
		Collection<ActionDescriptor> availableActions = ComponentAccessor.getComponent(IssueWorkflowManager.class).getAvailableActions(issue, user)
		
		if(!availableActions || availableActions.isEmpty()) {
			log.error("ERROR: No actions available. - transition aborted!")
			return
		}
		
		availableActions.each { action ->
			if(action.getName().equals(transitionName))	{
				log.info("Transition ID for name \'${transitionName}\' found: ${action.getId()}" )
				transitionIssue(issue, action.getId(), params)
			}
		}
	}
}