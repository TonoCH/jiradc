package jobs.global.project_information_change

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.*
import com.atlassian.jira.issue.changehistory.ChangeHistoryManager
import com.atlassian.jira.issue.customfields.manager.OptionsManager
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.user.util.UserManager
import org.apache.log4j.Logger
import utils.MyBaseUtil

/**
 * ProjectInfoPropagator — shared propagation logic for listeners.
 *
 * Authority model:
 *   jira.bot       = soft write, overwritable by propagation from above
 *   jira.bot-temp  = hard write (migrated SAP input), stops propagation
 *   real user      = hard write, stops propagation
 *
 * Propagation: only jira.bot writes get overwritten.
 *              jira.bot changes do NOT trigger propagation (it's just a relay).
 *              jira.bot-temp and real users DO trigger propagation.
 *              Children are written under the trigger user.
 *
 * @author chabrecek.anton
 */
class ProjectInfoPropagator {

    // ── Custom field IDs ──────────────────────────────────────
    static final String CF_PROJ_INF_N_ID   = "customfield_18702"
    static final String CF_PROJ_INF_N_NAME = "Project Information n"
    static final String CF_OLD_PROJ_INF    = "customfield_15600"
    static final String CF_SAP_INPUT       = "customfield_10225"  // SAP-Order-No (input) — manual text
    static final String CF_SAP_SCRIPTED    = "customfield_10217"  // SAP-Order-No — scripted field
    static final String CF_PARENT_LINK     = "customfield_10301"
    static final String CF_EPIC_LINK       = "customfield_10001"

    // ── Users ─────────────────────────────────────────────────
    static final String JIRA_BOT      = "jira.bot"
    static final String JIRA_BOT_TEMP = "jira.bot-temp"

    // ── Option label templates ────────────────────────────────
    static final String LABEL_MULTIPLE = "Multiple project with [%s]"
    static final String LABEL_HISTORY  = "history [%s]"

    // ── Services ──────────────────────────────────────────────
    private final Logger log = Logger.getLogger("scriptrunner.propagator.project-info")

    private final CustomFieldManager    cfManager    = ComponentAccessor.getCustomFieldManager()
    private final IssueManager          issueManager = ComponentAccessor.getIssueManager()
    private final OptionsManager        optManager   = ComponentAccessor.getOptionsManager()
    private final UserManager           userManager  = ComponentAccessor.getUserManager()
    private final ChangeHistoryManager  historyMgr   = ComponentAccessor.getChangeHistoryManager()

    private final def cfTarget     = cfManager.getCustomFieldObject(CF_PROJ_INF_N_ID)
    private final def cfParentLink = cfManager.getCustomFieldObject(CF_PARENT_LINK)
    private final def cfEpicLink   = cfManager.getCustomFieldObject(CF_EPIC_LINK)

    private final MyBaseUtil myBaseUtil = new MyBaseUtil()

    // ══════════════════════════════════════════════════════════
    //  PROPAGATION (top-down, for listeners)
    // ══════════════════════════════════════════════════════════

    /**
     * Propagate value from source issue downward.
     * Children are written under writeAsUser.
     * Stops at children where last author is NOT jira.bot.
     */
    void propagate(Issue source, String value, ApplicationUser writeAsUser) {
        log.info("Propagation start: '${value}' from ${source.key} as ${writeAsUser.name}")
        def visited = new HashSet<Long>()
        propagateDown(source, value, writeAsUser, visited)
        log.info("Propagation done from ${source.key}. Visited ${visited.size()} issues.")
    }

    private void propagateDown(Issue source, String value, ApplicationUser writeAsUser, Set<Long> visited) {
        if (!source || visited.contains(source.id)) return
        visited.add(source.id)

        getChildren(source).each { Issue child ->
            if (visited.contains(child.id)) return

            if (canOverwrite(child)) {
                Option opt = resolveOption(child, value)
                if (!opt) {
                    log.warn("  ✗ ${child.key}: option '${value}' not found, skipping subtree.")
                    return
                }
                setValue(child, opt, writeAsUser)
                log.info("  ✓ ${child.key} ← '${value}' (as ${writeAsUser.name})")
                propagateDown(child, value, writeAsUser, visited)
            } else {
                log.info("  ✗ ${child.key}: hard write (${getLastAuthorName(child)}), stopping.")
                // Full stop — do NOT recurse into this subtree
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  AUTHORITY CHECKS
    // ══════════════════════════════════════════════════════════

    /**
     * Can we overwrite the field on this issue?
     * TRUE only if the field was never set, or last set by jira.bot.
     */
    boolean canOverwrite(Issue issue) {
        ApplicationUser author = getLastAuthor(issue)
        if (!author) return true  // never set
        return author.name == JIRA_BOT || author.key == JIRA_BOT
    }

    /**
     * Get the ApplicationUser who last wrote Project Information n on this issue.
     * Returns null if the field was never changed.
     */
    ApplicationUser getLastAuthor(Issue issue) {
        def histories = historyMgr.getChangeHistories(issue)
        if (!histories) return null

        def lastRelevant = histories.reverse().find { history ->
            history.changeItemBeans?.any { it.field == CF_PROJ_INF_N_NAME }
        }
        if (!lastRelevant) return null

        try {
            return lastRelevant.authorObject
        } catch (Exception ignored) {
            return userManager.getUserByName(lastRelevant.authorKey)
        }
    }

    /**
     * Short helper: returns author name or "unset".
     */
    String getLastAuthorName(Issue issue) {
        return getLastAuthor(issue)?.name ?: "unset"
    }

    /**
     * Is the trigger user someone who should cause propagation?
     * jira.bot = NO (it's a relay). Everything else = YES.
     */
    static boolean shouldPropagate(ApplicationUser user) {
        return user?.name != JIRA_BOT && user?.key != JIRA_BOT
    }

    // ══════════════════════════════════════════════════════════
    //  CHILDREN
    // ══════════════════════════════════════════════════════════

    List<Issue> getChildren(Issue issue) {
        def children = []
        def user = userManager.getUserByName(JIRA_BOT)

        children.addAll(issue.subTaskObjects ?: [])

        def epicChildren = myBaseUtil.findIssuesAsUser(user,
                "\"Epic Link\" = \"${issue.key}\"")
        children.addAll(epicChildren ?: [])

        def plId = CF_PARENT_LINK.replace('customfield_', '')
        def plChildren = myBaseUtil.findIssuesAsUser(user,
                "cf[${plId}] = \"${issue.key}\"")
        children.addAll(plChildren ?: [])

        return children.unique { it.id }
    }

    // ══════════════════════════════════════════════════════════
    //  PARENT (for create listener)
    // ══════════════════════════════════════════════════════════

    Issue getParent(Issue issue) {
        if (issue.isSubTask()) return issue.parentObject

        def epic = issue.getCustomFieldValue(cfEpicLink)
        if (epic) return issueManager.getIssueObject(epic.toString())

        def pl = issue.getCustomFieldValue(cfParentLink)
        if (pl) return issueManager.getIssueObject(pl.toString())

        return null
    }

    // ══════════════════════════════════════════════════════════
    //  FIELD READ/WRITE
    // ══════════════════════════════════════════════════════════

    /**
     * Set PI n on issue. Uses DO_NOT_DISPATCH to prevent listener cascade.
     * Wraps option in list (multi-select field stores Collection<Option>).
     */
    void setValue(Issue issue, Option opt, ApplicationUser writeAsUser) {
        if (!issue || !opt || !writeAsUser) return

        String currentVal = getValue(issue)
        if (currentVal?.trim() == opt.value?.trim()) {
            log.debug("setValue: ${issue.key} already has '${opt.value}', skipping.")
            return
        }

        MutableIssue mutable = issueManager.getIssueObject(issue.id)
        mutable.setCustomFieldValue(cfTarget, [opt])
        issueManager.updateIssue(writeAsUser, mutable, EventDispatchOption.DO_NOT_DISPATCH, false)
    }

    /**
     * Read current PI n value as String.
     */
    String getValue(Issue issue) {
        def v = issue.getCustomFieldValue(cfTarget)
        if (v instanceof Collection) return v?.find { it != null }?.value
        if (v instanceof Option) return v.value
        return null
    }

    /**
     * Find Option by its value string in the issue's field context.
     */
    Option resolveOption(Issue issue, String value) {
        if (!value || !cfTarget) return null
        def config = cfTarget.getRelevantConfig(issue)
        if (!config) return null
        def options = optManager.getOptions(config)
        return options?.find { it?.value?.toString()?.trim() == value.trim() } as Option
    }
}
