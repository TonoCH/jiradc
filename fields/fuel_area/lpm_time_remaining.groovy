package fields.fuel_area

// Goal: This is a scripted field to derive the total time remaining in the issue
// by subtracting total time spent from the original budget. 
// Author: Vishal Choure | Date: 17.03.2025

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.onresolve.scriptrunner.runner.customisers.PluginModule
import com.onresolve.scriptrunner.runner.customisers.WithPlugin
import aptis.plugins.epicSumUp.api.ProgressProvider
import aptis.plugins.epicSumUp.api.model.ProgressTimeResult
import fields.fuel_area.FuelTimeCounter
import fields.fuel_area.FieldsUsage  // Import FieldsUsage class
import org.apache.log4j.Logger

@WithPlugin("aptis.plugins.epicSumUp")
@PluginModule
ProgressProvider progressProvider

Logger log = Logger.getLogger("lpm_time_remaining")

// Using the LPM Time Budget Field ID from FieldsUsage class
Long timeBudgetFieldId = FieldsUsage.LPM_TIME_BUDGET_FIELD_ID()

return new FuelTimeCounter().getTimeRemaining(issue, timeBudgetFieldId, progressProvider, log)
