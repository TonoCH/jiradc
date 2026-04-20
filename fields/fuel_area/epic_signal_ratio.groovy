package fields.fuel_area
//Goal: This is scripted field to derive the signal ratio that measures how much of the allocated budget remains relative to a 20% threshold, which helps in tracking budget consumption. Vishal Choure 17.03.2025.
import org.apache.log4j.Logger
import com.atlassian.jira.component.ComponentAccessor
import fields.fuel_area.SignalRatioCalculator // Importing the class from the package

// Initialize Logger
def log = Logger.getLogger("SignalRatioScript")

// Ensure the issue is available (ScriptRunner provides the 'issue' variable automatically)
if (!issue) {
    log.error("Issue is null in scripted field execution")
    return null
}

// Instantiate the SignalRatioCalculator class
def calculator = new SignalRatioCalculator()

// Calculate Signal Ratio
double signalRatio = calculator.calculateSignalRatio(issue, FieldsUsage.EPIC_TIME_BUDGET_FIELD_ID(), FieldsUsage.EPIC_TIME_REMAINING_FIELD_ID(), log)

// Log the calculated value
log.info("Calculated Signal Ratio for issue ${issue.key}: ${signalRatio}")

// Return the computed Signal Ratio (this will be the value displayed in the scripted field)
return signalRatio
