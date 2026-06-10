import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.onresolve.jira.groovy.user.FieldBehaviours
import groovy.transform.BaseScript

@BaseScript FieldBehaviours behaviours

final Long PROJECT_INFORMATION_FIELD_ID = 18702L
final Long EPIC_LINK_FIELD_ID = 10001L
final Long PARENT_LINK_FIELD_ID = 10301L

def projectInformationFormField = getFieldById("customfield_${PROJECT_INFORMATION_FIELD_ID}")
if (projectInformationFormField == null) return

def issue = underlyingIssue
if (issue == null) return  // brand new issue with no parent context yet

if (hasParent(issue, EPIC_LINK_FIELD_ID, PARENT_LINK_FIELD_ID)) {
    projectInformationFormField.setReadOnly(true)
    projectInformationFormField.setDescription('Inherited from the parent issue and cannot be edited here.')
} else {
    projectInformationFormField.setReadOnly(false)
}

boolean hasParent(Issue issue, Long epicLinkFieldId, Long parentLinkFieldId) {
    if (issue.isSubTask()) return true

    def customFieldManager = ComponentAccessor.customFieldManager
    return hasReference(issue, customFieldManager.getCustomFieldObject(epicLinkFieldId)) ||
           hasReference(issue, customFieldManager.getCustomFieldObject(parentLinkFieldId))
}

boolean hasReference(Issue issue, CustomField field) {
    field != null && issue.getCustomFieldValue(field) != null
}
