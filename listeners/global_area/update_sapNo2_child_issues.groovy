package listeners.global_area

/**
 * update_sapNo2_child_issues
 *
 * @author chabrecek.anton
 * Created on 22/05/2025.
 *
 * name: Update SAP Order Number for Child Issues
 * events: issue update
 * applied to: GLOBAL
 */

import org.ofbiz.core.entity.GenericValue;
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.changehistory.ChangeHistory
import org.ofbiz.core.entity.GenericValue;
import com.atlassian.jira.model.ChangeItem
import com.atlassian.jira.issue.history.ChangeItemBean
import groovy.time.TimeCategory
import groovy.time.TimeDuration
import groovy.transform.Field

import com.atlassian.jira.issue.fields.config.FieldConfig
import com.atlassian.jira.issue.ModifiedValue;
import com.atlassian.jira.issue.index.IssueIndexingService;
import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.web.bean.PagerFilter
import com.atlassian.jira.bc.filter.SearchRequestService
import com.atlassian.jira.issue.customfields.manager.OptionsManager
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.customfields.option.Options

import com.atlassian.jira.user.util.UserUtil
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.search.SearchResults;

import static com.atlassian.jira.event.type.EventDispatchOption.DO_NOT_DISPATCH;

// field definitions
@Field CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager();
@Field CustomField cf_AgileTeam = customFieldManager.getCustomFieldObject("customfield_10403"); //Agile Team Field
@Field CustomField cf_EpicLink = customFieldManager.getCustomFieldObject("customfield_10001"); // EPIC Link CF ID
@Field CustomField cf_ParentLink = customFieldManager.getCustomFieldObject("customfield_10301"); // Parent Link Field Portfolio
@Field CustomField cf_SapOrderNoInput = customFieldManager.getCustomFieldObject("customfield_10225");
@Field CustomField cf_ProjectInformation = customFieldManager.getCustomFieldObject("customfield_15600");//"customfield_10805");
@Field String jqlSearchForParentLink = "cf[10301] = ";  // Parent Links

@Field String jqlSearch = "cf[10001] = "; // EPIC Link CF ID
@Field CustomField cf_EpicName = customFieldManager.getCustomFieldObject("customfield_10003"); // EPIC Name CF ID
@Field CustomField cf_AgileTeams = customFieldManager.getCustomFieldObject("customfield_10210");//Agile Teams Field v

@Field String jqlSearchUpdatedLast10Minutes = "updated >= -10m"; // updated within the last 10 Minutes

@Field long tenMinInMs = 600000l;
@Field String serviceUser = "admin.thomas";

@Field SearchService searchService = ComponentAccessor.getComponent(SearchService);
@Field IssueManager issueManager = ComponentAccessor.getIssueManager();
@Field ApplicationUser user = ComponentAccessor.getUserManager().getUserByKey(serviceUser)

//get all required issues
searchForLastUpdatedIssues();
//this was only for debuggin reasons

String searchForLastUpdatedIssues() {

    checkForSAPOrderNoAndProjectInformationUpdate(event.issue);
}

String checkForSAPOrderNoAndProjectInformationUpdate(Issue issue) {
    List<ChangeItemBean> changeItemBeans = ComponentAccessor.getChangeHistoryManager().getChangeItemsForField(issue, cf_SapOrderNoInput.getName());
    ChangeItemBean lastSapOrderNoItem = null;

    if (changeItemBeans != null && !changeItemBeans.isEmpty()) {
        lastSapOrderNoItem = changeItemBeans.last();
    }

    if (lastSapOrderNoItem != null) {
        TimeDuration td = TimeCategory.minus(new Date(), new Date(lastSapOrderNoItem.created.getTime()))
        // if the last epic change was within the last 10 minutes, we will reindex both, the old and the new Index
        if (td.toMilliseconds() <= tenMinInMs) {
            //log.warn "SAP Order NO (input) has been updated for: " + issue.getKey();
            searchForChildrenAndUpdate(issue);
        }
    }

    changeItemBeans = ComponentAccessor.getChangeHistoryManager().getChangeItemsForField(issue, cf_ProjectInformation.getName());
    ChangeItemBean lastProjectInformationItem = null;

    if (changeItemBeans != null && !changeItemBeans.isEmpty()) {
        lastProjectInformationItem = changeItemBeans.last();
    }

    if (lastProjectInformationItem != null) {
        TimeDuration td = TimeCategory.minus(new Date(), new Date(lastProjectInformationItem.created.getTime()))
        // if the last epic change was within the last 10 minutes, we will reindex both, the old and the new Index
        if (td.toMilliseconds() <= tenMinInMs) {
            //log.warn "SAP Order NO (input) has been updated for: " + issue.getKey();
            searchForChildrenAndUpdate(issue);
        }
    }

    reIndexIssue((MutableIssue) issue);
}

String searchForChildrenAndUpdate(Issue issue) {
    String issueKeyString = issue.getKey();
    //log.warn "Searching for more child issues for " + issueKeyString;
    List<MutableIssue> lastUpdatedIssues = null

    SearchService.ParseResult parseResult =  searchService.parseQuery(user, jqlSearchForParentLink + issueKeyString);
    //SearchService.ParseResult parseResult =  searchService.parseQuery(user, jqlSearchUpdatedLast10Minutes);


    log.warn "JQL: " + jqlSearchForParentLink + " " + issueKeyString;

    if (parseResult.isValid()) {
        SearchResults searchResult = searchService.search(user, parseResult.getQuery(), PagerFilter.getUnlimitedFilter())
        //log.warn "Found more child issues to re-index:" + searchResult.getIssues().toString();
        //log.warn "parseResult.getQuery(): " + parseResult.getQuery();
        // Transform issues from DocumentIssueImpl to the "pure" form IssueImpl (some methods don't work with DocumentIssueImps)
        lastUpdatedIssues = searchResult.results.collect { issueManager.getIssueObject(it.id) }
    } else {
        //log.warn "Invalid JQL: " + jqlSearch;
    }

    log.warn "Found more child issues to re-index:" + lastUpdatedIssues.toString();

    for (Issue childIssue in lastUpdatedIssues) {
        searchForChildrenAndUpdate(childIssue);
    }

    reIndexIssue((MutableIssue) issue);
}



void reIndexIssue(MutableIssue epicIssue) {
    IssueIndexingService issueIndexingService = ComponentAccessor.getComponent(IssueIndexingService)
    issueIndexingService.reIndex(epicIssue);
}