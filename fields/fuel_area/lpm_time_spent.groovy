package fields.fuel_area

//Goal:This is a scripted field to dereive the total time spent in the issue including the portfolio children issues till the subtasks. Vishal Choure. 17.03.2025 
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.user.ApplicationUser
import com.onresolve.scriptrunner.runner.customisers.PluginModule
import com.onresolve.scriptrunner.runner.customisers.WithPlugin
import aptis.plugins.epicSumUp.api.ProgressProvider
import aptis.plugins.epicSumUp.api.model.ProgressTimeResult
import com.atlassian.jira.util.thread.JiraThreadLocalUtil
import org.apache.log4j.Logger

@WithPlugin("aptis.plugins.epicSumUp")
@PluginModule
ProgressProvider progressProvider

def logger = Logger.getLogger("fields.fuel_area.lpm_time_spent")
JiraThreadLocalUtil threadLocalUtil = ComponentAccessor.getComponent(JiraThreadLocalUtil)

try {
    // Get the current logged-in user (renamed to avoid masking issue)
    ApplicationUser loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
    if (loggedInUser == null) {
        return 0.0 // Return 0.0 if there's no authenticated user
    }

    threadLocalUtil.preCall()
    try {
        ProgressTimeResult progress = progressProvider?.getTimeProgress(issue, loggedInUser)
        if (progress == null) {
            return 0.0d
        }

        long timeSpentInSeconds = (long) progress.getTimeSpent()
        return timeSpentInSeconds / 3600.0d
    } finally {
        threadLocalUtil.postCall(logger, null)
    }


} catch (Exception e) {
    // Log the error for debugging purposes
    log.error("Error calculating time spent for issue: " + issue.getKey(), e)
    return 0.0 // Fail gracefully by returning 0.0
}
