package jobs.global.export

import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.component.ComponentAccessor

/**
 * exportJob
 *
 * @author chabrecek.anton
 * Created on 18. 8. 2026.
 */

final Map FILTER_TO_RECIPIENTS = [
        'filter = 42827': 'chabrecek.anton',
        //'filter = 23456': ['chabrecek.anton', 'sitkey.michal'],
        //'filter = 34567': [users: ['chabrecek.anton'], assignees: true],
]

final ApplicationUser RUN_AS = ComponentAccessor.jiraAuthenticationContext.loggedInUser
        ?: ComponentAccessor.userManager.getUserByName('jira.bot')

def exporter = new SavedFilterCsvExporter(RUN_AS)
def result = FILTER_TO_RECIPIENTS.collect { filterJql, recipients ->
    exporter.exportAndSend(filterJql as String, recipients)
}

return result.join('\n')
