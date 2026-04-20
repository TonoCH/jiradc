import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.issue.IssueInputParameters;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.link.IssueLinkManager;
import com.atlassian.jira.exception.CreateException;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.event.type.EventDispatchOption;
import com.atlassian.jira.issue.fields.CustomField;
import org.apache.log4j.Logger;
import risk_management.RiskManagement

def log = Logger.getLogger("com.onresolve.jira.groovy.PostFunction");

log.warn("-----------IN create-ria.groovy--------------");

//region declaration part
IssueService issueService = ComponentAccessor.getIssueService();
IssueInputParameters params = issueService.newIssueInputParameters();
JiraAuthenticationContext authContext = ComponentAccessor.getJiraAuthenticationContext();

def currentProjectId = issue.getProjectId();    //CURRENT project
def siblingActionProject = RiskManagement.getActionProject(issue.getProjectId());   //RISK_ACTIONS sibling project
def risk_actionIssueType = "10913"; // Issuetype Risk-Action
//endregion

if (siblingActionProject == null) { //RISK_ACTIONS sibling project not found
    log.warn("Action project not found.\n Current project id: " + issue.getProjectId());
} 
else {
    log.warn("Action project find:\n Current project id: " + issue.getProjectId() + " Action project id: " + siblingActionProject);
    params.setProjectId(siblingActionProject);//13430); // Projekt RIA
    params.setIssueTypeId(risk_actionIssueType);

    // RIA Zusammenfassung
    // TODO it will be good if field will be renamed.
    CustomField riaZusammenfassung = ComponentAccessor.getCustomFieldManager().getCustomFieldObject("customfield_12242");
    // RIA Beschreibung
    // TODO it will be good if field will be renamed.
    CustomField riaBeschreibung = ComponentAccessor.getCustomFieldManager().getCustomFieldObject("customfield_12247");

    params.setSummary((String) issue.getCustomFieldValue(riaZusammenfassung));
    params.setDescription((String) issue.getCustomFieldValue(riaBeschreibung));

    IssueService.CreateValidationResult createValidationResult = issueService.validateCreate(authContext.getLoggedInUser(), params);

    if (createValidationResult.isValid()) {
        IssueService.IssueResult createResult = issueService.create(authContext.getLoggedInUser(), createValidationResult);

        if (createResult.isValid()) {
            // set Issuelink
            try {
                ComponentAccessor.getIssueLinkManager().createIssueLink(issue.getId(), createResult.getIssue().getId(), Long.parseLong("10703"), 1l, authContext.getLoggedInUser());
            } 
            catch (CreateException e) {
                // do nothing
            }
        } 
        else {
            log.warn("createResult.isValid() is FALSE");
        }
    } 
    else {
        log.warn("createValidationResult.isValid() is FALSE");
    }
    
    // we delete the field values
    issue.setCustomFieldValue(riaZusammenfassung, null);
    issue.setCustomFieldValue(riaBeschreibung, null);
}

