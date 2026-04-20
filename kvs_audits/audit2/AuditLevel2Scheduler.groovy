package kvs_audits.audit2

import kvs_audits.common.CommonHelper
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.common.IAuditScheduler
import kvs_audits.common.AuditScheduler
import com.atlassian.jira.issue.Issue
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import kvs_audits.issueType.AuditPreparation
import kvs_audits.issueType.Question

import java.time.LocalDate
import java.time.ZoneId

/**
 * AuditLevel2Scheduler
 *
 * @author chabrecek.anton
 * Created on 08/04/2025.
 */
class AuditLevel2Scheduler extends AuditScheduler implements IAuditScheduler {

    public static final String auditLevel = CustomFieldsConstants.AUDIT_LEVEL_2

    AuditLevel2Scheduler() {
        super(auditLevel)
    }

    @Override
    void execute() {
        List<Issue> prepIssues = getPreparationIssues()
        logBasicInfo(prepIssues)

        prepIssues.each { prepIssue ->

            AuditPreparation auditPreparation = refreshAuditPreparation(prepIssue.key)
            AuditLevel2Handler auditHandler = new AuditLevel2Handler(auditPreparation.getIssue(), kvsProjectKey, auditLevel)
            Date dateOfNextRotation = auditPreparation.getDate_of_next_rotation()

            if (!dateOfNextRotation) {
                logger.setWarnMessage("Date Of Next Rotation missing on ${auditPreparation.getIssue()}, skipping.")
                return
            }

            LocalDate today = LocalDate.now()
            LocalDate rotationDay = dateOfNextRotation.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            logger.setInfoMessage("rotationDay is: $rotationDay")

            //generating audits 3 months ahead
            int safetyCounter = 0  // protection for cycling
            int safetyLimit = 100
            while (!rotationDay.isAfter(today.plusMonths(3))) {

                if (safetyCounter >= safetyLimit) {
                    logger.setErrorMessage("Safety limit of ${safetyLimit} rotations reached for ${auditPreparation.getIssue().key}. Exiting to prevent infinite loop.")
                    break
                }

                processRotation(auditPreparation.getIssue(), auditHandler, auditPreparation, rotationDay)

                //get updated data:
                auditPreparation = refreshAuditPreparation(auditPreparation.getIssue().key)

                dateOfNextRotation = auditPreparation.getDate_of_next_rotation()
                logger.setInfoMessage("Refreshed Date of Next Rotation is now: ${dateOfNextRotation}")
                rotationDay = dateOfNextRotation.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                safetyCounter++
            }

            if (rotationDay.isAfter(today.plusMonths(3))) {
                logger.setInfoMessage("Next rotation date (${rotationDay}) is more than 3 months ahead. No further rotation required.")
            }
        }
    }

    private AuditPreparation refreshAuditPreparation(String issueKey) {
        Issue updated = myBaseUtil.getIssueByKey(issueKey)
        if (!updated) {
            logger.setErrorMessage("Issue not found for key: ${issueKey}")
            throw new IllegalStateException("Issue not found: " + issueKey)
        }
        return new AuditPreparation(updated)
    }

    private void processRotation(Issue prepIssue, AuditLevel2Handler auditLevel2Handler, AuditPreparation auditPreparation, LocalDate rotationDay) {
        logger.setWarnMessage("Creating next Audits for ${prepIssue.key}")

        def rotationData = new JsonSlurper().parseText(auditPreparation.getRotation_data())
        Map usagesMap = rotationData.questions_usages as Map
        if (!usagesMap) {
            logger.setWarnMessage("RotationJob: No 'questions_usages' map found in ${AuditPreparation.ROTATION_DATA_FIELD_NAME} for ${prepIssue.key}")
            return
        }

        for (entry in usagesMap.entrySet()) {
            String usageKey        = entry.key
            def    usageObj        = entry.value

            //region check and update all units existance
            Issue profitCenter = jqlSearcher.getPCFromQuestionUsage(usageKey, auditLevel)
            if(profitCenter == null) {
                logger.setErrorMessage("Rotation failed, becouse usageKey:$usageKey is not valid, so profit center not found")
                continue;
            }

            Issue functionalArea = jqlSearcher.getFA_FromQuestionUsage(usageKey, profitCenter, auditLevel)
            if(functionalArea == null) {
                logger.setErrorMessage("Rotation failed, because usageKey:$usageKey for profit center ${profitCenter.getKey()} is not valid, so functional area didnt exists")
                continue;
            }

            List<String> liveWorkplaces = jqlSearcher.getWorkplaces(functionalArea)
            List<String> workplaces = dynamicReconcileRotationUnits(usageKey, usageObj.workplaces as List, liveWorkplaces)

            usageObj.workplaces = workplaces

            if (!workplaces || workplaces.isEmpty()) {
                logger.setErrorMessage("No workplaces for ${usageKey} after reconciliation – skipping.")
                continue
            }

            //endregion

            int wIndex = Math.min(usageObj.currentWorkplaceIndex ?: 0, workplaces.size() - 1)
            String nextWorkplaceKey = workplaces[wIndex]
            String nextAuditor       = getLiveAuditors(auditPreparation, usageKey, usageObj) // CHANGES old solution auditors[aIndex]

            List<String> auditors  = usageObj.auditors as List<String>
            int aIndex = usageObj.currentAuditorIndex ?: 0



            Map<String, Map> specialQuestionsMap = (usageObj.specialQuestions as Map) ?: [:]
            List<String> specialQuestionsDue = []

            //region 29.7.2025 update new solution rotate special question on each WP
            if (specialQuestionsMap && nextWorkplaceKey) {
                specialQuestionsMap.each { qKey, qData ->
                    Map<String, Map> wpRotation = qData.wpRotation as Map
                    if (!wpRotation) return

                    Map wpData = wpRotation[nextWorkplaceKey]
                    if (!wpData?.nextRotationDate) return

                    LocalDate nextEligible = LocalDate.parse(wpData.nextRotationDate)
                    if (!rotationDay.isBefore(nextEligible)) {
                        specialQuestionsDue << qKey

                        Issue questionIssue = myBaseUtil.getIssueByKey(qKey)
                        def question = new Question(questionIssue)
                        def occurrence = question.getAuditIntervalOccurance()

                        if (occurrence in ["semi-yearly", "yearly"]) {
                            String intervalRotation = (occurrence == "semi-yearly") ? "half-year" : occurrence
                            LocalDate newNext = CommonHelper.getNextDate(rotationDay, intervalRotation)
                            wpData.nextRotationDate = newNext.toString()
                        } else {
                            wpRotation.remove(nextWorkplaceKey)
                        }

                        logger.setInfoMessage("SpecialQuestion ${qKey} used in WP ${nextWorkplaceKey}, next eligible = ${wpData.nextRotationDate}")
                    }
                }
            }
            //endregion

            /*specialQuestionsMap.each { qKey, qInfo ->
                LocalDate nextEligible = LocalDate.parse(qInfo.nextRotationDate)

                if (!rotationDay.isBefore(nextEligible)) {
                    specialQuestionsDue << qKey

                    int currentWPIndex = qInfo.workplaceIndex ?: 0
                    qInfo.workplaceIndex = (currentWPIndex + 1) % workplaces.size()

                    Issue questionIssue = myBaseUtil.getIssueByKey(qKey)
                    def question = new Question(questionIssue)
                    def occurrence = question.getAuditIntervalOccurance()

                    if (occurrence in ["semi-yearly", "yearly"]) {
                        String intervalRotation = (occurrence == "semi-yearly") ? "half-year" : occurrence
                        qInfo.nextRotationDate = CommonHelper.getNextDate(rotationDay, intervalRotation).toString()
                    } else {
                        specialQuestionsMap.remove(qKey)
                    }

                    logger.setInfoMessage("SpecialQuestion ${qKey} used, new WP index: ${qInfo.workplaceIndex}")
                }
            }*/

            logger.setInfoMessage("RotationJob: UsageKey=${usageKey}, nextWorkplace=${nextWorkplaceKey}, nextAuditor=${nextAuditor}, specialQuestionsDue=${specialQuestionsDue}");

            auditLevel2Handler.rotateOneAudit(usageKey, nextWorkplaceKey, nextAuditor, specialQuestionsDue, rotationDay);

            usageObj.currentWorkplaceIndex = (wIndex + 1) % workplaces.size()

            List<String> curAuditors = (usageObj.auditors as List<String>) ?: []
            if (curAuditors) {
                int aIdxNorm = Math.min(usageObj.currentAuditorIndex ?: 0, curAuditors.size() - 1)
                usageObj.currentAuditorIndex = (aIdxNorm + 1) % curAuditors.size()
            }
            //CHANGES OLD SOLUTION
            //usageObj.currentAuditorIndex   = (aIndex + 1) % auditors.size()
        }

        rotationData.questions_usages = usagesMap
        def updatedJson = JsonOutput.toJson(rotationData)
        auditLevel2Handler.updateRotationData(updatedJson)

        String intervalValue = auditPreparation.getInterval();
        LocalDate nextRotation = CommonHelper.getNextDate(rotationDay, intervalValue)

        logger.setWarnMessage("Updating Date of Next Rotation to: ${nextRotation}")
        auditLevel2Handler.updateDateOfNextRotation(nextRotation);
    }
}
