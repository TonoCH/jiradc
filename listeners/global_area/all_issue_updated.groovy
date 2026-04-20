package listeners.global_area

/**
 * all_issue_updated
 *
 * @author chabrecek.anton
 * Updated on 22/05/2025.
 *
 * name: For all "Issue Updated"
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

import static com.atlassian.jira.event.type.EventDispatchOption.DO_NOT_DISPATCH;
import static com.atlassian.jira.event.type.EventDispatchOption.ISSUE_UPDATED;

// field definitions
@Field CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager();
@Field CustomField cf_AgileTeam = customFieldManager.getCustomFieldObject("customfield_10403"); //Agile Team Field
@Field CustomField cf_EpicLink = customFieldManager.getCustomFieldObject("customfield_10001"); // EPIC Link CF ID
@Field String jqlSearch = "cf[10001] = "; // EPIC Link CF ID
@Field CustomField cf_EpicName = customFieldManager.getCustomFieldObject("customfield_10003"); // EPIC Name CF ID
@Field CustomField cf_AgileTeams = customFieldManager.getCustomFieldObject("customfield_10210");//Agile Teams Field v
@Field boolean noEpicIndexTriggered = true;

/*
Check if the epic has changend within the last 2 seconds. If yes, check the Epic items itself and make a reindex if necessary
*/
List<ChangeItemBean> changeItemBeans = ComponentAccessor.getChangeHistoryManager().getChangeItemsForField(event.issue, cf_EpicLink.getName());
ChangeItemBean lastEpicItem = null;

if (changeItemBeans != null && !changeItemBeans.isEmpty()) {
    lastEpicItem = changeItemBeans.last();
}

if (lastEpicItem != null) {
    TimeDuration td = TimeCategory.minus(new Date(), new Date(lastEpicItem.created.getTime()))
    // if the last epic change was within the last 2 seconds, we will reindex both, the old and the new Index
    if (td.toMilliseconds() < 2000) {

        String debugComment = "";

        if (lastEpicItem.getFrom() != null
                && !lastEpicItem.getFrom().equals("")) {

            long oldEpicId = new Long(lastEpicItem.getFrom());
            checkAgileTeamsInEpic(oldEpicId, false);

            debugComment += oldEpicId + " to "
        }

        if (lastEpicItem.getTo() != null
                && !lastEpicItem.getTo().equals("")) {

            long newEpicId = new Long(lastEpicItem.getTo());
            checkAgileTeamsInEpic(newEpicId, true);

            debugComment += newEpicId;
        }

        //this was only for debuggin reasons
        //ComponentAccessor.commentManager.create(event.issue, event.user, "under 2 Seconds ago from " + debugComment, true)
    }
}

if (noEpicIndexTriggered) {

    // check if agile teams has been updated
    List<ChangeItemBean> changeItemBeansAgileTeam= ComponentAccessor.getChangeHistoryManager().getChangeItemsForField(event.issue, cf_AgileTeam.getName());
    ChangeItemBean changeItemBeansAgileTeamItem = null;

    if (changeItemBeansAgileTeam != null && !changeItemBeansAgileTeam.isEmpty()) {
        changeItemBeansAgileTeamItem = changeItemBeansAgileTeam.last();
    }

    if (changeItemBeansAgileTeamItem != null) {
        TimeDuration td = TimeCategory.minus(new Date(), new Date(changeItemBeansAgileTeamItem.created.getTime()))
        // if the last epic change was within the last 2 seconds, we will reindex both, the old and the new Index
        if (td.toMilliseconds() < 2000) {

            //checkAgileTeamsInEpic(event.issue.getCustomFieldValue(cf_EpicName));
            Issue epicIssue = (Issue) event.issue.getCustomFieldValue(cf_EpicLink);
            checkAgileTeamsInEpic(epicIssue.getId(), false);
            //this was only for debuggin reasons
            //ComponentAccessor.commentManager.create(event.issue, event.user, "YESSSSSSS " + epicIssue.getId().toString(), true)
        }
    }

}

String checkAgileTeamsInEpic(long epicId, boolean addOnly) {
    SearchService searchService = ComponentAccessor.getComponent(SearchService);
    UserUtil userUtil = ComponentAccessor.getUserUtil()
    ApplicationUser user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
    IssueManager issueManager = ComponentAccessor.getIssueManager()

    MutableIssue epicIssue = ComponentAccessor.getIssueManager().getIssueObject(epicId);
    Collection<Option> options = new ArrayList();

    if (!addOnly) {
        // fist of all check all the local stuff
        Object epicNameString = epicIssue.getCustomFieldValue(cf_EpicName);
        jqlSearch += "'" + epicNameString + "'";

        List<MutableIssue> issuesInEpic = null

        SearchService.ParseResult parseResult =  searchService.parseQuery(user, jqlSearch)

        if (parseResult.isValid()) {
            def searchResult = searchService.search(user, parseResult.getQuery(), PagerFilter.getUnlimitedFilter())
            // Transform issues from DocumentIssueImpl to the "pure" form IssueImpl (some methods don't work with DocumentIssueImps)
            issuesInEpic = searchResult.results.collect { issueManager.getIssueObject(it.id) }
        } else {
            return "Invalid JQL: " + jqlSearch;
        }

        ArrayList<String> agileTeams = new ArrayList();
        //Collection<Option> cfOptions = (Collection<Option>) epicIssue.getCustomFieldValue(cf_AgileTeams);

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
    } else {

        Options agileTeamsOptions = ComponentAccessor.getOptionsManager().getOptions(cf_AgileTeams.getRelevantConfig(epicIssue));
        String agileTeamInIssue = ((Option) event.issue.getCustomFieldValue(cf_AgileTeam)).getValue();
        Collection<Option> cfOptions = (Collection<Option>) epicIssue.getCustomFieldValue(cf_AgileTeams);

        if (cfOptions == null) {
            cfOptions = new ArrayList();
        }

        //ComponentAccessor.commentManager.create(event.issue, event.user, "agileTeamInIssue " + agileTeamInIssue, true)

        agileTeamsOptions.each() { option ->
            if (option.getValue().equals(agileTeamInIssue)) {
                if (!cfOptions.contains(option)) {
                    cfOptions.add(option);
                }
            }
        }

        options = cfOptions;

    }

    // ******** update commented 13 5 2025
    /*ModifiedValue mVal = new ModifiedValue(epicIssue.getCustomFieldValue(cf_AgileTeams), options);
    cf_AgileTeams.updateValue(null, epicIssue, mVal, new DefaultIssueChangeHolder());
    ((MutableIssue) epicIssue).setCustomFieldValue(cf_AgileTeams, options)
    issueManager.updateIssue(epicIssue.getAssignee(), epicIssue, ISSUE_UPDATED, false)
    */

    //reIndexIssue(epicIssue);

    noEpicIndexTriggered = false;
}

void reIndexIssue(MutableIssue epicIssue) {
    IssueIndexingService issueIndexingService = ComponentAccessor.getComponent(IssueIndexingService)
    issueIndexingService.reIndex(epicIssue);
}