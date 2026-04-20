package utils

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField

//chabrecek created 5.3.25
class CustomFieldUtil {

    static CustomField getCustomFieldByName(String customFieldName) {
        List<CustomField> cfs = ComponentAccessor.getCustomFieldManager().getCustomFieldObjectsByName(customFieldName)
        if (cfs) {
            return cfs?.first()
        }
        return null
    }

    //Select List (multiple choices)
    /*public List<String> getFieldValue_MultiSelectSearcher(Object fieldValue){
        if (fieldValue instanceof List) {
            return fieldValue*.value;
        } else {
            return null;
        }
    }*/

    //Select List (multiple choices)
    public List<String> getFieldValue_MultiSelectSearcher(Object fieldValue) {
        if (fieldValue instanceof Collection) {
            return fieldValue.collect { opt ->
                opt.respondsTo('getValue') ? opt.value : opt.toString()
            }
        }
        else if (fieldValue != null) {
            if (fieldValue.respondsTo('getValue')) {

                return [ fieldValue.value ]
            } else {
                return [ fieldValue.toString() ]
            }
        }
        return []
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

    public Object getCustomFieldValueFromIssuePicker(Issue issue, String customFieldName) {
        def customFieldManager = ComponentAccessor.customFieldManager
        def customField = customFieldManager.getCustomFieldObjects(issue).find { it.name == customFieldName }
        customField ? issue.getCustomFieldValue(customField) : null
    }

    /**
     * Returns the Option ID for a given select-list value (single or multi).
     *
     * @param fieldName the name of the custom field
     * @param value the desired option value (String)
     * @return Option ID as Long, or null if not found
     */
    Long getOptionIdByValue(String fieldName, String value) {
        if (!fieldName || !value) {
            return null
        }

        def customField = getCustomFieldByName(fieldName)
        if (customField == null) {
            return null
        }

        def optionsManager = ComponentAccessor.getOptionsManager()
        def config = customField.getConfigurationSchemes()?.first()?.oneAndOnlyConfig
        if (config == null) {
            return null
        }

        def options = optionsManager.getOptions(config)
        def option = options?.find { it.value == value }

        return option?.optionId
    }
}