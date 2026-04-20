package listeners.fuel_area

/**
 * issuelink_frslink_epic_feature
 *
 * @author chabrecek.anton
 * Updated on 22/05/2025.
 *
 * name: FRS Link Epics to Features automatically when parent Feature exists
 * events: issue create, issue update
 * applied to: Fuel Base Config (FBC), Fuel FASD AWG (FFA), Fuel NMS/Cloud Solutions (FFNS), Fuel Fast Fix Team (FFFT), Fuel IT BP A/CH/NL/LUX (FIBA), Fuel IT BP DE (FUBPDE), Fuel IT ENI/A1/Doppler/Schenk (FIA), Fuel IT ESSO/OMV (FIE), Fuel IT Q8 (FIQ), Fuel IT Shell DE TMS30 (FUSHEDE18A), Fuel IT Shell SSF AT (FUSHEAT19A), Fuel IT Shell SSF CH (FUSHECH18A), Fuel IT Shell SSF DE (FUSHEDE19), Fuel IT Shell SSF DE/Trassa (FISSD), Fuel IT T&R/Globus/Total (FITTROMV), Fuel PDM BOS30 Facelift (FPBF), Fuel PDM BOS40 (FPB), Fuel PDM Informationssicherheitsmanagementsystem (ISMS),  (FPII), Fuel PDM SIQMA Charge Connect (FPSCC), Fuel PDM SIQMA Cloud POS 3.0 (FPSCP), Fuel PDM SIQMA flex.CRID (FPSF), Fuel PDM SIQMA Scan & Go (FPSSG), Fuel PDM Vulnerabilities (FPV), Fuel Product Teams (FPT), Web Based POS (POS40),  (WBPP)
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.CustomField;

def issue = event.issue
IssueManager issueManager = ComponentAccessor.getIssueManager()
def issueLinkManager = ComponentAccessor.getIssueLinkManager()
def user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

// the sequence of the link
final Long sequence = 1L
if (issue.getIssueType().getName() == "Epic"){
    issueLinkManager.getOutwardLinks(issue.id).each {issueLink ->
        if (issueLink.issueLinkType.id == 10702) {
            issueLinkManager.removeIssueLink(issueLink, user)
        }
    }
    def customFieldManager = ComponentAccessor.getCustomFieldManager()
    def customField = customFieldManager.getCustomFieldObjectByName("Parent Link")
    def issueKey = issue.getCustomFieldValue(customField) as String
    def issueToLink = issueManager.getIssueObject(issueKey)
    if(issueToLink){
        issueLinkManager.createIssueLink(issueToLink.id, issue.id, 10702, sequence, user)
    }
}
