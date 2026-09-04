package project_information

import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue

/**
 * ProjectInformationCreateListener
 *
 * Fires on Issue Created. The hierarchy level of a new issue is not known in advance, so the
 * listener decides between the two possible roles:
 *
 *   1. The user supplied a Project Information value on the create screen. The new issue is then
 *      its own source of truth and becomes its own Override Key. It is queued, because during an
 *      import or a hierarchy clone children can already exist before their parent is created.
 *
 *   2. No value was supplied. The listener resolves the closest ancestor that carries a value and
 *      copies that value together with the ancestor's Override Key. Nothing is queued: a freshly
 *      created inheriting issue has no descendants of its own.
 *
 * @author chabrecek.anton
 * Created on 26. 8. 2026.
 */
class ProjectInformationCreateListener extends ProjectInformationConfig {

    void handle(IssueEvent event) {
        Issue created = event?.issue
        if (!created?.id) return
        if (!inScope(created)) return

        String configurationError = validateConfiguration()
        if (configurationError) {
            log.error("PI create listener disabled for ${created.key}: ${configurationError}")
            return
        }

        try {
            String authorityToRequeue = withIssueLock(created.id) {
                MutableIssue issue = issueManager.getIssueObject(created.id) as MutableIssue
                if (!issue) throw new IllegalStateException("Cannot reload ${created.key}")

                String suppliedValue = selectValue(issue)
                if (suppliedValue) {
                    // A value supplied on create is a deliberate local override, including on a root issue.
                    String result = applyValues(issue, suppliedValue, issue.key, QUEUE_PENDING)
                    log.debug("${issue.key}: created as its own authority, value='${suppliedValue}', result=${result}")
                    if (result == "FAILED") {
                        throw new IllegalStateException("Could not initialize ${issue.key} as its own authority")
                    }
                    return null
                }

                Issue parent = getParent(issue)
                if (!parent) {
                    log.debug("${issue.key}: no parent and no supplied value; nothing to inherit")
                    return null
                }

                Map authority = resolveEffectiveAuthority(parent)
                if (!authority.value) {
                    log.debug("${issue.key}: no ancestor carries a Project Information value")
                    return null
                }

                String result = applyValues(issue, authority.value as String, authority.overrideKey as String, null)
                log.debug("${issue.key}: inherited value='${authority.value}', " +
                        "override='${authority.overrideKey}', result=${result}")

                // A failed inheritance write leaves this issue with an empty value and an empty
                // Override Key. Queueing the issue itself would be useless: the job would treat that
                // empty pair as consistent, propagate emptiness to its descendants and clear the
                // entry, so the inheritance would never be retried. The authority is the correct
                // retry root, and it is queued outside this lock so two issue locks are never held
                // at the same time.
                return result == "FAILED" ? (authority.overrideKey as String) : null
            }

            if (authorityToRequeue) requeueAuthority(authorityToRequeue, created.key)
        } catch (Exception e) {
            log.error("PI initialization failed for ${created.key}: ${e.message}", e)
            markRepairNotPossible(created.id, created.key, "Issue Created", e)
        }
    }

    private void requeueAuthority(String authorityKey, String failedIssueKey) {
        Issue authority = issueManager.getIssueObject(authorityKey)
        if (!authority) {
            log.error("${failedIssueKey}: could not inherit and its authority '${authorityKey}' " +
                    "no longer exists; the weekly audit will report this issue")
            return
        }
        boolean queued = queueForSync(authority.id)
        log.warn("${failedIssueKey}: inheritance write failed; authority ${authorityKey} " +
                (queued ? "queued for re-propagation" : "already has a queue or failure state"))
    }
}
