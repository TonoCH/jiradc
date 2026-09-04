package behaviours.sales_delivery

import org.apache.log4j.Logger
import org.apache.log4j.Level

def log = Logger.getLogger("fuel.salesAndDeliveryStatus")
log.setLevel(Level.DEBUG)
/**
 * fuel_salesAndDeliveryStatus
 *
 * @author choure.vishal updated by chabrecek.anton
 * Created on 2. 9. 2026.
 */

// Combined Behaviour script for Sales Status + Delivery Status
// Uses field IDs instead of field names, avoids any issue with
// special characters (%, &, digits) in field name lookups.
//
// Paste this EXACT script into three places in the Behaviour:
//   1. Sales Status field's server-side script box
//   2. Delivery Status field's server-side script box
//   3. The Behaviour's top-level Initialiser script

def salesStatusField = getFieldById("customfield_18300") //	Select List (single choice)
def deliveryStatusField = getFieldById("customfield_18301") // 	Select List (single choice)

def salesValue = salesStatusField?.getValue()?.toString()?.trim()
def deliveryValue = deliveryStatusField?.getValue()?.toString()?.trim()

// Map: option value (must match dropdown text exactly) -> date field ID
def salesDateFieldMap = [
        "Sales Status Date - To Do"               : "customfield_20318", //Date Picker
        "Sales Status Date - Quotation Irrelevant": "customfield_20317", //Date Picker
        "Sales Status Date - Quotation Provided"  : "customfield_20316", //Date Picker
        "Sales Status Date - Order Signed"        : "customfield_20315", //Date Picker
        "Invoiced 45%"                            : "customfield_20313", //Date Picker
        "Invoiced 50%"                            : "customfield_20314", //Date Picker
        "Invoiced 100%"                           : "customfield_20312", //Date Picker
]

// "To Do" is shown but not required, and gets auto-filled with the
// issue's creation date instead of needing manual entry.
def salesNotRequiredValues = ["To Do"]
def salesAutoFillWithCreatedDate = ["To Do"]

def deliveryDateFieldMap = [
        "Delivery Status Date - In Refinement"        : "customfield_20303", //Date Picker
        "Delivery Status Date - Reviewed"             : "customfield_20307", //Date Picker
        "Delivery Status Date - Signed off S&B"       : "customfield_20308", //Date Picker
        "Delivery Status Date - Estimates"            : "customfield_20302", //Date Picker
        "Delivery Status Date - Awaiting PI Planning" : "customfield_20300", //Date Picker
        "Delivery Status Date - Development"          : "customfield_20301", //Date Picker
        "Delivery Status Date - Ready for Integration": "customfield_20319", //Date Picker
        "Delivery Status Date - Integration"          : "customfield_20304", //Date Picker
        "Delivery Status Date - Testing QA"           : "customfield_20309", //Date Picker
        "Delivery Status Date - UAT"                  : "customfield_20310", //Date Picker
        "Delivery Status Date - Pilot Phase"          : "customfield_20306", //Date Picker
        "Delivery Status Date - Mass Rollout"         : "customfield_20305", //Date Picker
        "Delivery Status Date - Waiting for 3rd Party": "customfield_20311", //Date Picker
]

def applyDateFieldVisibility = { Map<String, String> fieldMap, String selectedValue, List<String> notRequiredValues = [], List<String> autoFillValues = [] ->
    fieldMap.each { optionValue, dateFieldId ->
        def dateField = getFieldById(dateFieldId)
        if (dateField == null) {
            log.warn("Field ${dateFieldId} not found on current screen")
            //return // field not on this screen, skip safely
        } else {
            if (optionValue == selectedValue) {
                dateField.setHidden(false)
                dateField.setRequired(!notRequiredValues.contains(optionValue))

                if (autoFillValues.contains(optionValue) && !dateField.getValue()) {
                    // Falls back to "now" only if the issue has no created
                    // date yet (i.e. this is the Create screen, not Edit).
                    def createdDate = underlyingIssue?.getCreated() ?: new Date()
                    // Format matches this field's configured dd.MM.yyyy display.
                    // If setFormValue doesn't take, test the exact method name
                    // for your ScriptRunner version, this API can shift slightly.
                    def formatted = new java.text.SimpleDateFormat("dd.MM.yyyy").format(createdDate)
                    dateField.setFormValue(formatted)
                }
            } else {
                dateField.setHidden(true)
                dateField.setRequired(false)
                // Not clearing the value, so history is preserved.
            }
        }
    }
}

try {
    applyDateFieldVisibility(salesDateFieldMap, salesValue, salesNotRequiredValues, salesAutoFillWithCreatedDate)
    applyDateFieldVisibility(deliveryDateFieldMap, deliveryValue)
}
catch (Exception e) {
    log.error("Sales/Delivery Behaviour failed", e)
}