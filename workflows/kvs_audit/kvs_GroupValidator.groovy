package workflows.kvs_audit

import com.atlassian.jira.component.ComponentAccessor

def currentUser = ComponentAccessor.jiraAuthenticationContext.loggedInUser
if (!currentUser) {
    return false
}

def groupManager = ComponentAccessor.groupManager

// Check membership in the desired group (e.g., "group1")
return groupManager.isUserInGroup(currentUser, "kvs-audit")