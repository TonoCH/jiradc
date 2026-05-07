package behaviours.kvs

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.Issue
import com.onresolve.jira.groovy.user.FormField

class FieldChangeUtil {
    static String toId(val) {
        if (val == null) return null
        if (val instanceof Option) return val.optionId.toString()
        if (val instanceof Collection) {
            return val.isEmpty() ? null : val.collect { toId(it) }.sort().join(",")
        }
        return val.toString()
    }

    static boolean hasChangedAndNotNull(FormField field, String fieldName, Issue issue) {
        def currentId = toId(field.getValue())
        if (!currentId) return false
        def originalId = null
        if (issue) {
            def cf = ComponentAccessor.customFieldManager
                    .getCustomFieldObjectsByName(fieldName)?.first()
            if (cf) originalId = toId(issue.getCustomFieldValue(cf))
        }
        return currentId != originalId
    }
}