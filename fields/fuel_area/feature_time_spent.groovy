package fields.fuel_area

//Goal: This is a scripted field to derive the total time spent in the issue including the portfolio children issues till the subtasks. Vishal Choure 17.01.2025
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.user.ApplicationUser
import com.onresolve.scriptrunner.runner.customisers.PluginModule
import com.onresolve.scriptrunner.runner.customisers.WithPlugin
import aptis.plugins.epicSumUp.api.ProgressProvider
import aptis.plugins.epicSumUp.api.model.ProgressTimeResult

@WithPlugin("aptis.plugins.epicSumUp")
@PluginModule
ProgressProvider progressProvider

try {
    // Get the current logged-in user (renamed to avoid masking issue)
    ApplicationUser loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
    if (loggedInUser == null) {
        return 0.0 // Return 0.0 if there's no authenticated user
    }

    // Fetch time progress for the issue
    ProgressTimeResult progress = progressProvider?.getTimeProgress(issue, loggedInUser)

    // Ensure progress object is valid before proceeding
    if (progress == null) {
        return 0.0 // Return 0.0 to prevent null pointer exceptions
    }

    long timeSpentInSeconds = (long) progress.getTimeSpent()

    // Convert seconds to hours (double precision)
    double timeSpentInHours = timeSpentInSeconds / 3600.0

    // Return as a plain number (usable for further calculations)
    return timeSpentInHours

} catch (Exception e) {
    // Log the error for debugging purposes
    log.error("Error calculating time spent for issue: " + issue.getKey(), e)
    return 0.0 // Fail gracefully by returning 0.0
}
