package jobs.sap.export

import java.time.ZoneId
import java.time.format.DateTimeFormatter
/**
 * WorklogRow
 *
 * @author chabrecek.anton
 * Created on 15. 1. 2026.
 */
class WorklogExportRow {

    // ===== Property name constants (field names) =====
    static final String PROP_RELATED_INITIATIVE_KEY     = 'relatedInitiativeKey'
    static final String PROP_ISSUE_LINK                 = 'issueLink'
    static final String PROP_ISSUE_KEY                  = 'issueKey'
    static final String PROP_ISSUE_TYPE                 = 'issueType'
    static final String PROP_ASSIGNEE                   = 'assignee'
    static final String PROP_ORIGINAL_ESTIMATE_SECONDS  = 'originalEstimateSeconds'
    static final String PROP_REMAINING_ESTIMATE_SECONDS = 'remainingEstimateSeconds'
    static final String PROP_TIME_SPENT_SECONDS         = 'timeSpentSeconds'
    static final String PROP_TARGET_START               = 'targetStart'
    static final String PROP_TARGET_END                 = 'targetEnd'
    // static final String PROP_WORKLOG_TIME_SPENT_SECONDS = 'worklogTimeSpentSeconds'
    // static final String PROP_WORKLOG_AUTHOR            = 'worklogAuthor'
    // static final String PROP_WORKLOG_STARTED           = 'worklogStarted'

    String relatedInitiativeKey
    String issueLink
    String issueKey
    String issueType
    String assignee
    Long originalEstimateSeconds
    Long remainingEstimateSeconds
    Long timeSpentSeconds
    String targetStart
    String targetEnd
    //String worklogAuthor
    //String worklogStarted
    //Long worklogTimeSpentSeconds

    List<String> toCsvValues() {
        [
                relatedInitiativeKey,
                issueLink,
                issueKey,
                issueType,
                assignee,
                originalEstimateSeconds?.toString(),
                remainingEstimateSeconds?.toString(),
                timeSpentSeconds?.toString(),
                targetStart,
                targetEnd,
                //worklogAuthor,
                //worklogStarted,
                //worklogTimeSpentSeconds?.toString()
        ]
    }

    static List<String> csvHeader() {
        [
                "Related Initiative / Epic",
                "Issue link",
                "Issue key",
                "Issue type",
                "Assignee",
                "Original estimate (s)",
                "Remaining estimate (s)",
                "Time spent (s)",
                "Target start",
                "Target end",
                //"Worklog author",
                //"Worklog started",
                //"Worklog time spent (s)"
        ]
    }
}