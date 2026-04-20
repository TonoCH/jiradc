package fields.fuel_area

// Goal: This is a scripted field to derive the total time budget in numerical double format,
// as the source field is in long format and cannot be used directly for dashboards and other calculations.
// Author: Vishal Choure | Date: 17.03.2025

import com.atlassian.jira.component.ComponentAccessor
import fields.fuel_area.FieldsUsage  // Import FieldsUsage class

// Define the custom field ID dynamically using FieldsUsage
def customFieldManager = ComponentAccessor.getCustomFieldManager()
def timeBudgetField = customFieldManager.getCustomFieldObject("customfield_${FieldsUsage.LPM_TIME_BUDGET_FIELD_ID()}") // Use FieldsUsage

// Ensure the issue is available (ScriptRunner provides the 'issue' variable automatically)
if (!issue) {
    return 0.0 // Return default if issue is null
}

// Get the custom field value from the issue
def timeBudgetValue = issue.getCustomFieldValue(timeBudgetField)

// Ensure the value is a number before converting
if (timeBudgetValue instanceof Number) {
    return (timeBudgetValue as Double) / 3600  // Convert from seconds to hours
} else {
    return 0.0 // Default value if null or not a number
}
