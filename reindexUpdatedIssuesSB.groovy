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
import static com.atlassian.jira.event.type.EventDispatchOption.ISSUE_UPDATED;

// field definitions
@Field CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager();
@Field CustomField cf_AgileTeam = customFieldManager.getCustomFieldObject("customfield_10403"); //Agile Team Field 
@Field CustomField cf_EpicLink = customFieldManager.getCustomFieldObject("customfield_10001"); // EPIC Link CF ID
@Field CustomField cf_ParentLink = customFieldManager.getCustomFieldObject("customfield_10301"); // Parent Link Field Portfolio
@Field CustomField cf_ProjectInformation = customFieldManager.getCustomFieldObject("customfield_10809");
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
	   
    List<MutableIssue> lastUpdatedIssues = null

    SearchService.ParseResult parseResult =  searchService.parseQuery(user, jqlSearchUpdatedLast10Minutes)

    if (parseResult.isValid()) {
        def searchResult = searchService.search(user, parseResult.getQuery(), PagerFilter.getUnlimitedFilter())
        // Transform issues from DocumentIssueImpl to the "pure" form IssueImpl (some methods don't work with DocumentIssueImps)
        lastUpdatedIssues = searchResult.results.collect {issueManager.getIssueObject(it.id)}
    } else {
        return "Invalid JQL: " + jqlSearch;
    } 

	log.warn "Found some Issues for re-indexing:" + lastUpdatedIssues.toString();
	
	for (Issue issue in lastUpdatedIssues) {
		checkForEpicLinkUpdate(issue);
		checkForProjectInformationUpdate(issue);
	}
	
	
	return lastUpdatedIssues.toString();
}

String checkForProjectInformationUpdate(Issue issue) {
	List<ChangeItemBean> changeItemBeans = ComponentAccessor.getChangeHistoryManager().getChangeItemsForField(issue, cf_ProjectInformation.getName()); 
	ChangeItemBean lastSapOrderNoItem = null;

	if (changeItemBeans != null && !changeItemBeans.isEmpty()) {
		lastSapOrderNoItem = changeItemBeans.last();
	}

	if (lastSapOrderNoItem != null) {
		TimeDuration td = TimeCategory.minus(new Date(), new Date(lastSapOrderNoItem.created.getTime()))
		// if the last epic change was within the last 10 minutes, we will reindex both, the old and the new Index
		if (td.toMilliseconds() <= tenMinInMs) {
			log.warn "Project Information has been updated for: " + issue.getKey();
			searchForChildrenAndUpdate(issue);			
		}
	}
	//reIndexIssue((MutableIssue) issue);
}

String searchForChildrenAndUpdate(Issue issue) {
	String issueKeyString = issue.getKey();
    log.warn "Searching for more child issues for " + issueKeyString;
	List<MutableIssue> lastUpdatedIssues = null

	SearchService.ParseResult parseResult =  searchService.parseQuery(user, jqlSearchForParentLink + issueKeyString);
	//SearchService.ParseResult parseResult =  searchService.parseQuery(user, jqlSearchUpdatedLast10Minutes);
	
	
	log.warn "JQL: " + jqlSearchForParentLink + " " + issueKeyString;
	
	if (parseResult.isValid()) {
		SearchResults searchResult = searchService.search(user, parseResult.getQuery(), PagerFilter.getUnlimitedFilter())
        log.warn "Found more child issues to re-index:" + searchResult.getIssues().toString();
		log.warn "parseResult.getQuery(): " + parseResult.getQuery();
		// Transform issues from DocumentIssueImpl to the "pure" form IssueImpl (some methods don't work with DocumentIssueImps)
		lastUpdatedIssues = searchResult.issues.collect {issueManager.getIssueObject(it.id)}
	} else {
		log.warn "Invalid JQL: " + jqlSearch;
	} 

	//log.warn "Found more child issues to re-index:" + lastUpdatedIssues.toString();
	
	for (Issue childIssue in lastUpdatedIssues) {
		searchForChildrenAndUpdate(childIssue);
	}

	reIndexIssue((MutableIssue) issue);
}

String checkForEpicLinkUpdate(Issue issue) {
	/*
	Check if the epic has changend within the last 10 minutes. If yes, check the Epic items itself and make a reindex if necessary
	*/
	List<ChangeItemBean> changeItemBeans = ComponentAccessor.getChangeHistoryManager().getChangeItemsForField(issue, cf_EpicLink.getName()); 
	ChangeItemBean lastEpicItem = null;

	if (changeItemBeans != null && !changeItemBeans.isEmpty()) {
		lastEpicItem = changeItemBeans.last();
	}

	if (lastEpicItem != null) {
		TimeDuration td = TimeCategory.minus(new Date(), new Date(lastEpicItem.created.getTime()))
		// if the last epic change was within the last 10 minutes, we will reindex both, the old and the new Index
		if (td.toMilliseconds() <= tenMinInMs) {
			
			String debugComment = "";
			
			if (lastEpicItem.getFrom() != null 
				&& !lastEpicItem.getFrom().equals("")) {
				
				long oldEpicId = new Long(lastEpicItem.getFrom());
				checkAgileTeamsInEpic(oldEpicId, false, issue);
				
				debugComment += oldEpicId + " to "
			}
			
			if (lastEpicItem.getTo() != null 
				&& !lastEpicItem.getTo().equals("")) {
				
				long newEpicId = new Long(lastEpicItem.getTo());
				checkAgileTeamsInEpic(newEpicId, true, issue);
				
				debugComment += newEpicId;
			}
			
			//this was only for debuggin reasons
			//ComponentAccessor.commentManager.create(event.issue, event.user, "under 2 Seconds ago from " + debugComment, true)
		}
	}
    
    // and do a reindex in anycase
    //reIndexIssue((MutableIssue) issue);
    
}

String checkAgileTeamsInEpic(long epicId, boolean addOnly, Issue issue) {                          
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
            issuesInEpic = searchResult.issues.collect {issueManager.getIssueObject(it.id)}
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
	
	if (issue.getCustomFieldValue(cf_AgileTeam) != null) {	

            String agileTeamInIssue = ((Option) issue.getCustomFieldValue(cf_AgileTeam)).getValue();
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
        
    }

    //***************      commented on 13.5.2025        ****************
    /*ModifiedValue mVal = new ModifiedValue(epicIssue.getCustomFieldValue(cf_AgileTeams), options);
    cf_AgileTeams.updateValue(null, epicIssue, mVal, new DefaultIssueChangeHolder());
    ((MutableIssue) epicIssue).setCustomFieldValue(cf_AgileTeams, options)    
    issueManager.updateIssue(epicIssue.getAssignee(), epicIssue, ISSUE_UPDATED, false)*/
    //***************     END commented on 13.5.2025        ****************

    //reIndexIssue(epicIssue);

}

void reIndexIssue(MutableIssue epicIssue) {
    IssueIndexingService issueIndexingService = ComponentAccessor.getComponent(IssueIndexingService)
    issueIndexingService.reIndex(epicIssue);
}