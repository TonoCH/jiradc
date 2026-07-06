package jobs.global.projectInformation

/**
 * ProjectInformationConsistencyJob
 *
 * Weekly consistency job for Project Information hierarchy.
 *
 * Responsibilities:
 *  - Validate inherited branches (flag 0/empty) against their inheritance source.
 *  - Validate overridden branches (flag 1) and keep them internally consistent.
 *  - Validate synchronization between 18600 (select) and 18700 (text mirror).
 *  - Automatically repair inconsistencies (compare-before-update, DO_NOT_DISPATCH).
 *  - Send a summary email report with a CSV attachment listing every repair.
 *
 * * Authority model:
 *  - Initiative is the master source of its own value and does not use the flag.
 *  - Flag 0 means the issue inherits Project Information from the nearest Initiative.
 *  - Flag 1 means the issue is an OVERRIDE ROOT and is authoritative for its own 18600 value.
 *  - Flag 2 means the issue inherits Project Information from the nearest override root.
 *  - Nested overrides are supported: any non-Initiative issue with flag 1 starts a new override branch.
 *
 * Excluded projects (ProjectInformationHandler.EXCLUDED_PROJECT_KEYS) are skipped
 * together with their subtree, exactly like in the listener.
 *
 * @author chabrecek.anton
 * Created on 3. 7. 2026.
 */
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.permission.ProjectPermissions
import com.atlassian.jira.project.Project
import com.atlassian.jira.security.PermissionManager
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.user.util.UserManager
import listeners.global_area.projectInformation.ProjectInformationHandler2
import org.apache.log4j.Logger
import utils.mail_notifiers.Mailer

class ProjectInformationConsistencyJob {

    /** Jira usernames that receive the weekly summary report. */
    static final List<String> REPORT_RECIPIENTS = [
            "jira.bot",
    ].asImmutable()

    private final Logger log = Logger.getLogger("scriptrunner.project-information-consistency-job")

    private final UserManager userManager = ComponentAccessor.userManager
    private final PermissionManager permissionManager = ComponentAccessor.permissionManager
    private final ProjectInformationHandler2 handler = new ProjectInformationHandler2()
    private final CustomFieldManager cfManager = ComponentAccessor.customFieldManager

    // ----- run statistics -----
    private int initiativesScanned = 0
    private int issuesVisited = 0
    private int repaired = 0
    private int anomalies = 0
    private int errors = 0

    /** Project keys the service account cannot Browse (silent search gap). */
    private final List<String> projectsWithoutBrowse = []

    private final List<String[]> csvRows = []
    private final Set<Long> visited = new HashSet<>()

    /**
     * Entry point for the scheduled job.
     */
    void run() {
        resetState()

        ApplicationUser writeUser = userManager.getUserByName(ProjectInformationHandler2.JIRA_BOT)

        if (!writeUser) {
            log.error("Project Information consistency job: service user '${ProjectInformationHandler2.JIRA_BOT}' not found. Aborting.")
            return
        }

        log.info("START Project Information consistency job, user=${writeUser.name}")

        try {
            validateConfiguration()
            checkBrowsePermissions(writeUser)

            List<Issue> initiatives = handler.findIssuesAsUserPaged(
                    writeUser,
                    "issuetype = \"${ProjectInformationHandler2.ISSUE_TYPE_INITIATIVE}\""
            )

            initiatives.each { Issue initiative ->
                if (handler.isExcludedProject(initiative)) {
                    return
                }

                initiativesScanned++

                try {
                    validateTree(initiative, null, false, writeUser)
                } catch (Exception e) {
                    errors++
                    log.error("Consistency job failed for tree rooted at ${initiative?.key}: ${e.message}", e)

                    recordRow(initiative, "TREE_EXCEPTION",
                            handler.getProjectInformationValue(initiative),
                            handler.getProjectInformationValue(initiative),
                            handler.getTextMirrorValue(initiative),
                            handler.getTextMirrorValue(initiative),
                            null,
                            null,
                            "EXCEPTION",
                            "${e.class.simpleName}: ${e.message}")
                }
            }
        } catch (Exception e) {
            errors++
            log.error("Project Information consistency job failed: ${e.message}", e)

            recordRow(null, "JOB_EXCEPTION",
                    null, null,
                    null, null,
                    null, null,
                    "EXCEPTION",
                    "${e.class.simpleName}: ${e.message}")
        } finally {
            log.info("DONE Project Information consistency job. initiatives=${initiativesScanned}, visited=${issuesVisited}, repaired=${repaired}, anomalies=${anomalies}, errors=${errors}, projectsWithoutBrowse=${projectsWithoutBrowse.size()}")
            sendReport(writeUser)
        }
    }

    private void validateConfiguration() {
        List<String> missing = []

        if (!cfManager.getCustomFieldObject(ProjectInformationHandler2.CF_PROJECT_INFORMATION_ID)) {
            missing.add("CF_PROJECT_INFORMATION_ID=${ProjectInformationHandler2.CF_PROJECT_INFORMATION_ID}")
        }

        if (!cfManager.getCustomFieldObject(ProjectInformationHandler2.CF_PROJECT_INFORMATION_TEXT_ID)) {
            missing.add("CF_PROJECT_INFORMATION_TEXT_ID=${ProjectInformationHandler2.CF_PROJECT_INFORMATION_TEXT_ID}")
        }

        if (!cfManager.getCustomFieldObject(ProjectInformationHandler2.CF_PROJECT_INFORMATION_FLAG_ID)) {
            missing.add("CF_PROJECT_INFORMATION_FLAG_ID=${ProjectInformationHandler2.CF_PROJECT_INFORMATION_FLAG_ID}")
        }

        if (!cfManager.getCustomFieldObject(ProjectInformationHandler2.CF_PARENT_LINK)) {
            missing.add("CF_PARENT_LINK=${ProjectInformationHandler2.CF_PARENT_LINK}")
        }

        if (!ProjectInformationHandler2.CF_EPIC_LINK || !cfManager.getCustomFieldObject(ProjectInformationHandler2.CF_EPIC_LINK)) {
            missing.add("CF_EPIC_LINK=${ProjectInformationHandler2.CF_EPIC_LINK}")
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Project Information consistency job configuration invalid. Missing custom field(s): ${missing.join(', ')}"
            )
        }
    }

    /**
     * Verifies that the service account can Browse every non-excluded project.
     * Missing Browse permission is a silent gap: searches simply return nothing,
     * so those subtrees are never validated. Reported in the summary mail.
     */
    private void checkBrowsePermissions(ApplicationUser serviceUser) {
        List<Project> projects = ComponentAccessor.projectManager.projectObjects

        projects.each { Project project ->
            if (ProjectInformationHandler2.EXCLUDED_PROJECT_KEYS.contains(project.key)) {
                return
            }

            boolean canBrowse = permissionManager.hasPermission(
                    ProjectPermissions.BROWSE_PROJECTS, project, serviceUser)

            if (!canBrowse) {
                projectsWithoutBrowse.add(project.key)
                log.warn("Service account '${serviceUser.name}' has no Browse permission on project '${project.key}'. Issues there will not be validated/propagated.")
            }
        }
    }

    /**
     * Recursively validates and repairs a single issue and its descendants.
     *
     * @param inheritedValue value expected on this node if it inherits from Initiative or override root
     * @param inheritedFromOverride true when current inheritance source is an override root
     */
    private void validateTree(
            Issue issue,
            String inheritedValue,
            boolean inheritedFromOverride,
            ApplicationUser writeUser
    ) {
        if (!issue || visited.contains(issue.id)) {
            return
        }

        if (handler.isExcludedProject(issue)) {
            log.info("SKIP ${issue.key}: excluded project, subtree skipped.")
            return
        }

        visited.add(issue.id)
        issuesVisited++

        boolean initiative = handler.isInitiative(issue)
        Integer flag = initiative ? null : handler.getFlagValue(issue)
        String currentPi = handler.getProjectInformationValue(issue)

        String branch
        String expectedPi
        Double expectedFlag
        String childValue
        boolean childInheritedFromOverride

        if (initiative) {
            branch = "INITIATIVE"
            expectedPi = currentPi
            expectedFlag = null
            childValue = currentPi
            childInheritedFromOverride = false
        } else if (isEffectiveOverrideRoot(flag, currentPi, inheritedValue, inheritedFromOverride)) {
            branch = "OVERRIDE_ROOT"
            expectedPi = currentPi
            expectedFlag = ProjectInformationHandler2.FLAG_OVERRIDE_ROOT
            childValue = currentPi
            childInheritedFromOverride = true
        } else if (inheritedFromOverride) {
            branch = "INHERITED_FROM_OVERRIDE"
            expectedPi = inheritedValue
            expectedFlag = ProjectInformationHandler2.FLAG_INHERITED_FROM_OVERRIDE
            childValue = inheritedValue
            childInheritedFromOverride = true
        } else {
            branch = "INHERITED_FROM_INITIATIVE"
            expectedPi = inheritedValue
            expectedFlag = ProjectInformationHandler2.FLAG_INHERITED_FROM_INITIATIVE
            childValue = inheritedValue
            childInheritedFromOverride = false
        }

        if (!expectedPi) {
            anomalies++
            log.warn("ANOMALY ${issue.key} (${branch}): expected Project Information value is empty. Cannot repair, subtree not propagated.")

            recordRow(issue, branch, currentPi, currentPi,
                    handler.getTextMirrorValue(issue), handler.getTextMirrorValue(issue),
                    flag, flag, "ANOMALY_EMPTY_VALUE",
                    "Issue has no authoritative Project Information value to inherit from nearest Initiative or override root.")

            return
        }

        repairIfNeeded(issue, branch, expectedPi, expectedFlag, currentPi, flag, writeUser)

        try {
            handler.getChildren(issue, writeUser).each { Issue child ->
                validateTree(child, childValue, childInheritedFromOverride, writeUser)
            }
        } catch (Exception e) {
            errors++
            log.error("Failed to read children of ${issue?.key}: ${e.message}", e)

            recordRow(issue, branch, currentPi, currentPi,
                    handler.getTextMirrorValue(issue), handler.getTextMirrorValue(issue),
                    flag, flag, "CHILDREN_READ_EXCEPTION",
                    "${e.class.simpleName}: ${e.message}")
        }
    }

    /**
     * Determines whether the current issue should be treated as a real override root.
     *
     * Migration-safe rule:
     *  - Outside an override branch, flag 1 always means override root.
     *  - Inside an override branch:
     *      - flag 1 + different value than inherited authority = nested override root
     *      - flag 1 + same value as inherited authority = old inherited override descendant, repair to flag 2
     */
    private boolean isEffectiveOverrideRoot(
            Integer flag,
            String currentPi,
            String inheritedValue,
            boolean inheritedFromOverride
    ) {
        if (flag == null || flag != ProjectInformationHandler2.FLAG_OVERRIDE_ROOT_INT) {
            return false
        }

        if (!inheritedFromOverride) {
            return true
        }

        return normalize(currentPi) != normalize(inheritedValue)
    }

    /**
     * Compares current field values with the expected ones and repairs if they differ.
     * Records a CSV row for every issue that required a repair.
     */
    private void repairIfNeeded(
            Issue issue,
            String branch,
            String expectedPi,
            Double expectedFlag,
            String currentPi,
            Integer currentFlag,
            ApplicationUser writeUser
    ) {
        String currentText = handler.getTextMirrorValue(issue)

        boolean piMismatch = normalize(currentPi) != normalize(expectedPi)
        boolean textMismatch = normalize(currentText) != normalize(expectedPi)
        boolean flagMismatch = expectedFlag != null && (currentFlag == null || currentFlag != expectedFlag.intValue())

        if (!piMismatch && !textMismatch && !flagMismatch) {
            return
        }

        try {
            handler.setIssueFields(issue, expectedPi, expectedFlag, writeUser, true)

            // Re-read to report the real post-repair state.
            Issue after = ComponentAccessor.issueManager.getIssueObject(issue.id)
            String piAfter = handler.getProjectInformationValue(after)
            String textAfter = handler.getTextMirrorValue(after)
            Integer flagAfter = handler.getFlagValue(after)

            boolean stillWrong =
                    normalize(piAfter) != normalize(expectedPi) ||
                    normalize(textAfter) != normalize(expectedPi) ||
                    (expectedFlag != null && (flagAfter == null || flagAfter != expectedFlag.intValue()))

            String result = stillWrong ? "REPAIR_FAILED" : "REPAIRED"
            String detail = ""

            if (stillWrong) {
                errors++
                detail = "Value still not consistent after update (expected value='${expectedPi}', flag=${expectedFlag?.intValue()}). " +
                        "Likely missing option in field context or the service account cannot edit this issue."
                log.warn("REPAIR_FAILED ${issue.key} (${branch}): ${detail}")
            } else {
                repaired++
                log.info("REPAIRED ${issue.key} (${branch}): value='${expectedPi}', flag=${expectedFlag}")
            }

            recordRow(issue, branch, currentPi, piAfter, currentText, textAfter, currentFlag, flagAfter, result, detail)
        } catch (Exception e) {
            errors++
            log.error("Consistency repair failed for ${issue.key} (${branch}): ${e.message}", e)
            recordRow(issue, branch, currentPi, currentPi, currentText, currentText, currentFlag, currentFlag,
                    "EXCEPTION", "${e.class.simpleName}: ${e.message}")
        }
    }

    private void recordRow(
            Issue issue,
            String branch,
            String piBefore, String piAfter,
            String textBefore, String textAfter,
            Integer flagBefore, Integer flagAfter,
            String result,
            String detail
    ) {
        csvRows.add([
                new Date().format("yyyy-MM-dd HH:mm:ss"),
                issue?.key,
                issue?.projectObject?.key,
                issue?.issueType?.name,
                branch,
                piBefore,
                piAfter,
                textBefore,
                textAfter,
                flagBefore?.toString(),
                flagAfter?.toString(),
                result,
                detail
        ] as String[])
    }

    // ---------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------

    private void sendReport(ApplicationUser writeUser) {
        if (REPORT_RECIPIENTS.isEmpty()) {
            log.warn("No report recipients configured. Consistency report not sent.")
            return
        }

        boolean hasFailures = (errors > 0 || anomalies > 0 || !projectsWithoutBrowse.isEmpty())
        String flag = hasFailures ? "[ACTION NEEDED] " : "[OK] "

        int consistent = issuesVisited - csvRows.size()
        if (consistent < 0) {
            consistent = 0
        }

        String subject = "${flag}Jira Project Information - weekly consistency report " +
                "(${repaired} repaired, ${anomalies} anomalies, ${errors} failed)"

        String permissionSection = projectsWithoutBrowse.isEmpty() ?
                "Browse permission     : OK for all scanned projects" :
                "Browse permission     : MISSING for '${ProjectInformationHandler2.JIRA_BOT}' on ${projectsWithoutBrowse.size()} project(s):\n" +
                        "                        ${projectsWithoutBrowse.join(", ")}\n" +
                        "                        Issues in these projects are NOT validated/propagated - grant Browse to the service account."

        String body =
        """Project Information weekly consistency job finished.

        Initiatives scanned   : ${initiativesScanned}
        Issues visited        : ${issuesVisited}
        Consistent (no change): ${consistent}
        Repaired              : ${repaired}
        Anomalies             : ${anomalies}
        Failed / errors       : ${errors}
        Excluded projects     : ${ProjectInformationHandler2.EXCLUDED_PROJECT_KEYS.isEmpty() ? "none" : ProjectInformationHandler2.EXCLUDED_PROJECT_KEYS.join(", ")}
        ${permissionSection}
        
        ${csvRows.isEmpty() ? "No inconsistencies found - hierarchy is fully consistent." : "See attached CSV for the full list of affected issues. Rows with Result = REPAIR_FAILED / EXCEPTION / ANOMALY_EMPTY_VALUE could NOT be written - check the Detail column for the reason (often missing Browse/Edit permission for the service account on the affected project)."}
        """

        Mailer mailer = new Mailer()

        // Nothing to attach -> plain summary mail.
        if (csvRows.isEmpty()) {
            REPORT_RECIPIENTS.each { String recipient ->
                if (!mailer.sendMessage(recipient, subject, body)) {
                    log.warn("Consistency report to '${recipient}' not sent. ${mailer.errorMessages}")
                }
            }
            return
        }

        File csvFile = null
        try {
            csvFile = writeCsvFile()

            REPORT_RECIPIENTS.each { String recipient ->
                boolean sent = mailer.sendMessageWithAttachment(recipient, subject, body, [csvFile])
                if (!sent) {
                    log.warn("Consistency report to '${recipient}' not sent. ${mailer.errorMessages}")
                }
            }
        } catch (Exception e) {
            log.error("Failed to build/send consistency report CSV: ${e.message}", e)
        } finally {
            if (csvFile?.exists()) {
                csvFile.delete()
            }
        }
    }

    private File writeCsvFile() {
        String stamp = new Date().format("yyyyMMdd_HHmmss")
        File csvFile = File.createTempFile("project_information_consistency_${stamp}_", ".csv")

        StringBuilder sb = new StringBuilder()
        // UTF-8 BOM so Excel opens diacritics correctly.
        sb.append("﻿")
        sb.append(toCsvLine([
                "Timestamp", "Issue", "Project", "IssueType", "Branch",
                "PI_Before", "PI_After", "Text_Before", "Text_After",
                "Flag_Before", "Flag_After", "Result", "Detail"
        ] as String[]))

        csvRows.each { String[] row ->
            sb.append(toCsvLine(row))
        }

        csvFile.setText(sb.toString(), "UTF-8")
        return csvFile
    }

    private static String toCsvLine(String[] cells) {
        return cells.collect { csvEscape(it) }.join(",") + "\r\n"
    }

    private static String csvEscape(String value) {
        String v = value == null ? "" : value
        if (v.contains("\"") || v.contains(",") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\""
        }
        return v
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim()
    }

    private void resetState() {
        initiativesScanned = 0
        issuesVisited = 0
        repaired = 0
        anomalies = 0
        errors = 0

        projectsWithoutBrowse.clear()
        csvRows.clear()
        visited.clear()
    }
}
