package kvs_audits.audt_manual_unplanned

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.user.ApplicationUser
import kvs_audits.KVSLogger
import kvs_audits.common.CommonHelper
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.issueType.Audit
import kvs_audits.issueType.AuditPreparation
import kvs_audits.issueType.Question
import utils.CustomFieldUtil
import utils.MyBaseUtil

import java.util.logging.Logger

/**
 * AuditManualUnplanned
 *
 * Contains logic to generate Question sub-tasks for Manual and Unplanned audits.
 * This class is intended to be invoked by a listener script (auditManualUnplannedCreate.groovy).
 *
 * @author chabrecek.anton
 * Created on 04/06/2025.
 */
class AuditManualUnplanned {

    protected KVSLogger logger = new KVSLogger();
    protected CustomFieldUtil customFieldUtil = new CustomFieldUtil();
    protected MyBaseUtil myBaseUtil = new MyBaseUtil();
    protected CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager();

    void handleIssueCreated(MutableIssue eventIssue) {
        if (!eventIssue || eventIssue.issueType?.name != CustomFieldsConstants.AUDIT) {
            return
        }

        def audit = new Audit(eventIssue)
        def auditTypeValue = audit.getAuditType()

        // If Audit.PLANNED, do nothing here.
        if (!auditTypeValue || auditTypeValue == Audit.PLANNED) {
            logger.setInfoMessage("Audit is Planned, do nothing here.")
            return
        }

        ApplicationUser currentUser = ComponentAccessor.jiraAuthenticationContext.loggedInUser
        switch (auditTypeValue) {
            case Audit.MANUAL:
                createManualQuestions(eventIssue, currentUser, audit)
                break
            case Audit.UNPLANNED:
                createUnplannedQuestions(eventIssue, currentUser, audit)
                break
            default:
                logger.setWarnMessage("Unknown Audit Type – no action");
                break
        }

        logger.setInfoMessage("DONE.")
    }

    private void createManualQuestions(Issue auditIssue, ApplicationUser currentUser, Audit audit) {

        List<Issue> selectedQuestions = audit.getQuestions()
        if (!selectedQuestions || selectedQuestions.isEmpty()) {
            logger.setWarnMessage("Manual Audit ${auditIssue.key}: no Questions selected; skipping question creation.")
            return
        }

        audit.setAuditId()
        audit.commitIssueUpdate()
        logger.setInfoMessage("Manual Audit ${auditIssue.key}: Audit_ID set.")

        int created = 0
        CommonHelper commonHelper = new CommonHelper()

        selectedQuestions.forEach { questionIssue ->
            Issue createdQuestionIssue = commonHelper.createQuestion(auditIssue, questionIssue, currentUser.name)
            if (createdQuestionIssue != null) {
                created++
                commonHelper.verifyAndUpdateIssueMetadata(createdQuestionIssue, currentUser.name, auditIssue)
                logger.setInfoMessage("Created Question ${createdQuestionIssue.key} under Audit issue: ${auditIssue.key}")
            } else {
                logger.setErrorMessage("Failed to create Question issue for ${questionIssue.key} under Audit issue: ${auditIssue.key}")
            }
        }

        logger.setInfoMessage("Manual Audit ${auditIssue.key}: created ${created} from ${selectedQuestions.size()} selected question sub-tasks.")

    }

    private void createUnplannedQuestions(Issue auditIssue, ApplicationUser currentUser, Audit audit) {

        Issue profitCenter  = audit.getProfitCenter()
        Issue functionalArea  = audit.getFunctionalArea()
        List<Issue> wpList = audit.getWorkplaces() ?: []
        String level = audit.getAuditLevel()


        if (!profitCenter || !level) {
            logger.setErrorMessage("Unplanned Audit ${auditIssue.key}: missing PC or Level; cannot generate questions.")
            return
        }

        boolean isL2 = (level == CustomFieldsConstants.AUDIT_LEVEL_2)
        boolean isL3 = (level == CustomFieldsConstants.AUDIT_LEVEL_3)
        boolean isL4 = (level == CustomFieldsConstants.AUDIT_LEVEL_4)

        if (isL2) {
            if (!functionalArea) {
                logger.setErrorMessage("Unplanned Audit ${auditIssue.key}: Level 2 requires Functional Area.")
                return
            }
            if (wpList.isEmpty()) {
                logger.setErrorMessage("Unplanned Audit ${auditIssue.key}: Level 2 requires one Workplace.")
                return
            }
            if (wpList.size() > 1) {
                logger.setWarnMessage("Unplanned Audit ${auditIssue.key}: Level 2 has ${wpList.size()} workplaces selected; only the first will be used.")
            }
        }

        if (isL3 && !functionalArea) {
            logger.setErrorMessage("Unplanned Audit ${auditIssue.key}: Level 3 requires Functional Area.")
            return
        }

        CommonHelper helper = new CommonHelper()
        int generated = 0

        if (isL4) {
            List<String> usages = helper.buildQuestionUsageKeys(profitCenter, functionalArea, level) ?: []
            if (usages.isEmpty()) {
                logger.setErrorMessage("Unplanned Audit ${auditIssue.key}: no Question Usage derived for Level 4.")
                return
            }
            usages.each { usage ->
                helper.createQuestionsIssues(usage, auditIssue, currentUser?.name, null)
                logger.setInfoMessage("Unplanned Audit ${auditIssue.key}: created questions for Question Usage '${usage}'.")
                generated++
            }
        } else {
            // L2 and L3
            String usage = helper.buildQuestionUsage(profitCenter, functionalArea, level)
            if (!usage) {
                logger.setErrorMessage("Unplanned Audit ${auditIssue.key}: failed to build Question Usage for ${level}.")
                return
            }
            helper.createQuestionsIssues(usage, auditIssue, currentUser?.name, null)
            logger.setInfoMessage("Unplanned Audit ${auditIssue.key}: created questions for Question Usage '${usage}'.")
            generated++
        }

        // L2 info about WP
        if (isL2) {
            Issue wpUsed = wpList.first()
            logger.setInfoMessage("Unplanned Audit ${auditIssue.key}: Level 2 workplace used -> ${wpUsed?.key}")
        }

        logger.setInfoMessage("Unplanned Audit ${auditIssue.key}: total question groups generated: ${generated}.")
    }

}
