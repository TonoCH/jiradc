package kvs_audits.issueType

import com.atlassian.jira.issue.MutableIssue
import kvs_audits.common.CustomFieldsConstants;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueInputParametersImpl
import utils.CustomFieldUtil
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.event.type.EventDispatchOption

/**
 * Audit
 *
 * @author chabrecek.anton
 * Created on 26/03/2025.
 */
public class Audit extends BaseIssue{

    public static final String PROFIT_CENTER_FIELD_NAME = "Profit Center" //Single Issue Picker
    public static final String FUNCTIONAL_AREA_FIELD_NAME = "Functional Area" //Single Issue Picker
    public static final String WORKPLACES_FIELD_NAME = "Workplaces" //Multiple Issue Picker
    public static final String QUESTIONS_FIELD_NAME = "Questions" //Multiple Issue Picker

    public static final String AUDIT_ISSUE_TYPE_ID = "12700";//TODO be careful please check
    public static final String PROFIT_CENTER_FIELD_ID = CustomFieldUtil.getCustomFieldByName(PROFIT_CENTER_FIELD_NAME)?.getId()// "customfield_15901";//TODO rename all staticly typed ids
    public static final String FUNCTIONAL_AREA_FIELD_ID = CustomFieldUtil.getCustomFieldByName(FUNCTIONAL_AREA_FIELD_NAME)?.getId()// "customfield_15909";
    public static final String WORKPLACES_FIELD_ID = CustomFieldUtil.getCustomFieldByName(WORKPLACES_FIELD_NAME)?.getId()// "customfield_15910"; // Multi Issue Picker for Workplaces
    public static final String FUNCTIONAL_AREA_KEY = "Functional Area Key"; //Select List (single choice)
    public static final String AUDIT_ID_FIELD_NAME = 'Audit_ID'; //Text Field (single-line)
    public static final String AUDIT_ID_FIELD_ID = CustomFieldUtil.getCustomFieldByName(AUDIT_ID_FIELD_NAME)?.getId()
    public static final String TARGET_END_FIELD_NAME = "Target end"
    public static final String AUDIT_TYPE_FIELD_NAME = "Audit Type"
    public static final String AUDIT_DESCRIPTION_FIELD_NAME = "Audit Description"
    public static final String KVS_GENERATION_PAYLOAD_FIELD_NAME = "KVS Generation Payload"

    public static final String PLANNED = "Planned"
    public static final String UNPLANNED = "Unplanned"
    public static final String MANUAL = "Manual"

    Audit(Issue issue) {
        super(issue)
    }

    public def prepareAuditParams(AuditPreparation auditPreparationIssue, def profitCenterKey, def functionalAreaKey) {
        def auditParams = new IssueInputParametersImpl();

        /*def targetEndDate = null
        if (auditPreparationIssue.getDate_target_start()) {
            targetEndDate = auditPreparationIssue.getDate_target_start() + CustomFieldsConstants.NUM_OF_DAYS_FOR_TARGET_END
        }*/

        auditParams.setProjectId(auditPreparationIssue.getIssue().projectId)
                .setIssueTypeId(AUDIT_ISSUE_TYPE_ID)
                .setSummary(auditPreparationIssue.getIssue().summary)
        /*CHANGE No description depends on https://sb-conflu.ads.local/display/ANTON/Open+issues point 3*/
        //.setDescription(auditPreparationIssue.getIssue().description)
        //.addCustomFieldValue(TARGET_END_FIELD_NAME, targetEndDate)
        //.setReporterId(reporterName)
                .addCustomFieldValue(CustomFieldsConstants.PARENT_LINK_FIELD_ID, auditPreparationIssue.getIssue().key)
                .addCustomFieldValue(PROFIT_CENTER_FIELD_ID, profitCenterKey)
        //.addCustomFieldValue(AUDIT_ID_FIELD_ID, profitCenterKey);

        if(functionalAreaKey != null)
            auditParams.addCustomFieldValue(FUNCTIONAL_AREA_FIELD_ID, functionalAreaKey)

        return auditParams;
    }

    void setAuditId() {
        if (issue == null)
            throw new NullPointerException("Get issue key on null Audit.");

        def auditId = myBaseUtil.getIssueKeyNumberPart(issue)
        if (auditId == null)
            throw new IllegalStateException("Could not extract audit ID from parent issue key.")

        CustomField customField = CustomFieldUtil.getCustomFieldByName(AUDIT_ID_FIELD_NAME)
        if (customField == null)
            throw new IllegalArgumentException("Custom field not found: ${AUDIT_ID_FIELD_ID}")

        logger.setInfoMessage("Info auditId set for audit is:"+ auditId)
        issue.setCustomFieldValue(customField, auditId)

        //ComponentAccessor.issueManager.updateIssue(loggedInUser, issue, EventDispatchOption.ISSUE_UPDATED, false)
    }

    void setAuditType(String newAuditType) {
        if (issue == null)
            throw new NullPointerException("Get issue key on null Audit.");

        CustomField customField = CustomFieldUtil.getCustomFieldByName(AUDIT_TYPE_FIELD_NAME)
        if (customField == null)
            throw new IllegalArgumentException("Custom field not found: ${AUDIT_TYPE_FIELD_NAME}")

        issue.setCustomFieldValue(customField, newAuditType)
        //ComponentAccessor.issueManager.updateIssue(loggedInUser, issue, EventDispatchOption.ISSUE_UPDATED, false)
    }

    // !!NOTE!! this method is called by listener
    void setAuditDescription() {
        Issue pc = getProfitCenter()
        Issue fa = getFunctionalArea()
        List<Issue> wpc = getWorkplaces()

        String pcKey  = pc?.key ?: "-"
        String faKey  = fa?.key ?: "-"
        String wpcStr = (wpc && !wpc.isEmpty())
                ? wpc.findAll { it != null }
                .collect { "${it.key} - ${it.summary}" }
                .join(", ")
                : "-"

        String pcName  = pc?.getSummary() ?: "-"
        String faName  = fa?.getSummary() ?: "-"

        String payload = "{*}PC={*}${pcKey} ${pcName}\n{*}FA={*}${faKey} ${faName}\n{*}WPC={*}${wpcStr}\n{*}Audit Level={*}${getAuditLevel()}"

        if(getAuditLevel().equals(CustomFieldsConstants.AUDIT_LEVEL_4)){
            payload = "{*}PC={*}${pcKey} ${pcName}\n{*}Audit Level={*}${getAuditLevel()}"
            //payload += get_A_B_SubAreaString(fa)
        }
        if(getAuditLevel().equals(CustomFieldsConstants.AUDIT_LEVEL_3)){
            payload = "{*}PC={*}${pcKey} ${pcName}\n{*}FA={*}${faKey} ${faName}\n{*}Audit Level={*}${getAuditLevel()}"
        }

        def cf = CustomFieldUtil.getCustomFieldByName(AUDIT_DESCRIPTION_FIELD_NAME)
        if (!cf) {
            throw new IllegalArgumentException("Custom field not found: ${AUDIT_DESCRIPTION_FIELD_NAME}")
        }

        String current = (String) myBaseUtil.getCustomFieldValue(issue, AUDIT_DESCRIPTION_FIELD_NAME)
        if (current?.trim() == payload) {
            logger.setInfoMessage("Audit description unchanged on ${issue.key}; skipping update.")
            return
        }

        // Persist and dispatch update
        issue.setCustomFieldValue(cf, payload)
        commitIssueUpdate()
        logger.setInfoMessage("Audit description updated on ${issue.key} -> ${payload}")
    }

    private String get_A_B_SubAreaString(MutableIssue faIssue){
        def subAreaLetter = faIssue ? customFieldUtil.getFieldValue_SingleSelect(
                myBaseUtil.getCustomFieldValue(faIssue, BaseIssue.KVS_PC_SUB_AREA_FIELD_NAME)
        ) : null

        return subAreaLetter ? "\n{*}Sub-Area={*} ${subAreaLetter}" : "\n{*}Sub-Area={*}"
    }

    Issue getProfitCenter() {
        customFieldUtil.getCustomFieldValueFromIssuePicker(issue, PROFIT_CENTER_FIELD_NAME) as Issue
    }

    Issue getFunctionalArea() {
        customFieldUtil.getCustomFieldValueFromIssuePicker(issue, FUNCTIONAL_AREA_FIELD_NAME) as Issue
    }

    List<Issue> getWorkplaces() {
        customFieldUtil.getCustomFieldValueFromIssuePicker(issue, WORKPLACES_FIELD_NAME) as List<Issue>
    }

    List<Issue> getQuestions() {
        customFieldUtil.getCustomFieldValueFromIssuePicker(issue, QUESTIONS_FIELD_NAME) as List<Issue>
    }

    String getAuditLevel() {
        return myBaseUtil.getCustomFieldValue(issue, AuditPreparation.AUDIT_LEVEL_FIELD_NAME)
    }

    Double getAuditId() {
        def value = myBaseUtil.getCustomFieldValue(issue, AUDIT_ID_FIELD_NAME);
        if (value != null) {
            return value instanceof Number ? value.toDouble() : value.toString().toDouble()
        }

        return 0;
    }

    String getAuditType(){
        return myBaseUtil.getCustomFieldValue(issue, AUDIT_TYPE_FIELD_NAME)
    }

    def getDate_target_end() {
        return myBaseUtil.getCustomFieldValue(issue, TARGET_END_FIELD_NAME)
    }
}