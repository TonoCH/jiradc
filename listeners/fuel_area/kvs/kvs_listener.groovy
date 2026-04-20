package listeners.fuel_area.kvs

/**
 * kvs_listener
 *
 * @author chabrecek.anton
 * Created on 5. 3. 2026.
 */

import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.ModifiedValue
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder
import com.atlassian.jira.user.ApplicationUser
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.issueType.Audit
import kvs_audits.issueType.Question
import utils.CustomFieldUtil
import utils.MyBaseUtil
import com.atlassian.jira.event.type.EventType
import kvs_audits.common.AuditMailer
import kvs_audits.issueType.AuditPreparation
import java.time.ZoneId
import java.time.LocalDate
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption

Issue actualIssue = event.issue
CustomFieldUtil customFieldUtil = new CustomFieldUtil();
def unplannedAuditType = customFieldUtil.getOptionIdByValue(Audit.AUDIT_TYPE_FIELD_NAME, Audit.UNPLANNED)
ApplicationUser jiraBot = ComponentAccessor.getUserManager().getUserByName("jira.bot");

//IF issue type AUDIT
if (actualIssue?.issueType?.name == CustomFieldsConstants.AUDIT) {
    def auditIdCf = CustomFieldsConstants.getCustomFieldByName(Audit.AUDIT_ID_FIELD_NAME)

    if (event.eventTypeId == EventType.ISSUE_UPDATED_ID) {
        //region allow only jira.bot edit audit id
        if (!auditIdCf) {
            log.warn("Audit listener: custom field '${Audit.AUDIT_ID_FIELD_NAME}' not found")
        } else {
            def auditIdChange = event?.changeLog?.getRelated("ChildChangeItem")?.find { ci ->
                (ci.field as String) == auditIdCf.name
            }

            if (auditIdChange) {
                ApplicationUser currentUser = event?.user ?: ComponentAccessor.jiraAuthenticationContext?.loggedInUser

                if (currentUser?.name != jiraBot.username) {
                    def oldValue = auditIdChange.oldstring
                    def newValue = auditIdChange.newstring

                    log.warn("Audit listener: unauthorized Audit ID change detected on ${actualIssue.key} by '${currentUser?.name}'. Reverting '${newValue}' -> '${oldValue}'")

                    def issueService = ComponentAccessor.issueService
                    def issueInputParameters = issueService.newIssueInputParameters()

                    if (oldValue) {
                        issueInputParameters.addCustomFieldValue(auditIdCf.idAsLong, oldValue)
                    } else {
                        issueInputParameters.addCustomFieldValue(auditIdCf.idAsLong, "")
                    }

                    issueInputParameters.setSkipScreenCheck(true)

                    def validation = issueService.validateUpdate(jiraBot, actualIssue.id, issueInputParameters)
                    if (validation.isValid()) {
                        issueService.update(jiraBot, validation, EventDispatchOption.DO_NOT_DISPATCH, false)
                    } else {
                        log.warn("Audit listener: failed to revert Audit ID on ${actualIssue.key}: ${validation.errorCollection}")
                    }
                }
            }
        }
        //endregion

        // region send ICS when assignee changes on update
        def assigneeChanged = event?.changeLog?.getRelated("ChildChangeItem")?.any { ci ->
            (ci.field as String) == "assignee"
        }

        if (assigneeChanged && actualIssue?.assignee) {
            MyBaseUtil myBaseUtil = new MyBaseUtil()

            def dateTargetStart = myBaseUtil.getCustomFieldValue(actualIssue, AuditPreparation.TARGET_START_FIELD_NAME)
            LocalDate targetStartLocalDate = null

            if (dateTargetStart instanceof Date) {
                targetStartLocalDate = dateTargetStart.toInstant()
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
            }

            String assigneeId = actualIssue.assignee.name

            if (targetStartLocalDate && assigneeId) {
                new AuditMailer().sendAuditInviteAsICS(actualIssue, assigneeId, targetStartLocalDate)
            } else {
                log.warn("Audit listener: skipping ICS for ${actualIssue.key} – targetStartLocalDate=${targetStartLocalDate}, assigneeId=${assigneeId}")
            }
        }
        // endregion
    }


    // region audit description – recompute on create / relevant change
    def changed = event?.getChangeLog()?.getRelated("ChildChangeItem")?.any { ci ->
        def f = ci.field as String
        f in [
                Audit.PROFIT_CENTER_FIELD_NAME,
                Audit.FUNCTIONAL_AREA_FIELD_NAME,
                Audit.WORKPLACES_FIELD_NAME
        ]
    }

    String selectedAuditType = new MyBaseUtil().getCustomFieldValue(actualIssue, Audit.AUDIT_TYPE_FIELD_NAME)
    if (selectedAuditType != Audit.UNPLANNED) {
        if (event.eventTypeId == EventType.ISSUE_CREATED_ID || changed) {
            new Audit(actualIssue).setAuditDescription()
        }

        //new Audit(actualIssue).setAuditDescription()
    }
// endregion
}

// if issue type MEASURE OR QUESTION OR MEASURE
if (actualIssue?.issueType?.name == CustomFieldsConstants.QUESTION || actualIssue?.issueType?.name == CustomFieldsConstants.MEASURE) {

    if (event.eventTypeId == EventType.ISSUE_CREATED_ID || event.eventTypeId == EventType.ISSUE_UPDATED_ID) {

        boolean hasAttachments = actualIssue?.attachments && !actualIssue.attachments.isEmpty()
        def cfHasAttachment = CustomFieldsConstants.getCustomFieldByName(Question.HAS_ATTACHMENTS_FIELD_NAME)

        if (!cfHasAttachment) {
            log.warn("Has Attachments: custom field '${Question.HAS_ATTACHMENTS_FIELD_NAME}' not found")
        }
        else {
            def optionsManager = ComponentAccessor.optionsManager
            def config = cfHasAttachment.getRelevantConfig(actualIssue)
            def options = optionsManager.getOptions(config)

            def yesOption = options?.find { it.value == "YES" }

            if (hasAttachments && yesOption) {
                new MyBaseUtil().setCustomFieldValue(actualIssue, cfHasAttachment, yesOption)
            } else {
                def changeHolder = new DefaultIssueChangeHolder()
                def oldValue = actualIssue.getCustomFieldValue(cfHasAttachment)
                cfHasAttachment.updateValue(null, actualIssue, new ModifiedValue(oldValue, null), changeHolder)
            }
        }
    }
}
