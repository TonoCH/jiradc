package jobs.kvs

import kvs_audits.audit2.AuditLevel2Scheduler

/**
 * @author chabrecek.anton
 * Created on 07/03/2025.
 */

AuditLevel2Scheduler auditLevel2Scheduler = new AuditLevel2Scheduler();
auditLevel2Scheduler.execute();

/*
public void executeScheduledJob() {
    KVSLogger logger = new KVSLogger();
    MyBaseUtil myBaseUtil = new MyBaseUtil();
    CustomFieldUtil customFieldUtil = new CustomFieldUtil()
    String kvsProjectKey = CustomFieldsConstants.PROJECT_KVS_AUDIT

    String auditLevel = "Level 2";

    def jql = "project = ${kvsProjectKey} AND issuetype = 'Audit Preparation'  AND resolution = Unresolved AND 'Audit level' = '$auditLevel'"

    List<Issue> prepIssues = myBaseUtil.findIssues(jql)

    log.warn "Finded Audit Preparation issues: ${prepIssues}"
    logger.setInfoMessage("RotationJob: Finded Audit Preparation issues: ${prepIssues}")

    logger.setInfoMessage("Script executed as user: ${ComponentAccessor.jiraAuthenticationContext.loggedInUser?.name}")

    prepIssues.each { prepIssue ->
        log.warn "Handle Audit Preparation issue: ${prepIssue}"
        AuditLevel2Handler auditLevel2IssueHelper = new AuditLevel2Handler(prepIssue, kvsProjectKey);
        AuditPreparation auditPreparation = new AuditPreparation(prepIssue);

        Date dateOfNextRotation = (Date) auditPreparation.getDate_of_next_rotation();
        if (!dateOfNextRotation) {
            logger.setWarnMessage("RotationJob: ${AuditPreparation.DATE_OF_NEXT_ROTATION} is missing on ${prepIssue.key}, skipping.")
            log.warn "${AuditPreparation.DATE_OF_NEXT_ROTATION} is missing on ${prepIssue.key}, skipping."
            return
        }

        def today = LocalDate.now()
        def rotationDay = dateOfNextRotation.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()

        if (!rotationDay.isAfter(today)) {
            logger.setWarnMessage("Scheduled RotationJob: Creating next Audits for ${prepIssue.key}")

            String rotationJson = (String) auditPreparation.getRotation_data();
            def rotationData = new JsonSlurper().parseText(rotationJson)

            Map usagesMap = rotationData.questions_usages as Map
            if (!usagesMap) {
                logger.setWarnMessage("RotationJob: No 'questions_usages' map found in ${AuditPreparation.ROTATION_DATA_FIELD_NAME} for ${prepIssue.key}")
                return
            }

            usagesMap.each { usageKey, usageObj ->
                def wIndex = usageObj.currentWorkplaceIndex ?: 0
                def aIndex = usageObj.currentAuditorIndex ?: 0
                List<String> workplaces = usageObj.workplaces as List
                List<String> auditors = usageObj.auditors as List
                def nextWorkplaceKey = workplaces[wIndex]
                def nextAuditor = (usageObj.auditors as List)[aIndex]
                Map specialQuestionsMap = null;

                List<String> specialQuestionsDue = []
                if (usageObj.specialQuestions) {
                    specialQuestionsMap = usageObj.specialQuestions as Map
                    specialQuestionsMap.each { qKey, qInfo ->
                        LocalDate nextEligible = LocalDate.parse(qInfo.nextRotationDate)
                        if (!today.isBefore(nextEligible)) {
                            specialQuestionsDue << qKey
                        }
                    }
                }

                //auditLevel2IssueHelper.rotateOneAudit(usageKey, nextWorkplaceKey, nextAuditor, specialQuestionsDue)
                

                if(specialQuestionsMap != null){
                specialQuestionsDue.each { questionKey ->
                    Map questionInfo = specialQuestionsMap[questionKey]
                    int currentWPIndex = questionInfo.workplaceIndex ?: 0
                    questionInfo.workplaceIndex = (currentWPIndex + 1) % workplaces.size()

                    Issue questionIssue = myBaseUtil.getIssueByKey(questionKey)
                    def question = new Question(questionIssue)
                    def occurrence = question.getAuditIntervalOccurance()
                    if (occurrence in ["semi-yearly", "yearly"]) {
                        String intervalRotation = (occurrence == "semi-yearly") ? "half-year" : occurrence
                        questionInfo.nextRotationDate = auditLevel2IssueHelper.getNextDate(today, intervalRotation).toString()
                    } else {
                        specialQuestionsMap.remove(questionKey)
                    }
                }
                }

                logger.setInfoMessage("RotationJob: UsageKey=${usageKey}, nextWorkplace=${nextWorkplaceKey}, nextAuditor=${nextAuditor}, specialQuestionsDue=${specialQuestionsDue}");
                log.info "UsageKey=${usageKey}, nextWorkplace=${nextWorkplaceKey}, nextAuditor=${nextAuditor}, specialQuestionsDue=${specialQuestionsDue}";

                auditLevel2IssueHelper.rotateOneAudit(usageKey, nextWorkplaceKey, nextAuditor, specialQuestionsDue);

                usageObj.currentWorkplaceIndex = (wIndex + 1) % workplaces.size();
                usageObj.currentAuditorIndex = (aIndex + 1) % auditors.size();
            }

            rotationData.questions_usages = usagesMap
            def updatedJson = JsonOutput.toJson(rotationData)
            auditLevel2IssueHelper.updateRotationData(updatedJson)
            //myBaseUtil.updateCustomField(prepIssue, CustomFieldsConstants.ROTATION_DATA_FIELD_NAME, updatedJson)

            String intervalValue = auditPreparation.getInterval();
            LocalDate nextRotation = auditLevel2IssueHelper.getNextDate(LocalDate.now(), intervalValue)
            auditLevel2IssueHelper.updateDateOfNextRotation(nextRotation);
        } else {
            log.info "No rotation for today next rotation is on: $rotationDay"
            logger.setInfoMessage("No rotation for today next rotation is on: $rotationDay")
        }
    }
}*/
