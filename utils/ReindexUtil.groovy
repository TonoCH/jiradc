package utils

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.index.IssueIndexingService

class ReindexUtil {

    static void performReIndex(Issue issue){
        IssueIndexingService issueIndexingService = ComponentAccessor.getComponent(IssueIndexingService.class)
        issueIndexingService.reIndex(issue)
    }
}
