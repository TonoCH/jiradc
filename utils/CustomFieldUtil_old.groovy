package utils.old

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField

class CustomFieldUtil {

    static CustomField getCustomFieldByName(String customFieldName) {
        List<CustomField> cfs = ComponentAccessor.getCustomFieldManager().getCustomFieldObjectsByName(customFieldName)
        if (cfs) {
            return cfs?.first()
        }
        return null
    }


    //Select List (multiple choices)
    public List<String> getFieldValue_MultiSelectSearcher(Object fieldValue){
        if (fieldValue instanceof List) {
            return fieldValue*.value;
        } else {
            return null;
        }
    }

    //Select List (multiple users)
    public List<String> getFieldValue_MultiUsersSearcher(Object fieldValue){
        if (fieldValue instanceof List) {
            return fieldValue*.name;
        } else {
            return null;
        }
    }

    //Select List (Single Choice)
    public String getFieldValue_SingleSelect(Object fieldValue) {
        if (fieldValue instanceof com.atlassian.jira.issue.customfields.option.Option) {
            return fieldValue.value;
        }
        
        return null;
    }

    //Checkboxes (Multiple)
    public List<String> getFieldValue_Checkboxes(Object fieldValue) {
        if (fieldValue instanceof List) {
            return fieldValue.collect { it.value }; // Extracts checkbox values
        }
        return null;
    }

    //Single Issue Picker
    public Issue getFieldValue_SingleIssuePicker(Object fieldValue) {
        if (fieldValue instanceof Issue) {
            return fieldValue; // Returns issue key (e.g., "PROJ-123")
        }
        
        return null;
    }
}