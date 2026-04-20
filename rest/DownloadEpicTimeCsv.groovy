package rest

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.web.bean.PagerFilter
import java.math.RoundingMode

import com.onresolve.scriptrunner.runner.customisers.WithPlugin
import com.onresolve.scriptrunner.runner.customisers.PluginModule
import aptis.plugins.epicSumUp.api.ProgressProvider
import aptis.plugins.epicSumUp.api.model.ProgressTimeResult

class DownloadEpicTimeCsv {
    @WithPlugin("aptis.plugins.epicSumUp")
    @PluginModule
    ProgressProvider progressProvider

    private String formatHours(long secs) {
        if (secs <= 0) return ""
        def hrs = new BigDecimal(secs / 3600.0)
            .setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        return hrs + "h"
    }

    private String cfValue(issue, cfName) {
        def cf = ComponentAccessor.customFieldManager.getCustomFieldObjectByName(cfName)
        def v = cf ? issue.getCustomFieldValue(cf) : null
        if (!v) return ""
        if (v instanceof Collection) {
            return v.findResults {
                it?.toString()
            }.join(",")
        }
        return v.toString()
    }

    String buildCsv() {
        def searchSvc   = ComponentAccessor.getComponent(SearchService)
        def worklogMgr  = ComponentAccessor.getWorklogManager()
        def groupMgr    = ComponentAccessor.getGroupManager()
        def userMgr     = ComponentAccessor.getUserManager()
        def cfManager   = ComponentAccessor.getCustomFieldManager()
        def userCtx     = ComponentAccessor.jiraAuthenticationContext.loggedInUser

        def sprintCf    = cfManager.getCustomFieldObjectByName("Sprint")
        
        StringBuilder sb = new StringBuilder()
        sb << [
            'Custom field (SAP-Order-No)',
            'Custom field (Project Reference)',
            'Issue key','Issue id','Priority','Status','Resolution',
            'Custom field (QA Status)','Custom field (Bug Origin)','Custom field (Parent Bug)',
            'Custom field (Epic Link)','Custom field (Child Bugs)','Created','Resolved',
            'Affects Version/s','Fix Version/s','Summary','Reporter','Assignee',
            'Custom field (Sum Time spent)','Custom field (Product Area)','Custom field (Parent Project)',
            'Sprint','Sprint','Sprint','Sprint','Sprint','Sprint',
            'Labels','Labels','Custom field (Device Type)','Custom field (Case ID)','Due Date',
            'Custom field (Sum Story Points Done)','Custom field (Readiness)','Custom field (Sum Story Points maximum)',
            'Project key','Project name','Project type','Project lead','Project description','Project url',
            'Custom field (Sum Issues All)','Custom field (Sum Issues Done)','Custom field (Sum Time maximum)'
        ].join(',') << '\n'

        def jql = 'issuetype = Bug AND project = "Market Team UK/IE"'
        def pr  = searchSvc.parseQuery(userCtx, jql)
        if (!pr.isValid()) throw new IllegalArgumentException(pr.errors.toString())
        def issues = searchSvc.search(userCtx, pr.query, PagerFilter.unlimitedFilter).results

        issues.each { issue ->
            def project = issue.getProjectObject()
            ProgressTimeResult pt = progressProvider.getTimeProgress(issue, userCtx)
            long sumSec = pt.timeSpent ?: 0L
            long tieSec = issue.getTimeSpent() ?: 0L
            long maxSec = pt.originalEstimate ?: 0L

            // Sprint values up to 6
            def sprints = sprintCf ? (issue.getCustomFieldValue(sprintCf) ?: []) : []
            def sprintCols = (0..<6).collect { i ->
                i < sprints.size() ? sprints[i]?.name?.toString() : ''
            }

            // Labels up to 2
            def labels = issue.labels ?: []
            def labelCols = (0..<2).collect { i ->
                i < labels.size() ? labels[i] : ''
            }

            sb << [
                cfValue(issue, 'SAP-Order-No'),
                cfValue(issue, 'Project Reference'),
                issue.key,
                issue.id,
                issue.priority.name,
                issue.status.name,
                issue.resolution?.name ?: '',
                cfValue(issue, 'QA Status'),
                cfValue(issue, 'Bug Origin'),
                cfValue(issue, 'Parent Bug'),
                cfValue(issue, 'Epic Link'),
                cfValue(issue, 'Child Bugs'),
                issue.created.format('dd.MM.yyyy HH:mm'),
                issue.resolutionDate ? issue.resolutionDate.format('dd.MM.yyyy HH:mm') : '',
                issue.getAffectedVersions()*.name.join(','),
                issue.getFixVersions()*.name.join(','),
                issue.summary.replaceAll(/[\r\n;]/, ' '),
                issue.reporter?.name ?: '',
                issue.assignee?.name ?: '',
                formatHours(sumSec),
                cfValue(issue, 'Product Area'),
                cfValue(issue, 'Parent Project')
            ].plus(sprintCols)
             .plus(labelCols)
             .plus([
                cfValue(issue, 'Device Type'),
                cfValue(issue, 'Case ID'),
                issue.dueDate ? issue.dueDate.format('dd.MM.yyyy') : '',
                cfValue(issue, 'Sum Story Points Done'),
                cfValue(issue, 'Readiness'),
                cfValue(issue, 'Sum Story Points maximum'),
                project.key,
                project.name,
                project.projectTypeKey,
                project.lead.name,
                (project.description ?: '').replaceAll(/[\r\n;]/, ' '),
                project.url ?: '',
                cfValue(issue, 'Sum Issues All'),
                cfValue(issue, 'Sum Issues Done'),
                formatHours(maxSec)
            ]).join(',') << '\n'
        }

        return sb.toString()
    }
}
