package listeners.ps_area

/**
 * create_links_updatenote
 *
 * @author chabrecek.anton
 * Updated on 22/05/2025.
 *
 * name: Create links to Updatenote-Tool based on entered Updatenote label
 * events: issue resolved, issue update, issue closed
 * applied to: PS Access (ACCESS), PS Access Support (ACCSUP), PS Agile Working Group (AWG), PS App Gateway (APPGW), PS Audit (AUDIT), PS AWG Test (AWGTEST), PS Bugs (PSBUGS), PS Carpark (CPI), PS Cloud Services 1 Support (CLOUD1SUP), PS Cloud Services 2 Support (CLOUD2SUP), PS Common JSF component library (JSFCL), PS Configuration (CONFIG), PS Context API (CAPI), PS Parking Portal CAM (CAM), PS Parking Portal Customer Cardno (CARDNO), PS Parking Portal Customerportal (CMSWEB), PS DevOps Support (DEVOPSSUP), PS econnect Adapter (ECADAPT), PS Entervo Monitor (EMONT), PS entervo BOS MG (BMG), PS entervo BOS MG Support (BMGSUP), PS entervo V2 Team Blazek (EV2BLA), PS entervo V2 Team IoT (EV2IOT), PS entervo V2 Team Klizan (EV2KLI), PS entervo V2 Team Mobile Software (EV2MS), PS Master Authorizers Support (MASUP), PS Field Device Comp. (FDC), PS Field Device Components Support (FDCSUP), PS Hardware (HW), PS Hardware Support (HWSUP), PS Parking Portal Invoicing (PIS), PS IoT Device (IOTDEV), PS IoT Team Support (IOTSUP), PS Java Money Provide (JMONEY), PS Smart Web Pay (SWP), PS License Server (LS), PS Mobile AppGateway (MAPPGW), PS Mobile Checkout API (CKOUTAPI), PS Mobile Checkout App (CKOUT), PS Mobile Checkout Plugin (CKOUTPLUG), PS Mobile Common Info (COMINF), PS Mobile ContentProvider (MCONTENT), PS Mobile Software Support (MOBILESUP), PS Mobility App (PDMMA), PS Payment Masters Support (PAYSUP), PS Portal Deployments (PPD), PS Portal ERP Adapter (ERPADAPT), PS Product (PAM), PS Project Specific Development (PSD), PS Release Management Support (LIFESUP), PS Reporting Support (REPSUP), PS Smart Cashier (SCASHIER), PS Smart Control (SCONTROL), PS Specialists Support (SPECSUP), PS Tariff (TARIFF), PS Tasklist (TASK), PS entervo BOS ZA Support (BZASUP), PS Validation Portal (VAL), PS Vehicle Classification (VCLASS)
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.label.Label
import com.atlassian.jira.issue.link.RemoteIssueLinkBuilder
import com.atlassian.jira.issue.link.RemoteIssueLink
import com.atlassian.jira.issue.link.RemoteIssueLinkManager
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil

Logger log = Logger.getLogger("LIS_psCreateUpdatenoteLinks")
log.setLevel(Level.INFO)

Issue issue = event.issue

log.info("Start - ${issue.key}" )

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

CustomField cfUpdatenotes = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.UPDATENOTES)
Set<Label> updatenotes = (HashSet<Label>) issue.getCustomFieldValue(cfUpdatenotes)
List<RemoteIssueLink> remoteIssueLinks = ComponentAccessor.getComponent(RemoteIssueLinkManager.class).getRemoteIssueLinksForIssue(issue)

updatenotes.each { updatenote ->
    String updatenoteLabel = updatenote.getLabel()
    if(updatenoteLabel == "N/A") {
        return
    }
    String updatenoteId = updatenoteLabel.substring(updatenoteLabel.lastIndexOf("_") + 1)
    log.info("Found updatenote: ${updatenoteLabel} with Id: ${updatenoteId}" )
    updatenoteId = updatenoteId.replace("A","10")
    updatenoteId = updatenoteId.replace("B","11")
    updatenoteId = updatenoteId.replace("C","12")
    if(!remoteIssueLinks.findAll{it.getTitle().equals(updatenoteLabel)}) {
        RemoteIssueLink newLink = new RemoteIssueLinkBuilder().issueId(issue.getId())
                .url("http://bugzilla.gsph:8084/UpdateNote_WEB/ShowUP.jsp?systemId=12&id="+updatenoteId)
                .title(updatenoteLabel)
                .iconUrl("http://bugzilla.gsph:8084/UpdateNote_WEB/images/favicon.ico")
                .iconTitle("Updatenote-Tool")
                .build();
        ComponentAccessor.getComponent(RemoteIssueLinkManager.class).createRemoteIssueLink(newLink, currentUser);
    }
}