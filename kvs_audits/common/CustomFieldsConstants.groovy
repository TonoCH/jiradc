package kvs_audits.common

import com.atlassian.jira.component.ComponentAccessor

//TODO remove or shring this class
class CustomFieldsConstants {

    static final String DEFAULT_PROJECT_FOR_JOBS = PROJECT_KVS_AUDIT; //PROJECT_KVS_AUDIT_TEST; //PROJECT_KVS_AUDIT;

    static final String PARENT_LINK_FIELD_ID = "customfield_10301"
    static final String PROJECT_KVS_AUDIT_QUESTION = "KVSAQ";
    static final String PROJECT_KVS_AUDIT_TEST = "KVSTEST";
    static final String PROJECT_KVS_AUDIT = "KVSAUDIT";
    static final String PROJECT_KVS_PROFIT_CENTERS = "KVSPC";
    static final String PROJECT_KPI = "KPI"
    static final int NUM_OF_DAYS_FOR_TARGET_END = 4

    static final String PROFIT_CENTER_KEY = "Profit Center Key"
    static final String FUNCTIONAL_AREA_KEY = "Functional Area Key"
    static final String CROSS_AUDITORS_POOL = "Cross Auditors Pool"

    static final String AUDIT_PREPARATION = "Audit Preparation"
    static final String AUDIT = "Audit"
    static final String QUESTION = "Question"
    static final String MEASURE = "Measure"
    static final String KVS_SETTING = "KVS Setting"
    static final String WORKPLACE = " Workplace"

    static final String AUDIT_LEVEL_2 = "Level 2"
    static final String AUDIT_LEVEL_3 = "Level 3"
    static final String AUDIT_LEVEL_4 = "Level 4"
    static final String AUDIT_LEVEL_5 = "Level 5"

    // TODO old setting need check.... Mapping between issue types and their relevant fields
    /*static final Map<String, List<String>> ISSUE_TYPE_FIELD_MAPPING = [
            'Epic': ['Abbreviation', 'Profit Center', 'Date Archives', 'Auditors', 'Interval', 'Summer Interval'],
            'Audit ': ['Profit Center', 'Questions'],
            'Sub-task': ['Questions', 'Date Archives'],
            'Question': ['Summary'],
            // Add other issue types and their relevant fields as needed
    ]*/

    private static final def customFieldManager = ComponentAccessor.getCustomFieldManager()

    static def getCustomFieldByName(String name) {
        return customFieldManager.getCustomFieldObjectByName(name)
    }

    static def getCustomFieldById(String id) {
        return customFieldManager.getCustomFieldObject(id)
    }

    /**
     * Retrieves applicable fields for a given issue type.
     *
     * @param issueType The name of the issue type.
     * @return List of applicable field names.
     */
    /*static List<String> getApplicableFields(String issueType) {
        return ISSUE_TYPE_FIELD_MAPPING.getOrDefault(issueType, [])
    }*/
}