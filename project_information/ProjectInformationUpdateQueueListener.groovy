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
        Map change = changedProjectInformation(event)
        if (!(change.detected as boolean)) return

        String configurationError = validateConfiguration()
        if (configurationError) {
            log.error("PI update listener disabled for ${eventIssue.key}: ${configurationError}")
            return
        }

        try {
            Map outcome = withIssueLock(eventIssue.id) {
                MutableIssue source = issueManager.getIssueObject(eventIssue.id) as MutableIssue
                if (!source) throw new IllegalStateException("Cannot reload ${eventIssue.key}")

                // The value comes from the change item, not from the reloaded issue. The incremental
                // job can win the race for this issue's lock and write an inherited value back
                // between the user's commit and this listener; a reload would then report the job's
                // value and the user's edit would be lost for good. The reload is only a fallback
                // for a change item that carries no readable value.
                String value = (change.valueKnown as boolean)
                        ? (change.value as String)
                        : selectValue(source)
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

    /**
     * The Project Information change carried by this event.
     *
     * detected    - Project Information N appears in the changelog at all
     * valueKnown  - the change item carries a value this listener can act on
     * value       - the label the user selected, or "" when the field was cleared
     *
     * Project Information holds at most one option, so exactly one change item is expected and its
     * newstring is taken as-is. Nothing is joined across items, so an option label containing a
     * comma is preserved.
     */
    private Map changedProjectInformation(IssueEvent event) {
        Map none = [detected: false, valueKnown: false, value: null]
        if (!cfProjectInformation) return none

        Set<String> aliases = [cfProjectInformation.id?.toLowerCase(Locale.ROOT),
                               cfProjectInformation.name?.toLowerCase(Locale.ROOT),
                               cfProjectInformation.idAsLong?.toString()].findAll { it } as Set<String>

        List items = event.changeLog?.getRelated("ChildChangeItem")?.findAll { item ->
            ["field", "fieldid"].any { String property ->
                String token = stringOf(item, property)?.trim()?.toLowerCase(Locale.ROOT)
                return token && aliases.contains(token)
            }
        } as List
        if (!items) return none

        def item = items.last()
        String display = stringOf(item, "newstring")
        if (display != null) return [detected: true, valueKnown: true, value: normalize(display)]

        // No display value: either the field was cleared, or only raw option IDs were recorded and
        // the reloaded issue has to answer instead.
        String stored = stringOf(item, "newvalue")
        if (!normalize(stored)) return [detected: true, valueKnown: true, value: ""]
        return [detected: true, valueKnown: false, value: null]
    }

    private static String stringOf(Object changeItem, String property) {
        try { return changeItem.getString(property) as String }
        catch (Exception ignored) { return null }
    }
}
