package jobs.global.project_information_change

import com.atlassian.jira.issue.Issue
import org.apache.log4j.Logger

/**
 * projectInformationUpdate – ScriptRunner Listener
 *
 * Triggers on any issue update where "Project Information n" was changed.
 * If the change was by a real user (not jira.bot), propagates the new
 * value to all child issues in the hierarchy.
 *
 * Rules:
 *   - Propagation stops at children where the field was last set by a real user.
 *   - If cleared (empty new value), do NOT propagate (children keep their values).
 *   - Changes made by jira.bot are ignored (prevents infinite loops from
 *     EventDispatchOption.DO_NOT_DISPATCH fallback).
 *
 * @author chabrecek.anton
 * Created on 16. 3. 2026.
 */

def log = Logger.getLogger("scriptrunner.listener.project-info-propagator")

Issue issue = event.issue
if (!issue) {
    log.warn("project-info-propagator: No issue in event ${event?.class?.simpleName}, skipping.")
    return
}

// ── Identify the change ──────────────────────────────────────
def changeItems = event.changeLog?.getRelated("ChildChangeItem")
def relevantChange = changeItems?.find {
    it.getString("field") == ProjectInfoPropagator.CF_PROJ_INF_N_NAME
}

if (!relevantChange) {
    // Field not changed in this update – nothing to do
    return
}

// ── Guard: skip if changed by jira.bot ───────────────────────
def changedBy = event.user
if (changedBy?.name == ProjectInfoPropagator.JIRA_BOT ||
        changedBy?.key  == ProjectInfoPropagator.JIRA_BOT) {
    log.debug("project-info-propagator: Change by jira.bot on ${issue.key} – skipping.")
    return
}

// ── Extract old / new values ─────────────────────────────────
String oldValue = relevantChange.getString("oldstring")
String newValue = relevantChange.getString("newstring")

if (!newValue?.trim()) {
    log.info("project-info-propagator: Field cleared on ${issue.key} – not propagating empty value.")
    return
}

if (oldValue?.trim() == newValue?.trim()) {
    log.debug("project-info-propagator: No effective change on ${issue.key}, skipping.")
    return
}

// ── Propagate ────────────────────────────────────────────────
log.info("project-info-propagator: User '${changedBy?.name ?: changedBy?.key}' " +
        "changed '${ProjectInfoPropagator.CF_PROJ_INF_N_NAME}' on ${issue.key} " +
        "from '${oldValue}' to '${newValue}' – starting propagation.")

new ProjectInfoPropagator().propagate(issue, newValue)
