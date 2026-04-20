package listeners.ps_area

/**
 * update_fields_parent_bugs
 *
 * @author chabrecek.anton
 * Updated on 22/05/2025.
 *
 * name: Update fields of parent bugs automatically
 * events: issue resolved, issue update, issue closed
 * applied to: PHFA Apcoa.connect (APCO), PHFA API Gateway (APIGW), PHFA Comprehensive Reporting (REP), PHFA Content (CMS), PHFA Document Generator (DOCGEN), PHFA Enco Apcoa Customer Care GUI (CCGUI), PHFA Enco Apcoa Virtual Site Controller (ENCOVSC), PHFA Enco Authorization (ENCOAUTH), PHFA Enco Counting (ENCOCOUNT), PHFA Enco Customermedia (ENCOCM), PHFA Enco Device Control API (PHEDCAPI), PHFA Enco Master Data (ENCOMD), PHFA enco Platform (ENCOPLAT), PHFA enco Smart Connect (SMART), PHFA entervo V2 Team Schroeren (EV2SKN), PHFA entervo.connect (ENCO), PHFA Finance (FIN), PHFA IoT Dashboard (DASHB), PHFA Masterdata Import (MDM), PHFA Portal Platform (PORTALPLAT), PHFA Trouble Shooting (TROUBLE), PS Access (ACCESS), PS Agile Working Group (AWG), PS App Gateway (APPGW), PS Audit (AUDIT), PS AWG Test (AWGTEST), PS Carpark (CPI), PS Common JSF component library (JSFCL), PS Configuration (CONFIG), PS Context API (CAPI), PS Parking Portal CAM (CAM), PS Parking Portal Customer Cardno (CARDNO), PS Parking Portal Customerportal (CMSWEB), PS econnect Adapter (ECADAPT), PS Generic Traffic Manager (GENTM), PS entervo BOS MG (BMG), PS Entervo Monitor (EMONT), PS entervo V2 Team Blazek (EV2BLA), PS entervo V2 Team IoT (EV2IOT), PS entervo V2 Team Klizan (EV2KLI), PS entervo V2 Team Mobile Software (EV2MS), PS Field Device Comp. (FDC), PS Field Device (FD), PS Hardware (HW), PS Identity&Access Management (IAM), PS Parking Portal Invoicing (PIS), PS IoT Device (IOTDEV), PS Java Money Provide (JMONEY), PS Smart Web Pay (SWP), PS License Server (LS), PS Mobile AppGateway (MAPPGW), PS Mobile Checkout API (CKOUTAPI), PS Mobile Checkout App (CKOUT), PS Mobile Checkout Plugin (CKOUTPLUG), PS Mobile Common Info (COMINF), PS Mobile ContentProvider (MCONTENT), PS Mobility App (PDMMA), PS Payment Masters (PAY), PS Personal Project B. Möller (EV2MOE), PS Portal Deployments (PPD), PS Portal ERP Adapter (ERPADAPT), PS Product (PAM), PS Project Specific Development (PSD), PS Smart Cashier (SCASHIER), PS Smart Control (SCONTROL), PS Smart Insights (SMARTIN), PS Smart Pay (SMARTPAY), PS Tariff (TARIFF), PS Tasklist (TASK), PS entervo BOS ZA (BZA), PS Validation Portal (VAL), PS Vehicle Classification (VCLASS)
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.attachment.Attachment
import com.atlassian.jira.issue.label.Label
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil

Logger log = Logger.getLogger("LIS_psUpdateFieldsOnParent")
log.setLevel(Level.INFO)

Issue issue = event.issue

log.info("Start - ${issue.key}" )

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

// determine parent issue
CustomField cfParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PARENT_BUG)
MutableIssue parentIssue = (MutableIssue) issue.getCustomFieldValue(cfParentBug)

if(!parentIssue) {
    return
}
log.info("Found parent - ${parentIssue.key}" )

Set<Label> parentUpdatenotes = createParentUpdatenotesFromChild(issue, parentIssue, currentUser)
CustomField cfUpdatenotes = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.UPDATENOTES)
parentIssue.setCustomFieldValue(cfUpdatenotes, parentUpdatenotes)

linkNewAttachmentsFromChildInParentComment(issue, parentIssue, currentUser)

addCommentsForFixVersionChanges(event, issue, parentIssue, currentUser)

ComponentAccessor.getIssueManager().updateIssue(currentUser, parentIssue, EventDispatchOption.ISSUE_UPDATED, false)


static Set<Label> createParentUpdatenotesFromChild(Issue childIssue, MutableIssue parentIssue, ApplicationUser currentUser) {
    CustomField cfUpdatenotes = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.UPDATENOTES)
    Set<Label> childUpdatenotes = (HashSet<Label>) childIssue.getCustomFieldValue(cfUpdatenotes)
    Set<Label> parentUpdatenotes = (HashSet<Label>) parentIssue.getCustomFieldValue(cfUpdatenotes)
    childUpdatenotes.each { childUpdatenote ->
        if(!parentUpdatenotes.findAll{it.getLabel()==childUpdatenote.getLabel()}) {
            Label newUpdatenote = new Label(childUpdatenote.getId(), parentIssue.getId(), childUpdatenote.getLabel())
            if(!parentUpdatenotes) {
                parentUpdatenotes = new HashSet<Label>();
            }
            parentUpdatenotes.add(newUpdatenote)
            String commentForParent = "Added updatenote \'${childUpdatenote.getLabel()}\' from child issue ${childIssue.key} automatically"
            ComponentAccessor.getCommentManager().create(parentIssue, currentUser, commentForParent, false)
        }
    }
    return parentUpdatenotes
}

static void linkNewAttachmentsFromChildInParentComment(Issue childIssue, MutableIssue parentIssue, ApplicationUser currentUser) {
    Logger logSub = Logger.getLogger("LIS_psUpdateFieldsOnParent.linkNewAttachmentsFromChildInParentComment")
    String baseUrl = ComponentAccessor.getApplicationProperties().getString("jira.baseurl")
    List<Attachment> childAttachments = (ArrayList) ComponentAccessor.getAttachmentManager().getAttachments(childIssue)
    childAttachments.each { childAttachment ->
        logSub.info("Found attachment - ${childAttachment.getFilename()} w/ id ${childAttachment.getId()}")
        if(childIssue.getUpdated().getTime() - childAttachment.getCreated().getTime() <= 3000) {
            logSub.info("Attachment createtime is within 3sec. timeframe to issue updatetime -> new link from parent needs to be added")
            String commentForParent = "Attachment [${childAttachment.getFilename()}|${baseUrl}/secure/attachment/${childAttachment.getId()}/${childAttachment.getFilename()}]"
            commentForParent += " has been uploaded to child issue ${childIssue.key}"
            ComponentAccessor.getCommentManager().create(parentIssue, currentUser, commentForParent, false)
        }
    }
}

static void addCommentsForFixVersionChanges(IssueEvent event, Issue childIssue, MutableIssue parentIssue, ApplicationUser currentUser) {
    Logger logSub = Logger.getLogger("LIS_psUpdateFieldsOnParent.addCommentsForFixVersionChanges")
    def fixVersionChanges = event?.getChangeLog()?.getRelated("ChildChangeItem")?.findAll {it.field == "Fix Version"}
    fixVersionChanges.each { fixVersionChange ->
        logSub.info("Found Fix Version change from ${fixVersionChange.oldstring} to ${fixVersionChange.newstring}")
        String commentForParent
        if(!fixVersionChange.oldstring) {
            commentForParent = "Fix for child issue ${childIssue.key} provided with child project version ${fixVersionChange.newstring}"
        } else {
            commentForParent = "Fix for child issue ${childIssue.key} no longer contained in child project version ${fixVersionChange.oldstring}"
        }
        ComponentAccessor.getCommentManager().create(parentIssue, currentUser, commentForParent, false)
    }
}