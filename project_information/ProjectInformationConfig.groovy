package project_information

import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.mail.server.MailServerManager

/**
 * ProjectInformationConfig
 *
 * @author chabrecek.anton
 * Created on 26. 8. 2026.
 */

class ProjectInformationConfig {
    public static final String CF_PROJECT_INFORMATION_N = "customfield_18600"       // prod: customfield_18702
    public static final String CF_PROJECT_INFORMATION_TEXT = "customfield_18700"    // prod: customfield_20200
    public static final String CF_PROJECT_INFORMATION_OVERRIDE_KEY = "customfield_18802" // prod: customfield_20201
    public static final String CF_PI_SYNC_REQUIRED = "customfield_19003" //prod TODO need to be created
    public static final String CF_OLD_PROJECT_INFORMATION = "customfield_15600"
    public static final String CF_PARENT_LINK = "customfield_10301"
    public static final String CF_EPIC_LINK = "customfield_10001"

    public static final String JIRA_BOT = "jira.bot"
    public static final String[] ERROR_RECIPIENTS = ["chabrecek.anton@scheidt-bachmann.sk"]
    public static final int SEARCH_BATCH_SIZE = 200
    public static final int MAX_HIERARCHY_DEPTH = 50

    public final def customFieldManager = ComponentAccessor.customFieldManager
    public final def issueManager = ComponentAccessor.issueManager
    public final def optionsManager = ComponentAccessor.optionsManager
    public final def userManager = ComponentAccessor.userManager
    public final SearchService searchService = ComponentAccessor.getComponent(SearchService)
    public final IssueIndexingService indexingService = ComponentAccessor.getComponent(IssueIndexingService)
    public final MailServerManager mailServerManager = ComponentAccessor.getComponent(MailServerManager)

    public final CustomField cfProjectInformation = customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_N)
    public final CustomField cfOldProjectInformation = customFieldManager.getCustomFieldObject(CF_OLD_PROJECT_INFORMATION)
    public final CustomField cfTextMirror = customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_TEXT)
    public final CustomField cfOverride = customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_OVERRIDE_KEY)
    public final CustomField cfPiSyncRequired = customFieldManager.getCustomFieldObject(CF_PI_SYNC_REQUIRED)
    public final CustomField cfParentLink = customFieldManager.getCustomFieldObject(CF_PARENT_LINK)
    public final CustomField cfEpicLink = customFieldManager.getCustomFieldObject(CF_EPIC_LINK)
}