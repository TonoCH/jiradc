package utils

import org.apache.log4j.Logger

import java.util.stream.Collectors
import com.atlassian.jira.exception.NotFoundException
import java.lang.StringBuilder
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.jql.parser.JqlQueryParser
import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.comments.CommentManager
import com.atlassian.jira.issue.history.ChangeItemBean
import com.atlassian.jira.issue.customfields.manager.OptionsManager
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.ModifiedValue
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.issue.status.Status
import com.atlassian.jira.workflow.WorkflowManager
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.security.JiraAuthenticationContext
import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.web.bean.PagerFilter
import com.atlassian.jira.issue.IssueFactory
import com.atlassian.jira.issue.fields.config.FieldConfig
import com.atlassian.jira.workflow.TransitionOptions
import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.bc.issue.IssueService.TransitionValidationResult
import com.atlassian.jira.bc.issue.IssueService.IssueResult

public class MyBaseUtil {

    def jiraAuthenticationContext = ComponentAccessor.jiraAuthenticationContext
    def searchService = ComponentAccessor.getComponent(SearchService)
    def issueManager = ComponentAccessor.issueManager
    def commentManager = ComponentAccessor.commentManager
    def workflowManager = ComponentAccessor.workflowManager
    def issueService = ComponentAccessor.getComponent(IssueService)
    def customFieldManager = ComponentAccessor.getCustomFieldManager()
    def log = Logger.getLogger("scriptrunner.myBaseUtil")

    /**
     * Finds issues based on the given JQL query.
     *
     * @param jqlQuery the JQL query string to search for issues
     * @return a list of issues matching the JQL query
     * @throws IllegalArgumentException if jqlQuery is null or empty
     */
    public List<Issue> findIssues(String jqlQuery) {
        if (jqlQuery == null || jqlQuery.trim().isEmpty()) {
            throw new IllegalArgumentException("String jqlQuery cannot be null or empty");
        }

        def user = ComponentAccessor.getJiraAuthenticationContext().loggedInUser
        //def user = ComponentAccessor.jiraAuthenticationContext.user
        def jqlQueryParser = ComponentAccessor.getComponent(JqlQueryParser.class)
        def searchService = ComponentAccessor.getComponent(SearchService)
        def query = jqlQueryParser.parseQuery(jqlQuery)
        //TODO print jql
        List<Issue> results = searchService.search(user, query, PagerFilter.getUnlimitedFilter()).getResults()

        return results;
    }

    public List<Issue> findIssuesAsUser(def user, String jql) {
        try {
            def parseResult = searchService.parseQuery(user, jql)
            if (!parseResult.isValid()) {
                log.warn("Invalid JQL [${jql}]: ${parseResult.errors}")
                return []
            }
            def results = searchService.search(user, parseResult.query, PagerFilter.getUnlimitedFilter())
            return results.getResults()
                    .collect { issueManager.getIssueObject(it.id) }
                    .findAll { it != null }
        } catch (Exception e) {
            log.error("JQL search failed [${jql}]", e)
            return []
        }
    }

    /**
     * Sets a custom field value for an issue.
     *
     * @param issue the issue to update
     * @param customField the custom field to update
     * @param value the new value to set
     */
    void setCustomFieldValue(Issue issue, CustomField customField, Object value) {
        if (value != null) {
            def changeHolder = new DefaultIssueChangeHolder()
            customField.updateValue(null, issue, new ModifiedValue(issue.getCustomFieldValue(customField), value), changeHolder)
        }
    }

    /**
     * Retrieves the value of a custom field for a given issue.
     *
     * @param issue the issue to retrieve the custom field value from
     * @param customFieldName the name of the custom field
     * @return the value of the custom field, or null if no value
     */
    Object getCustomFieldValue(Issue issue, String customFieldName) {
        CustomField customField = ComponentAccessor.customFieldManager.getCustomFieldObjects(issue).find { it.name == customFieldName }
        return customField ? issue.getCustomFieldValue(customField) : null
    }

    Object getCustomFieldValueById(Issue issue, String customFieldObject) {
        def customField = ComponentAccessor.customFieldManager.getCustomFieldObject(customFieldObject)
        return customField ? issue.getCustomFieldValue(customField) : null
    }

    public String getIssueKeyNumberPart(Issue issue) {
        if(issue == null)
            throw new NullPointerException("Issue cannot be null.");

        def issueKey = issue.getKey();
        def matcher = issueKey =~ /-(\d+)$/

        return matcher ? matcher[0][1].toInteger() : null
    }

    /**
     * Adds a comment to a given issue.
     *
     * @param issue the issue to add a comment to
     * @param commentText the text of the comment
     * @param user the user adding the comment
     */
    void addCommentToIssue(Issue issue, String commentText, ApplicationUser user) {
        commentManager.create(issue, user, commentText, true)
    }

    /**
     * Changes the status of an issue.
     *
     * @param issue the issue to update
     * @param newStatus the new status to set
     */
    void changeIssueStatus(Issue issue, String newStatus) {
        def status = workflowManager.getStatus(newStatus)
        if (status) {
            issue.setStatus(status)
            issueManager.updateIssue(jiraAuthenticationContext.loggedInUser, issue, EventDispatchOption.DO_NOT_DISPATCH, false)
        }
    }

    /**
     * Assigns an issue to a user.
     *
     * @param issue the issue to assign
     * @param user the user to assign the issue to
     */
    void assignIssueToUser(Issue issue, ApplicationUser user) {
        issue.setAssignee(user)
        issueManager.updateIssue(jiraAuthenticationContext.loggedInUser, issue, EventDispatchOption.DO_NOT_DISPATCH, false)
    }

    /**
     * Retrieves the history of changes for a given issue.
     *
     * @param issue the issue to get history for
     * @return a list of change items related to the issue status
     */
    List<ChangeItemBean> getIssueHistory(Issue issue) {
        return ComponentAccessor.changeHistoryManager.getChangeItemsForField(issue, "status")
    }

    /**
     * Retrieves a user by username.
     *
     * @param username the username of the user to retrieve
     * @return the ApplicationUser object, or null if not found
     */
    ApplicationUser getUserByName(String username) {
        return ComponentAccessor.userManager.getUserByName(username)
    }

    def getIssueByKey(issueKey){
        return issueManager.getIssueByCurrentKey(issueKey)    
    }

    /*
    *  Reindex issue   
    *  @param myIssue the issue for indexing
    */
    void reIndexIssue(MutableIssue myIssue) {
        IssueIndexingService issueIndexingService = ComponentAccessor.getComponent(IssueIndexingService)
        issueIndexingService.reIndex(myIssue);
    }

    public void validateFieldsExist(def fieldsNamesConstantClass) {
        List<String> allFields = Arrays.stream(fieldsNamesConstantClass.getDeclaredFields())
            .filter(field -> java.lang.reflect.Modifier.isStatic(field.getModifiers())) // Only static fields
            .map(field -> {
                try {
                    field.setAccessible(true); // Ensure accessibility
                    return (String) field.get(null); // Extract actual String value
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Error accessing field: " + field.getName(), e);
            }
            })
            .collect(Collectors.toList());

        for (String fieldName : allFields) {
            if(fieldName == null || fieldName == "false")
                continue;

            def fieldByName = CustomFieldUtil.getCustomFieldByName(fieldName); // Pass the extracted String
            if(fieldByName == null)
                throw new RuntimeException("Field is null: " + fieldName);
        }
    }

    public void validateFieldsNamesExist(List<String> fieldsNamesList) {
        for (String fieldName : fieldsNamesList) {
            if(fieldName == null || fieldName == "false")
                continue;

            def fieldByName = CustomFieldUtil.getCustomFieldByName(fieldName); // Pass the extracted String
            if(fieldByName == null)
                throw new RuntimeException("Field is null: " + fieldName);
        }
    }

    public void validateFieldsIdsExist(List<String> fieldsIdsList) {
        for (String fieldId : fieldsIdsList) {
            if(fieldId == null || fieldId == "false")
                continue;
            
            def customField = customFieldManager.getCustomFieldObject(fieldId)    
            if(customField == null)
                throw new RuntimeException("Field ID is null: " + fieldId);
        }
    }
}
