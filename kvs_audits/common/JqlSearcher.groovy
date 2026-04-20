package kvs_audits.common;

import com.atlassian.jira.issue.Issue
import kvs_audits.KVSLogger
import kvs_audits.issueType.Audit
import kvs_audits.issueType.AuditPreparation
import kvs_audits.issueType.BaseIssue
import kvs_audits.issueType.Question
import utils.CustomFieldUtil

import java.util.List;
import utils.MyBaseUtil;

/**
 * JqlSearcher
 *
 * @author chabrecek.anton
 * Created on 12/05/2025.
 */
public class JqlSearcher {

    public String kvsProjectKey;

    JqlSearcher(String kvsProjectKey) {
        this.kvsProjectKey = kvsProjectKey
    }

    private CommonHelper commonHelper = new CommonHelper();
    private MyBaseUtil myBaseUtil = new MyBaseUtil()
    private KVSLogger logger = new KVSLogger();
    private CustomFieldUtil customFieldUtil = new CustomFieldUtil();

    /**
     * @param questionUsage for example:
     *                      PC2_FA1_Level_2,
     *                      PC3_FA10_Level_3,
     *                      PC2_Level_4,
     *                      PC4_Level_5,
     *                      PC9_A_Level_5,
     *                      PC9_B_Level_5
     * @return
     */
    public Issue getPCFromQuestionUsage(String questionUsage, String currentAuditLevel) {
        def usage = commonHelper.extractValuesFromQuestionUsage(questionUsage)
        String profitCenterKey = usage[0] ?: null

        if (profitCenterKey != null) {
            List<Issue> issues = myBaseUtil.findIssues("project = ${CustomFieldsConstants.PROJECT_KVS_PROFIT_CENTERS} AND resolution = Unresolved AND \"Profit Center Key\" = \"${profitCenterKey}\"")
            return issues ? issues[0] : null  // Return first PC issue, or null if none found
        } else {
            String errorMsg = "INTERRUPTED: Combination for PC not found for $currentAuditLevel: " + questionUsage;
            logger.setErrorMessage(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
    }

    public Issue getFA_FromQuestionUsage(String questionUsage, Issue profitCenter, String currentAuditLevel) {
        def usage = commonHelper.extractValuesFromQuestionUsage(questionUsage)
        String functionalAreaKey = usage[1] ?: null  // Second part, might be null
        if (functionalAreaKey != null) {

            String jqlQuery = """ project = ${CustomFieldsConstants.PROJECT_KVS_PROFIT_CENTERS} AND resolution = Unresolved AND "${Audit.FUNCTIONAL_AREA_KEY}" = "${functionalAreaKey}"
            AND "${AuditPreparation.PROFIT_CENTER_FIELD_NAME}" = "${profitCenter.key}" """.stripIndent().trim();

            List<Issue> issues = myBaseUtil.findIssues(jqlQuery);
            return issues ? issues[0] : null  // Return first found issue, or null if none found
        } else {
            String errorMsg = "INTERRUPTED: Combination for FA not found for $currentAuditLevel: " + questionUsage;
            logger.setErrorMessage(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
    }

    //return workplaces keys
    public def getWorkplaces(Issue functionalArea) {
        def workplaces = functionalArea.getSubTaskObjects()
                .findAll { it.issueType.name == 'Workplace' }
                .findAll { it.status?.statusCategory?.key != 'done' }

        logger.setInfoMessage("Functional area: " + functionalArea.getSummary() + " contains this workplaces: " + workplaces.join(", "))

        // Collect all Workplace issue keys and return
        return workplaces.collect { it.key }
    }

    public List<Issue> getWorkplacesIssues(Issue functionalArea) {
        def workplaces = functionalArea.getSubTaskObjects()
                .findAll { it.issueType.name == 'Workplace' }
                .findAll { it.status?.statusCategory?.key != 'done' }

        logger.setInfoMessage("Functional area: " + functionalArea.getSummary() +
                " contains these workplaces: " + workplaces.collect { "${it.key} - ${it.summary}" }.join(", "))

        return workplaces
    }

    public List<Issue> getFunctionalAreasIssues(Issue profitCenter) {
        String jql = """
        project = ${CustomFieldsConstants.PROJECT_KVS_PROFIT_CENTERS}
        AND issuetype = "Functional Areas" 
        AND "Profit Center" = "${profitCenter.key}"
        """.stripIndent().trim()

        List<Issue> faIssues = myBaseUtil.findIssues(jql)
        if (!faIssues || faIssues.isEmpty()) {
            logger.setWarnMessage("No Functional Areas found for Profit Center: ${profitCenter.key} using JQL: ${jql}")
        } else {
            logger.setInfoMessage("Found ${faIssues.size()} Functional Areas for PC ${profitCenter.key}")
        }

        return faIssues;
    }

    //get Functional Areas keys by Profit Center from project CustomFieldsConstants.PROJECT_KVS_PROFIT_CENTERS
    public List<String> getFunctionalAreas(Issue profitCenter) {
        List<Issue> faIssues = getFunctionalAreasIssues(profitCenter)

        return faIssues.collect { it.key }
    }

    public List<Issue> getFunctionalAreas(Issue profitCenter, String subAreaLetter) {
        List<Issue> faIssues = getFunctionalAreasIssues(profitCenter)

        List<Issue> filtered = faIssues.findAll { Issue fa ->
            def raw = customFieldUtil.getFieldValue_MultiSelectSearcher(
                    myBaseUtil.getCustomFieldValue(fa, BaseIssue.KVS_PC_SUB_AREA_FIELD_NAME)
            )

            return raw?.contains(subAreaLetter)
        }

        if (!filtered) {
            logger.setWarnMessage("No Functional Areas under PC ${profitCenter.key} have Sub-Area '${subAreaLetter}'")
        } else {
            logger.setInfoMessage("Found ${filtered.size()} Functional Areas under PC ${profitCenter.key} with Sub-Area '${subAreaLetter}'")
        }

        return filtered
    }

    public List<Issue> getAuditsByParentLink(Issue parentLinkIssue) {
        String jqlQuery = """
            project = ${kvsProjectKey}
            AND resolution = Unresolved
            AND "Parent Link" = "${parentLinkIssue.key}"
            ORDER BY priority DESC, updated DESC
        """.stripIndent().trim();

        return myBaseUtil.findIssues(jqlQuery);
    }

    public List<Issue> getAllAuditsPreparation() {
        String jqlQuery = """project = ${kvsProjectKey} AND issuetype = '${CustomFieldsConstants.AUDIT_PREPARATION}' AND resolution = Unresolved""".stripIndent().trim();

        return myBaseUtil.findIssues(jqlQuery);
    }

    public List<Issue> getQuestions(AuditPreparation auditPreparation) {
        List<Issue> audits = getAuditsByParentLink(auditPreparation.getIssue())
        List<Issue> questions = new ArrayList<>();

        for (Issue auditIssue : audits){
            questions.addAll(getQuestions(new Audit(auditIssue)));
        }

        return questions;
    }

    public List<Issue> getQuestions(Audit audit){
        String jqlQuery = """
            project = ${kvsProjectKey}
            AND resolution = Unresolved
            AND "Parent Link" = "${audit.getIssue().key}"
            ORDER BY priority DESC, updated DESC
        """.stripIndent().trim();

        return myBaseUtil.findIssues(jqlQuery);
    }

    /*List<String> getSubAreas(Issue pc) {
        List<Issue> fas = getFunctionalAreas(pc)
        return fas.collectMany { fa ->
            def raw = customFieldUtil.getFieldValue_MultiSelectSearcher(
                    myBaseUtil.getCustomFieldValue(fa, BaseIssue.KVS_PC_SUB_AREA_FIELD_NAME)
            )
            return raw ?: []
        }
    }*/

    List<String> getSubAreas(Issue pc) {
        List<String> faKeys = getFunctionalAreas(pc)
        return faKeys.collectMany { faKey ->
            Issue fa = myBaseUtil.getIssueByKey(faKey)
            def subAreaValue = myBaseUtil.getCustomFieldValue(fa, BaseIssue.KVS_PC_SUB_AREA_FIELD_NAME)

            String raw = customFieldUtil.getFieldValue_SingleSelect(subAreaValue);

            if (raw && !raw.isEmpty()) {
                return [ raw ]
            } else {
                return [ faKey ]
            }
        }
    }

    List<String> getFunctionalAreasKeys(Issue pc, String subAreaLetter) {
        List<Issue> faIssues = getFunctionalAreasIssues(pc)

        List<Issue> matchingFAs = faIssues.findAll { Issue fa ->
            def raw = customFieldUtil.getFieldValue_SingleSelect(
                    myBaseUtil.getCustomFieldValue(fa, BaseIssue.KVS_PC_SUB_AREA_FIELD_NAME)
            )
            return raw == subAreaLetter
        }

        if (!matchingFAs) {
            logger.setWarnMessage("No Functional Areas under PC ${pc.key} have Sub-Area '${subAreaLetter}'")
            return []
        }

        logger.setInfoMessage("Found ${matchingFAs.size()} Functional Areas under PC ${pc.key} with Sub-Area '${subAreaLetter}'")
        return matchingFAs.collect { it.key }
    }

    Issue getKvsSetting(){
        String jqlQuery = """
            project = ${CustomFieldsConstants.PROJECT_KVS_PROFIT_CENTERS}
            AND issuetype = "${BaseIssue.KVS_SETTING}"
        """.stripIndent().trim();

        def issues = myBaseUtil.findIssues(jqlQuery);
        return issues.isEmpty() ? null : issues.get(0)
    }

    public List<Issue> findSpecialQuestionsByUsage(String usageKey) {
        def jql = """
            project = ${CustomFieldsConstants.PROJECT_KVS_AUDIT_QUESTION}
            AND resolution = Unresolved AND "${Question.QUESTION_USAGE_FIELD_NAME}" = ${usageKey} AND "${Question.AUDIT_INTERVAL_OCCURANCE_FIELD_NAME}" in ("semi-yearly","yearly")""".stripIndent().trim()

        return myBaseUtil.findIssues(jql)
    }



    /*private Issue getWorkplace(String workplaceIssueKey){
        String jqlQuery = """ project = ${CustomFieldsConstants.PROJECT_KVS_PROFIT_CENTERS} AND resolution = Unresolved AND "${Audit.FUNCTIONAL_AREA_KEY}" = "${functionalAreaKey}"
            AND "${AuditPreparation.PROFIT_CENTER_FIELD_NAME}" = "${profitCenter.key}" """.stripIndent().trim();

        List<Issue> issues = myBaseUtil.findIssues(jqlQuery);
        return issues ? issues[0] : null  // Return first found issue, or null if none found

    }*/
}
