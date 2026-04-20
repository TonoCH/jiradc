/*
    UPDATE ISSUES - RETURN PREVIOUSLY SEAT ASSIGNEE
    1.Search all issues by jql query
    2.Select only issues which has previously set Assignee
    3.For selected issues reset previously set Assignee
    - For all operation must be created some informations log what was changed
 */
import java.lang.Iterable
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.history.ChangeItemBean
import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.jql.parser.JqlQueryParser;
import com.atlassian.jira.web.bean.PagerFilter;
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption

searchAndSetAssignee();

public String searchAndSetAssignee() {
    def sb = new StringBuffer()
    def changeHistoryManager = ComponentAccessor.changeHistoryManager
    def userManager = ComponentAccessor.getUserManager()
    def issueManager = ComponentAccessor.issueManager

    def jqlQuery = "category = SST AND type in (Improvement, Sub-task, Task) AND assignee is EMPTY AND updated >= '2023/08/18' and assignee changed BY 'Moellgaard' AND status != Closed AND status != Done ORDER BY updated ASC"
    //def jqlQuery = 'project = Anton_scrum';
    int counterHasAssignee = 0, counterEmptyAssignee = 0;
    List<Issue> issues = findIssues(jqlQuery)

    issues.each { issue ->
        List<ChangeItemBean> changeItems = changeHistoryManager.getChangeItemsForField(issue, "assignee");

        if(changeItems == null || changeItems.empty){
            counterEmptyAssignee++;
        }
        else{
            ChangeItemBean changeItem = changeItems.last();

            if(changeItem.getCreated().dateString == "8/18/23" && changeItem.toString == null){
            //if(changeItem.toString == null){
                Iterable<ApplicationUser> users = ComponentAccessor.userSearchService.findUsersByFullName(changeItem.fromString)
                if(users.size()>1 || users.size() == 0){
                    if (users.size() > 1) {
                        sb.append("ERROR: For isseu key:"+issue.getKey()+" there is more than one user with full name like:"+changeItem.fromString+"<br />");
                    } 
                    else if (users.isEmpty()) {
                        sb.append("ERROR: For isseu key:"+issue.getKey()+" there is no user find from full name:"+changeItem.fromString+"<br />");
                    }
                }            
                else{
                    //ApplicationUser user = users.first();

                    def issueForUpd = issueManager.getIssueByCurrentKey(issue.getKey())
                    issueForUpd.setAssignee(user);
                    
                    ApplicationUser jiraBot = userManager.getUserByName("jira.bot");
                    //issueManager.updateIssue(jiraBot, issueForUpd, EventDispatchOption.ISSUE_UPDATED, false)
                    sb.append("User "+changeItem.fromString+" is set again as assignee in issueKey:"+issueForUpd.getKey()+" in project name:"+issueForUpd.getProjectObject().getName()+"<br />");
                    counterHasAssignee++;                    
                }
            }
        }
    }

    sb.append("counterHasAssignee:"+counterHasAssignee +" and counterEmptyAssignee:"+counterEmptyAssignee+"<br />");
    return sb.toString();
}

private List<Issue> findIssues(String jqlQuery){
    //def issueManager = ComponentAccessor.issueManager
    def user = ComponentAccessor.jiraAuthenticationContext.user
    def jqlQueryParser = ComponentAccessor.getComponent(JqlQueryParser.class)
    def searchService = ComponentAccessor.getComponent(SearchService)
    def query = jqlQueryParser.parseQuery(jqlQuery)
    List<Issue> results = searchService.search(user,query, PagerFilter.getUnlimitedFilter()).getResults()

    return results;
}

/*protected void logThis(String logMessage) {
    log.info "Log : ${logMessage} <br />"
}*/