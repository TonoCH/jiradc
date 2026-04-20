package listeners.global_area

/**
 * reindex_sapNo2_related_issues
 *
 * @author chabrecek.anton
 * Updated on 22/05/2025.
 *
 * name: Re Index Issue and related issues after SAP No 2 has been updated
 * events: issue update
 * applied to: GLOBAL
 */

import org.ofbiz.core.entity.GenericValue;
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.changehistory.ChangeHistory
import org.ofbiz.core.entity.GenericValue;
import com.atlassian.jira.model.ChangeItem
import com.atlassian.jira.issue.history.ChangeItemBean
import groovy.transform.Field

import com.atlassian.jira.issue.index.IssueIndexingService;
import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.web.bean.PagerFilter
import com.atlassian.jira.bc.filter.SearchRequestService

import com.atlassian.jira.user.util.UserUtil
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.CustomFieldManager;

import groovy.time.TimeCategory
import groovy.time.TimeDuration

import static com.atlassian.jira.event.type.EventDispatchOption.DO_NOT_DISPATCH;

// field definitions
@Field CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager();
@Field String jqlSearch = "cf[10001] = "; // EPIC Link CF ID
@Field CustomField cf_EpicName = customFieldManager.getCustomFieldObject("customfield_10003"); // EPIC Name CF ID
@Field CustomField cf_SapNo2 = customFieldManager.getCustomFieldObject("customfield_10225"); //Agile Teams Field
@Field CustomField cf_PrjRef = customFieldManager.getCustomFieldObject("customfield_10501"); //Agile Teams Field
@Field boolean reindex = false;

// check if SAP NO 2 has been updated
List<ChangeItemBean> changeItemBeansSapNo2 = ComponentAccessor.getChangeHistoryManager().getChangeItemsForField(event.issue, cf_SapNo2.getName());
ChangeItemBean changeItemBeansSAPNo2Item = null;

if (changeItemBeansSapNo2.size() > 0) {
    changeItemBeansSAPNo2Item = changeItemBeansSapNo2.last();
}

if (changeItemBeansSAPNo2Item != null) {
    TimeDuration td = TimeCategory.minus(new Date(), new Date(changeItemBeansSAPNo2Item.created.getTime()))
    // if the last epic change was within the last 2 seconds, we will reindex both, the old and the new Index
    if (td.toMilliseconds() < 2000) {
        reindex = true;
    }
}

// check if SAP NO 2 has been updated
List<ChangeItemBean> changeItemBeansPrjRef = ComponentAccessor.getChangeHistoryManager().getChangeItemsForField(event.issue, cf_PrjRef.getName());
ChangeItemBean changeItemBeansPrjRefItem = null;

if (changeItemBeansPrjRef.size() > 0) {
    changeItemBeansPrjRefItem = changeItemBeansPrjRef.last();
}

if (changeItemBeansPrjRefItem != null) {
    TimeDuration td = TimeCategory.minus(new Date(), new Date(changeItemBeansPrjRefItem.created.getTime()))
    // if the last epic change was within the last 2 seconds, we will reindex both, the old and the new Index
    if (td.toMilliseconds() < 2000) {
        reindex = true;
    }
}

if (reindex) {
    reIndexRelatedIssues((Issue) event.issue);
}


String reindexIssuesInEpic(long epicId) {
    SearchService searchService = ComponentAccessor.getComponent(SearchService);
    UserUtil userUtil = ComponentAccessor.getUserUtil()
    ApplicationUser user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
    IssueManager issueManager = ComponentAccessor.getIssueManager()

    MutableIssue epicIssue = ComponentAccessor.getIssueManager().getIssueObject(epicId);

    // fist of all check all the local stuff
    Object epicNameString = epicIssue.getCustomFieldValue(cf_EpicName);
    jqlSearch += "'" + epicNameString + "'";

    List<MutableIssue> issuesInEpic = null

    SearchService.ParseResult parseResult =  searchService.parseQuery(user, jqlSearch)

    if (parseResult.isValid()) {
        def searchResult = searchService.search(user, parseResult.getQuery(), PagerFilter.getUnlimitedFilter())
        // Transform issues from DocumentIssueImpl to the "pure" form IssueImpl (some methods don't work with DocumentIssueImps)
        issuesInEpic = searchResult.issues.collect {issueManager.getIssueObject(it.id)}
    } else {
        return "Invalid JQL: " + jqlSearch;
    }
    reIndexAllCollectedIssues(issuesInEpic);
}

void reIndexAllCollectedIssues(List<MutableIssue> collectedIssues) {
    for (collectedIssue in collectedIssues) {
        reIndexRelatedIssues(collectedIssue)
    }
}

void reIndexRelatedIssues(Issue issue) {
    reIndexIssue(issue);

    for (subtask in issue.getSubTaskObjects()) {
        reIndexIssue(subtask);
    }

    if (issue.getIssueType().getName().equals("Epic")) {
        reindexIssuesInEpic(issue.getId());
    }
}

void reIndexIssue(Issue issue) {
    IssueIndexingService issueIndexingService = ComponentAccessor.getComponent(IssueIndexingService)

    issueIndexingService.reIndex(issue);
}