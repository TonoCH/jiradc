package validators

import com.atlassian.jira.component.ComponentAccessor

def issueService = ComponentAccessor.getIssueService()
def issueManager = ComponentAccessor.issueManager
def issueTypeName = "KVS Setting" 

def project = issue.projectObject

def existingSettings = issueManager.getIssueObjects(
    issueManager.getIssueIdsForProject(project.id)
).findAll { 
    it.issueType.name == issueTypeName && it.id != issue.id
}

if (existingSettings.isEmpty()) {
    log.warn("will return true")
    return true
} else {
    log.warn("will return false")
    return false
   //invalidInputException("Only one KVS Setting issue is allowed per project. Please edit the existing issue if you need to update the Cross Auditors Pool.")
}