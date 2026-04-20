package utils

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.context.IssueContextImpl
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.customfields.option.Options
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.fields.config.FieldConfig
import com.atlassian.jira.project.Project

class OptionUtil {

    static Options getOption(Issue issue, CustomField cf) {
        IssueContextImpl issueContext = new IssueContextImpl(issue.projectId, issue.issueTypeId)
        FieldConfig fieldConfig = cf.getRelevantConfig(issueContext)
        return ComponentAccessor.optionsManager.getOptions(fieldConfig)
    }

    static Option getOptionByName(Issue issue, CustomField cf, String name) {
        IssueContextImpl issueContext = new IssueContextImpl(issue.projectId, issue.issueTypeId)
        FieldConfig fieldConfig = cf.getRelevantConfig(issueContext)
        def options = ComponentAccessor.optionsManager.getOptions(fieldConfig)

        return options.find {it.value == name}
    }

    static Options getOption(Project project, Issue issue, CustomField cf) {
        IssueContextImpl issueContext = new IssueContextImpl(project.id, issue.issueTypeId)
        FieldConfig fieldConfig = cf.getRelevantConfig(issueContext)
        return ComponentAccessor.optionsManager.getOptions(fieldConfig)
    }
}