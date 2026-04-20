package fields.fuel_area

//Goal: This is scripted field to derive the total number of man-days being used for an initiative. The APT and AIT values are added together. Vishal Choure 17.03.2025.
import com.atlassian.jira.component.ComponentAccessor

// Define custom field IDs - Ensure the format is correct
def field1Id = "customfield_16400"
def field2Id = "customfield_16401"
def log = Logger.getLogger("FrsLpmJeMdTotal")

def customFieldManager = ComponentAccessor.getCustomFieldManager()

// Ensure issue is not null
if (!issue) {
    log.error("Issue object is null. Aborting script.")
    return null
}

// Retrieve the custom field objects safely
def field1 = customFieldManager.getCustomFieldObject(field1Id)
def field2 = customFieldManager.getCustomFieldObject(field2Id)

if (!field1 || !field2) {
    log.error("One or both custom fields not found. Check field IDs.")
    return null
}

// Retrieve field values, ensuring null safety
def field1Value = issue.getCustomFieldValue(field1)
def field2Value = issue.getCustomFieldValue(field2)

// Log retrieved values for debugging
log.info("Field 1 (${field1.name}) Value: ${field1Value}, Field 2 (${field2.name}) Value: ${field2Value}")

// Ensure values are valid numbers; if null, assume zero
def num1 = (field1Value instanceof Number) ? field1Value.toDouble() : 0.0
def num2 = (field2Value instanceof Number) ? field2Value.toDouble() : 0.0

// If either value is non-numeric but not null, abort execution
if ((field1Value != null && !(field1Value instanceof Number)) || 
    (field2Value != null && !(field2Value instanceof Number))) {
    log.error("One or both fields contain non-numeric data. Aborting script.")
    return null
}

// Perform the addition and explicitly return as Double
def result = (num1 + num2) as Double
log.info("Calculated Sum: ${result}")

return result
