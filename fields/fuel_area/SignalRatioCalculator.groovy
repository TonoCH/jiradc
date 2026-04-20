package fields.fuel_area

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import org.apache.log4j.Logger
import java.math.BigDecimal

class SignalRatioCalculator {

    public Double calculateSignalRatio(Issue issue, Long timeBudgetFieldId, Long remainingTimeFieldId, Logger log) {
        try {
            def customFieldManager = ComponentAccessor.getCustomFieldManager()

            // Get the custom fields
            CustomField timeBudgetField = customFieldManager.getCustomFieldObject(timeBudgetFieldId)
            CustomField remainingTimeField = customFieldManager.getCustomFieldObject(remainingTimeFieldId)

            // Retrieve field values from the issue
            Object timeBudgetValue = issue.getCustomFieldValue(timeBudgetField)
            Object remainingTimeValue = issue.getCustomFieldValue(remainingTimeField)

            // Convert values safely to double (Handling BigDecimal, Integer, Long, Double, and null)
            double totalBudget = 0.0
            if (timeBudgetValue instanceof Number) {
                totalBudget = (timeBudgetValue as Number).doubleValue()
            }

            double remainingBudget = 0.0
            if (remainingTimeValue instanceof Number) {
                remainingBudget = (remainingTimeValue as Number).doubleValue() * 3600
            }

            // Prevent division by zero
            if (totalBudget == 0.0) {
                log.warn("Invalid values detected: timeBudget=${totalBudget}, remainingTime=${remainingBudget}")
                return 0.0
            }

            // Calculate threshold (20% of total budget)
            double threshold = 0.2 * totalBudget

            // Compute the Signal Ratio
            double signalRatio = 0.0
            if (remainingBudget > threshold) {
                signalRatio = (remainingBudget - threshold) / totalBudget
            } else if (remainingBudget < 0) {
                signalRatio = remainingBudget / threshold
            }

            return signalRatio

        } catch (Exception e) {
            log.error("Error calculating Signal Ratio for issue: ${issue?.key}", e)
            return 0.0
        }
    }
}
