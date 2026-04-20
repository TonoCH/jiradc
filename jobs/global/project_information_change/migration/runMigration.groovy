package jobs.global.project_information_change.migration

/**
 * Run from ScriptRunner Console.
 * Check logs: scriptrunner.migration.project-info
 */
try {
    new ProjectInfoMigrationJob().run()
    return "Migration completed. Check logs for details."
} catch (Exception e) {
    return "FAILED: ${e.message}"
}
