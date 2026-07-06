package jobs.global.projectInformation

/**
 * projectInformationConsistencyJobRunner
 *
 * ScriptRunner "Scheduled Job" entry point.
 * Recommended cron: once per week, e.g. "0 0 3 ? * SUN" (Sunday 03:00).
 *
 * @author chabrecek.anton
 * Created on 3. 7. 2026.
 */
new ProjectInformationConsistencyJob().run()
