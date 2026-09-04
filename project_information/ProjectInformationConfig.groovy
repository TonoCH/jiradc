package project_information

import com.atlassian.beehive.ClusterLockService
import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.mail.server.MailServerManager
import com.atlassian.sal.api.transaction.TransactionTemplate

/**
 * ProjectInformationConfig
 *
 * @author chabrecek.anton
 * Created on 26. 8. 2026.
 */

class ProjectInformationConfig {
    // Override these with JVM -D properties on every DC node; defaults preserve the current environment.
    public static final String CF_PROJECT_INFORMATION_N = configured(
            "pi.cf.projectInformation", "customfield_18600") // prod example: customfield_18702
    public static final String CF_PROJECT_INFORMATION_TEXT = configured(
            "pi.cf.textMirror", "customfield_18700") // prod example: customfield_20200
    public static final String CF_PROJECT_INFORMATION_OVERRIDE_KEY = configured(
            "pi.cf.overrideKey", "customfield_18802") // prod example: customfield_20201
    public static final String CF_PI_SYNC_REQUIRED = configured(
            "pi.cf.syncRequired", "customfield_19003")
    public static final String CF_OLD_PROJECT_INFORMATION = configured(
            "pi.cf.oldProjectInformation", "customfield_15600")
    public static final String CF_PARENT_LINK = configured(
            "pi.cf.parentLink", "customfield_10301")
    public static final String CF_EPIC_LINK = configured(
            "pi.cf.epicLink", "customfield_10001")

    public static final String JIRA_BOT = configured("pi.bot.username", "jira.bot")
    public static final String[] ERROR_RECIPIENTS = configuredRecipients(
            "pi.errorRecipients", "chabrecek.anton@scheidt-bachmann.sk")
    public static final int SEARCH_BATCH_SIZE = 200
    public static final int MAX_HIERARCHY_DEPTH = 50
    public static final String LOCK_NAMESPACE = "sk-scheidt-bachmann-project-information-sync"

    public final def customFieldManager = ComponentAccessor.customFieldManager
    public final def issueManager = ComponentAccessor.issueManager
    public final def optionsManager = ComponentAccessor.optionsManager
    public final def userManager = ComponentAccessor.userManager
    public final SearchService searchService = ComponentAccessor.getComponent(SearchService)
    public final IssueIndexingService indexingService = ComponentAccessor.getComponent(IssueIndexingService)
    public final MailServerManager mailServerManager = ComponentAccessor.getComponent(MailServerManager)
    public final ClusterLockService clusterLockService = ComponentAccessor.getComponent(ClusterLockService)
    public final TransactionTemplate transactionTemplate = ComponentAccessor.getComponent(TransactionTemplate)

    public final CustomField cfProjectInformation = customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_N)
    public final CustomField cfOldProjectInformation = customFieldManager.getCustomFieldObject(CF_OLD_PROJECT_INFORMATION)
    public final CustomField cfTextMirror = customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_TEXT)
    public final CustomField cfOverride = customFieldManager.getCustomFieldObject(CF_PROJECT_INFORMATION_OVERRIDE_KEY)
    public final CustomField cfPiSyncRequired = customFieldManager.getCustomFieldObject(CF_PI_SYNC_REQUIRED)
    public final CustomField cfParentLink = customFieldManager.getCustomFieldObject(CF_PARENT_LINK)
    public final CustomField cfEpicLink = customFieldManager.getCustomFieldObject(CF_EPIC_LINK)

    protected <T> T withIssueLock(Long issueId, Closure<T> work) {
        if (!issueId) {
            throw new IllegalArgumentException("Issue ID is required for the PI synchronization lock")
        }
        return withNamedLock("${LOCK_NAMESPACE}-${issueId}", work)
    }

    protected <T> T withNamedLock(String lockName, Closure<T> work) {
        if (!clusterLockService) {
            throw new IllegalStateException("ClusterLockService is not available")
        }

        def lock = clusterLockService.getLockForName(lockName)
        lock.lock()
        try {
            return work.call()
        } finally {
            lock.unlock()
        }
    }

    protected boolean tryWithNamedLock(String lockName, Closure work) {
        if (!clusterLockService) {
            throw new IllegalStateException("ClusterLockService is not available")
        }

        def lock = clusterLockService.getLockForName(lockName)
        if (!lock.tryLock()) return false
        try {
            work.call()
            return true
        } finally {
            lock.unlock()
        }
    }

    protected <T> T inTransaction(Closure<T> work) {
        if (!transactionTemplate) {
            throw new IllegalStateException("TransactionTemplate is not available")
        }
        return transactionTemplate.execute { work.call() } as T
    }

    private static String configured(String propertyName, String defaultValue) {
        String value = System.getProperty(propertyName)
        return value?.trim() ?: defaultValue
    }

    private static String[] configuredRecipients(String propertyName, String defaultValue) {
        return configured(propertyName, defaultValue)
                .split(",")
                .collect { it.trim() }
                .findAll { it } as String[]
    }
}
