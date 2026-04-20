package utils.creator

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.IssueInputParameters
import com.atlassian.jira.issue.IssueInputParametersImpl
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.context.IssueContext
import com.atlassian.jira.issue.context.IssueContextImpl
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.fields.config.FieldConfig
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.issueType.Question
import utils.CustomFieldUtil

/**
 * QuestionIssue
 *
 * @author chabrecek.anton
 * Created on 7. 7. 2025.
 * Updated on 4. 8. 2025.
 */
public class QuestionIssue extends BaseIssueCreator implements ISubTaskLevel {

    private String destinationProjectKey
    private IssueInputParameters params;
    MutableIssue parrentIssue;

    QuestionIssue(String destinationProjectKey, String parentKey) {
        log.warn("QUESTIONISSUE")
        if (!destinationProjectKey) {
            throw new IllegalArgumentException("Project key for import must not be null")
        }

        parrentIssue = Issues.getByKey(parentKey)// ComponentAccessor.getIssueManager().getIssueObject(parentKey)
        if(parrentIssue == null){
            throw new IllegalArgumentException("ParentKey didnt exists")
        }

        this.destinationProjectKey = destinationProjectKey
    }

    private static final String issueTypeQuestion = "12701" //"12100"
    private static final Map<String,String> SOURCE_TO_TARGET_CF = [
            // source keys          target keys
            "customfield_16300" : "Question Usage",
            "customfield_15910" : "Workplaces",
            "customfield_16422" : "Audit interval occurance",
            "customfield_16502" : "Category DE",
            "customfield_16503" : "Category EN",
            "customfield_16504" : "Category SK",
            "customfield_16418" : "Description EN",
            "customfield_16419" : "Description SK",
            "customfield_16421" : "Summary EN",
            "customfield_16420" : "Summary SK",
            "customfield_16501" : "Audit_ID",
            "customfield_16505" : "Question_ID",
    ]

    @Override
    String getProjectKey() {
        return destinationProjectKey
    }

    @Override
    String getIssueTypeName() {
        return CustomFieldsConstants.QUESTION
    }

    @Override
    Set<String> getOptionalHeaders() {
        return [
                "summary", "issuetype", "reporter", "assignee",
                "description", "updated", "duedate", "timespent"
        ] as Set
    }

    @Override
    Set<String> getMandatoryHeaders() {
        return (SOURCE_TO_TARGET_CF.keySet() + ["summary"]) as Set
    }

    //TODO find solution for handling different sub-task level issue types, and others
    @Override
    void fillFields(IssueInputParameters params, Map<String, String> data) {
        def implParams = params as IssueInputParametersImpl
        this.params = params;

        //implParams.setParentId(parrentIssue.id as Long)
        params.setDescription(data["description"])
        params.setIssueTypeId(issueTypeQuestion)

        Map<String,String> TARGET_TO_SOURCE_CF = SOURCE_TO_TARGET_CF.collectEntries { sourceKey, targetName -> [ (targetName) : sourceKey ] }

        setCustomField(Question.CATEGORY_DE_FIELD_NAME, data[TARGET_TO_SOURCE_CF.get(Question.CATEGORY_DE_FIELD_NAME)])
        setCustomField(Question.CATEGORY_EN_FIELD_NAME, data[TARGET_TO_SOURCE_CF.get(Question.CATEGORY_EN_FIELD_NAME)])
        setCustomField(Question.CATEGORY_SK_FIELD_NAME, data[TARGET_TO_SOURCE_CF.get(Question.CATEGORY_SK_FIELD_NAME)])

        setCustomField(Question.DESCRIPTION_EN_FIELD_NAME, data[TARGET_TO_SOURCE_CF.get(Question.DESCRIPTION_EN_FIELD_NAME)])
        setCustomField(Question.DESCRIPTION_SK_FIELD_NAME, data[TARGET_TO_SOURCE_CF.get(Question.DESCRIPTION_SK_FIELD_NAME)])

        setCustomField(Question.SUMMARY_EN_FIELD_NAME, data[TARGET_TO_SOURCE_CF.get(Question.SUMMARY_EN_FIELD_NAME)])
        setCustomField(Question.SUMMARY_SK_FIELD_NAME, data[TARGET_TO_SOURCE_CF.get(Question.SUMMARY_SK_FIELD_NAME)])

        setCustomField(Question.QUESTION_ID_FIELD_NAME, data[TARGET_TO_SOURCE_CF.get(Question.QUESTION_ID_FIELD_NAME)])

        setSingleSelectField(Question.AUDIT_INTERVAL_OCCURANCE_FIELD_NAME, data[TARGET_TO_SOURCE_CF.get(Question.AUDIT_INTERVAL_OCCURANCE_FIELD_NAME)])
        setMultiSelectField(Question.QUESTION_USAGE_FIELD_NAME, data[TARGET_TO_SOURCE_CF.get(Question.QUESTION_USAGE_FIELD_NAME)])
    }

    private void setCustomField(String fieldName, String value) {
        if (!value) return
        def cf = customFieldManager.getCustomFieldObjectsByName(fieldName)?.first()
        if (cf) {
            params.addCustomFieldValue(cf.id, value)
        } else {
            log.warn("Custom field not found: ${fieldName}")
        }
    }

    private void setMultiSelectField(String fieldName, String raw) {
        //log.warn("QUESTION USAGE2:${raw}")
        if (!raw) return

        CustomField cf = ComponentAccessor.getCustomFieldManager()
                .getCustomFieldObjectsByName(fieldName)
                .find { it }

        //log.warn("cf:$cf")
        if (!cf) {
            log.error("CustomField '${fieldName}' not found")
            return
        }

        def project = ComponentAccessor.getProjectManager().getProjectObjByKey(destinationProjectKey)
        IssueContext ctx = new IssueContextImpl(project, null)
        FieldConfig config = cf.getRelevantConfig(ctx)

        //log.warn("config:$config")
        if (!config) {
            log.error("FieldConfig for '${fieldName}' not found using project context ${destinationProjectKey}")
            return
        }

        def options = ComponentAccessor.getOptionsManager().getOptions(config)
        List<String> ids = raw.split(',').collect { String code ->
            code = code.trim()
            def opt = options.find { it.value == code }
            if (opt) {
                return opt.optionId.toString()
            } else {
                log.warn("Unknown option for ${fieldName}: ${code}")
                return null
            }
        }.findAll { it }

        if (ids) {
            params.addCustomFieldValue(cf.getIdAsLong(), ids as String[])
        }
    }

    @Override
    MutableIssue getParentIssue() {
        return parrentIssue;
    }

    private void setSingleSelectField(String fieldName, String raw) {
        log.warn("setSingleSelectField2:${raw}")
        if (!raw) return

        CustomField cf = ComponentAccessor.getCustomFieldManager()
                .getCustomFieldObjectsByName(fieldName)
                .find { it }

        log.warn("cf:$cf")
        if (!cf) {
            log.error("CustomField '${fieldName}' not found")
            return
        }

        def project = ComponentAccessor.getProjectManager()
                .getProjectObjByKey(destinationProjectKey)
        IssueContext ctx = new IssueContextImpl(project, null)
        FieldConfig config = cf.getRelevantConfig(ctx)

        log.warn("config:$config")
        if (!config) {
            log.error("FieldConfig for '${fieldName}' not found in project context ${destinationProjectKey}")
            return
        }

        def options = ComponentAccessor.getOptionsManager().getOptions(config)

        log.warn("options:$options")

        String code = raw.trim()

        log.warn("code:$code")
        def opt = options.find { it.value == code }
        if (opt) {
            params.addCustomFieldValue(cf.getIdAsLong(), opt.optionId.toString())
            log.warn(" -> set field '${fieldName}' to optionId=${opt.optionId}")
        } else {
            log.warn("Unknown option for ${fieldName}: ${code}")
        }
    }
}