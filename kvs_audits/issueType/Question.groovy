package kvs_audits.issueType


import utils.CustomFieldUtil
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.IssueInputParametersImpl
import kvs_audits.common.CustomFieldsConstants
import com.atlassian.jira.component.ComponentAccessor;

import java.text.NumberFormat

/**
 * Question
 *
 * @author chabrecek.anton
 * Created on 14/03/2025.
 */
public class Question extends BaseIssue {

    public Question(Issue issue) {
        super(issue);
    }

    public static final List<String> QUESTION_CUSTOM_FIELD_NAMES = [
            AUDIT_LOCATION_FIELD_NAME, DEVIATION_FIELD_NAME, MEASURE_FIELD_NAME, NAME_FIELD_NAME, PERSON_RESPONSIBILITY_FIELD_NAME,
            QUESTION_USAGE_FIELD_NAME, QUESTION_STATUS_FIELD_NAME, SUMMARY_EN_FIELD_NAME, SUMMARY_SK_FIELD_NAME, DESCRIPTION_EN_FIELD_NAME, DESCRIPTION_SK_FIELD_NAME,
            AUDIT_INTERVAL_OCCURANCE_FIELD_NAME, AUDIT_ID_FIELD_NAME, QUESTION_ID_FIELD_NAME, CATEGORY_SK_FIELD_NAME, CATEGORY_EN_FIELD_NAME, CATEGORY_DE_FIELD_NAME
    ]

    public static final String PROJECT_KVS_AUDIT_QUESTION = "KVSAQ";
    public static final String QUESTION_ISSUE_TYPE_ID = "12701" //TODO CHECK IF IS CORRECT

    static final String AUDIT_LOCATION_FIELD_NAME = 'Audit Location'; //Multiple Issue Picker
    static final String DEVIATION_FIELD_NAME = 'Deviation'; //Text Field (single line)
    //static final String FINAL_STATUS_FIELD_NAME = 'Final Status'; //Text Field (multi-line)
    static final String MEASURE_FIELD_NAME = 'Measure'; //Text Field (single line)
    static final String NAME_FIELD_NAME = 'Name'; //
    static final String PERSON_RESPONSIBILITY_FIELD_NAME = 'Person Responsibility'; //User Picker (multiple users)
    static final String QUESTION_USAGE_FIELD_NAME = "Question Usage" //Select List (multiple choice)
    static final String QUESTION_STATUS_FIELD_NAME = 'Question Status'; //Select List (single choice)
    //  REMOVED static final String REMARK_FIELD_NAME = 'Remark'; //Text Field (multi-line)
    static final String SUMMARY_EN_FIELD_NAME = "Summary EN" //Text Field (single-line)
    static final String SUMMARY_SK_FIELD_NAME = "Summary SK" //Text Field (single-line)
    static final String DESCRIPTION_EN_FIELD_NAME = "Description EN" //Text Field (multi-line)
    static final String DESCRIPTION_SK_FIELD_NAME = "Description SK" //Text Field (multi-line)
    static final String AUDIT_INTERVAL_OCCURANCE_FIELD_NAME = "Audit interval occurance" //Select List (single choice)     -    always  semi-yearly   yearly
    static final String AUDIT_ID_FIELD_NAME = 'Audit_ID'; //Text Field (single-line)
    static final String CATEGORY_SK_FIELD_NAME = 'Category SK'; //Text Field (single-line)
    static final String CATEGORY_EN_FIELD_NAME = 'Category EN'; //Text Field (single-line)
    static final String CATEGORY_DE_FIELD_NAME = 'Category DE'; //Text Field (single-line)
    static final String QUESTION_ID_FIELD_NAME = 'Question_ID'; //Number Field
    static final String HAS_ATTACHMENTS_FIELD_NAME = "Has Attachments"  // Select List (single choice)
    static final String DELAY_WEIGHTING_FIELD_NAME = "Delay weighting";

    static final String SUMMARY_EN_FIELD_ID = CustomFieldUtil.getCustomFieldByName(SUMMARY_EN_FIELD_NAME)?.getId()
    static final String SUMMARY_SK_FIELD_ID = CustomFieldUtil.getCustomFieldByName(SUMMARY_SK_FIELD_NAME)?.getId()
    static final String DESCRIPTION_EN_FIELD_ID = CustomFieldUtil.getCustomFieldByName(DESCRIPTION_EN_FIELD_NAME)?.getId()
    static final String DESCRIPTION_SK_FIELD_ID = CustomFieldUtil.getCustomFieldByName(DESCRIPTION_SK_FIELD_NAME)?.getId()
    static final String ID_FIELD_ID = CustomFieldUtil.getCustomFieldByName(AUDIT_ID_FIELD_NAME)?.getId()
    static final String AUDIT_ID_FIELD_ID = CustomFieldUtil.getCustomFieldByName(AUDIT_ID_FIELD_NAME)?.getId()
    static final String QUESTION_ID_FIELD_ID = CustomFieldUtil.getCustomFieldByName(QUESTION_ID_FIELD_NAME)?.getId()

    public IssueInputParametersImpl cloneQuestion(Issue auditIssue, def assignee) {
        if (issue == null)
            throw new NullPointerException("Issue cannot be null.");

        if (issue.issueType?.id != QUESTION_ISSUE_TYPE_ID)
            throw new IllegalStateException("Provided issue is not a valid 'Question' type.");

        def issueParams = new IssueInputParametersImpl();
        def auditId = myBaseUtil.getIssueKeyNumberPart(auditIssue);
        def reporterName = auditIssue.reporter?.name ? auditIssue.reporter?.name : loggedInUser.name
        issueParams.with {
            setSummary(issue.summary)
            setDescription(issue.description)
            setProjectId(auditIssue.getProjectId())
            setIssueTypeId(QUESTION_ISSUE_TYPE_ID)
            setAssigneeId(assignee)
            setReporterId(reporterName)
            addCustomFieldValue(CustomFieldsConstants.PARENT_LINK_FIELD_ID, auditIssue.getKey())
        }

        addCustomFieldIfPresent(issueParams, SUMMARY_EN_FIELD_ID, getSummaryEN())
        addCustomFieldIfPresent(issueParams, SUMMARY_SK_FIELD_ID, getSummarySK())
        addCustomFieldIfPresent(issueParams, DESCRIPTION_EN_FIELD_ID, getDescriptionEN())
        addCustomFieldIfPresent(issueParams, DESCRIPTION_SK_FIELD_ID, getDescriptionSK())
        addCustomFieldIfPresent(issueParams, ID_FIELD_ID, getValueOfID())
        addCustomFieldIfPresent(issueParams, AUDIT_ID_FIELD_ID, auditId)
        addCustomFieldIfPresent_QuestionID(issueParams, QUESTION_ID_FIELD_ID, getQuestionId())

        return issueParams
    }

    private void addCustomFieldIfPresent(issueParams, fieldId, value) {
        if (value) {
            issueParams.addCustomFieldValue(fieldId, value)
        }
    }

    //ADD new method to handle problem
    // problem was with formating numeric values in DE some user use , as number separator, but expected is .
    //this leads to error when cloning Qeustion ... Errors: {customfield_17527='10.04' ist eine ungültige Nummer}
    private void addCustomFieldIfPresent_QuestionID(issueParams, String fieldId, def value) {
        if (value == null || value.toString().trim().length() == 0) {
            return
        }

        def normalized = value.toString().trim().replace(',', '.')
        BigDecimal bd
        try {
            bd = new BigDecimal(normalized)
        } catch (Exception e) {
            log.warn("Wrong numeric value for Question_ID: '${value}'")
            return
        }

        def i18n = ComponentAccessor.jiraAuthenticationContext.i18nHelper
        NumberFormat nf = NumberFormat.getNumberInstance(i18n.locale)
        nf.setGroupingUsed(false)
        String valueStr = nf.format(bd)

        issueParams.addCustomFieldValue(fieldId, valueStr)
    }

    String getSummaryEN() { myBaseUtil.getCustomFieldValue(issue, SUMMARY_EN_FIELD_NAME) }
    String getSummarySK() { myBaseUtil.getCustomFieldValue(issue, SUMMARY_SK_FIELD_NAME) }
    String getDescriptionEN() { myBaseUtil.getCustomFieldValue(issue, DESCRIPTION_EN_FIELD_NAME) }
    String getDescriptionSK() { myBaseUtil.getCustomFieldValue(issue, DESCRIPTION_SK_FIELD_NAME) }
    String getValueOfID() { myBaseUtil.getCustomFieldValue(issue, AUDIT_ID_FIELD_NAME) }
    String getValueOfCategoryDE() { myBaseUtil.getCustomFieldValue(issue, CATEGORY_DE_FIELD_NAME) }
    String getValueOfCategoryEN() { myBaseUtil.getCustomFieldValue(issue, CATEGORY_EN_FIELD_NAME) }
    String getValueOfCategorySK() { myBaseUtil.getCustomFieldValue(issue, CATEGORY_SK_FIELD_NAME) }
    String getAuditIntervalOccurance() { myBaseUtil.getCustomFieldValue(issue, AUDIT_INTERVAL_OCCURANCE_FIELD_NAME) }
    String getQuestionId() { myBaseUtil.getCustomFieldValue(issue, QUESTION_ID_FIELD_NAME) }
}