package listeners.fuel_area

//This is a listener designed to trigger reindexing for four issue types — Epic, Feature, LPM Initiative, and Initiative — in the Fuel area, which contain scripted fields.
//The listener is triggered everytime there is an worklog event.
//Scripted field data becomes visible in the Jira search directory only after reindexing.
//Therefore, triggering reindexing for these issues is necessary to ensure that reports and dashboards display up-to-date data.
//— Vishal Choure, 03.04.2025

/**
 * update_issue_set_sprint
 *
 * @author chabrecek.anton
 * Updated on 22/05/2025.
 *
 * name: Reindex Fuel Issues (Epic, Feature, LPM Initiative, Intiative)
 * events: WorklogCreatedEvent, WorklogDeletedEvent, WorklogUpdatedEvent
 * applied to: Fuel Activity Input (FAI), Fuel Base Config (FBC), Fuel Base Release/SW Maintenance (FBRSWM), Fuel BI Reporting (FBR), Fuel Blue Team (FBT), Fuel Digital Transformation Strategy (FDTS), Fuel FASD AWG (FFA), Fuel FASD Improvements (FUALL20B), Fuel FASD Template (FFT), Fuel Fast Fix Team (FFFT), Fuel IT AVIA/Total/Globus (FITAVTOGL), Fuel IT BP A/CH/NL/LUX (FIBA), Fuel IT BP DE (FUBPDE), Fuel IT ENI/A1/Doppler/Schenk (FIA), Fuel IT ESSO/OMV (FIE), Fuel IT Q8 (FIQ), Fuel IT Shell DE TMS30 (FUSHEDE18A), Fuel IT Shell SSF AT (FUSHEAT19A), Fuel IT Shell SSF CH (FUSHECH18A), Fuel IT Shell SSF DE (FUSHEDE19), Fuel IT Shell SSF DE/Trassa (FISSD), Fuel IT T&R/Globus/Total (FITTROMV), Fuel Kubernetes Operations (FKO), Fuel NMS/Cloud Solutions (FFNS), Fuel PDM BOS30 Facelift (FPBF), Fuel PDM BOS40 (FPB), Fuel PDM Informationssicherheitsmanagementsystem (ISMS),  (FPII), Fuel PDM SIQMA Charge Connect (FPSCC), Fuel PDM SIQMA Cloud POS 3.0 (FPSCP), Fuel PDM SIQMA flex.CRID (FPSF), Fuel PDM SIQMA Hardware Maintenance (FPSHM), Fuel PDM SIQMA Scan & Go (FPSSG), Fuel PDM SIQMA Software Maintenance (FPSSM), Fuel PDM Vulnerabilities (FPV), Fuel Product Teams (FPT), Fuel R&D Architecture Board (FURAD19A), Fuel R&D Hardware Team (FRHT), Fuel SIQMApedia (FS), Fuel template (Agile),  (FRST), FUEL TEST PROJECT A (FTPA), Fuel_All_10-Standards (FUALL20A), Fuel_All_2018_DevOps-Program (FRSDO18A), Fuel_All_TOC_TODO (FUALL18C), Fuel_All_TTT (FUALLTTT), Fuel_KAM_2019_ToDos (FUKAM19A), Fuel_Marketing (FRSMA19A), Fuel_PDM_2018_generic-product-development (FUPDM18B), Fuel_PDM_2019_KassSichV (FUPDM19A), Fuel_PDM_2020_SelfCheckout (FUPDM20A), Fuel_Personal_Alla_Remfert (FUEL), Fuel_Personal_Henz_Dominik (FUPHD), Fuel_Personal_Viddelaers_Daniel (FUPVD), Fuel_PM_2019_JF-Linden-Niessen (FUPM19A), Fuel_PM_2019_ToDo (FUPMDE19B), Fuel_QA_2018_Internal (FUQA18B), Fuel_QA_2018_Test-Automation (FUQA18A), Fuel_QA_2019_General (FUQA19A), Fuel_R&D_2018_Common-Backlog (FRSTMS), Fuel_R&D_2018_SSF-SIP (FURD18A), Fuel_R&D_2019_Cloud BOS 2.0 (Multi-Branch BOS),  (FURD19A), Fuel_RM_2019_allgm_OL (FUPLO), Fuel_RM_2019_allgm_TP (FUPPT), Fuel_RM_2019_ToDo (FURMDE19A), Fuel_SD2_2019_ToDos (FUSD219A), Fuel_Ser_2018_Hosted-BOS_Tablet-POS (FUSER18C), Fuel_Ser_2018_PAM-Backlog (FUSER18A), Fuel_Ser_2019_Hosting (FUSER18B), Fuel_Ser_2019_IT (FUSER19A), Fuel_SheDe_13.0_Shell-Terminal-Replacement (FUSHED18B), Fuel_SheXx_xx_SSF (FUSHEXX18A), Fuel_TrassaRu_General (FUTRARU20A), Fuel_WsAT_xx (FUWSAT19A)
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.event.worklog.WorklogCreatedEvent
import com.atlassian.jira.event.worklog.WorklogUpdatedEvent
import com.atlassian.jira.event.worklog.WorklogDeletedEvent

// Start timer
long scriptStartTime = System.currentTimeMillis()

def issueManager = ComponentAccessor.issueManager
def issueIndexingService = ComponentAccessor.getComponent(IssueIndexingService)
def customFieldManager = ComponentAccessor.customFieldManager
def constantsManager = ComponentAccessor.constantsManager
def user = ComponentAccessor.jiraAuthenticationContext.loggedInUser

// Get the issue from the worklog event
Issue worklogIssue

//update chabrecek
if (event instanceof WorklogCreatedEvent || event instanceof WorklogUpdatedEvent || event instanceof WorklogDeletedEvent) {
    worklogIssue = event.worklog.issue
} else {
    log.warn("Unknown event type: ${event.getClass()}")
    return
}

// Helper method to safely reindex
def reindexSafe = { Issue issueToReindex ->
    if (issueToReindex) {
        log.debug("Reindexing issue: ${issueToReindex.key}")
        issueIndexingService.reIndex(issueToReindex)
    }
}

// Reindex the issue where worklog happened
reindexSafe(worklogIssue)

def epicIssue = null
def featureIssue = null
def initiativeIssue = null

// updated chabrecek
def epicLinkField = customFieldManager.getCustomFieldObjectsByName("Epic Link")?.first()
def parentLinkField = customFieldManager.getCustomFieldObjectsByName("Parent Link")?.first()

// Identify issue type names (adjust if your actual Jira names differ)
def issueTypeName = worklogIssue.issueType.name

if (issueTypeName in ["Epic"]) {
    // Worklog is directly on an Epic
    epicIssue = worklogIssue
    if (parentLinkField) {
        def featureKey = worklogIssue.getCustomFieldValue(parentLinkField)?.toString()
        if (featureKey) {
            featureIssue = issueManager.getIssueByCurrentKey(featureKey)
            if (featureIssue && parentLinkField) {
                def initiativeKey = featureIssue.getCustomFieldValue(parentLinkField)?.toString()
                if (initiativeKey) {
                    initiativeIssue = issueManager.getIssueByCurrentKey(initiativeKey)
                }
            }
        }
    }
} else if (issueTypeName in ["Feature"]) {
    // Worklog is directly on a Feature
    featureIssue = worklogIssue
    if (parentLinkField) {
        def initiativeKey = worklogIssue.getCustomFieldValue(parentLinkField)?.toString()
        if (initiativeKey) {
            initiativeIssue = issueManager.getIssueByCurrentKey(initiativeKey)
        }
    }
} else if (issueTypeName in ["LPM Initiative"]) {
    // Worklog is directly on an Initiative
    initiativeIssue = worklogIssue
} else {
    // Story/Task/Subtask handling
    if (epicLinkField) {
        def epicKey = worklogIssue.getCustomFieldValue(epicLinkField)?.toString()
        if (epicKey) {
            epicIssue = issueManager.getIssueByCurrentKey(epicKey)
        } else if (worklogIssue.issueType.subTask) {
            // Subtask - check parent
            def parent = worklogIssue.parentObject
            if (parent) {
                if (parent.issueType.name == "Epic") {
                    // Parent itself is an Epic
                    epicIssue = parent
                } else {
                    // Parent is a normal Story/Task, check Epic Link field
                    def parentEpicKey = parent.getCustomFieldValue(epicLinkField)?.toString()
                    if (parentEpicKey) {
                        epicIssue = issueManager.getIssueByCurrentKey(parentEpicKey)
                    }
                }
            }
        }
    }

    if (epicIssue && parentLinkField) {
        def featureKey = epicIssue.getCustomFieldValue(parentLinkField)?.toString()
        if (featureKey) {
            featureIssue = issueManager.getIssueByCurrentKey(featureKey)
            if (featureIssue && parentLinkField) {
                def initiativeKey = featureIssue.getCustomFieldValue(parentLinkField)?.toString()
                if (initiativeKey) {
                    initiativeIssue = issueManager.getIssueByCurrentKey(initiativeKey)
                }
            }
        }
    }
}

// Now reindex all found issues
reindexSafe(epicIssue)
reindexSafe(featureIssue)
reindexSafe(initiativeIssue)

// End timer
long scriptEndTime = System.currentTimeMillis()
long scriptDuration = scriptEndTime - scriptStartTime

log.info("Script execution completed in ${scriptDuration} milliseconds.")