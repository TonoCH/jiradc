package listeners.global_area

/**
 * all_issue_created
 *
 * @author chabrecek.anton
 * updated on 22/05/2025.
 *
 * name: For all "Issue Created"
 * events: issue create
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

import static com.atlassian.jira.event.type.EventDispatchOption.DO_NOT_DISPATCH;

// field definitions
@Field CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager();
@Field CustomField cf_AgileTeam = customFieldManager.getCustomFieldObject("customfield_10403"); //Agile Team Field
@Field CustomField cf_EpicLink = customFieldManager.getCustomFieldObject("customfield_10001"); // EPIC Link CF ID
@Field String jqlSearch = "cf[10001] = "; // EPIC Link CF ID
@Field CustomField cf_EpicName = customFieldManager.getCustomFieldObject("customfield_10003"); // EPIC Name CF ID
@Field CustomField cf_AgileTeams = customFieldManager.getCustomFieldObject("customfield_10210"); //Agile Teams Field
@Field boolean noEpicIndexTriggered = true;


Issue epicIssue = (Issue) event.issue.getCustomFieldValue(cf_EpicLink);

if (epicIssue != null) {
    checkAgileTeamsInEpic(epicIssue.getId());
}

//this was only for debuggin reasons
//ComponentAccessor.commentManager.create(event.issue, event.user, "YESSSSSSS " + epicIssue.getId().toString(), true)

String checkAgileTeamsInEpic(long epicId) {
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
        //update becouse error :  groovy.lang.MissingPropertyException: No such property: issues for class: com.atlassian.jira.issue.search.SearchResults
        if (searchResult && searchResult.getResults()) {
            // Transform issues from DocumentIssueImpl to the "pure" form IssueImpl (some methods don't work with DocumentIssueImps)
            issuesInEpic = searchResult.results.collect { issueManager.getIssueObject(it.id) }
        }
        else {
            return "No search results found for query: " + parseResult.getQuery()
        }
    } else {
        return "Invalid JQL: " + jqlSearch;
    }

    ArrayList<String> agileTeams = new ArrayList();
    Collection<Option> cfOptions = (Collection<Option>) epicIssue.getCustomFieldValue(cf_AgileTeams);
    Collection<Option> options = new ArrayList();
    String test = "";

    Options agileTeamsOptions = ComponentAccessor.getOptionsManager().getOptions(cf_AgileTeams.getRelevantConfig(epicIssue));

    issuesInEpic.each() {collectedIssue->

        if (collectedIssue.getCustomFieldValue(cf_AgileTeam) != null) {

            String agileTeamInIssue = ((Option) collectedIssue.getCustomFieldValue(cf_AgileTeam)).getValue();

            if (agileTeamInIssue != null && !"null".equals(agileTeamInIssue)) {

                agileTeamsOptions.each() { option ->

                    if (option.getValue().equals(agileTeamInIssue)) {
                        if (!options.contains(option)) {
                            options.add(option);
                        }
                    }
                }
            }
        }
    }

    /*ModifiedValue mVal = new ModifiedValue(epicIssue.getCustomFieldValue(cf_AgileTeams), options);
    cf_AgileTeams.updateValue(null, epicIssue, mVal, new DefaultIssueChangeHolder());
    ((MutableIssue) epicIssue).setCustomFieldValue(cf_AgileTeams, options)
    issueManager.updateIssue(epicIssue.getAssignee(), epicIssue, DO_NOT_DISPATCH, false)
    reIndexIssue(epicIssue);*/

    noEpicIndexTriggered = false;
}

void reIndexIssue(MutableIssue epicIssue) {
    IssueIndexingService issueIndexingService = ComponentAccessor.getComponent(IssueIndexingService)
    issueIndexingService.reIndex(epicIssue);
}