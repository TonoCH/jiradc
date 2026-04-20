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

/**
 * WorkplaceIssue
 *
 * @author chabrecek.anton
 * Created on 4. 8. 2025.
 */
class WorkplaceIssue extends BaseIssueCreator implements ISubTaskLevel {

    private String destinationProjectKey
    private IssueInputParameters params;
    MutableIssue parrentIssue;

    WorkplaceIssue(String destinationProjectKey, String parentKey) {
        log.warn("Workplace Issue")
        if (!destinationProjectKey) {
            throw new IllegalArgumentException("Project key for import must not be null")
        }

        parrentIssue = Issues.getByKey(parentKey)// ComponentAccessor.getIssueManager().getIssueObject(parentKey)
        if(parrentIssue == null){
            throw new IllegalArgumentException("ParentKey didnt exists")
        }

        this.destinationProjectKey = destinationProjectKey
    }

    private static final String issueTypeQuestion = "12104" //"12100"
    private static final Map<String,String> SOURCE_TO_TARGET_CF = []

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
    }

    @Override
    MutableIssue getParentIssue() {
        return parrentIssue;
    }
}