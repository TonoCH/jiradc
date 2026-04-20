package listeners.qs_area

/**
 * inheritance_IA_fields
 *
 * @author chabrecek.anton
 * Created on 22/05/2025.
 *
 * name: Inheritance - Responsible Company, Auditor, Co-auditor
 * events: all issue events
 * applied to: IA, IATESTPROJ
 */

package listeners

import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.issue.link.IssueLink
import com.atlassian.jira.issue.link.IssueLinkManager
import com.atlassian.jira.issue.Issue
import org.apache.log4j.Logger
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.ModifiedValue
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.util.ImportUtils

Logger log = Logger.getLogger(this.class.name)
//def issueManager = ComponentAccessor.issueManager
//MutableIssue issue = (MutableIssue) event.getIssue()
//def issueKey = "IATESTPROJ-3"//"FPT-22327"//"FPB-19106"
//def issue = issueManager.getIssueByCurrentKey(issueKey)

if (ImportUtils.isIndexIssues()) {
    if(issue != null)
        findAndSet(issue);
}

void findAndSet(Issue issue) {
    String issueTypeName = issue.getIssueType().getName();
    Issue epicIssue = null;

    try {
        switch(issueTypeName){
            case("Epic"):
                log.info("Epic");
                epicIssue = issue;
                break;
            case("Story"):
                log.info("Story");
                epicIssue = getEpicIssueFromStory(issue)
                break;
            case("Sub-task"):
                log.info("Sub-task");
                epicIssue = getEpicIssueFromSubTask(issue)
                break;
            default:
                log.info("default:"+issueTypeName);
                sb.append("default:"+issueTypeName);
                break;
        }
    }
    catch (Exception e) {
        logError("Error finding epic issue for " + issue.getKey()+ e);
    }

    if(epicIssue != null){
        checkAndSetFromEpic(epicIssue);
    }
}

void checkAndSetFromEpic(epicIssue){
    def issueManager = ComponentAccessor.issueManager
    def customFieldManager = ComponentAccessor.getCustomFieldManager()
    def responsibleCompanyField = customFieldManager.getCustomFieldObject(14790)
    def auditorField = customFieldManager.getCustomFieldObject(15401)
    def coAuditorField = customFieldManager.getCustomFieldObject(15402)
    def issueLinkManager = ComponentAccessor.getIssueLinkManager()

    Object responsibleCompanyValue = epicIssue.getCustomFieldValue(responsibleCompanyField);
    Object epicAuditorValue = epicIssue.getCustomFieldValue(auditorField);
    Object epicCoAuditorValue = epicIssue.getCustomFieldValue(coAuditorField);

    issueLinkManager.getOutwardLinks(epicIssue.id).each {issueLink ->

        def storyIssue = issueLink.getDestinationObject();
        Object storyAuditorValue = storyIssue.getCustomFieldValue(auditorField);
        Object storyCoAuditorValue = storyIssue.getCustomFieldValue(coAuditorField);

        updateCompanyOnIssue(storyIssue, responsibleCompanyValue);
        //updateAuditorOnIssue(storyIssue, auditorValue);
        //updateCoAutitorOnIssue(storyIssue, coAuditorValue);

        storyIssue.subTasks.each { childIssue ->
            def childIssueFin = issueManager.getIssueByCurrentKey(childIssue?.key)

            updateCompanyOnIssue(childIssueFin, responsibleCompanyValue);
            updateAuditorOnIssue(childIssueFin, storyAuditorValue);
            updateCoAutitorOnIssue(childIssueFin, storyCoAuditorValue);
        }
    }

}

def getEpicIssueFromStory(Issue storyIssue){
    def customFieldManager = ComponentAccessor.getCustomFieldManager()
    def epicLinkField = customFieldManager.getCustomFieldObject(10001)

    Object epicLinkObject = storyIssue.getCustomFieldValue(epicLinkField);

    if (epicLinkObject != null) {
        issueManager = ComponentAccessor.getIssueManager();
        Issue epicIssueObject = issueManager.getIssueObject(epicLinkObject.toString());

        return epicIssueObject;
    }

    return null;
}

def getEpicIssueFromSubTask(Issue subTaskIssue){
    def issueManager = ComponentAccessor.issueManager
    def parentIssueKey = subTaskIssue?.parent?.key
    if(parentIssueKey == null){
        return null;
    }

    Issue storyIssue = issueManager.getIssueByCurrentKey(parentIssueKey)
    return getEpicIssueFromStory(storyIssue);
}

void updateCompanyOnIssue(issueForUpdate, Object responsibleCompanyValue){
    def customFieldManager = ComponentAccessor.getCustomFieldManager()
    def responsibleCompanyField = customFieldManager.getCustomFieldObject(14790)
    String issueTypeName = issueForUpdate.getIssueType().getName();
    Object issueResponsibleCompanyValue = issueForUpdate.getCustomFieldValue(responsibleCompanyField);

    if(responsibleCompanyValue != null && (responsibleCompanyValue != issueResponsibleCompanyValue)){
        def modifiedValue = new ModifiedValue(issueResponsibleCompanyValue, responsibleCompanyValue)
        responsibleCompanyField.updateValue(null, issueForUpdate, modifiedValue, new DefaultIssueChangeHolder())
        indexIssue(issueForUpdate)

        logInfo(issueTypeName +" "+issueForUpdate+" will update Responsible Company value \""+responsibleCompanyValue+"\" instead of \""+issueResponsibleCompanyValue+"\"<br />");
    }
}

void updateAuditorOnIssue(issueForUpdate, Object auditorValue){
    def customFieldManager = ComponentAccessor.getCustomFieldManager()
    def auditorField = customFieldManager.getCustomFieldObject(15401)
    String issueTypeName = issueForUpdate.getIssueType().getName();
    Object issueAuditorValue = issueForUpdate.getCustomFieldValue(auditorField);

    if(auditorValue != null && (auditorValue != issueAuditorValue)){
        def modifiedValue = new ModifiedValue(issueAuditorValue, auditorValue)
        auditorField.updateValue(null, issueForUpdate, modifiedValue, new DefaultIssueChangeHolder())
        indexIssue(issueForUpdate)

        logInfo(issueTypeName +" "+issueForUpdate+" will update Auditor value \""+auditorValue+"\" instead of \""+issueAuditorValue+"\"<br />");
    }
}

void updateCoAutitorOnIssue(issueForUpdate, Object coAuditorValue){
    //def coAuditorField = ComponentAccessor.customFieldManager.getCustomFieldObjectByName("Co-auditor")
    def customFieldManager = ComponentAccessor.getCustomFieldManager()
    def coAuditorField = customFieldManager.getCustomFieldObject(15402)
    def auditorField = customFieldManager.getCustomFieldObject(15401)
    def issueTypeName = issueForUpdate.getIssueType().getName()
    def issueCoAuditorValue = issueForUpdate.getCustomFieldValue(coAuditorField)

    if (coAuditorValue != null && coAuditorValue != issueCoAuditorValue) {
        def modifiedValue = new ModifiedValue(issueCoAuditorValue, coAuditorValue)
        coAuditorField.updateValue(null, issueForUpdate, modifiedValue, new DefaultIssueChangeHolder())
        indexIssue(issueForUpdate)

        logInfo("${issueTypeName} ${issueForUpdate} will update Co-auditor value \"${coAuditorValue}\" instead of \"${issueCoAuditorValue}\"<br />")
    }
}

void setCustomFieldValue(issue, customField, value) {
    if (value != null) {
        def changeHolder = new DefaultIssueChangeHolder()
        customField.updateValue(null, issue, new ModifiedValue(issue.getCustomFieldValue(customField), value), changeHolder)
    }
}

void indexIssue(issueForIndex){
    boolean isIndex = ImportUtils.isIndexIssues();
    ImportUtils.setIndexIssues(true);
    IssueIndexingService IssueIndexingService = (IssueIndexingService) ComponentAccessor.getComponent(IssueIndexingService.class);
    IssueIndexingService.reIndex(issueForIndex);

    ImportUtils.setIndexIssues(isIndex);
}

void logInfo(String message){
    log.info(message);
    //sb.append(message)
}

void logError(String message){
    log.error(message);
    // sb.append(message)
}