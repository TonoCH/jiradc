package behaviours

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.context.IssueContextImpl
import com.atlassian.jira.issue.customfields.impl.CascadingSelectCFType
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.customfields.option.Options
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.fields.config.FieldConfig
import com.atlassian.jira.project.Project
import com.onresolve.jira.groovy.user.FieldBehaviours
import com.onresolve.jira.groovy.user.FormField
import org.apache.log4j.Level
import org.apache.log4j.Logger

class BEH_targetProductAreaByTargetProject extends FieldBehaviours {

    private static final Logger log = Logger.getLogger(BEH_targetProductAreaByTargetProject.class)

    void update() {
        log.setLevel(Level.INFO)
        log.info("START - update")

        Project targetProject = (Project) getFieldById(getFieldChanged()).value
        log.info("targetProject - ${targetProject}")
        if (!targetProject) {
            return
        }

        FormField tpaField = getFieldByName("Target Product Area")
        CustomField cfTpa = customFieldManager.getCustomFieldObject(tpaField.getFieldId())
        Options options = ComponentAccessor.optionsManager.getOptions(cfTpa.getRelevantConfig(getIssueContext()))

        IssueContextImpl issueContext = new IssueContextImpl(targetProject.id, getIssueContext().issueTypeId)
        Options contextOptions = ComponentAccessor.optionsManager.getOptions(cfTpa.getRelevantConfig(issueContext))

        List<String> contextOptionsKeys = contextOptions.findAll().collect { it -> it.value }

        List<Option> parentOptions = options.findAll {
            it.value in contextOptionsKeys
        }

        def resultParent = new LinkedHashMap()
        resultParent.put("-1", "None")
        resultParent.putAll(parentOptions.collectEntries {
            [(it.optionId.toString()) : it.value]
        })

        tpaField.setFieldOptions(resultParent)
    }
}