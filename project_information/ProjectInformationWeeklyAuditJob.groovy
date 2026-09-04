package project_information

import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.user.ApplicationUser

/**
 * ProjectInformationWeeklyAuditJob
 *
 * Weekly consistency check. It always sends a report.
 *
 * By default the job is read-only. With -Dpi.audit.repair=true it does not repair anything itself
 * either; it only sets PI_SYNC_REQUIRED on the issue that is authoritative for each finding, so the
 * incremental job performs the actual correction through the normal path. Queueing the authority
 * rather than the broken issue matters: queueing a wrongly valued descendant would propagate the
 * wrong value further down.
 *
 * The flat scan cannot see an issue whose four fields are all empty, so authorities are additionally
 * checked against their direct children. A break anywhere in a subtree always shows up as an empty
 * or divergent direct child of a correct issue.
 *
 * @author chabrecek.anton
 * Created on 26. 8. 2026.
 */
class ProjectInformationWeeklyAuditJob extends ProjectInformationConfig {

    private static final boolean REPAIR_ENABLED =
            Boolean.parseBoolean(System.getProperty("pi.audit.repair", "false"))
    private static final boolean DEEP_CHECK_ENABLED =
            Boolean.parseBoolean(System.getProperty("pi.audit.deepCheck", "true"))

    void run() {
        List<Map> findings = []
        Set<Long> repairTargets = [] as Set<Long>
        List<Long> authorityIds = []
        int checked = 0
        int valid = 0
        int pending = 0
        int processing = 0
        int exhausted = 0

        try {
            clearHierarchyCache()

            String configurationError = validateConfiguration()
            if (configurationError) throw new IllegalStateException(configurationError)
            ApplicationUser bot = userManager.getUserByName(JIRA_BOT)
            if (!bot) throw new IllegalStateException("User '${JIRA_BOT}' not found")

            searchIssueIds(bot, scopedJql(auditJql())).each { Long id ->
                Issue issue = issueManager.getIssueObject(id)
                if (!issue) return
                checked++
                try {
                    String queue = normalize(textValue(issue, cfPiSyncRequired))
                    if (QUEUE_PENDING.equalsIgnoreCase(queue)) pending++
                    if (QUEUE_PROCESSING.equalsIgnoreCase(queue)) processing++
                    if (isExhausted(queue)) {
                        exhausted++
                        findings << finding(issue, "SYNC_GIVEN_UP", "", queue,
                                "Propagation failed ${MAX_SYNC_ATTEMPTS} times and is no longer retried")
                    }

                    if (auditIssue(issue, findings, repairTargets)) valid++

                    if (normalize(textValue(issue, cfOverride)) == issue.key && selectValue(issue)) {
                        authorityIds << id
                    }
                } catch (Exception issueError) {
                    findings << finding(issue, "ISSUE_AUDIT_FAILURE", "", "",
                            issueError.message ?: issueError.class.name)
                    log.error("Weekly PI audit failed for ${issue.key}: ${issueError.message}", issueError)
                }
            }

            if (DEEP_CHECK_ENABLED) {
                auditAuthorityChildren(authorityIds, bot, findings, repairTargets)
            }
            if (REPAIR_ENABLED && repairTargets) {
                queueRepairs(repairTargets, findings)
            }
        } catch (Exception e) {
            findings << [issue: "AUDIT", type: "JOB_FAILURE", value: "", actual: "",
                         description: e.message ?: e.class.name]
            log.error("Weekly PI audit failed: ${e.message}", e)
        }

        log.info("Weekly PI audit finished; checked=${checked}; valid=${valid}; pending=${pending}; " +
                "processing=${processing}; givenUp=${exhausted}; findings=${findings.size()}; " +
                "repairQueued=${REPAIR_ENABLED ? repairTargets.size() : 0}")
        sendReport(checked, valid, pending, processing, exhausted, repairTargets.size(), findings)
    }

    /** Returns true when the issue satisfies the invariant. */
    private boolean auditIssue(Issue issue, List<Map> findings, Set<Long> repairTargets) {
        String value = selectValue(issue)
        String text = normalize(textValue(issue, cfTextMirror))
        String override = normalize(textValue(issue, cfOverride))
        boolean ok = true

        if (value != text) {
            ok = false
            findings << finding(issue, "PI_TEXT_MISMATCH", value, text,
                    "Project Information N and the text mirror differ")
        }
        if (value && !override) {
            ok = false
            findings << finding(issue, "MISSING_OVERRIDE", value, override,
                    "Project Information is set but the Override Key is empty")
        }
        if (!value && override) {
            ok = false
            findings << finding(issue, "EMPTY_SELF_OVERRIDE", value, override,
                    "Override Key is set but there is no Project Information value")
        }

        Issue authority = override ? issueManager.getIssueObject(override) : null
        if (override && !authority) {
            ok = false
            findings << finding(issue, "INVALID_OVERRIDE", value, override,
                    "Override Key does not reference an existing issue")
        } else if (override && override != issue.key && !isAncestor(issue, authority.id)) {
            ok = false
            findings << finding(issue, "OVERRIDE_NOT_ANCESTOR", value, override,
                    "Override Key exists but is not an ancestor of this issue")
        }

        Map expected = null
        if (override != issue.key) {
            expected = resolveEffectiveAuthority(getParent(issue))
            if (value != normalize(expected.value as String)) {
                ok = false
                findings << finding(issue, "INHERITED_VALUE_MISMATCH", value, expected.value,
                        "Value differs from the effective parent authority")
            }
            if (override != normalize(expected.overrideKey as String)) {
                ok = false
                findings << finding(issue, "INHERITED_OVERRIDE_MISMATCH", override, expected.overrideKey,
                        "Override Key differs from the effective parent authority")
            }
        }

        if (!ok) {
            Long target = repairTarget(issue, value, override, expected)
            if (target) repairTargets << target
            else findings << finding(issue, "REPAIR_NOT_POSSIBLE", value, override,
                    "No authority could be determined; this issue needs a manual decision")
        }
        return ok
    }

    /**
     * The issue whose queue entry fixes this finding: the issue itself when it is a valid self
     * authority, otherwise the ancestor authority that should be governing it.
     */
    private Long repairTarget(Issue issue, String value, String override, Map expected) {
        if (override == issue.key && value) return issue.id
        String authorityKey = normalize(expected?.overrideKey as String)
        if (authorityKey) {
            Issue authority = issueManager.getIssueObject(authorityKey)
            if (authority) return authority.id
        }
        return null
    }

    /** Detects subtrees the flat scan cannot see, because every field on them is empty. */
    private void auditAuthorityChildren(List<Long> authorityIds, ApplicationUser bot,
                                        List<Map> findings, Set<Long> repairTargets) {
        authorityIds.collate(100).each { List<Long> chunk ->
            List<Issue> authorities = chunk.collect { issueManager.getIssueObject(it) }.findAll { it }
            if (!authorities) return
            Map<Long, Issue> byId = authorities.collectEntries { [(it.id): it] }

            findDirectChildren(authorities, bot).each { Issue child ->
                Issue parent = getParent(child)
                Issue authority = parent ? byId[parent.id] : null
                if (!authority) return

                String childValue = selectValue(child)
                String childOverride = normalize(textValue(child, cfOverride))
                if (childOverride == child.key && childValue) return  // legitimate boundary

                String expectedValue = selectValue(authority)
                String expectedOverride = normalize(textValue(authority, cfOverride)) ?: authority.key
                if (childValue == expectedValue && childOverride == expectedOverride) return

                findings << finding(child, "MISSING_INHERITANCE", childValue, expectedValue,
                        "Direct child of authority ${authority.key} does not carry the inherited value")
                repairTargets << authority.id
            }
        }
    }

    /** Repair means queueing the authority; the incremental job performs the correction itself. */
    private void queueRepairs(Set<Long> targets, List<Map> findings) {
        targets.each { Long id ->
            try {
                withIssueLock(id) {
                    MutableIssue issue = issueManager.getIssueObject(id) as MutableIssue
                    if (!issue) return null
                    String state = normalize(textValue(issue, cfPiSyncRequired))
                    // Do not disturb an entry the incremental job is already working on.
                    if (QUEUE_PROCESSING.equalsIgnoreCase(state) || QUEUE_PENDING.equalsIgnoreCase(state)) {
                        return null
                    }
                    setQueueState(issue, QUEUE_PENDING)
                    log.info("${issue.key}: queued for repair by the incremental job")
                    return null
                }
            } catch (Exception e) {
                findings << [issue: "id=${id}", type: "REPAIR_FAILED", value: "", actual: "",
                             description: e.message ?: e.class.name]
            }
        }
    }

    private boolean isAncestor(Issue issue, Long expectedAncestorId) {
        Set<Long> seen = [] as Set<Long>
        Issue current = getParent(issue)
        int depth = 0
        while (current && depth++ < MAX_HIERARCHY_DEPTH) {
            if (!seen.add(current.id)) throw new IllegalStateException("Hierarchy cycle detected at ${current.key}")
            if (current.id == expectedAncestorId) return true
            current = getParent(current)
        }
        if (current) throw new IllegalStateException("Hierarchy exceeds ${MAX_HIERARCHY_DEPTH} levels")
        return false
    }

    private static Map finding(Issue issue, String type, Object value, Object actual, String description) {
        [issue: issue.key, type: type, value: value ?: "", actual: actual ?: "", description: description]
    }

    private String auditJql() {
        return "cf[${numericId(CF_PROJECT_INFORMATION_N)}] is not EMPTY OR " +
                "cf[${numericId(CF_PROJECT_INFORMATION_TEXT)}] is not EMPTY OR " +
                "cf[${numericId(CF_PROJECT_INFORMATION_OVERRIDE_KEY)}] is not EMPTY OR " +
                "cf[${numericId(CF_PI_SYNC_REQUIRED)}] is not EMPTY"
    }

    private void sendReport(int checked, int valid, int pending, int processing,
                            int exhausted, int repairTargets, List<Map> findings) {
        List<List<Object>> rows = findings.collect {
            [it.issue, it.type, it.value, it.actual, it.description] as List<Object>
        }
        String repairMode = REPAIR_ENABLED ? "on, ${repairTargets} authorities queued" : "off, report only"
        String body = "<h2>Weekly Project Information consistency report</h2>" +
                "<p>Issues checked: ${checked}<br/>" +
                "Valid issues: ${valid}<br/>" +
                "Pending queue entries: ${pending}<br/>" +
                "Currently processing: ${processing}<br/>" +
                "Given up after ${MAX_SYNC_ATTEMPTS} attempts: ${exhausted}<br/>" +
                "Findings: ${findings.size()}<br/>" +
                "Repair mode: ${repairMode}</p>" +
                htmlTable(["Issue", "Finding", "Value", "Expected / actual", "Description"], rows, "No findings")
        sendMail(findings ? "[PI Audit] Findings reported" : "[PI Audit] Successful", body)
    }
}
