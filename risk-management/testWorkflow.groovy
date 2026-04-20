// Unable to add package declaration. The parent folder names contain identifiers that are not valid in a package name.

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.event.type.EventDispatchOption;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.index.IssueIndexingService;
import org.apache.log4j.Level
import org.apache.log4j.Logger
import com.atlassian.jira.workflow.TransitionOptions
import com.onresolve.scriptrunner.runner.customisers.PluginModule
import com.opensymphony.workflow.loader.ActionDescriptor
import com.opensymphony.workflow.loader.StepDescriptor
import com.opensymphony.workflow.loader.WorkflowDescriptor
import com.opensymphony.workflow.loader.WorkflowLoader
import com.atlassian.jira.workflow.WorkflowTransitionUtilImpl
import com.atlassian.jira.workflow.WorkflowTransitionUtil
import com.atlassian.jira.workflow.WorkflowTransitionUtil;
import com.atlassian.jira.workflow.WorkflowTransitionUtilImpl;
import com.atlassian.jira.util.JiraUtils;
import com.atlassian.jira.issue.comments.CommentManager
import com.atlassian.jira.workflow.WorkflowProgressAware.ProgressResult
import risk_management.RiskManagement

setWorkflow();

public void setWorkflow(){
    //MutableIssue parentIssue = (MutableIssue) issue.getParentObject();
    //processParentWorkflow(parentIssue);
    def issueKey = "RISKTEST-2"
    IssueManager issueManager = ComponentAccessor.getIssueManager()
    MutableIssue myIssue = issueManager.getIssueByCurrentKey(issueKey)
    processParentWorkflow(myIssue)
}

public void processParentWorkflow(MutableIssue parent){//, String issueKey){
   def tragbar = true;
   WorkflowTransitionUtil workflowTransitionUtil = (WorkflowTransitionUtil) JiraUtils.loadComponent( WorkflowTransitionUtilImpl.class);
    // Transition einleiten
    workflowTransitionUtil.setIssue(parent);
    workflowTransitionUtil.setUsername("riskmanagementbot");
     
    log.error(ComponentAccessor.getUserManager().getUserByName("riskmanagementbot"));
    
    String comment = "The Risk Bot says: this Risk has been rated ";
    if (tragbar) {
        workflowTransitionUtil.setAction(101); //tragbar
        comment += "'acceptable' (101) ";
    } else {
        workflowTransitionUtil.setAction(71); //nicht tragbar
        comment += "'not acceptable' (71) ";
    }
    comment += "in " + parent.getKey();
 
    // validate and transition issue


WorkflowProgressAware.ProgressResult progressResult = workflowTransitionUtil.progress(new ProgressAware() {
    @Override
    void progress()
    {
        // Optional progress callback
    }
});

if (!progressResult.isSuccessful()) {
    String errorMessage = "Error(s) occurred while progressing the issue: ";
    for (Map.Entry<String, Exception> error : progressResult.getErrors().entrySet()) {
        errorMessage += "\n" + error.getKey() + ": " + error.getValue().getMessage();
    }
    // Set the error message as the issue description
    parent.setDescription(errorMessage);
}
    //workflowTransitionUtil.validate();
    //workflowTransitionUtil.progress();
 
    // Add a comment so people have a clue why the risk has been transitioned      
    ComponentAccessor.getCommentManager().create(parent, ComponentAccessor.getUserManager().getUserByName("riskmanagementbot"), comment, true);
}