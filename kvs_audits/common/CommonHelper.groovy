package kvs_audits.common

import com.atlassian.jira.issue.IssueInputParametersImpl
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.issue.MutableIssue
import kvs_audits.KVSLogger
import kvs_audits.issueType.Audit
import kvs_audits.issueType.Question
import utils.CustomFieldUtil
import utils.MyBaseUtil
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.bc.issue.IssueService;

/**
 * CommonHelper
 *
 * @author chabrecek.anton
 * Created on 12/05/2025.
 */
class CommonHelper {

    private CustomFieldUtil customFieldUtil = new CustomFieldUtil()
    private KVSLogger logger = new KVSLogger();
    private loggerIssueOnly;
    private def loggedInUser;
    private def issueManager,
                userManager;
    private IssueService issueService;
    private MyBaseUtil myBaseUtil;

    CommonHelper() {
        this.issueManager = ComponentAccessor.issueManager;
        this.userManager = ComponentAccessor.userManager;
        this.loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
        this.issueService = ComponentAccessor.getIssueService();
        this.myBaseUtil = new MyBaseUtil();
        this.loggerIssueOnly = new KVSLogger(true)
    }

    /*public def extractValuesFromQuestionUsage(String questionUsage) {
        def parts = questionUsage.tokenize('_')
        def extractedValues = []

        if (parts.size() == 2) {
            // Case: "PC2_Level_4"
            extractedValues << parts[0]  // PC2
            extractedValues << null       // No Functional Area
            extractedValues << parts[1]   // Level_4
        } else if (parts.size() == 3) {
            // Case: "PC3_A_Level_4" or "PC9_B_Level_5"
            extractedValues << parts[0]  // PC3 or PC9
            extractedValues << parts[1]  // A or B
            extractedValues << parts[2]  // Level_4 or Level_5
        } else if (parts.size() >= 4) {
            // Case: "PC2_FA1_Level_2" (default case)
            extractedValues << parts[0]  // PC2
            extractedValues << parts[1]  // FA1
            extractedValues << parts[2] + "_" + parts[3]  // Level_2
        }

        return extractedValues
    }*/

    public def extractValuesFromQuestionUsage(String questionUsage) {

        def levelMatcher = questionUsage =~ /(Level_[2|3|4|5])$/
        if (!levelMatcher) {
            throw new IllegalArgumentException("Invalid questionUsage, missing level: ${questionUsage}")
        }
        String level = levelMatcher[0][1]    // "Level_4"

        String prefix = questionUsage - "_${level}"
        def parts = prefix.tokenize('_')

        String pcKey = parts[0]               // "PC3" or "SKCABLE"
        String faOrSub = null

        if (parts.size() >= 2) {
            faOrSub = parts[1]                // FA1 or A or B
        }

        return [pcKey, faOrSub, level]
    }

    /**
     * @param profitCenter Issue represents Profit Center (example. „PC2“)
     * @param functionalArea Issue represents Functional Area (example. „FA1“), or null
     * @param selectedLevel String represents level (example. „Level_2“, „Level_4“)
     * @return String or null
     */
    //TODO for LEVEL4 not return all sub areas needs to be replaced with buildQuestionUsageKeys
    public String buildQuestionUsage(Issue profitCenter, Issue functionalArea, String selectedLevel) {

        if (profitCenter == null || !selectedLevel) {
            return null
        }

        String pcKey = myBaseUtil.getCustomFieldValue(profitCenter, CustomFieldsConstants.PROFIT_CENTER_KEY);
        // example „PC2“
        String level = selectedLevel.trim().replaceAll(' ', '_')   // e.g. "Level_2"

        // if functionalArea is null return „PCx_Level_x“
        if (functionalArea == null) {
            return "${pcKey}_${level}"
        }

        String faKey = myBaseUtil.getCustomFieldValue(functionalArea, CustomFieldsConstants.FUNCTIONAL_AREA_KEY);
        // example „FA1“

        if (faKey == null) {
            return "${pcKey}_${level}"
        } else {
            return "${pcKey}_${faKey}_${level}"
        }
    }

    /**
     * Return one or multiple usage keys for Questions pool.
     * - Level 4 without FA: return [PC_Level_4, PC_A_Level_4, PC_B_Level_4]
     */
    List<String> buildQuestionUsageKeys(Issue pc, Issue fa, String selectedLevel) {
        if (!pc || !selectedLevel) return []
        String pcKey = myBaseUtil.getCustomFieldValue(pc, CustomFieldsConstants.PROFIT_CENTER_KEY)
        String level = selectedLevel.trim().replace(' ', '_')

        // Level 4 with no FA include base + A + B
        if (fa == null && level == "Level_4") {
            return ["${pcKey}_${level}", "${pcKey}_A_${level}", "${pcKey}_B_${level}"]
        }

        String faKey = fa ? myBaseUtil.getCustomFieldValue(fa, CustomFieldsConstants.FUNCTIONAL_AREA_KEY) : null
        return faKey ? ["${pcKey}_${faKey}_${level}"] : ["${pcKey}_${level}"]
    }


    /**
     * Returns all Question Usage values matching the given level.
     *
     * @param selectedLevel String represents level (example: "Level 2")
     * @return List<String> matching Question Usage values
     */
    public List<String> getQuestionUsageValuesForLevel(String selectedLevel) {

        if (!selectedLevel) {
            return []
        }

        String level = selectedLevel.trim().replaceAll(' ', '_')   // e.g. "Level_2"
        CustomField customField = CustomFieldUtil.getCustomFieldByName(Question.QUESTION_USAGE_FIELD_NAME)

        def config = customField.getConfigurationSchemes()?.first()?.oneAndOnlyConfig
        if (config == null) {
            logger.setErrorMessage("No config found for field 'Question Usage'")
            return []
        }

        def options = ComponentAccessor.optionsManager.getOptions(config)
        if (options == null) {
            logger.setErrorMessage("No options found for field 'Question Usage'")
            return []
        }

        List<String> result = []
        options.each { option ->
            String value = option.value
            if (value?.endsWith("_${level}")) {
                result << value
            }
        }

        return result
    }

    public static LocalDate getNextDate(LocalDate ourDate, String intervalValue) {
        switch (intervalValue?.toLowerCase()) {
            case "daily":
                return ourDate.plusDays(1)
            case "7 days":
                return ourDate.plusDays(7)
            case "14 days":
                return ourDate.plusDays(14)
            case "21 days":
                return ourDate.plusDays(21)
            case "28 days":
                return ourDate.plusDays(28)
            case "half-year":
                return ourDate.plusMonths(6)
            case "yearly":
                return ourDate.plusYears(1)
            default:
                // default weekly
                return ourDate.plusDays(7)
        }
    }

    public void updateTargetEndDate(Issue issue, LocalDate targetEndDate) {
        CustomField targetEndField = customFieldUtil.getCustomFieldByName(Audit.TARGET_END_FIELD_NAME);

        if (targetEndField) {
            Timestamp targetEndTimestamp = Timestamp.from(targetEndDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
            issue.setCustomFieldValue(targetEndField, targetEndTimestamp);
            logger.setInfoMessage("Updated targetEndDate on ${issue.key} to ${targetEndDate}");
            issueManager.updateIssue(loggedInUser, issue, EventDispatchOption.ISSUE_UPDATED, false);
        } else {
            logger.setErrorMessage("Custom field '${Audit.TARGET_END_FIELD_NAME}' not found.");
        }
    }

    public void verifyAndUpdateIssueMetadata(Issue createdIssue, String assigneeUsername, Issue parrentIssue) {
        ApplicationUser expectedAssignee = userManager.getUserByName(assigneeUsername)
        ApplicationUser expectedReporter = parrentIssue.reporter ?: loggedInUser

        boolean changed = false

        if (createdIssue.assigneeId != expectedAssignee?.username) {
            createdIssue.setAssignee(expectedAssignee)
            logger.setInfoMessage("Updated assignee on ${createdIssue.key} to ${expectedAssignee?.username}")
            changed = true
        }

        if (createdIssue.reporter?.username != expectedReporter?.username) {
            createdIssue.setReporter(expectedReporter)
            logger.setInfoMessage("Updated reporter on ${createdIssue.key} to ${expectedReporter?.username}")
            changed = true
        }

        if (changed) {
            issueManager.updateIssue(loggedInUser, createdIssue, EventDispatchOption.ISSUE_UPDATED, false)
        }
    }

    /**
     * Creates questions for the given auditIssue based on question usage.
     * If the question has a semi-yearly or yearly interval and its key is in the specialQuestionsDue list,
     * calculates the next rotation date and records it (with a workplace index) in the returned map.
     *
     * @param questionUsageValue the value of the Question Usage custom field to filter by
     * @param auditIssue the parent audit issue under which new question will be created
     * @param assignee the user to assign the newly created question
     * @param specialQuestionsDue list of question keys requiring special rotation handling
     * @return a map where each key is a question issue key and each value is a nested map containing:
     *         - nextRotationDate: the calculated date of the next rotation
     *         - workplaceIndex: the index of the workplace for rotation
     */
    public Map<String, String> createQuestionsIssues(String questionUsageValue, Issue auditIssue, String assignee, List<String> specialQuestionsDue) {
        def createdIssueKeys = []
        this.logger = new KVSLogger();

        Map<String, String> auditOccurrenceMap = [:]  //semi-yearly   yearly
        try {
            String jqlQuery = """ project = ${CustomFieldsConstants.PROJECT_KVS_AUDIT_QUESTION} AND resolution = Unresolved AND "${Question.QUESTION_USAGE_FIELD_NAME}" = "${questionUsageValue}" """.stripIndent().trim();

            List<Issue> questionsIssues = myBaseUtil.findIssues(jqlQuery);
            logger.setWarnMessage("Number of Questions found for questionUsageValue ${questionUsageValue} is: ${questionsIssues.size()}")

            int created = 0;
            // Create subtasks(Questions) for Audit issues, cloning from Questions issues
            questionsIssues?.each { questionIssue ->
                def question = new Question(questionIssue)
                def occurrence = question.getAuditIntervalOccurance();

                boolean shouldCreate = false;
                if (!occurrence || (occurrence != "semi-yearly" && occurrence != "yearly")) {
                    shouldCreate = true
                } else {
                    if (specialQuestionsDue == null || specialQuestionsDue.contains(questionIssue.key)) {
                        shouldCreate = true
                        String intervalForRotation = (occurrence == "semi-yearly") ? "half-year" : occurrence
                        LocalDate nextRotationDate = getNextDate(LocalDate.now(), intervalForRotation)
                        auditOccurrenceMap[questionIssue.key] = [
                                nextRotationDate: nextRotationDate.toString(),
                                workplaceIndex  : 0
                        ]
                    }
                }
                if (shouldCreate) {
                    def createdQuestionIssue = createQuestion(auditIssue, questionIssue, assignee);
                    if (createdQuestionIssue) {
                        verifyAndUpdateIssueMetadata(createdQuestionIssue, assignee, auditIssue);
                        logger.setInfoMessage("Created Question ${createdQuestionIssue.key} under Audit issue: ${auditIssue.key}");

                        createdIssueKeys.add(createdQuestionIssue.key)

                        created++;
                    } else {
                        logger.setErrorMessage("Failed to create Question issue for ${questionIssue.key} under Audit issue: ${auditIssue.key}");
                    }
                }
            }
            logger.setInfoMessage("Successfully created Questions issues: " + created);
        }
        catch (Exception e) {
            // log and rollback
            logger.setErrorMessage("Error during audit generation: ${e.message}, rolling back ${createdIssueKeys.size()} issues...")
            loggerIssueOnly.setErrorMessage("Error during audit generation: ${e.message}, rolling back ${createdIssueKeys.size()} issues... ${createdIssueKeys}")
            rollbackCreatedIssues(createdIssueKeys)
            throw e
        }

        if (logger.getWasErrorLogged()) {
            logger.setErrorMessage("Error during audit generation: rolling back ${createdIssueKeys.size()} issues...")
            loggerIssueOnly.setErrorMessage("Error during audit generation: rolling back ${createdIssueKeys.size()} issues... ${createdIssueKeys}")
            rollbackCreatedIssues(createdIssueKeys)
        }

        return auditOccurrenceMap;
    }

    public Map<String, String> createAuditOccurrenceMap(String questionUsageValue, Issue auditIssue, String assignee, List<String> specialQuestionsDue) {
        def createdIssueKeys = []
        this.logger = new KVSLogger();

        Map<String, String> auditOccurrenceMap = [:]  //semi-yearly   yearly
        try {
            String jqlQuery = """ project = ${CustomFieldsConstants.PROJECT_KVS_AUDIT_QUESTION} AND resolution = Unresolved AND "${Question.QUESTION_USAGE_FIELD_NAME}" = ${questionUsageValue} """.stripIndent().trim();

            List<Issue> questionsIssues = myBaseUtil.findIssues(jqlQuery);
            logger.setWarnMessage("Number of Questions found for questionUsageValue ${questionUsageValue} is: ${questionsIssues.size()}")

            int created = 0;
            // Create subtasks(Questions) for Audit issues, cloning from Questions issues
            questionsIssues?.each { questionIssue ->
                def question = new Question(questionIssue)
                def occurrence = question.getAuditIntervalOccurance();

                boolean shouldCreate = false;
                if (!occurrence || (occurrence != "semi-yearly" && occurrence != "yearly")) {
                    shouldCreate = true
                } else {
                    if (specialQuestionsDue == null || specialQuestionsDue.contains(questionIssue.key)) {
                        shouldCreate = true
                        String intervalForRotation = (occurrence == "semi-yearly") ? "half-year" : occurrence
                        LocalDate nextRotationDate = getNextDate(LocalDate.now(), intervalForRotation)
                        auditOccurrenceMap[questionIssue.key] = [
                                nextRotationDate: nextRotationDate.toString(),
                                workplaceIndex  : 0
                        ]
                    }
                }
                if (shouldCreate) {
                    def createdQuestionIssue = createQuestion(auditIssue, questionIssue, assignee);
                    if (createdQuestionIssue) {
                        verifyAndUpdateIssueMetadata(createdQuestionIssue, assignee, auditIssue);
                        logger.setInfoMessage("Created Question ${createdQuestionIssue.key} under Audit issue: ${auditIssue.key}");

                        createdIssueKeys.add(createdQuestionIssue.key)

                        created++;
                    } else {
                        logger.setErrorMessage("Failed to create Question issue for ${questionIssue.key} under Audit issue: ${auditIssue.key}");
                    }
                }
            }
            logger.setInfoMessage("Successfully created Questions issues: " + created);
        }
        catch (Exception e) {
            // log and rollback
            logger.setErrorMessage("Error during audit generation: ${e.message}, rolling back ${createdIssueKeys.size()} issues...")
            loggerIssueOnly.setErrorMessage("Error during audit generation: ${e.message}, rolling back ${createdIssueKeys.size()} issues... ${createdIssueKeys}")
            rollbackCreatedIssues(createdIssueKeys)
            throw e
        }

        if (logger.getWasErrorLogged()) {
            logger.setErrorMessage("Error during audit generation: rolling back ${createdIssueKeys.size()} issues...")
            loggerIssueOnly.setErrorMessage("Error during audit generation: rolling back ${createdIssueKeys.size()} issues... ${createdIssueKeys}")
            rollbackCreatedIssues(createdIssueKeys)
        }

        return auditOccurrenceMap;
    }

    public Issue createQuestion(Issue auditIssue, Issue questionIssue, String assignee) {
        def subTaskManager = ComponentAccessor.getSubTaskManager();

        Question question = new Question(questionIssue);
        IssueInputParametersImpl questionParams = question.cloneQuestion(auditIssue, assignee);

        def questionValidationResult = validateSubTaskCreate(questionParams, auditIssue, "Failed to validate Question creation for " + questionIssue)
        def questionCreateResult = create(questionValidationResult, "Failed to create Question issue for " + questionIssue)

        updateCategoryOnQuestion(question, questionCreateResult.issue);
        updateLocationOnQuestion(new Audit(auditIssue), questionCreateResult.issue);

        def newQuestionIssue = questionCreateResult.issue
        subTaskManager.createSubTaskIssueLink(auditIssue, newQuestionIssue, loggedInUser);
        auditIssue.store();

        return newQuestionIssue;
    }

    void updateLocationOnQuestion(Audit audit, Issue newQuestionIssue) {
        String auditLevel = audit.getAuditLevel()
        List<Issue> locationIssues = []

        if (auditLevel == CustomFieldsConstants.AUDIT_LEVEL_2) {
            locationIssues = audit.getWorkplaces() ?: []
        } else if (auditLevel == CustomFieldsConstants.AUDIT_LEVEL_3) {
            Issue fa = audit.getFunctionalArea()
            if (fa) locationIssues = [fa]
        }

        if (!locationIssues.isEmpty()) {
            def auditLocationField = CustomFieldUtil.getCustomFieldByName(Question.AUDIT_LOCATION_FIELD_NAME)
            if (auditLocationField) {
                newQuestionIssue.setCustomFieldValue(auditLocationField, locationIssues)
                issueManager.updateIssue(loggedInUser, newQuestionIssue, EventDispatchOption.DO_NOT_DISPATCH, false)
                logger.setInfoMessage("Set Audit Location on ${newQuestionIssue.key} to ${locationIssues*.key} (level: ${auditLevel})")
            } else {
                logger.setErrorMessage("Custom field not found: ${Question.AUDIT_LOCATION_FIELD_NAME}")
            }
        }
    }

    void updateCategoryOnQuestion(Question originalQuestion, Issue newIssue) {
        newIssue.setCustomFieldValue(CustomFieldUtil.getCustomFieldByName(Question.CATEGORY_SK_FIELD_NAME), originalQuestion.getValueOfCategorySK());
        newIssue.setCustomFieldValue(CustomFieldUtil.getCustomFieldByName(Question.CATEGORY_EN_FIELD_NAME), originalQuestion.getValueOfCategoryEN());
        newIssue.setCustomFieldValue(CustomFieldUtil.getCustomFieldByName(Question.CATEGORY_DE_FIELD_NAME), originalQuestion.getValueOfCategoryDE());

        issueManager.updateIssue(loggedInUser, newIssue, EventDispatchOption.DO_NOT_DISPATCH, false)
    }

    protected def create(def issueValidationResult, String errorMsg) {
        def issueResult = issueService.create(loggedInUser, issueValidationResult);
        if (!issueResult.isValid()) {
            logger.setErrorMessage(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        MutableIssue createdIssue = issueResult.issue;
        def parentValue = myBaseUtil.getCustomFieldValue(createdIssue, CustomFieldsConstants.PARENT_LINK_FIELD_ID)
        parentValue = parentValue ?: createdIssue.getParentObject()?.getKey()

        if (parentValue)
            loggerIssueOnly.setInfoMessage("created issue:; ${createdIssue.getIssueType().getName()} ; ${createdIssue.getKey()} ; parentValue: $parentValue")
        else
            loggerIssueOnly.setInfoMessage("created issue:; ${createdIssue.getIssueType().getName()} ; ${createdIssue.getKey()}")

        return issueResult;
    }

    protected def validateSubTaskCreate(IssueInputParametersImpl issueParams, Issue parentIssue, String errorMsg) {
        def issueValidationResult = issueService.validateSubTaskCreate(loggedInUser, parentIssue.id, issueParams)

        if (!issueValidationResult.isValid()) {
            logger.setErrorMessage(errorMsg + " : " + issueValidationResult.errorCollection)
            throw new IllegalStateException(errorMsg + " : " + issueValidationResult.errorCollection)
        }
        return issueValidationResult
    }

    /**
     * Delete all created issues in reverse order
     */
    public void rollbackCreatedIssues(def issueKeysForRollback) {
        IssueManager issueManager = ComponentAccessor.getIssueManager()
        issueKeysForRollback.reverse().each { key ->
            Issue issue = issueManager.getIssueByCurrentKey(key)
            if (issue) {
                issueManager.deleteIssueNoEvent(issue)
                logger.setWarnMessage("Rolled back issue: ${key}")
            }
        }
    }

}
