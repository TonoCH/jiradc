package validators

import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.project.Project
import com.opensymphony.workflow.InvalidInputException
import constants.CustomFieldNameConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil

Logger log = Logger.getLogger("VAL_checkChangeParentIssueAction")
log.setLevel(Level.INFO)

Issue issue = issue

log.info("Start - ${issue.key}" )

CustomField cfTargetParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.TARGET_PARENT_BUG)

Issue targetParentBug = issue.getCustomFieldValue(cfTargetParentBug)

if (targetParentBug.projectId == issue.projectId) {
    invalidInputException = new InvalidInputException()
    invalidInputException.addError("\"Target Parent Project\" cannot be in the same project as child issue")
    return
}