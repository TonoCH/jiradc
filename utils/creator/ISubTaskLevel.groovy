package utils.creator

import com.atlassian.jira.issue.MutableIssue

/**
 * ISubTaskLevel
 *
 * @author chabrecek.anton
 * Created on 9. 7. 2025.
 */
interface ISubTaskLevel {
    MutableIssue getParentIssue()
}