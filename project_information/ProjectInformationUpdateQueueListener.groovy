package project_information

import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField

/**
 * ProjectInformationUpdateQueueListener
 *
 * Fires on Issue Updated. It reacts to exactly one thing: a change of Project Information N.
 * Nothing else is inspected, so ordinary edits, transitions, hierarchy moves and issue type
 * changes cost one changelog scan and nothing more.
 *
 * The listener only writes the source issue and queues it:
 *   - a new value makes the issue its own Override Key
 *   - a cleared value makes the issue inherit from the closest ancestor authority
 *   - PI_SYNC_REQUIRED is set to "true" so the incremental job propagates to the descendants
 *
 * @author chabrecek.anton
 * Created on 26. 8. 2026.
 */
class ProjectInformationUpdateQueueListener extends ProjectInformationConfig {

    void handle(IssueEvent event) {
        Issue eventIssue = event?.issue
        if (!eventIssue?.id) return
        if (!inScope(eventIssue)) return

        // Internal writes never produce a change item, so a synchronization write cannot queue again.
        if (!projectInformationChanged(event)) return

        String configurationError = validateConfiguration()
        if (configurationError) {
            log.error("PI update listener disabled for ${eventIssue.key}: ${configurationError}")
            return
        }

        try {
            Map outcome = withIssueLock(eventIssue.id) {
                MutableIssue source = issueManager.getIssueObject(eventIssue.id) as MutableIssue
                if (!source) throw new IllegalStateException("Cannot reload ${eventIssue.key}")

                // The issue was reloaded after the change, so its own field is the authoritative
                // post-change value. The changelog is used for detection only.
                String value = selectValue(source)
                Map authority = value
                        ? [value: value, overrideKey: source.key]
                        : resolveEffectiveAuthority(getParent(source))

                String result = applyValues(source,
                        authority.value as String, authority.overrideKey as String, QUEUE_PENDING)
                if (result == "FAILED") {
                    throw new IllegalStateException("Source issue could not be written or queued")
                }
                return [key: source.key, authority: authority, result: result]
            }
            log.info("PI synchronization queued for ${outcome.key}; result=${outcome.result}; " +
                    "value='${outcome.authority.value ?: ''}'; override='${outcome.authority.overrideKey ?: ''}'")
        } catch (Exception e) {
            log.error("PI update listener failed for ${eventIssue.key}: ${e.message}", e)
        }
    }

    /** True only when Project Information N itself appears in the event changelog. */
    private boolean projectInformationChanged(IssueEvent event) {
        if (!cfProjectInformation) return false
        Set<String> aliases = [cfProjectInformation.id?.toLowerCase(Locale.ROOT),
                               cfProjectInformation.name?.toLowerCase(Locale.ROOT),
                               cfProjectInformation.idAsLong?.toString()].findAll { it } as Set<String>

        return event.changeLog?.getRelated("ChildChangeItem")?.any { item ->
            ["field", "fieldid"].any { String property ->
                try {
                    String token = item.getString(property)?.trim()?.toLowerCase(Locale.ROOT)
                    return token && aliases.contains(token)
                } catch (Exception ignored) {
                    return false
                }
            }
        } ?: false
    }
}
