package kvs_audits.audit3

import kvs_audits.common.AuditScheduler
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.common.IAuditScheduler
import com.atlassian.jira.issue.Issue
import kvs_audits.issueType.AuditPreparation
import kvs_audits.common.CommonHelper

import java.time.LocalDate
import java.time.ZoneId
import kvs_audits.issueType.Question

/**
 * AuditLevel3Scheduler
 *
 * @author chabrecek.anton
 * Created on 08/04/2025.
 */
class AuditLevel3Scheduler extends AuditScheduler implements IAuditScheduler {

    public static final String auditLevel = CustomFieldsConstants.AUDIT_LEVEL_3

    AuditLevel3Scheduler() {
        super(auditLevel)
    }

    @Override
    void execute() {
        List<Issue> prepIssues = getPreparationIssues()
        logBasicInfo(prepIssues)

        prepIssues.each { prepIssue ->
            AuditPreparation auditPreparation = refreshAuditPreparation(prepIssue.key)
            AuditLevel3Handler auditHandler = new AuditLevel3Handler(prepIssue, kvsProjectKey, auditLevel)
            Date dateOfNextRotation = auditPreparation.getDate_of_next_rotation()

            if (!dateOfNextRotation) {
                logger.setWarnMessage("Date Of Next Rotation missing on ${prepIssue.key}, skipping.")
                return
            }

            LocalDate today = LocalDate.now()
            LocalDate rotationDay = dateOfNextRotation.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            logger.setInfoMessage("rotationDay is: $rotationDay")

            int safetyCounter = 0
            int safetyLimit = 100
            while (!rotationDay.isAfter(today.plusMonths(3))) {

                if (safetyCounter >= safetyLimit) {
                    logger.setErrorMessage("Safety limit of ${safetyLimit} rotations reached for ${auditPreparation.getIssue().key}. Exiting to prevent infinite loop.")
                    break
                }

                processRotation(auditPreparation.getIssue(), auditHandler, auditPreparation, rotationDay)

                auditPreparation = refreshAuditPreparation(auditPreparation.getIssue().key)
                dateOfNextRotation = auditPreparation.getDate_of_next_rotation()
                rotationDay = dateOfNextRotation.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                logger.setInfoMessage("Refreshed Date of Next Rotation is now: ${dateOfNextRotation}")
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

    private void processRotation(Issue prepIssue, AuditLevel3Handler auditHandler, AuditPreparation auditPreparation, LocalDate rotationDay) {
        logger.setWarnMessage("Creating next Level 3 audits for ${prepIssue.key}")

        def rotationData = new groovy.json.JsonSlurper().parseText(auditPreparation.getRotation_data())
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
                logger.setErrorMessage("Rotation failed, because usageKey:$usageKey for profit center $profitCenter.getKey() is not valid, so functional area didnt exists")
                continue;
            }

            //List<Issue> liveFunctionalAreas = jqlSearcher.getFunctionalAreasIssues(profitCenter)
            //List<String> actualFunctionalAreas = dynamicReconcileRotationUnits(usageKey, usageObj.workplaces as List, liveFunctionalAreas)
            boolean useFa = usageObj.fas != null ? true : false;
            boolean useFaIndex = usageObj.currentFaIndex != null ? true : false;

            def usageObjWorkplaces = usageObj.fas != null ? usageObj.fas : usageObj.workplaces;

            List<Issue> liveFunctionalAreas   = jqlSearcher.getFunctionalAreasIssues(profitCenter)
            List<String> liveKeys = liveFunctionalAreas*.key
            List<String> actualFunctionalAreas = dynamicReconcileRotationUnits(usageKey, usageObjWorkplaces as List<String>, liveKeys)
            actualFunctionalAreas = (actualFunctionalAreas ?: []).sort { faIssueKey ->
                Issue faIssue = liveFunctionalAreas.find { it.key == faIssueKey }
                String faKeyValue = faIssue ? myBaseUtil.getCustomFieldValue(faIssue, CustomFieldsConstants.FUNCTIONAL_AREA_KEY) : faIssueKey
                return faKeyValue ?: faIssueKey
            }  // Sort by Functional Area Key ASC (e.g., FA1, FA2, FA3, ...)

            if(useFa)
                usageObj.fas = actualFunctionalAreas
            else
                usageObj.workplaces = actualFunctionalAreas
            //endregion

            int currentFaIndex = usageObj.currentFaIndex != null ? usageObj.currentFaIndex : usageObj.currentWorkplaceIndex
            currentFaIndex = currentFaIndex ?: 0
            int faIndex = Math.min(currentFaIndex ?: 0, actualFunctionalAreas.size() - 1)
            int auditorIndex = usageObj.currentAuditorIndex ?: 0
            List<String> functionalAreas = actualFunctionalAreas
            List<String> auditors        = usageObj.auditors as List

            if (!functionalAreas || functionalAreas.isEmpty()) {
                logger.setErrorMessage("No functional areas found for usage ${usageKey}, skipping rotation.")
                continue
            }

            String nextFA      = functionalAreas[faIndex]
            logger.setWarnMessage("Next FA will be set:${nextFA}")
            //CHANGES old solution
            //String nextAuditor = auditors[auditorIndex]
            // Reconcile and pick next auditor dynamically
            String nextAuditor = getLiveAuditors(auditPreparation, usageKey, usageObj)
            if (!nextAuditor) {
                logger.setErrorMessage("No auditors available for ${usageKey} after reconciliation – skipping.")
                continue
            }

            Map<String, Map> specialQuestionsMap = usageObj.specialQuestions as Map
            List<String> specialQuestionsDue = []
            if (specialQuestionsMap && nextFA) {

                //actual solutian
                specialQuestionsMap.each { qKey, qInfo ->
                    //region 29.7.2025 update new solution rotate special question on each FA
                    Map<String, Map> faRotation = qInfo.faRotation as Map
                    if (!faRotation) return

                    Map faData = faRotation[nextFA]
                    if (!faData?.nextRotationDate) return

                    LocalDate nextEligible = LocalDate.parse(faData.nextRotationDate)
                    if (!rotationDay.isBefore(nextEligible)) {
                        specialQuestionsDue << qKey

                        Issue questionIssue = myBaseUtil.getIssueByKey(qKey)
                        def question = new Question(questionIssue)
                        def occurrence = question.getAuditIntervalOccurance()

                        if (occurrence in ["semi-yearly", "yearly"]) {
                            String intervalRotation = (occurrence == "semi-yearly") ? "half-year" : occurrence
                            LocalDate newNext = CommonHelper.getNextDate(rotationDay, intervalRotation)
                            faData.nextRotationDate = newNext.toString()
                        } else {
                            // remove if not periodic
                            faRotation.remove(nextFA)
                        }

                        logger.setInfoMessage("SpecialQuestion ${qKey} used in FA ${nextFA}, next eligible = ${faData.nextRotationDate}")
                    }
                    //endregion

                    /*LocalDate nextEligible = LocalDate.parse(qInfo.nextRotationDate)
                    if (!rotationDay.isBefore(nextEligible)) {
                        specialQuestionsDue << qKey

                        int currentFAIndex = qInfo.functionalAreaIndex ?: 0
                        qInfo.functionalAreaIndex = (currentFAIndex + 1) % functionalAreas.size()

                        Issue questionIssue = myBaseUtil.getIssueByKey(qKey)
                        def question = new Question(questionIssue)
                        def occurrence = question.getAuditIntervalOccurance()

                        if (occurrence in ["semi-yearly", "yearly"]) {
                            String intervalRotation = (occurrence == "semi-yearly") ? "half-year" : occurrence
                            qInfo.nextRotationDate = CommonHelper.getNextDate(rotationDay, intervalRotation).toString()
                        } else {
                            specialQuestionsMap.remove(qKey)
                        }

                        logger.setInfoMessage("SpecialQuestion ${qKey} used, new FA index: ${qInfo.functionalAreaIndex}")
                    }*/
                }
            }

            logger.setInfoMessage("RotationJob: usage=${usageKey}, nextFA=${nextFA}, nextAuditor=${nextAuditor}, specialDue=${specialQuestionsDue}")

            auditHandler.rotateOneAudit(usageKey, nextFA, nextAuditor, specialQuestionsDue, rotationDay)

            if(useFaIndex) {
                usageObj.currentFaIndex = (faIndex + 1) % functionalAreas.size()
            }
            else {
                usageObj.currentWorkplaceIndex = (faIndex + 1) % functionalAreas.size()
            }

            List<String> curAuditors = (usageObj.auditors as List<String>) ?: []
            if (curAuditors) {
                int aIdxNorm = Math.min(usageObj.currentAuditorIndex ?: 0, curAuditors.size() - 1)
                usageObj.currentAuditorIndex = (aIdxNorm + 1) % curAuditors.size()
            }


            //OLD SOLUTION
            //usageObj.currentAuditorIndex       = (auditorIndex + 1) % auditors.size()
        }

        rotationData.questions_usages = usagesMap
        String updatedJson = groovy.json.JsonOutput.toJson(rotationData)
        auditHandler.updateRotationData(updatedJson)

        String intervalValue = auditPreparation.getInterval()
        LocalDate nextRotation = CommonHelper.getNextDate(rotationDay, intervalValue)
        auditHandler.updateDateOfNextRotation(nextRotation)
    }
}