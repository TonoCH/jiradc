package listeners.fuel_area

import com.atlassian.jira.issue.fields.CustomField
import utils.CustomFieldUtil;
import utils.MyBaseUtil;
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.issue.customfields.option.Option

//check fields existence
MyBaseUtil myBaseUtil = new MyBaseUtil();
myBaseUtil.validateFieldsExist(FieldNameConstants.class)

//FIELDS WHICH WILL WE NEED, NEEDS TO BE DECLARED IN FieldNameConstants !!!
CustomField total_planned_capacity_epic = CustomFieldUtil.getCustomFieldByName(FieldNameConstants.TOTAL_PLANNED_CAPACITY_EPIC);
CustomField total_planned_used_epic = CustomFieldUtil.getCustomFieldByName(FieldNameConstants.TOTAL_CAPACITY_USED_EPIC);
CustomField total_capacity_remaining_epic = CustomFieldUtil.getCustomFieldByName(FieldNameConstants.TOTAL_CAPACITY_REMAINING_EPIC);
CustomField time_budget_epic = CustomFieldUtil.getCustomFieldByName(FieldNameConstants.TIME_BUDGET_EPIC);
CustomField total_time_spent_in_epic = CustomFieldUtil.getCustomFieldByName(FieldNameConstants.TOTAL_TIME_SPENT_IN_EPIC);
CustomField remaining_budget_epic = CustomFieldUtil.getCustomFieldByName(FieldNameConstants.REMAINING_BUDGET_EPIC);
CustomField team = CustomFieldUtil.getCustomFieldByName(FieldNameConstants.TEAM);
CustomField commitment = CustomFieldUtil.getCustomFieldByName(FieldNameConstants.COMMITMENT);

def Issue issue = event.issue
def allowedProjects = ["TESNANPROJ", "TESNANPROJ2", "TESNANPROJ3"] //EACH PROJECT MUST BE ALLOWED ALSO IN SCRIPT
def issueProjectKey = issue.getProjectObject().getKey()
def mainQuery = "project = TESNANPROJ AND " + commitment.getName() + " = " + "Committed";

if (!allowedProjects.contains(issueProjectKey)) {
    log.error("Project key doesnt belongs to allowed project: $issueProjectKey")
    return;
} 

def commitmentValue = issue.getCustomFieldValue(commitment)
String commitmentText = commitmentValue instanceof Option ? commitmentValue.getValue() : commitmentValue?.toString()

// WE PROCESSING ONLY IF CURRENT ISSUE IS EPIC AND COMMITED VALUE SET
if(issue.getIssueType().getName() == "Epic" && commitmentText == "committed"){

    def teamValue = issue.getCustomFieldValue(team)
    if(teamValue != null){  
         mainQuery += " AND " + team.getName() + " = " + teamValue.toString();
        
        List<Issue> results = myBaseUtil.findIssues(mainQuery)

        //YOUR LOGIC HERE
        //All issues is inside results, be carrefull also current issue is in there
        //Add rest of logic here i mean counting budgests and other staff
        //dont forget on end call reindexing for updated issue which will be changed

        //CALL INDEXATION
        //myBaseUtil.reIndexIssue(issueToReindex);
    }
}
else{

    //   LOG SOMETHIG LIKE THIS 
    //  "issue must by an epic and Commitment needs to be set as Committed"
}
