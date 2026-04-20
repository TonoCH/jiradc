package listeners.fuel_area

/**
 * lpm_priority
 *
 * @author chabrecek.anton
 * Updated on 22/05/2025.
 *
 * name: FRS LPM Priority
 * events: Issue Created, Issue Updated, Issue Assigned, Issue Commented, CustomFieldUpdatedEvent, EpicCustomFieldChangedEvent, CustomFieldCreatedEvent
 * applied to: Fuel Base Config (FBC), Fuel Fast Fix Team (FFFT), Fuel IT AVIA/Total/Globus (FITAVTOGL), Fuel IT BP A/CH/NL/LUX (FIBA), Fuel IT BP DE (FUBPDE), Fuel IT ENI/A1/Doppler/Schenk (FIA), Fuel IT ESSO/OMV (FIE), Fuel IT Q8 (FIQ), Fuel IT Shell DE TMS30 (FUSHEDE18A), Fuel IT Shell SSF AT (FUSHEAT19A), Fuel IT Shell SSF CH (FUSHECH18A), Fuel IT Shell SSF DE (FUSHEDE19), Fuel IT Shell SSF DE/Trassa (FISSD), Fuel IT T&R/Globus/Total (FITTROMV), Fuel NMS/Cloud Solutions (FFNS), Fuel PDM BOS30 Facelift (FPBF), Fuel PDM Informationssicherheitsmanagementsystem (ISMS),  (FPII), Fuel PDM SIQMA Charge Connect (FPSCC), Fuel PDM SIQMA Cloud POS 3.0 (FPSCP), Fuel PDM SIQMA flex.CRID (FPSF), Fuel PDM SIQMA Hardware Maintenance (FPSHM), Fuel PDM SIQMA Scan & Go (FPSSG), Fuel PDM SIQMA Software Maintenance (FPSSM), Fuel PDM Vulnerabilities (FPV), Fuel Product Teams (FPT), Fuel R&D Architecture Board (FURAD19A), Fuel R&D Hardware Team (FRHT), Fuel SIQMApedia (FS), Fuel PDM Generic Product Development (FUPDM18B), Fuel_PDM_2019_KassSichV (FUPDM19A), Fuel_PDM_2020_SelfCheckout (FUPDM20A), Fuel_QA_Test-Automation (FUQA18A), Fuel_R&D_2018_SSF-SIP (FURD18A), Fuel_SheXx_xx_SSF (FUSHEXX18A), Fuel_TrassaRu_General (FUTRARU20A), Web Based POS (POS40),  (WBPP), Fuel PDM BOS40 (FPB)
 */

// This script is used only in FRS
// Question to Frank Clever (FRS-MG)
// it copies the field "FRS LPM Priority Value" to "FRS LPM Priority Value Roadmaps"
// in all issues the field is field... usually only for type LPM Initiative

import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.user.UserUtils
import com.atlassian.jira.user.util.UserUtil

import com.atlassian.greenhopper.service.rapid.view.RapidViewService
import com.atlassian.greenhopper.service.sprint.SprintIssueService
import com.atlassian.greenhopper.service.sprint.SprintManager
import com.atlassian.greenhopper.service.sprint.Sprint
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.onresolve.scriptrunner.runner.customisers.JiraAgileBean
import com.onresolve.scriptrunner.runner.customisers.WithPlugin
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil

@WithPlugin("com.pyxis.greenhopper.jira")

@JiraAgileBean
RapidViewService rapidViewService

@JiraAgileBean
SprintIssueService sprintIssueService

@JiraAgileBean
SprintManager sprintManager


Logger mylog = Logger.getLogger("scriptrunner.Listener.FRS_LPM_Priority")
mylog.setLevel(Level.INFO)

try {
    MutableIssue issue = (MutableIssue) event.getIssue()

    mylog.info("----START FRSLPMPriorityValueRoadmap ${issue.key} for <${issue.summary}> ")

    //##########################################################
    //#  Fill Field FRS LPM Priority Roadmap                   #
    //##########################################################
    switch ( issue.issueType?.name ) {
//    case ['Task', 'Story', 'Epic', 'Feature', 'LPM Initiative']:
        case ['Story', 'Epic', 'Feature', 'LPM Initiative']:
            mylog.info("-----------${issue.issueType?.name}-----------")

            // ---------------------------------------------------------------
            // start work here
            CustomField cfFRSLPMPriorityValue = CustomFieldUtil.getCustomFieldByName("FRS LPM Priority Value")
            CustomField cfFRSLPMPriorityValueRoadmap = CustomFieldUtil.getCustomFieldByName("FRS LPM Priority Roadmap")
            mylog.info("cfFRSLPMPriorityValue.toString: ${cfFRSLPMPriorityValue.toString()}")
            mylog.info("cfFRSLPMPriorityValueRoadmap.toString: ${cfFRSLPMPriorityValueRoadmap.toString()}")

            def loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
            mylog.info("loggedInUser: ${loggedInUser.toString()}")

            if (cfFRSLPMPriorityValue){
                if (cfFRSLPMPriorityValueRoadmap){

                    mylog.info("-----------0-----------")
                    try{
                        mylog.info("-----------1-----------")
                        String FRSLPMPriorityValue = issue.getCustomFieldValue(cfFRSLPMPriorityValue)
                        mylog.info("-----------2-----------")
                        mylog.info("FRSLPMPriorityValue: ${FRSLPMPriorityValue}")

                        if (FRSLPMPriorityValue != null){
                            issue.setCustomFieldValue(cfFRSLPMPriorityValueRoadmap, FRSLPMPriorityValue.toDouble())

//                            ComponentAccessor.getIssueManager().updateIssue(UserUtils.getUser("FRSLPMPriorityListener"), issue, EventDispatchOption.DO_NOT_DISPATCH, false)
//                            ComponentAccessor.getIssueManager().updateIssue(UserUtils.getUser("currentUser"), issue, EventDispatchOption.DO_NOT_DISPATCH, false)
//                            ComponentAccessor.getIssueManager().updateIssue(UserUtils.getUser("Clever.Frank"), issue, EventDispatchOption.DO_NOT_DISPATCH, false)
                            ComponentAccessor.getIssueManager().updateIssue(ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser(), issue, EventDispatchOption.DO_NOT_DISPATCH, false)
                            ComponentAccessor.getComponent(IssueIndexingService.class).reIndex(issue)
                        }else{
                            mylog.info("-----------null: Don't set it -----------")
                        }

                        //-----------------crosscheck: spool new results (remove later ...) -----------------
                        mylog.info("crosscheck - for ${issue.key} ")

                        CustomField cfFRSLPMPriorityValue2 = CustomFieldUtil.getCustomFieldByName("FRS LPM Priority Value")
                        String FRSLPMPriorityValue2 = issue.getCustomFieldValue(cfFRSLPMPriorityValue2)
                        mylog.info("FRSLPMPriorityValue2: ${FRSLPMPriorityValue2}")

                        CustomField cfFRSLPMPriorityValueRoadmap2 = CustomFieldUtil.getCustomFieldByName("FRS LPM Priority Roadmap")
                        String FRSLPMPriorityValueRoadmap2 = issue.getCustomFieldValue(cfFRSLPMPriorityValueRoadmap2)
                        mylog.info("FRSLPMPriorityValueRoadmap2: ${FRSLPMPriorityValueRoadmap2}")

                    }catch(Exception ex) {
                        mylog.info("FRSLPMPriorityValueRoadmap Exception Handler got it.. Field is null")
                    }
                }
                else{
                    mylog.info("ERROR cfFRSLPMPriorityValueRoadmap: ${cfFRSLPMPriorityValueRoadmap.toString()}")
                }
            }
            else{
                mylog.info("ERROR cfFRSLPMPriorityValue: ${cfFRSLPMPriorityValue.toString()}")
            }
            break
        default:
            mylog.info("-----------${issue.issueType?.name}-----------")
            mylog.info("Wrong type... stop here")
    }


    //##########################################################
    //#  Fill Field EpicName as copy from Summary              #
    //##########################################################
    switch ( issue.issueType?.name ) {
        case ['Epic']:
            mylog.info("------Fill EpicName for ${issue.key} type ${issue.issueType?.name}-----------")

            String summary = issue.summary
            if (summary != null){
                CustomField cfEpicName = CustomFieldUtil.getCustomFieldByName("Epic Name")
//            mylog.info("cfEpicName.toString: ${cfEpicName.toString()}")
                String EpicName = issue.getCustomFieldValue(cfEpicName)

                issue.setCustomFieldValue(cfEpicName, issue.getSummary())
                mylog.info("### 1 ###")
                ComponentAccessor.getIssueManager().updateIssue(ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser(), issue, EventDispatchOption.DO_NOT_DISPATCH, false)
                ComponentAccessor.getComponent(IssueIndexingService.class).reIndex(issue)

                //-----------------crosscheck: spool new results (remove later ...) -----------------
                mylog.info("crosscheck - for ${issue.key} ")

                CustomField cfEpicName2 = CustomFieldUtil.getCustomFieldByName("Epic Name")
                String EpicName2 = issue.getCustomFieldValue(cfEpicName2)
                mylog.info("old EpicName: <${EpicName}>  New EpicName: <${EpicName2}> for <${issue.summary}>")
                mylog.info("Fill Field EpicName done")
            }

            break
        default:
            mylog.info("Copy Summary to Epic-Name only for EPICS... stop here... not for ${issue.issueType?.name} ")
    }

}catch(Exception ex) {
    mylog.info("FRSLPMPriorityValueRoadmap Exception")
}

mylog.info("----FRSLPMPriorityValueRoadmap DONE")