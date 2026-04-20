package jobs.global.project_information_change.migration

/**
 * Run from ScriptRunner Console.
 * Check logs: scriptrunner.analyze.project-info
 *
 * Output: {jira.home}/export/project-info-analyze/project-info-analyze-{timestamp}.csv
 */
try {
    return new ProjectInfoMigrationAnalyze().run()
} catch (Exception e) {
    return "FAILED: ${e.message}"
}
