package validators

import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.project.Project
import com.opensymphony.workflow.InvalidInputException
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil

Logger log = Logger.getLogger("VAL_checkCloneIssueAction")
log.setLevel(Level.INFO)

Issue issue = issue

log.info("Start - ${issue.key}" )

CustomField cfTargetProject = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.TARGET_PROJECT)
CustomField cfCloningType = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.CLONING_TYPE)
CustomField cfParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PARENT_BUG)

String cloningType = issue.getCustomFieldValue(cfCloningType)

if (!cloningType) {
    invalidInputException = new InvalidInputException()
    invalidInputException.addError("\"Cloning Type\" field not specified")
    return
}

Project targetProject = (Project) issue.getCustomFieldValue(cfTargetProject)

if (!targetProject) {
    invalidInputException = new InvalidInputException()
    invalidInputException.addError("\"Target Project\" field not specified")
    return
}

boolean isSiblingType = (cloningType == "Sibling")

if (!isSiblingType && issue.projectId == targetProject.id) {
    invalidInputException = new InvalidInputException()
    invalidInputException.addError("Can not child-clone issue ${issue.key} to the same project \"${issue.projectObject.name}\"")
    return
}

Issue parentBug = (Issue) issue.getCustomFieldValue(cfParentBug) 
if(isSiblingType && !parentBug) {
    invalidInputException = new InvalidInputException()
    invalidInputException.addError("Can not sibling-clone issue ${issue.key} without parent bug")
    return
}

// all is OK
log.info("End - passed")
return true