package fields.fuel_area

import java.lang.NullPointerException
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import aptis.plugins.epicSumUp.api.ProgressProvider
import aptis.plugins.epicSumUp.api.model.ProgressTimeResult
import org.apache.log4j.Logger
import java.math.BigDecimal
import java.math.RoundingMode

class FuelTimeCounter {

    public double getTimeRemaining(Issue issue,
                                   Long budgetFieldId,
                                   ProgressProvider progressProvider,
                                   Logger log) {
        try {
            def customFieldManager = ComponentAccessor.getCustomFieldManager()

            // Fetch field and handle missing values
            def timeBudgetField = customFieldManager.getCustomFieldObject(budgetFieldId)
            def timeBudgetValue = issue.getCustomFieldValue(timeBudgetField)

            // Convert to a valid double, handling BigDecimal and Number types safely
            double timeBudgetInSeconds = 0.0
            if (timeBudgetValue instanceof Number) {
                timeBudgetInSeconds = ((Number) timeBudgetValue).doubleValue()
            } else if (timeBudgetValue instanceof String) {
                timeBudgetInSeconds = Double.parseDouble(timeBudgetValue)
            }

            // Convert Epic Time Budget from seconds to hours
            double timeBudgetInHours = timeBudgetInSeconds / 3600.0

            // Ensure we have a valid user
            def loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
            if (loggedInUser == null) {
                log.warn("No authenticated user found. Aborting.")
                return 0.0 // Abort safely
            }

            // Fetch Time Spent via ProgressProvider
            ProgressTimeResult progress = progressProvider?.getTimeProgress(issue, loggedInUser)

            // Ensure valid progress object, handling nulls
            double timeSpentInHours = 0.0
            if (progress != null && progress.getTimeSpent() != null) {
                timeSpentInHours = progress.getTimeSpent() / 3600.0
            }

            // Calculate remaining time (negative if overspent)
            double remainingTime = timeBudgetInHours - timeSpentInHours

            // Return a proper double value
            //return remainingTime
            return Math.round(remainingTime * 100) / 100.0

        } catch (Exception e) {
            log.error("Unexpected error while calculating remaining time for issue: ${issue?.key}", e)
            return 0.0 // Fail safely
        }
    }
}