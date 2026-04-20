package kvs_audits.issueType


import com.atlassian.jira.issue.Issue

import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * groovy
 *
 * @author chabrecek.anton
 * Created on 11/03/2025.
 */
public class AuditPreparation extends BaseIssue{

    AuditPreparation(Issue issue){
        super(issue)
    }

    public static final List<String> AUDIT_PREPARATION_CUSTOM_FIELD_NAMES = [
            ABBREVIATION_FIELD_NAME, AUDIT_LEVEL_FIELD_NAME, QUESTON_USAGE_FIELD_NAME, PROFIT_CENTER_FIELD_NAME,
            AUDITORS_FIELD_NAME, INTERVAL_FIELD_NAME, SUMMER_INTERVAL_FIELD_NAME, DATE_OF_NEXT_ROTATION,
            ROTATION_DATA_FIELD_NAME, TARGET_START_FIELD_NAME, KVS_KPI_JSON_FIELD_NAME, KVS_PC_SUB_AREA_FIELD_NAME,
    ]

    //static final String DATE_ARCHIVES_FIELD_NAME = "Date Archives" //Date Picker
    static final String ABBREVIATION_FIELD_NAME = "Abbreviation" //Text Field (single line)
    static final String AUDITORS_FIELD_NAME = "Auditors" //User Picker (multiple users)
    static final String INTERVAL_FIELD_NAME = "Interval" //Select List (single choice)
    static final String SUMMER_INTERVAL_FIELD_NAME = "Summer Interval" //Select List (single choice)
    static final String DATE_OF_NEXT_ROTATION = "Date Of Next Rotation" //Date Picker
    static final String ROTATION_DATA_FIELD_NAME = "Rotation Data" //Text Field (multi-line)
    static final String QUESTON_USAGE_FIELD_NAME = "Question Usage" //Select List (multiple choice)
    static final String AUDIT_LEVEL_FIELD_NAME = "Audit level" //Select List (single choice)
    static final String PROFIT_CENTER_FIELD_NAME = "Profit Center" //Single Issue Picker
    static final String TARGET_START_FIELD_NAME = "Target start"
    static final String KVS_KPI_JSON_FIELD_NAME = "KVS kpi json"

    private String auditLevel,
                   abbreviation,
                   interval,
                   summer_interval,
                   rotation_data,
                   kvs_kpi_json
    private List<String> questionUsage,
                         auditors;
    private def date_of_next_rotation, date_target_start;

    String getAuditLevel() {
        this.auditLevel = myBaseUtil.getCustomFieldValue(issue, AUDIT_LEVEL_FIELD_NAME);

        return auditLevel
    }

    String getAbbreviation() {
        this.abbreviation = myBaseUtil.getCustomFieldValue(issue, ABBREVIATION_FIELD_NAME)

        return abbreviation
    }

    String getInterval() {
        this.interval = myBaseUtil.getCustomFieldValue(issue, INTERVAL_FIELD_NAME)

        return interval
    }

    String getSummer_interval() {
        this.summer_interval = myBaseUtil.getCustomFieldValue(issue, SUMMER_INTERVAL_FIELD_NAME)

        return summer_interval
    }

    String getRotation_data() {
        this.rotation_data = myBaseUtil.getCustomFieldValue(issue, ROTATION_DATA_FIELD_NAME)

        return rotation_data
    }

    List<String> getQuestionUsage() {
        this.questionUsage = customFieldUtil.getFieldValue_MultiSelectSearcher( myBaseUtil.getCustomFieldValue(issue, Question.QUESTION_USAGE_FIELD_NAME) );

        return questionUsage
    }

    List<String> getAuditors() {
        this.auditors = customFieldUtil.getFieldValue_MultiUsersSearcher(
                myBaseUtil.getCustomFieldValue(issue, AUDITORS_FIELD_NAME)) ?: []

        return auditors
    }

    def getDate_of_next_rotation() {
        this.date_of_next_rotation = myBaseUtil.getCustomFieldValue(issue, DATE_OF_NEXT_ROTATION)

        return date_of_next_rotation
    }

    String getKvs_kpi_json() {
        this.kvs_kpi_json = myBaseUtil.getCustomFieldValue(issue, KVS_KPI_JSON_FIELD_NAME)

        return kvs_kpi_json
    }

    def getDate_target_start() {
        this.date_target_start = myBaseUtil.getCustomFieldValue(issue, TARGET_START_FIELD_NAME)

        return date_target_start
    }

    def getDate_targetStartAsDateTime() {
        getDate_target_start()

        return date_target_start?.toInstant()
                ?.atZone(ZoneId.systemDefault())
                ?.toLocalDate()
                ?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }

    public List<String> getExternalAuditors() {
        return customFieldUtil.getFieldValue_MultiUsersSearcher(
                myBaseUtil.getCustomFieldValue(issue, AUDITORS_EXTERNAL_FIELD_NAME)
        ) ?: []
    }
}
