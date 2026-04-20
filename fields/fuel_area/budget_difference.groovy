package fields.fuel_area


// The goal of this script is to calculate the budget planning difference between LPM Initiative and Epic. 
//The LPM planning is done by the CTL and the Epic planning is done by the PO. 
//Based on the value of the difference between the two the planning status will be dereived. Vishal Choure 18.06.2025

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.web.bean.PagerFilter
import fields.fuel_area.FieldsUsage 

// import your class with field constants
def issueManager = ComponentAccessor.issueManager
def customFieldManager = ComponentAccessor.customFieldManager
def searchService = ComponentAccessor.getComponent(com.atlassian.jira.bc.issue.search.SearchService)
def user = ComponentAccessor.jiraAuthenticationContext.loggedInUser

// Use constants from the FieldsUsage class
def lpmTimeBudgetField = customFieldManager.getCustomFieldObject(FieldsUsage.LPM_TIME_BUDGET_FIELD_ID())
def epicTimeBudgetField = customFieldManager.getCustomFieldObject(FieldsUsage.EPIC_TIME_BUDGET_FIELD_ID())

// Get the LPM Time Budget value from the current issue
def lpmTimeBudget = issue.getCustomFieldValue(lpmTimeBudgetField) as Double ?: 0.0

// 1. Find all Features that have this LPM Initiative as their Parent Link
def jqlForFeatures = "\"Parent Link\" = ${issue.key} AND issuetype = Feature"
def parseResult1 = searchService.parseQuery(user, jqlForFeatures)
if (!parseResult1.isValid()) return null

def featureResults = searchService.search(user, parseResult1.query, PagerFilter.getUnlimitedFilter())
def featureIssues = featureResults.results

double totalEpicBudget = 0.0

// 2. For each Feature, find all Epics that have it as Parent Link
featureIssues.each { feature ->
    def jqlForEpics = "\"Parent Link\" = ${feature.key} AND issuetype = Epic"
    def parseResult2 = searchService.parseQuery(user, jqlForEpics)
    if (!parseResult2.isValid()) return

    def epicResults = searchService.search(user, parseResult2.query, PagerFilter.getUnlimitedFilter())
    def epics = epicResults.results

    // 3. Sum Epic Time Budgets
    epics.each { epic ->
        def budget = epic.getCustomFieldValue(epicTimeBudgetField) as Double ?: 0.0
        totalEpicBudget += budget
    }
}

// 4. Subtract and convert to person-days
def budgetDifference = (lpmTimeBudget - totalEpicBudget) / 28800

return budgetDifference