package postfunctions

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.customfields.impl.CascadingSelectCFType
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.customfields.option.Options
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.project.Project
import com.atlassian.jira.user.ApplicationUser
import constants.CustomFieldNameConstants
import constants.FieldsValueConstants
import constants.IssueTypeIdConstants
import constants.ProjectIdConstants
import org.apache.log4j.Level
import org.apache.log4j.Logger
import utils.CustomFieldUtil
import utils.OptionUtil

Logger log = Logger.getLogger("PF_cloneIssueOnCreate")
log.setLevel(Level.INFO)

MutableIssue currentIssue = issue

log.info("Start - ${currentIssue.key}" )

if (!(currentIssue.projectId in [ProjectIdConstants.PROBLEM, ProjectIdConstants.CORE_BUG])) return

def comment = transientVars?.comment as String

ApplicationUser currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

CustomField cfTargetProject = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.TARGET_PROJECT)
CustomField cfTargetProductArea = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.TARGET_PRODUCT_AREA)
CustomField cfProductArea = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PRODUCT_AREA)
CustomField cfParentBug = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PARENT_BUG)
CustomField cfChildBugs = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.CHILD_BUGS)
CustomField cfProjectInformation = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.PROJECT_INFORMATION)
CustomField cfBugOrigin = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.BUG_ORIGIN)
CustomField cfSapOrderNo = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.SAP_ORDER_NO)
CustomField cfSapOrderNoInput = CustomFieldUtil.getCustomFieldByName(CustomFieldNameConstants.SAP_ORDER_NO_INPUT)

// get target project from current issue dialog
def targetProject = currentIssue.getCustomFieldValue(cfTargetProject) as Project
log.info("targetProject - ${targetProject}" )

if (!targetProject) return

def parentIssue = currentIssue

String parentCommentStr = ""

// create new Issue
MutableIssue newIssue = ComponentAccessor.issueFactory.getIssue()

newIssue.setProjectObject(targetProject)
newIssue.setSummary(parentIssue.summary)
newIssue.setDescription(parentIssue.description)
newIssue.setIssueTypeId(IssueTypeIdConstants.BUG)
newIssue.setReporter(currentUser)
newIssue.setPriority(parentIssue.priority)
def affectedVersions = targetProject.versions.findAll{ it.name in currentIssue?.affectedVersions*.name }
if (affectedVersions) {
    newIssue.setAffectedVersions(affectedVersions)
} else {
    addComment("*NOTE*: affected versions: ${currentIssue?.affectedVersions*.name.join(",")} - not found in project: ${targetProject.key} - ${targetProject.name}", comment)
}
// copy CFs
if (parentIssue.getCustomFieldValue(cfProjectInformation)) {
    newIssue.setCustomFieldValue(cfProjectInformation, parentIssue.getCustomFieldValue(cfProjectInformation))
} else {
    newIssue.setCustomFieldValue(cfSapOrderNoInput,  parentIssue.getCustomFieldValue(cfSapOrderNo))
}
newIssue.setCustomFieldValue(cfBugOrigin, OptionUtil.getOptionByName(newIssue, cfBugOrigin, parentIssue.getCustomFieldValue(cfBugOrigin)?.value))
// add parent bug
newIssue.setCustomFieldValue(cfParentBug, parentIssue)

// Product Area
String productAreaComment
def hashMapEntries = currentIssue.getCustomFieldValue(cfTargetProductArea) as HashMap<String, Option>
if (hashMapEntries != null) {
    Option parentOption = hashMapEntries.get(CascadingSelectCFType.PARENT_KEY)
    Option childOption = hashMapEntries.get(CascadingSelectCFType.CHILD_KEY)
    Options options = OptionUtil.getOption(targetProject, newIssue, cfProductArea)
    Option newParentOption = options.find { it.value == parentOption?.value }
    Option newChildOption = newParentOption?.childOptions?.find { it.value == childOption?.value }

    if (newChildOption) {
        def newOptions = new HashMap()
        newOptions.put(CascadingSelectCFType.PARENT_KEY, newParentOption)
        newOptions.put(CascadingSelectCFType.CHILD_KEY, newChildOption)
        newIssue.setCustomFieldValue(cfProductArea, newOptions)
    } else {
        productAreaComment = "*NOTE*: invalid product area: ${parentOption?.value} - ${childOption?.value}"
    }
}

// Create new Issue in DB
newIssue = (MutableIssue) ComponentAccessor.getIssueManager().createIssueObject(currentUser, newIssue)

// Create comment
String commentStr = addComment("issue child-cloned from ${parentIssue.key}", comment)
ComponentAccessor.getCommentManager().create(newIssue, currentUser, commentStr, false)
    
parentCommentStr = addComment("issue child-cloned to ${newIssue.key}", comment)

def childrenIssue = parentIssue.getCustomFieldValue(cfChildBugs) as List<Issue>
if (!childrenIssue) {
    childrenIssue = new ArrayList<Issue>()
}
childrenIssue.add(newIssue)
parentIssue.setCustomFieldValue(cfChildBugs, childrenIssue)

// if parent issue is not same as current must be re-saved
if (currentIssue.id != parentIssue.id) {
    log.info("parent issue is not same as current must be re-saved" )
    ComponentAccessor.getIssueManager().updateIssue(currentUser, parentIssue, EventDispatchOption.ISSUE_UPDATED, false)
}

if (productAreaComment) parentCommentStr += "\n${productAreaComment}"
if (parentCommentStr.size() > 0) {
    ComponentAccessor.getCommentManager().create(parentIssue, currentUser, parentCommentStr, false)
}

//clean up and save current issue
currentIssue.setCustomFieldValue(cfTargetProject, null)
currentIssue.setCustomFieldValue(cfTargetProductArea, null)
ComponentAccessor.getIssueManager().updateIssue(currentUser, currentIssue, EventDispatchOption.ISSUE_UPDATED, false)

static String addComment(String defaultComment, String commentFromDialog) {
    if (commentFromDialog) {
        defaultComment += "\n\n${commentFromDialog}"
    }
    return defaultComment
}