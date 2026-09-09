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
def changedField = getFieldChanged()

def salesValue = salesStatusField?.getValue()?.toString()?.trim()
def deliveryValue = deliveryStatusField?.getValue()?.toString()?.trim()

// Map: option value (must match dropdown text exactly) -> date field ID

// "To Do" is shown but not required, and gets auto-filled with the
// issue's creation date instead of needing manual entry.
def salesNotRequiredValues = ["To Do"]
def salesAutoFillWithCreatedDate = ["To Do"]

def salesDateFieldMap = [
        "To Do"               : ["Sales Status Date - To Do", "customfield_20318"],
        "Quotation Irrelevant": ["Sales Status Date - Quotation Irrelevant", "customfield_20317"],
        "Quotation Provided"  : ["Sales Status Date - Quotation Provided", "customfield_20316"],
        "Order Signed"        : ["Sales Status Date - Order Signed", "customfield_20315"],
        "Invoiced 45%"        : ["Sales Status Date - Invoiced 45%", "customfield_20313"],
        "Invoiced 50%"        : ["Sales Status Date - Invoiced 50%", "customfield_20314"],
        "Invoiced 100%"       : ["Sales Status Date - Invoiced 100%", "customfield_20312"]
]

def deliveryDateFieldMap = [
        "In Refinement"        : ["Delivery Status Date - In Refinement", "customfield_20303"],
        "Reviewed"             : ["Delivery Status Date - Reviewed", "customfield_20307"],
        "Signed off S&B"       : ["Delivery Status Date - Signed off S&B", "customfield_20308"],
        "Estimates"            : ["Delivery Status Date - Estimates", "customfield_20302"],
        "Awaiting PI Planning" : ["Delivery Status Date - Awaiting PI Planning", "customfield_20300"],
        "Development"          : ["Delivery Status Date - Development", "customfield_20301"],
        "Ready for Integration": ["Delivery Status Date - Ready for Integration", "customfield_20319"],
        "Integration"          : ["Delivery Status Date - Integration", "customfield_20304"],
        "Testing QA"           : ["Delivery Status Date - Testing QA", "customfield_20309"],
        "UAT"                  : ["Delivery Status Date - UAT", "customfield_20310"],
        "Pilot Phase"          : ["Delivery Status Date - Pilot Phase", "customfield_20306"],
        "Mass Rollout"         : ["Delivery Status Date - Mass Rollout", "customfield_20305"],
        "Waiting for 3rd Party": ["Delivery Status Date - Waiting for 3rd Party", "customfield_20311"]
]

log.warn("Sales value = [${salesValue}]")

def applyDateFieldVisibility = {
    Map<String, List<String>> fieldMap,
    String selectedValue,
    Collection<String> notRequiredValues = [],
    Collection<String> autoFillValues = [] ->

        fieldMap.each { String optionValue, List<String> data ->
            String fieldId = data[1]
            def dateField = getFieldById(fieldId)
            boolean selected = optionValue == selectedValue

            dateField.setRequired(selected && !notRequiredValues.contains(optionValue))
            dateField.setHidden(!selected)

            if (selected && autoFillValues.contains(optionValue) && !dateField.getValue()) {
                Date createdDate = underlyingIssue?.created ?: new Date()
                String formattedDate =
                        new java.text.SimpleDateFormat("dd.MM.yyyy").format(createdDate)

                dateField.setFormValue(formattedDate)
            }
        }
}

try {
    switch (getFieldChanged()) {

        case "customfield_18300":
            applyDateFieldVisibility(salesDateFieldMap,salesValue,salesNotRequiredValues,salesAutoFillWithCreatedDate)
            break

        case "customfield_18301":
            applyDateFieldVisibility(deliveryDateFieldMap,deliveryValue)
            break

        default:
            // Initialiser
            applyDateFieldVisibility(salesDateFieldMap,salesValue,salesNotRequiredValues,salesAutoFillWithCreatedDate)
            applyDateFieldVisibility(deliveryDateFieldMap,deliveryValue)
    }
}
catch (Exception e) {
    log.error("Sales/Delivery Behaviour failed", e)
}