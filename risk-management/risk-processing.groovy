import com.atlassian.jira.workflow.WorkflowTransitionUtilFactory
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
import com.atlassian.jira.workflow.WorkflowTransitionUtil;
import com.atlassian.jira.workflow.WorkflowTransitionUtilImpl;
import com.atlassian.jira.util.JiraUtils;
import com.atlassian.jira.issue.comments.CommentManager
import risk_management.RiskManagement

def log2 = Logger.getLogger("com.onresolve.jira.groovy.PostFunction");

riskMatrix();

public void riskMatrix() {

    //region declaration part

    IssueManager issueManager = ComponentAccessor.getIssueManager();
    CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager();
    OptionsManager optionsManager = ComponentAccessor.getOptionsManager();
    MutableIssue parentIssue = (MutableIssue) issue.getParentObject();

    //Contains name of colors: Not Rated, Green, Yellow, Orange, Red
    CustomField field_ResultingRiskLevel = customFieldManager.getCustomFieldObject("customfield_12254");
    //Contains extend of damage values: neglible, minor, significant, extensive, catastrophic
    CustomField field_ExtendOfDamage = customFieldManager.getCustomFieldObject("customfield_12252");
    //Contains Likelihood of occurance : low, moderate, frequent, certainly to be expected
    CustomField field_likelihoodOfOccurance = customFieldManager.getCustomFieldObject("customfield_12251");
    //Date of Assessment
    CustomField field_dateOfAssessment = customFieldManager.getCustomFieldObject("customfield_12253");
    //Field Resulting Risk Level config
    FieldConfig fieldConfig_ResultingRiskLevel = field_ResultingRiskLevel.getRelevantConfig(parentIssue); 

    //endregion declaration part

    String extendOfDamageVal = issue.getCustomFieldValue(field_ExtendOfDamage);
    String riskAcceptanceVal = issue.getCustomFieldValue(field_likelihoodOfOccurance);
    //String issueKey = issue.getKey();

    def colorName = RiskManagement.getColorName(extendOfDamageVal, riskAcceptanceVal);

    if(colorName == null){
        log.debug("NONE One of the values or all of fields contains null: \n Current field value for extend of damage: " + extendOfDamageVal +
                "\n" + "Current field value for extend of damage: " + riskAcceptanceVal);
    }
    else {
        log.debug("Extend of damage: " + extendOfDamageVal + "; Risk acceptance: " + riskAcceptanceVal);

        Option option = optionsManager.getOptions(fieldConfig_ResultingRiskLevel).getOptionForValue(colorName, null);

        //region set and update values

        parentIssue.setCustomFieldValue(field_ResultingRiskLevel, option);//set color for parentIssue
        parentIssue.setCustomFieldValue(field_dateOfAssessment, issue.getCustomFieldValue(field_dateOfAssessment));//set same date from sub-task to parrent
        issue.setCustomFieldValue(field_ResultingRiskLevel, option);//set color for subtask


        //set extend of damage and Likelihood of occurance to parrent issue
        parentIssue.setCustomFieldValue(field_ExtendOfDamage, issue.getCustomFieldValue(field_ExtendOfDamage));
        parentIssue.setCustomFieldValue(field_likelihoodOfOccurance, issue.getCustomFieldValue(field_likelihoodOfOccurance));

        issueManager.updateIssue(issue.getAssignee(), parentIssue, EventDispatchOption.DO_NOT_DISPATCH, false);

        //endregion

        IssueIndexingService issueIndexingService = ComponentAccessor.getComponent(IssueIndexingService)
        issueIndexingService.reIndex(parentIssue);
        issueIndexingService.reIndex(issue);

        
        //processParentWorkflow(colorName, (MutableIssue) parentIssue);//, issueKey);
    }
}

/*
    workflow action ids:
        Risk not acceptable (71) IN_ASSESSMENT => HANDLING
        Risk acceptable (321)    IN_ASSESSMENT => PROCESSED
* */
//Changing workflow state, by this user: riskmanagementbot is used
public void processParentWorkflow(String colorName, MutableIssue parent){//, String issueKey){

    WorkflowTransitionUtil workflowTransitionUtil = ComponentAccessor.getComponent(WorkflowTransitionUtilFactory.class).create()
    workflowTransitionUtil.setIssue(parent)
    workflowTransitionUtil.setUsername("riskmanagementbot")

    log.error(ComponentAccessor.getUserManager().getUserByName("riskmanagementbot"));
    String comment = "The Risk Bot says: this Risk has been rated '${colorName}'."

    int action = colorName == RiskManagement.GREEN ? 321 : 71
    workflowTransitionUtil.setAction(action)

    comment += " in ${issue.key}"

    // validate and transition issue
    workflowTransitionUtil.validate();
    workflowTransitionUtil.progress();

    // Add a comment so people have a clue why the risk has been transitioned
    ComponentAccessor.getCommentManager().create(parent, ComponentAccessor.getUserManager().getUserByName("riskmanagementbot"), comment, true);
}