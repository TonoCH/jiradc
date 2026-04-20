package workflows.kvs_audit

import com.opensymphony.workflow.InvalidInputException
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import kvs_audits.issueType.AuditPreparation
import kvs_audits.common.CustomFieldsConstants

def invalidInputException = new InvalidInputException()
    
if (!issue || !"Audit Preparation".equalsIgnoreCase(issue.issueType?.name)) {
    log.warn("is not Audit Preparation so return true")
    //invalidInputException.addError("is not Audit Preparation so return true")
    return true
}

def ap = new AuditPreparation(issue)

final String levelStr = ap.getAuditLevel()

if (!levelStr) {
    log.warn("Audit Level must be selected")
    invalidInputException.addError("Audit Level must be selected")
    return false
}

def cfm = ComponentAccessor.customFieldManager
def pcCf    = cfm.getCustomFieldObjectByName(AuditPreparation.PROFIT_CENTER_FIELD_NAME)     // Single Issue Picker
def pcVal   = pcCf ? issue.getCustomFieldValue(pcCf) : null

boolean isL3 = CustomFieldsConstants.AUDIT_LEVEL_3.equalsIgnoreCase(levelStr)
boolean isL4 = CustomFieldsConstants.AUDIT_LEVEL_4.equalsIgnoreCase(levelStr)

if (isL3 || isL4) {
    
    if (!pcCf) {
        log.error("Configuration error: custom field '${AuditPreparation.PROFIT_CENTER_FIELD_NAME}' is missing on the screen.")
        invalidInputException.addError("Configuration error: custom field '${AuditPreparation.PROFIT_CENTER_FIELD_NAME}' is missing on the screen.")
        return false;
    }
    if (pcVal == null) {
        invalidInputException.addError("For ${levelStr}, '${AuditPreparation.PROFIT_CENTER_FIELD_NAME}' is required.")
        log.error("For ${levelStr}, '${AuditPreparation.PROFIT_CENTER_FIELD_NAME}' is required.")
        return false;
    }

    return true
}

log.warn("only Level 3 and Level 4 are handled return true")
return true