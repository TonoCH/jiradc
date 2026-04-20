package kvs_audits.common

import kvs_audits.KVSLogger
import kvs_audits.issueType.AuditPreparation
import utils.CustomFieldUtil
import utils.MyBaseUtil
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.component.ComponentAccessor

/**
 * AuditScheduler
 *
 * @author chabrecek.anton
 * Created on 08/04/2025.
 */
class AuditScheduler {

    private String auditLevel

    AuditScheduler(String auditLevel){
        this.auditLevel = auditLevel
        jqlSearcher = new JqlSearcher(kvsProjectKey);
    }
    protected KVSLogger logger = new KVSLogger()
    protected MyBaseUtil myBaseUtil = new MyBaseUtil()
    protected CustomFieldUtil customFieldUtil = new CustomFieldUtil()
    protected JqlSearcher jqlSearcher;
    protected String kvsProjectKey = CustomFieldsConstants.DEFAULT_PROJECT_FOR_JOBS

    protected List<Issue> getPreparationIssues() {
        def jql = "project = ${kvsProjectKey} AND issuetype = 'Audit Preparation' AND resolution = Unresolved AND 'Audit level' = '${auditLevel}'"
        return myBaseUtil.findIssues(jql)
    }

    protected void logBasicInfo(List<Issue> prepIssues) {
        logger.setInfoMessage("Finded Audit Preparation issues : ${prepIssues} for Audit level:${auditLevel}")
        logger.setInfoMessage("Script executed as user: ${ComponentAccessor.jiraAuthenticationContext.loggedInUser?.name}")
    }

    /**
     * Dynamic reconciliation of the saved list of rotational units (e.g. workplaces or FAs) with the current existing issues list
     */
    protected List<String> dynamicReconcileRotationUnits(String usageKey, List<String> storedUnits, List<String> currentUnits) {
        List<String> removed = storedUnits.findAll { !currentUnits.contains(it) }
        List<String> added = currentUnits.findAll { !storedUnits.contains(it) }

        if (removed) {
            logger.setWarnMessage("Usage ${usageKey}: Removing no-longer-existing units: ${removed}")
        }
        if (added) {
            logger.setWarnMessage("Usage ${usageKey}: Adding new units: ${added}")
        }

        List<String> reconciled = []
        storedUnits.each {
            if (currentUnits.contains(it)) {
                reconciled << it
            }
        }
        reconciled.addAll(added)
        return reconciled
    }

    // English comments kept
    protected String getLiveAuditors(AuditPreparation auditPreparation, String usageKey, def usageObj) {
        List<String> liveAuditors = auditPreparation.getAuditors() ?: []
        List<String> reconciledAuditors = dynamicReconcileRotationUnits(
                usageKey + ":auditors",
                (usageObj.auditors as List<String>) ?: [],
                liveAuditors
        )

        usageObj.auditors = reconciledAuditors

        int safeSize = Math.max(reconciledAuditors.size() - 1, 0)
        int aIndex = Math.min(usageObj.currentAuditorIndex ?: 0, safeSize)

        return reconciledAuditors ? reconciledAuditors[aIndex] : null
    }

}
