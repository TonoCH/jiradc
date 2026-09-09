package project_information

import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.user.ApplicationUser

/**
 * ProjectInformationIncrementalSyncJob
 *
 * Runs every five minutes and propagates every queued source to its descendants.
 * Sends mail only when something fails.
 *
 * Queue state machine, guarded by a per-issue cluster lock:
 *
 *   true          a listener marked this issue as a source
 *   PROCESSING    claimed by this job
 *   (empty)       propagated successfully
 *   FAILED n      propagation failed n times; retried until MAX_SYNC_ATTEMPTS, then left alone
 *
 * If a listener sets the state back to "true" while the job is working, the job does not clear it
 * and the item is processed again in the next run, so a concurrent user edit always wins.
 *
 * @author chabrecek.anton
 * Created on 26. 8. 2026.
 */
class ProjectInformationIncrementalSyncJob extends ProjectInformationConfig {

    void run() {
        try {
            boolean executed = tryWithNamedLock("${LOCK_NAMESPACE}-job") { runExclusive() }
            if (!executed) log.info("PI incremental job skipped because another cluster node is running it")
        } catch (Exception e) {
            log.error("PI incremental job could not complete its run: ${e.message}", e)
            sendErrorMail(0, 0, 0, 0, 0, [[root: "JOB", issue: "", error: e.message ?: e.class.name]])
        }
    }

    private void runExclusive() {
        // ScriptRunner may reuse the instance; hierarchy data is valid for one execution only.
        clearHierarchyCache()

        List<Map> errors = []
        int rootsFound = 0
        int rootsCompleted = 0
        int rootsExhausted = 0
        int visitedCount = 0
        int updatedCount = 0

        String configurationError = validateConfiguration()
        ApplicationUser bot = userManager.getUserByName(JIRA_BOT)
        if (configurationError || !bot) {
            String message = configurationError ?: "User '${JIRA_BOT}' not found"
            log.error("PI incremental job cannot start: ${message}")
            sendErrorMail(0, 0, 0, 0, 0, [[root: "CONFIG", issue: "", error: message]])
            return
        }

        recoverAbandonedClaims(bot, errors)

        List<Long> rootIds
        try {
            rootIds = searchIssueIds(bot, scopedJql(queueSearchJql()))
        } catch (Exception e) {
            log.error("PI incremental job could not read the queue: ${e.message}", e)
            sendErrorMail(0, 0, 0, 0, 0, [[root: "SEARCH", issue: "", error: e.message ?: e.class.name]])
            return
        }

        rootIds.each { Long rootId ->
            String rootKey = null
            // Stays -1 until the claim succeeds, so the catch block below can only advance the
            // attempt counter with the real number of previous attempts. Resetting it there would
            // recreate the endless five-minute retry this state machine exists to prevent.
            int claimedAttempts = -1
            try {
                Map claim = claimRoot(rootId)
                if (!(claim.claimed as boolean)) {
                    if (claim.exhausted as boolean) rootsExhausted++
                    return
                }

                rootsFound++
                rootKey = claim.key as String
                claimedAttempts = claim.attempts as int
                boolean failed

                if (claim.problem) {
                    failed = true
                    errors << [root: rootKey, issue: rootKey, error: claim.problem]
                    log.error("${rootKey}: not propagated: ${claim.problem}")
                } else {
                    Set<Long> visited = [] as Set<Long>
                    Map result = distribute(claim.root as MutableIssue, claim.value as String,
                            claim.override as String, bot, visited, errors)
                    visitedCount += visited.size()
                    updatedCount += result.updated as int
                    failed = result.failed as boolean
                }

                boolean applied = releaseClaim(rootId, failed, claimedAttempts)
                if (!applied) {
                    log.info("${rootKey}: queue changed during processing; retained for the next run")
                } else if (failed) {
                    log.warn("${rootKey}: propagation failed, " +
                            "attempt ${claimedAttempts + 1}/${MAX_SYNC_ATTEMPTS}")
                } else {
                    rootsCompleted++
                }
            } catch (Exception e) {
                String key = rootKey ?: "id=${rootId}"
                errors << [root: key, issue: key, error: e.message ?: e.class.name]
                log.error("PI incremental synchronization failed for ${key}: ${e.message}", e)
                if (claimedAttempts >= 0) {
                    try {
                        releaseClaim(rootId, true, claimedAttempts)
                    } catch (Exception releaseError) {
                        log.error("${key}: could not release the claim: ${releaseError.message}", releaseError)
                    }
                }
            }
        }

        log.info("PI incremental synchronization finished; rootsClaimed=${rootsFound}; " +
                "rootsCompleted=${rootsCompleted}; rootsExhausted=${rootsExhausted}; " +
                "visited=${visitedCount}; updated=${updatedCount}; errors=${errors.size()}")
        if (errors) {
            sendErrorMail(rootsFound, rootsCompleted, rootsExhausted, visitedCount, updatedCount, errors)
        }
    }

    /**
     * Moves a claimable root from "true" or "FAILED n" to PROCESSING and snapshots its value.
     * The snapshot is what gets propagated; a concurrent edit re-queues the issue instead.
     *
     * The snapshot is read before the claim is written, so a root whose own data is broken is
     * reported through the normal failure path instead of throwing and leaving a PROCESSING entry
     * that the next run would only have to recover.
     */
    private Map claimRoot(Long rootId) {
        return withIssueLock(rootId) {
            MutableIssue latest = issueManager.getIssueObject(rootId) as MutableIssue
            if (!latest) return [claimed: false, exhausted: false]

            String state = normalize(textValue(latest, cfPiSyncRequired))
            int attempts = claimableAttempts(state)
            if (attempts < 0) return [claimed: false, exhausted: isExhausted(state)]

            String value = ""
            String override = ""
            String problem = null
            try {
                value = selectValue(latest)
                override = normalize(textValue(latest, cfOverride))
                if ((value && !override) || (!value && override)) {
                    problem = "Inconsistent value/Override Key pair: " +
                            "value='${value}', override='${override}'"
                }
            } catch (Exception e) {
                problem = e.message ?: e.class.name
            }

            setQueueState(latest, QUEUE_PROCESSING)
            return [claimed: true, exhausted: false, root: latest, key: latest.key,
                    value: value, override: override, attempts: attempts, problem: problem]
        }
    }

    /**
     * Writes the final queue state, but only while the claim still holds. Returns false when a
     * listener re-queued the issue in the meantime, in which case the entry is kept for the next run.
     */
    private boolean releaseClaim(Long rootId, boolean failed, int attempts) {
        return withIssueLock(rootId) {
            MutableIssue latest = issueManager.getIssueObject(rootId) as MutableIssue
            if (!latest) return false
            if (!QUEUE_PROCESSING.equalsIgnoreCase(normalize(textValue(latest, cfPiSyncRequired)))) return false
            setQueueState(latest, failed ? failedState(attempts + 1) : "")
            return true
        }
    }

    /** A node that died mid-run leaves PROCESSING behind; put those entries back into the queue. */
    private void recoverAbandonedClaims(ApplicationUser bot, List<Map> errors) {
        List<Long> abandoned
        try {
            abandoned = searchIssueIds(bot, scopedJql(
                    "cf[${numericId(CF_PI_SYNC_REQUIRED)}] ~ \"${QUEUE_PROCESSING}\""))
        } catch (Exception e) {
            errors << [root: "RECOVERY", issue: "", error: e.message ?: e.class.name]
            return
        }

        abandoned.each { Long id ->
            try {
                withIssueLock(id) {
                    MutableIssue latest = issueManager.getIssueObject(id) as MutableIssue
                    if (latest && QUEUE_PROCESSING.equalsIgnoreCase(
                            normalize(textValue(latest, cfPiSyncRequired)))) {
                        setQueueState(latest, QUEUE_PENDING)
                        log.warn("${latest.key}: recovered an abandoned PROCESSING queue state")
                    }
                    return null
                }
            } catch (Exception e) {
                errors << [root: "RECOVERY", issue: "id=${id}",
                           error: "Could not recover the PROCESSING state: ${e.message ?: e.class.name}"]
            }
        }
    }

    /**
     * Breadth-first walk over the descendants of a claimed root. A descendant that is its own
     * authority is a boundary: it keeps its value and its subtree is skipped, because that subtree
     * is governed by the boundary's own queue entry.
     */
    private Map distribute(MutableIssue root, String value, String override,
                           ApplicationUser bot, Set<Long> visited, List<Map> errors) {
        int updated = 0
        boolean failed = false
        visited.add(root.id)

        List<Issue> frontier = findDirectChildren([root], bot)
        int depth = 0

        while (frontier && depth++ < MAX_HIERARCHY_DEPTH) {
            List<Issue> expandable = []
            frontier.sort { it.key }.each { Issue child ->
                if (!child || !visited.add(child.id)) return

                Map childResult = withIssueLock(child.id) {
                    MutableIssue mutableChild = issueManager.getIssueObject(child.id) as MutableIssue
                    if (!mutableChild) return [status: "MISSING"]

                    String childOverride = normalize(textValue(mutableChild, cfOverride))
                    if (childOverride == mutableChild.key && selectValue(mutableChild)) {
                        return [status: "BOUNDARY", issue: mutableChild]
                    }
                    return [status: applyValues(mutableChild, value, override, null), issue: mutableChild]
                }

                switch (childResult.status) {
                    case "BOUNDARY":
                        log.debug("${child.key}: independent override root; subtree skipped")
                        return
                    case "MISSING":
                        failed = true
                        errors << [root: root.key, issue: child.key, error: "Cannot reload the descendant"]
                        return
                    case "FAILED":
                        failed = true
                        errors << [root: root.key, issue: child.key, error: "Values could not be synchronized"]
                        return
                    case "UPDATED":
                        updated++
                        break
                }
                expandable << (childResult.issue as Issue)
            }
            frontier = findDirectChildren(expandable, bot)
        }

        if (frontier) {
            failed = true
            errors << [root: root.key, issue: root.key,
                       error: "Hierarchy exceeds ${MAX_HIERARCHY_DEPTH} levels"]
        }
        return [updated: updated, failed: failed]
    }

    private void sendErrorMail(int rootsFound, int rootsCompleted, int rootsExhausted,
                               int visited, int updated, List<Map> errors) {
        List<List<Object>> rows = errors.collect { [it.root, it.issue, it.error] as List<Object> }
        String body = "<h2>Project Information incremental synchronization errors</h2>" +
                "<p>Roots claimed: ${rootsFound}<br/>" +
                "Roots completed: ${rootsCompleted}<br/>" +
                "Roots given up on after ${MAX_SYNC_ATTEMPTS} attempts: ${rootsExhausted}<br/>" +
                "Issues visited: ${visited}<br/>" +
                "Issues updated: ${updated}<br/>" +
                "Errors: ${errors.size()}</p>" +
                htmlTable(["Root", "Issue", "Error"], rows, "No errors")
        sendMail("[PI Sync] Errors found", body)
    }
}
