package utils.creator

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.IssueInputParameters
import com.atlassian.jira.issue.context.IssueContext
import com.atlassian.jira.issue.context.IssueContextImpl
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.fields.config.FieldConfig
import kvs_audits.common.CustomFieldsConstants

/**
 * ProfitCenterIssue
 *
 * @author chabrecek.anton
 * Created on 4. 8. 2025.
 */
class ProfitCenterIssue extends BaseIssueCreator {

    private String destinationProjectKey
    private IssueInputParameters params;

    ProfitCenterIssue(String destinationProjectKey) {
        log.warn("PC ISSUE")
        if (!destinationProjectKey) {
            throw new IllegalArgumentException("Project key for import must not be null")
        }

        this.destinationProjectKey = destinationProjectKey
    }

    private static final String issueTypeProfitCenter = "12702" //"12100"
    private static final Map<String,String> SOURCE_TO_TARGET_CF = [
            // source keys          target keys
            "customfield_16301" : "Profit Center Key",
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

    @Override
    void fillFields(IssueInputParameters params, Map<String, String> data) {
        this.params = params;

        params.setDescription(data["description"])
        params.setIssueTypeId(issueTypeProfitCenter)

        Map<String,String> TARGET_TO_SOURCE_CF = SOURCE_TO_TARGET_CF.collectEntries { sourceKey, targetName -> [ (targetName) : sourceKey ] }

        setSingleSelectField("Profit Center Key", data[TARGET_TO_SOURCE_CF.get("Profit Center Key")])
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