package jobs.global.project_information_change

import com.atlassian.jira.issue.Issue
import org.apache.log4j.Logger

/**
 * Listener: Update Issue — Project Information n propagation
 *
 * When PI n is changed on an issue:
 *   - jira.bot changed it     → SKIP (it's a relay from propagation, not a source)
 *   - jira.bot-temp changed   → PROPAGATE (quasi-user, migrated SAP input)
 *   - Real user changed       → PROPAGATE
 *
 * Children are written under the trigger user (hard write).
 * Propagation stops at children where last author is NOT jira.bot.
 * Uses DO_NOT_DISPATCH on children to prevent cascade.
 *
 * @author chabrecek.anton
 */

def log = Logger.getLogger("scriptrunner.listener.project-info-update")

Issue issue = event.issue
if (!issue) return

// ── Find change to PI n ──────────────────────────────────────
def changeItems = event.changeLog?.getRelated("ChildChangeItem")
def change = changeItems?.find {
    it.getString("field") == ProjectInfoPropagator.CF_PROJ_INF_N_NAME
}
if (!change) return

// ── Who changed it? ──────────────────────────────────────────
def changedBy = event.user

// jira.bot = relay from propagation → do NOT propagate again
if (!ProjectInfoPropagator.shouldPropagate(changedBy)) {
    log.debug("Change by ${changedBy?.name} on ${issue.key} — relay, skipping.")
    return
}

// ── What changed? ────────────────────────────────────────────
String newValue = change.getString("newstring")
String oldValue = change.getString("oldstring")

if (!newValue?.trim()) {
    log.info("PI n cleared on ${issue.key} — not propagating empty value.")
    return
}
if (oldValue?.trim() == newValue?.trim()) {
    log.debug("No effective change on ${issue.key}, skipping.")
    return
}

// ── Propagate downward ───────────────────────────────────────
log.info("User '${changedBy.name}' changed PI n on ${issue.key}: " +
        "'${oldValue}' → '${newValue}' — propagating.")

new ProjectInfoPropagator().propagate(issue, newValue, changedBy)
