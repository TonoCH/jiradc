package listeners.global_area.projectInformation;

/**
 * ProjectInformationHandler
 *
 * @author chabrecek.anton
 * Created on 3. 7. 2026.
 */
import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.customfields.manager.OptionsManager
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.user.util.UserManager
import com.atlassian.jira.web.bean.PagerFilter
import org.apache.log4j.Logger
import utils.mail_notifiers.Mailer

class ProjectInformationHandler2 {

    static final Long CF_PROJECT_INFORMATION_ID      = 18600L //Select List (multiple choices)
    static final Long CF_PROJECT_INFORMATION_TEXT_ID = 18700L //ext mirror
    //static final Long CF_PROJECT_INFORMATION_FLAG_ID = 18801L //numeric flag, except Initiative
    static final Long CF_PROJECT_INFORMATION_OVERRIDE_KEY_ID = 18802L
    private final def cfOverrideKey = cfManager.getCustomFieldObject(CF_PROJECT_INFORMATION_OVERRIDE_KEY_ID

    static final String CF_PARENT_LINK = "customfield_10301"
    static final String CF_EPIC_LINK   = "customfield_10001"

    static final String ISSUE_TYPE_INITIATIVE = "Initiative"
    static final String JIRA_BOT = "jira.bot"

    static final Integer FLAG_INHERITED_FROM_INITIATIVE_INT = 0
    static final Integer FLAG_OVERRIDE_ROOT_INT = 1
    static final Integer FLAG_INHERITED_FROM_OVERRIDE_INT = 2

    static final Double FLAG_INHERITED_FROM_INITIATIVE = 0D
    static final Double FLAG_OVERRIDE_ROOT = 1D
    static final Double FLAG_INHERITED_FROM_OVERRIDE = 2D

    /**
     * Projects excluded from Project Information propagation.
     *
     * If an issue belongs to one of these projects, the inheritance/override
     * logic is not applied to it and its subtree is not traversed further.
     * Add project keys here to opt a project out of the global mechanism.
     */
    static final List<String> EXCLUDED_PROJECT_KEYS = [
            // "ABC",
            // "XYZ",
    ].asImmutable()

    static final List<String> FAILURE_MAIL_RECIPIENTS = [
            "chabrecek.anton",
    ].asImmutable()

    private final Logger log = Logger.getLogger("scriptrunner.project-information-handler")

    private final CustomFieldManager cfManager = ComponentAccessor.customFieldManager
    private final IssueManager issueManager = ComponentAccessor.issueManager
    private final UserManager userManager = ComponentAccessor.userManager
    private final SearchService searchService = ComponentAccessor.getComponent(SearchService)
    private final OptionsManager optionsManager = ComponentAccessor.getComponent(OptionsManager)

    private final def cfProjectInformation =
            cfManager.getCustomFieldObject(CF_PROJECT_INFORMATION_ID)

    private final def cfProjectInformationText =
            cfManager.getCustomFieldObject(CF_PROJECT_INFORMATION_TEXT_ID)

    private final def cfProjectInformationFlag =
            cfManager.getCustomFieldObject(CF_PROJECT_INFORMATION_FLAG_ID)

    private final def cfParentLink = cfManager.getCustomFieldObject(CF_PARENT_LINK)
    private final def cfEpicLink = cfManager.getCustomFieldObject(CF_EPIC_LINK)

    /**
     * Per-run collector of issues that could not be written during propagation.
     * Each entry: [key: issueKey, reason: description]. Reset on every run.
     */
    private final List<Map<String, String>> writeFailures = []

    /**
     * Listener guard.
     * React only if custom field 18600 was changed.
     */
    boolean isProjectInformationChanged(IssueEvent event) {
        if (!event?.issue || !event?.changeLog || !cfProjectInformation) {
            return false
        }

        def changeItems = event.changeLog.getRelated("ChildChangeItem")

        return changeItems?.any { item ->
                item.field == cfProjectInformation.name ||
                        item.field == "Project Information" ||
                        item.field == "customfield_${CF_PROJECT_INFORMATION_ID}"
        } as boolean
    }

    /**
     * Main listener entry.
     */
    void handleProjectInformationChange(Issue sourceIssue, ApplicationUser eventUser) {
        if (!sourceIssue) {
            return
        }

        if (isExcludedProject(sourceIssue)) {
            log.info("${sourceIssue.key}: project '${sourceIssue.projectObject?.key}' is excluded. Propagation skipped.")
            return
        }

        /*ApplicationUser writeUser =
                eventUser ?:
        ComponentAccessor.jiraAuthenticationContext.loggedInUser ?:
        userManager.getUserByName(JIRA_BOT)*/

        ApplicationUser writeUser = userManager.getUserByName(JIRA_BOT) /*?:  eventUser ?: ComponentAccessor.jiraAuthenticationContext.loggedInUser*/

        if (!writeUser) {
            throw new IllegalStateException("No write user available.")
        }

        String sourceValue = getProjectInformationValue(sourceIssue)

        if (!sourceValue) {
            log.warn("${sourceIssue.key}: Project Information is empty. Propagation skipped.")
            return
        }

        boolean initiative = isInitiative(sourceIssue)

        log.warn("START Project Information propagation from ${sourceIssue.key}, value='${sourceValue}', initiative=${initiative}, user=${writeUser.name}")

        writeFailures.clear()
        Set<Long> visited = new HashSet<>()

        if (initiative) {
            // Initiative is root. Flag is not maintained on Initiative.
            safeSyncTextMirror(sourceIssue, sourceValue, writeUser)
            propagateInheritedBranch(sourceIssue, sourceValue, writeUser, visited)
        } else {
            // Manual change below Initiative creates overridden branch.

            safeSetIssueFields(sourceIssue, sourceValue, sourceIssue.key, writeUser, true)
            propagateOverrideBranch(sourceIssue, sourceValue, writeUser, visited)
        }

        log.warn("DONE Project Information propagation from ${sourceIssue.key}. Visited=${visited.size()}, failures=${writeFailures.size()}")

        if (!writeFailures.isEmpty()) {
            sendWriteFailuresMail(sourceIssue, writeFailures)
        }
    }

    /**
     * Propagation from Initiative:
     * - updates only child issues with flag 0 or empty
     * - if flag 1 is found, issue is not updated and subtree is skipped
     */
    private void propagateInheritedBranch(
            Issue parent,
            String value,
            ApplicationUser writeUser,
            Set<Long> visited
    ) {
        if (!parent || visited.contains(parent.id)) {
            return
        }

        visited.add(parent.id)

        safeGetChildren(parent, writeUser).each { Issue child ->
            if (!child || visited.contains(child.id)) {
                return
            }

            if (isExcludedProject(child)) {
                log.info("SKIP ${child.key}: project '${child.projectObject?.key}' is excluded, subtree skipped.")
                return
            }

            String overrideKey = getOverrideKey(child)

            if (overrideKey) {
                log.info("STOP ${child.key}: override root branch '${overrideKey}'")
                return
            }

            safeSetIssueFields(sourceIssue, sourceValue, sourceIssue.key, writeUser, true)
            log.info("INHERITED ${child.key}: value='${value}', sourceIssue=${sourceIssue.key}")

            propagateInheritedBranch(child, value, writeUser, visited)
        }
    }

    /**
     * Propagation from Feature/Epic/Story/Sub-task override:
     * - source issue becomes override root (flag 1)
     * - descendants inherit from override root (flag 2)
     * - entire subtree receives same Project Information value
     */
    private void propagateOverrideBranch(
            Issue parent,
            String value,
            ApplicationUser writeUser,
            Set<Long> visited
    ) {
        if (!parent || visited.contains(parent.id)) {
            return
        }

        visited.add(parent.id)

        safeGetChildren(parent, writeUser).each { Issue child ->
            if (!child || visited.contains(child.id)) {
                return
            }

            if (isExcludedProject(child)) {
                log.info("SKIP ${child.key}: project '${child.projectObject?.key}' is excluded, subtree skipped.")
                return
            }

            safeSetIssueFields(sourceIssue, sourceValue, sourceIssue.key, writeUser, true)
            log.info("OVERRIDE-INHERITED ${child.key}: value='${value}', sourceIssue=${sourceIssue.key}")

            propagateOverrideBranch(child, value, writeUser, visited)
        }
    }

    /**
     * Updates:
     */
    void setIssueFields(
            Issue issue,
            String projectInformationValue,
            String overrideRootKey,
            ApplicationUser writeUser,
            boolean updateProjectInformation
    )
    {
        if (!issue || !projectInformationValue || !writeUser) {
            return
        }

        MutableIssue mutable = issueManager.getIssueObject(issue.id)

        boolean changed = false

        if (updateProjectInformation) {
            String currentPi = getProjectInformationValue(mutable)

            if (normalize(currentPi) != normalize(projectInformationValue)) {
                Option option = resolveProjectInformationOption(mutable, projectInformationValue)

                /*if (!option) {
                    log.warn("${issue.key}: option '${projectInformationValue}' not found in field context. Issue skipped.")
                    return
                }*/

                if (!option) {
                    throw new IllegalStateException("${issue.key}: option '${projectInformationValue}' not found in Project Information field context.")
                }


                mutable.setCustomFieldValue(cfProjectInformation, [option])
                changed = true
            }
        }

        String currentText = getTextMirrorValue(mutable)

        if (normalize(currentText) != normalize(projectInformationValue)) {
            mutable.setCustomFieldValue(cfProjectInformationText, projectInformationValue)
            changed = true
        }

        if (!isInitiative(mutable) && flagValue != null) {
            Integer currentFlag = getFlagValue(mutable)

            if (currentFlag == null || currentFlag != flagValue.intValue()) {
                mutable.setCustomFieldValue(cfOverrideKey, overrideRootKey)
                changed = true
            }
        }

        if (!changed) {
            log.debug("${issue.key}: already consistent, update skipped.")
            return
        }

        issueManager.updateIssue(
                writeUser,
                mutable,
                EventDispatchOption.DO_NOT_DISPATCH,
                false
        )
    }

    /**
     * Synchronizes only 18700 from 18600.
     * Used mainly for Initiative.
     */
    private void syncTextMirror(Issue issue, String value, ApplicationUser writeUser) {
        if (!issue || !value || !writeUser) {
            return
        }

        MutableIssue mutable = issueManager.getIssueObject(issue.id)

        String currentText = getTextMirrorValue(mutable)

        if (normalize(currentText) == normalize(value)) {
            return
        }

        mutable.setCustomFieldValue(cfProjectInformationText, value)

        issueManager.updateIssue(
                writeUser,
                mutable,
                EventDispatchOption.DO_NOT_DISPATCH,
                false
        )

        log.info("${issue.key}: text mirror synchronized to '${value}'")
    }

    /**
     * Wrapper around {@link #setIssueFields} that never aborts the whole run.
     * On failure the issue key + reason is recorded so it can be reported by mail.
     */
    private void safeSetIssueFields(
            Issue issue,
            String projectInformationValue,
            Double flagValue,
            ApplicationUser writeUser,
            boolean updateProjectInformation
    ) {
        try {
            setIssueFields(issue, projectInformationValue, flagValue, writeUser, updateProjectInformation)
        } catch (Exception e) {
            recordWriteFailure(issue, e)
        }
    }

    /**
     * Wrapper around {@link #syncTextMirror} that never aborts the whole run.
     */
    private void safeSyncTextMirror(Issue issue, String value, ApplicationUser writeUser) {
        try {
            syncTextMirror(issue, value, writeUser)
        } catch (Exception e) {
            recordWriteFailure(issue, e)
        }
    }

    private void recordWriteFailure(Issue issue, Exception e) {
        String key = issue?.key ?: "unknown"
        String reason = "${e.class.simpleName}: ${e.message}"
        writeFailures.add([key: key, reason: reason])
        log.error("Project Information write failed for ${key}: ${reason}", e)
    }

    String getProjectInformationValue(Issue issue) {
        def value = issue?.getCustomFieldValue(cfProjectInformation)

        if (!value) {
            return null
        }

        if (value instanceof Collection) {
            return value.find { it != null }?.value?.toString()
        }

        if (value instanceof Option) {
            return value.value?.toString()
        }

        if (value.hasProperty("value")) {
            return value.value?.toString()
        }

        return value.toString()
    }

    String getTextMirrorValue(Issue issue) {
        return issue?.getCustomFieldValue(cfProjectInformationText)?.toString()
    }

    Integer getFlagValue(Issue issue) {
        def value = issue?.getCustomFieldValue(cfProjectInformationFlag)

        if (value == null) {
            return null
        }

        if (value instanceof Number) {
            return value.intValue()
        }

        try {
            return value.toString().toBigDecimal().intValue()
        } catch (Exception ignored) {
            log.warn("${issue?.key}: invalid Project Information Flag value '${value}'")
            return null
        }
    }

    Option resolveProjectInformationOption(Issue issue, String value) {
        if (!issue || !value || !cfProjectInformation) {
            return null
        }

        def config = cfProjectInformation.getRelevantConfig(issue)

        if (!config) {
            return null
        }

        def options = optionsManager.getOptions(config)

        return options?.find {
            normalize(it?.value?.toString()) == normalize(value)
        } as Option
    }

    private List<Issue> safeGetChildren(Issue parent, ApplicationUser searchUser) {
        try {
            return getChildren(parent, searchUser)
        } catch (Exception e) {
            recordWriteFailure(parent, e)
            return []
        }
    }

    /**
     * Children:
     * */
     List<Issue> getChildren(Issue issue, ApplicationUser searchUser) {
     if (!issue || !searchUser) {
     log.warn("${issue?.key}: children cannot be searched because search user is not available.")
     return []
     }

     List<Issue> children = []

     children.addAll(issue.subTaskObjects ?: [])

     children.addAll(findIssuesAsUserPaged(
     searchUser,
     "\"Epic Link\" = \"${issue.key}\""
     ))

     String parentLinkId = CF_PARENT_LINK.replace("customfield_", "")

     children.addAll(findIssuesAsUserPaged(
     searchUser,
     "cf[${parentLinkId}] = \"${issue.key}\""
     ))

     return children.findAll { it != null }.unique { it.id }
     }

    /**
     * Safer variant for listener than PagerFilter.getUnlimitedFilter().
     */
    List<Issue> findIssuesAsUserPaged(ApplicationUser user, String jql) {
        List<Issue> found = []

        if (!user || !jql) {
            return found
        }

        def parseResult = searchService.parseQuery(user, jql)

        if (!parseResult.isValid()) {
            log.error("Invalid JQL [${jql}]: ${parseResult.errors}")
            return found
        }

        int pageSize = 500
        int start = 0

        while (true) {
            def pager = new PagerFilter(start, pageSize)
            def results = searchService.search(user, parseResult.query, pager)

            if (!results?.results) {
                break
            }

            results.results.each { resultIssue ->
                    def issue = issueManager.getIssueObject(resultIssue.id)
                if (issue) {
                    found.add(issue)
                }
            }

            if (results.results.size() < pageSize) {
                break
            }

            start += pageSize
        }

        return found
    }

    boolean isInitiative(Issue issue) {
        return issue?.issueType?.name == ISSUE_TYPE_INITIATIVE
    }

    /**
     * Returns true if the issue belongs to a project that is opted out
     * of the Project Information propagation mechanism.
     */
    boolean isExcludedProject(Issue issue) {
        if (!issue || EXCLUDED_PROJECT_KEYS.isEmpty()) {
            return false
        }

        String projectKey = issue.projectObject?.key

        return projectKey != null && EXCLUDED_PROJECT_KEYS.contains(projectKey)
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim()
    }

    void logFailure(Issue issue, Exception e) {
        log.error("Project Information propagation failed for ${issue?.key}: ${e.message}", e)
    }

    void sendFailureMail(Issue issue, Exception e) {
        try {
            if (FAILURE_MAIL_RECIPIENTS.isEmpty()) {
                log.warn("No failure mail recipients configured. Failure mail not sent.")
                return
            }

            String subject = "Jira Project Information propagation failed - ${issue?.key}"
            String body =
                """Project Information propagation failed.

                Issue: ${issue?.key}
                Error: ${e.message}
                
                Check ScriptRunner logs for full stacktrace.
                """

            Mailer mailer = new Mailer()

            FAILURE_MAIL_RECIPIENTS.each { String recipient ->
                boolean sent = mailer.sendMessage(recipient, subject, body)
                if (!sent) {
                    log.warn("${issue?.key}: failure mail to '${recipient}' not sent. ${mailer.errorMessages}")
                }
            }
        } catch (Exception mailException) {
            log.error("Failed to send Project Information failure mail.", mailException)
        }
    }

    /**
     * Sends a single mail listing every issue that could not be written during
     * one propagation run, together with the reason for each failure.
     */
    void sendWriteFailuresMail(Issue sourceIssue, List<Map<String, String>> failures) {
        try {
            if (failures.isEmpty()) {
                return
            }

            if (FAILURE_MAIL_RECIPIENTS.isEmpty()) {
                log.warn("No failure mail recipients configured. Write-failure mail not sent.")
                return
            }

            String subject = "Jira Project Information propagation - ${failures.size()} issue(s) failed (source ${sourceIssue?.key})"

            StringBuilder body = new StringBuilder()
            body.append("Project Information propagation started from ${sourceIssue?.key} could not update the following issue(s):\n\n")

            failures.each { Map<String, String> f ->
                body.append("- ${f.key}: ${f.reason}\n")
            }

            body.append("\nThe rest of the hierarchy was processed. Check ScriptRunner logs for full stacktraces.\n")
            body.append("Tip: verify the service account has Browse/Edit permission on the affected projects.\n")

            Mailer mailer = new Mailer()

            FAILURE_MAIL_RECIPIENTS.each { String recipient ->
                boolean sent = mailer.sendMessage(recipient, subject, body.toString())
                if (!sent) {
                    log.warn("Write-failure mail to '${recipient}' not sent. ${mailer.errorMessages}")
                }
            }
        } catch (Exception mailException) {
            log.error("Failed to send Project Information write-failure mail.", mailException)
        }
    }

    String getOverrideKey(Issue issue) {
        return issue?.getCustomFieldValue(cfOverrideKey)?.toString()?.trim()
    }

    boolean isOverrideRoot(Issue issue) {
        String overrideKey = getOverrideKey(issue)

        return overrideKey &&
                issue?.key == overrideKey
    }

    boolean belongsToOverrideBranch(Issue issue) {
        return getOverrideKey(issue) != null
    }

}