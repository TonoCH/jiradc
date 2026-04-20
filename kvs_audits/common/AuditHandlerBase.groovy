package kvs_audits.common

import com.atlassian.jira.config.properties.ApplicationProperties
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import groovy.json.JsonOutput;
import kvs_audits.KVSLogger
import kvs_audits.issueType.Audit
import kvs_audits.issueType.AuditPreparation
import kvs_audits.issueType.BaseIssue
import utils.CustomFieldUtil;
import utils.MyBaseUtil;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.IssueInputParametersImpl

import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId

/**
 * AuditHandlerBase
 *
 * @author chabrecek.anton
 * Created on 06/03/2025.
 */
public class AuditHandlerBase {
    protected KVSLogger logger;
    protected KVSLogger loggerIssueOnly;
    protected def loggedInUser;
    protected IssueService issueService;
    protected CustomFieldUtil customFieldUtil;
    protected MyBaseUtil myBaseUtil;
    protected AuditPreparation auditPreparationIssue;
    protected def userManager;
    protected def issueManager;
    protected String currentAuditLevel;
    protected CommonHelper commonHelper;
    protected JqlSearcher jqlSearcher;
    public String kvsProjectKey;
    protected List<String> createdIssueKeys = []
    protected def applicationProperties;

    public AuditHandlerBase(Issue auditPrepIssue, String currentAuditLevel, String kvsProjectKey) {

        this.currentAuditLevel = currentAuditLevel;
        this.kvsProjectKey = kvsProjectKey
        this.loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
        this.userManager = ComponentAccessor.userManager;
        this.issueManager = ComponentAccessor.issueManager;
        this.logger = new KVSLogger();
        this.loggerIssueOnly = new KVSLogger(true)
        this.issueService = ComponentAccessor.getIssueService();
        this.customFieldUtil = new CustomFieldUtil();
        this.myBaseUtil = new MyBaseUtil();
        auditPreparationIssue = new AuditPreparation(auditPrepIssue);
        commonHelper = new CommonHelper()
        jqlSearcher = new JqlSearcher(kvsProjectKey);
        this.applicationProperties = ComponentAccessor.applicationProperties
    }

    /**
     * Template method for creating all initial audits.
     *
     * @param questionUsage list of question-usage keys to drive one Audit per usage
     * @param getRotationUnits closure: (Issue profitCenter, Issue functionalArea) -> List<String> of rotation keys
     * @param rotationFieldId the custom-field ID (e.g. WORKPLACES_FIELD_ID or FUNCTIONAL_AREAS_FIELD_ID)
     */

    protected void executeCreateAudit(List<String> questionUsage, Closure<List<String>> getRotationUnits, String rotationFieldId) {
        try {
            this.createdIssueKeys = []
            this.logger = new KVSLogger();

            Map<String, Map<String, Object>> rotationData = [:]
            List<String> allAuditors = auditPreparationIssue.getAuditors() ?: []
            String assigneeAuditorBase = allAuditors ? allAuditors[0] : loggedInUser.name

            int initialAuditorIndex = 0 // ticket QPL-137
            Set<String> processedLargePCs = [] as Set

            for (String questionUsageValue : questionUsage) {

                String assigneeForThisAudit = assigneeAuditorBase   // default = first auditor

                if (!isCorrectAuditLevel(questionUsageValue)) {
                    logger.setWarnMessage("question usage: $questionUsageValue is not for audit $currentAuditLevel continue with next question usage");
                    continue
                }

                Issue profitCenter = jqlSearcher.getPCFromQuestionUsage(questionUsageValue, currentAuditLevel)
                if (profitCenter == null) {
                    logger.setErrorMessage("Profit center for questionUsageValue: $questionUsageValue cannot be null, creation of issues failed no issue for these usage was generated, continue");
                    continue;
                }

                //Issue functionalArea  = jqlSearcher.getFA_FromQuestionUsage(questionUsageValue, profitCenter, currentAuditLevel)
                Issue functionalArea = null
                List<String> units

                if (currentAuditLevel == CustomFieldsConstants.AUDIT_LEVEL_2) {
                    // L2 stays as-is: FA must come from usage
                    functionalArea = jqlSearcher.getFA_FromQuestionUsage(questionUsageValue, profitCenter, currentAuditLevel)
                    if (functionalArea == null) {
                        logger.setErrorMessage("Functional Area for questionUsageValue: $questionUsageValue cannot be null, creation of issues failed no issue for these usage was generated, continue");
                        continue
                    }
                    units = getRotationUnits(profitCenter, functionalArea)

                } else if (currentAuditLevel == CustomFieldsConstants.AUDIT_LEVEL_3) {
                    // L3: do NOT require FA in 'Question Usage'. Rotate across all FAs under the PC.
                    List<String> faKeys = getRotationUnits(profitCenter, null)
                    // closure returns FA keys for this PC (see L3 handler)
                    if (!faKeys || faKeys.isEmpty()) {
                        logger.setErrorMessage("No Functional Areas found under ${profitCenter.key} for usage ${questionUsageValue} – skipping.")
                        continue
                    }
                    functionalArea = myBaseUtil.getIssueByKey(faKeys[0])          // pick first FA for initial audit
                    units = faKeys
                    logger.setInfoMessage("L3 create: using first FA '${functionalArea.key}' and rotating across ${units.size()} FAs for ${profitCenter.key}")

                }
                else if (currentAuditLevel == CustomFieldsConstants.AUDIT_LEVEL_4 || currentAuditLevel == CustomFieldsConstants.AUDIT_LEVEL_5) {

                    boolean isLargePcUsage = ((questionUsageValue =~ /_(A|B)_Level_[45]$/).find())

                    // L5: keep original auditor cycling for initial audits
                    if (currentAuditLevel == CustomFieldsConstants.AUDIT_LEVEL_5 && isLargePcUsage && allAuditors) {
                        assigneeForThisAudit = allAuditors[initialAuditorIndex % allAuditors.size()]
                        initialAuditorIndex++
                    }

                    //update 27.8
                    String subArea = commonHelper.extractValuesFromQuestionUsage(questionUsageValue)[1]
                    logger.setWarnMessage("subAreaLetter 1:"+subArea)
                    subArea = parseSubAreaLetterOrNull(questionUsageValue)
                    logger.setWarnMessage("subAreaLetter 2:"+subArea)
                    if (!subArea) {
                        List<Issue> aFAs = jqlSearcher.getFunctionalAreas(profitCenter, "A") ?: []
                        List<Issue> bFAs = jqlSearcher.getFunctionalAreas(profitCenter, "B") ?: []
                        boolean onlyA = aFAs && !bFAs
                        boolean onlyB = bFAs && !aFAs

                        if (onlyA) {
                            subArea = "A"
                            logger.setInfoMessage("L4 create: usage '${questionUsageValue}' no A/B → 'A' for ${profitCenter.key}")
                        } else if (onlyB) {
                            subArea = "B"
                            logger.setInfoMessage("L4 create: usage '${questionUsageValue}' no A/B → 'B' for ${profitCenter.key}")
                        } else {
                            logger.setErrorMessage("Level 4 requires sub-area A or B in usage or value from PC. ${profitCenter.key} has A and B → '${questionUsageValue}' is not determinated.")
                            continue
                        }
                    }
                    List<Issue> matchingFAs = jqlSearcher.getFunctionalAreas(profitCenter, subArea)
                    if (!matchingFAs) {
                        logger.setErrorMessage("No Functional Areas under ${profitCenter.key} marked with sub-area '${subArea}' for ${questionUsageValue}")
                        continue
                    }
                    functionalArea = matchingFAs[0]
                    units = matchingFAs*.key

                    // Large PC L4 only: create only one initial audit; second area handled by rotation
                    if (currentAuditLevel == CustomFieldsConstants.AUDIT_LEVEL_4 && isLargePcUsage && processedLargePCs.contains(profitCenter.key)) {
                        String rotationUsageKey = questionUsageValue
                        List<Issue> specialQs = jqlSearcher.findSpecialQuestionsByUsage(rotationUsageKey)
                        LocalDate startBase = Optional.ofNullable(auditPreparationIssue.getDate_target_start())
                                .map { it instanceof java.sql.Timestamp ? it.toLocalDate() : it }
                                .orElse(LocalDate.now())
                        LocalDate baseDate = CommonHelper.getNextDate(startBase, auditPreparationIssue.getInterval())
                        Map<String, Object> occMap = [:]
                        specialQs?.each { qIssue -> occMap[qIssue.key] = [nextRotationDate: baseDate.toString()] }
                        rotationData[rotationUsageKey] = createRotationData(rotationUsageKey, (units ?: []) as String[], occMap)
                        logger.setInfoMessage("Large PC ${profitCenter.key}: rotation data for area '${subArea}' prepared, no extra initial audit")
                        continue
                    }
                    if (currentAuditLevel == CustomFieldsConstants.AUDIT_LEVEL_4 && isLargePcUsage) processedLargePCs << profitCenter.key
                } else {
                    // default (ak pribudnú iné levely): správanie ako L2
                    functionalArea = jqlSearcher.getFA_FromQuestionUsage(questionUsageValue, profitCenter, currentAuditLevel)
                    if (!functionalArea) {
                        logger.setErrorMessage("Functional Area ... cannot be null ...")
                        continue
                    }
                    units = getRotationUnits(profitCenter, functionalArea)
                }

                String reporterName = auditPreparationIssue.getIssue().reporter?.name ?: loggedInUser.name

                // prepare the Audit
                def auditLevelOptionId = customFieldUtil.getOptionIdByValue(AuditPreparation.AUDIT_LEVEL_FIELD_NAME, auditPreparationIssue.getAuditLevel())
                def auditTypeOptionId = customFieldUtil.getOptionIdByValue(Audit.AUDIT_TYPE_FIELD_NAME, Audit.PLANNED)

                def auditParams = new Audit(null)
                        .prepareAuditParams(auditPreparationIssue, profitCenter.key, functionalArea?.key)
                        .setAssigneeId(assigneeForThisAudit)
                        .setReporterId(reporterName)
                        .addCustomFieldValue(CustomFieldsConstants.getCustomFieldByName(AuditPreparation.AUDIT_LEVEL_FIELD_NAME)?.id, auditLevelOptionId?.toString())
                        .addCustomFieldValue(CustomFieldsConstants.getCustomFieldByName(AuditPreparation.TARGET_START_FIELD_NAME)?.id, auditPreparationIssue.getDate_targetStartAsDateTime())
                        .addCustomFieldValue(CustomFieldsConstants.getCustomFieldByName(Audit.AUDIT_TYPE_FIELD_NAME)?.id, auditTypeOptionId?.toString())

                // assign the *first* rotation unit
                //List<String> units = getRotationUnits(profitCenter, functionalArea)
                if (units && !units.isEmpty()) {
                    auditParams.addCustomFieldValue(rotationFieldId, [units[0]] as String[])
                    logger.setInfoMessage("Assigning FIRST $rotationFieldId '${units[0]}' on initial Audit for usage=$questionUsageValue")
                } else {
                    logger.setErrorMessage("No units found for $rotationFieldId on FA $functionalArea")
                }

                // create the Audit issue
                def validation = validateCreate(auditParams, "Failed to validate Audit creation for $questionUsageValue")
                def result = create(validation, "Failed to create Audit issue for $questionUsageValue")

                def audit = new Audit(result.issue)
                audit.setAuditId();
                //audit.setAuditType(Audit.PLANNED)
                audit.commitIssueUpdate(EventDispatchOption.DO_NOT_DISPATCH)
                recordCreatedIssue(result.issue.key)

                // target end date
                LocalDate targetEndDate;
                auditPreparationIssue.getDate_target_start()?.with { ts ->
                    LocalDate targetStartLocalDate = ts.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                    targetEndDate = targetStartLocalDate.plusDays(CustomFieldsConstants.NUM_OF_DAYS_FOR_TARGET_END)
                    commonHelper.updateTargetEndDate(result.issue, targetEndDate)
                }

                commonHelper.verifyAndUpdateIssueMetadata(result.issue, assigneeAuditorBase, auditPreparationIssue.getIssue())

                //send mail if level 3
                if (currentAuditLevel == CustomFieldsConstants.AUDIT_LEVEL_3 && targetEndDate) {
                    new AuditMailer().sendAuditInviteAsICS(result.issue, assigneeForThisAudit, targetEndDate)
                    logger.setInfoMessage("Email invitation send for audit:$result.issue.key with targetEndDate: $targetEndDate")
                } else {
                    logger.setWarnMessage("Email invitation was not send for audit:$result.issue.key with targetEndDate: $targetEndDate")
                }

                logger.setInfoMessage("Created Audit ${result.issue.key}, now continue with questions…")

                // --- CHANGED: question generation for L3 uses dynamic usage ---
                String usageForQuestions = questionUsageValue
                if (currentAuditLevel == CustomFieldsConstants.AUDIT_LEVEL_3) {
                    // Build dynamic usage: PC + selected FA for this audit + Level_3
                    usageForQuestions = commonHelper.buildQuestionUsage(profitCenter, functionalArea, currentAuditLevel)
                    logger.setInfoMessage("L3 create: using dynamic usage '${usageForQuestions}' for question generation")
                }


                String usageForSpecials = (currentAuditLevel == CustomFieldsConstants.AUDIT_LEVEL_3) ? usageForQuestions : questionUsageValue
                List<Issue> specialQuestions = jqlSearcher.findSpecialQuestionsByUsage(usageForSpecials)

                LocalDate startBase = Optional.ofNullable(auditPreparationIssue.getDate_target_start())
                        .map { it instanceof java.sql.Timestamp ? it.toLocalDate() : it }
                        .orElse(LocalDate.now())

                LocalDate firstRotationDay = CommonHelper.getNextDate(startBase, auditPreparationIssue.getInterval())
                LocalDate baseDate = firstRotationDay

                Map<String, Object> auditOccurrenceMap = [:]
                specialQuestions?.each { qIssue ->
                    auditOccurrenceMap[qIssue.key] = [nextRotationDate: baseDate.toString()]
                }

                String rotationUsageKey = (currentAuditLevel == CustomFieldsConstants.AUDIT_LEVEL_3)
                        ? usageForQuestions
                        : questionUsageValue

                rotationData[rotationUsageKey] = createRotationData(rotationUsageKey, (units ?: []) as String[], auditOccurrenceMap)
                logger.setInfoMessage("Rotation data prepared for usage=${rotationUsageKey} (questions deferred)")

                def payloadCf = CustomFieldsConstants.getCustomFieldByName(Audit.KVS_GENERATION_PAYLOAD_FIELD_NAME)
                if (!payloadCf) {
                    logger.setErrorMessage("Custom field not found: " + Audit.KVS_GENERATION_PAYLOAD_FIELD_NAME)
                } else {
                    List<String> specialDue = specialQuestions*.key  // at create-time we include all; transition will decide usage
                    Map payload = [
                            usageKeys : [usageForSpecials],
                            specialDue: (specialDue ?: []),
                            skip      : false
                    ]
                    myBaseUtil.setCustomFieldValue(result.issue, payloadCf, groovy.json.JsonOutput.toJson(payload))
                    logger.setInfoMessage("Init payload for ${result.issue.key}: ${payload}")
                }

            }

            logger.setInfoMessage("Initializing rotation data…")
            initializeRotationData(rotationData, !processedLargePCs.isEmpty() ? 1 : 0)
            logger.setInfoMessage("Rotation data set")
        }
        catch (Exception e) {
            // log and rollback
            logger.setErrorMessage("Error during audit generation: ${e.message}, rolling back ${createdIssueKeys.size()} issues...")
            loggerIssueOnly.setErrorMessage("Error during audit generation: ${e.message}, rolling back ${createdIssueKeys.size()} issues... ${createdIssueKeys}")
            commonHelper.rollbackCreatedIssues(createdIssueKeys)
            throw e
        }

        if (logger.getWasErrorLogged()) {
            logger.setErrorMessage("Error during audit generation: rolling back ${createdIssueKeys.size()} issues...")
            loggerIssueOnly.setErrorMessage("Error during audit generation: rolling back ${createdIssueKeys.size()} issues... ${createdIssueKeys}")
            commonHelper.rollbackCreatedIssues(createdIssueKeys)
        }
    }

    protected void recordCreatedIssue(String issueKey) {
        createdIssueKeys << issueKey
    }

    protected void initializeRotationData(Map<String, Map<String, Object>> questions_usages, int initialTurnIndex = 0) {

        String interval = auditPreparationIssue.getInterval();
        LocalDate today = LocalDate.now()
        LocalDate startDate = Optional.ofNullable(auditPreparationIssue.getDate_target_start())
                .map { it instanceof java.sql.Timestamp ? it.toLocalDate() : it }
                .orElse(today)

        logger.setWarnMessage("startDate: $startDate")

        def rotationData = [
                questions_usages: questions_usages
        ]

        String rotationJson = JsonOutput.toJson(rotationData)
        updateRotationData(rotationJson);

        LocalDate nextRotation = CommonHelper.getNextDate(startDate, interval)
        updateDateOfNextRotation(nextRotation);
    }

    public updateRotationData(String rotationJson) {
        Object rotation_data_customFiled = customFieldUtil.getCustomFieldByName(AuditPreparation.ROTATION_DATA_FIELD_NAME);
        if (rotation_data_customFiled == null) {
            String errorMsg = "Custom field didnt exists: $AuditPreparation.ROTATION_DATA_FIELD_NAME"
            logger.setErrorMessage(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        myBaseUtil.setCustomFieldValue(auditPreparationIssue.getIssue(), rotation_data_customFiled, rotationJson)
    }

    public updateDateOfNextRotation(LocalDate nextRotation) {
        logger.setWarnMessage("Date of next rotation: nextRotation = $nextRotation")
        Object date_of_next_rotation_customFiled = customFieldUtil.getCustomFieldByName(AuditPreparation.DATE_OF_NEXT_ROTATION);
        if (date_of_next_rotation_customFiled == null) {
            String errorMsg = "Custom field didnt exists: $AuditPreparation.DATE_OF_NEXT_ROTATION"
            logger.setErrorMessage(errorMsg);
            throw new IllegalStateException(errorMsg);
        }


        Timestamp timestamp = Timestamp.from(nextRotation.atStartOfDay(ZoneId.systemDefault()).toInstant())
        myBaseUtil.setCustomFieldValue(auditPreparationIssue.getIssue(), date_of_next_rotation_customFiled, timestamp)
        logger.setWarnMessage("AFTER SET Date of next rotation: timestamp = $timestamp")
    }

    protected IssueService.CreateValidationResult validateCreate(IssueInputParametersImpl issueParams, String errorMsg) {
        IssueService.CreateValidationResult issueValidationResult = issueService.validateCreate(loggedInUser, issueParams);
        if (!issueValidationResult.isValid()) {
            logger.setErrorMessage(errorMsg + " : " + issueValidationResult.errorCollection);
            throw new IllegalStateException(errorMsg + " : " + issueValidationResult.errorCollection);
        }

        return issueValidationResult;
    }

    /*protected def validateSubTaskCreate(IssueInputParametersImpl issueParams, Issue parrentIssue, String errorMsg) {
        def issueValidationResult = issueService.validateSubTaskCreate(loggedInUser, issueParams);
        if (!issueValidationResult.isValid()) {
            logger.setErrorMessage(errorMsg + " : " + issueValidationResult.errorCollection);
            throw new IllegalStateException(errorMsg + " : " + issueValidationResult.errorCollection);
        }

        return issueValidationResult;
    }*/

    protected def create(IssueService.CreateValidationResult issueValidationResult, String errorMsg) {
        def issueResult = issueService.create(loggedInUser, issueValidationResult);
        if (!issueResult.isValid()) {
            logger.setErrorMessage("${errorMsg}. Errors: ${issueResult.errorCollection}");
            throw new IllegalStateException("${errorMsg}. Errors: ${issueResult.errorCollection}");
        }

        recordCreatedIssue(issueValidationResult.getIssue().getKey())

        MutableIssue createdIssue = issueResult.issue;
        CustomField parentValue = myBaseUtil.getCustomFieldValue(createdIssue, CustomFieldsConstants.PARENT_LINK_FIELD_ID)
        parentValue = parentValue ?: createdIssue.getParentObject()?.getKey()

        if (parentValue)
            loggerIssueOnly.setInfoMessage("created issue:; ${createdIssue.getIssueType().getName()} ; ${createdIssue.getKey()} ; parentValue: $parentValue")
        else
            loggerIssueOnly.setInfoMessage("created issue:; ${createdIssue.getIssueType().getName()} ; ${createdIssue.getKey()}")

        return issueResult;
    }

    protected boolean isCorrectAuditLevel(String questionUsage) {
        def usage = commonHelper.extractValuesFromQuestionUsage(questionUsage)
        String usageAuditLevel = usage[2] ?: null

        if (usageAuditLevel != null && currentAuditLevel.replace(" ", "_") == usageAuditLevel) {
            return true;
        }

        return false
    }

    /*private Map<String, Object> createRotationData(String questionUsageValue, String[] workplacesKeys, Map<String, Object> auditOccurrenceMap) {
        List<String> auditors = auditPreparationIssue.getAuditors();

        Map<String, Object> questions_usages = [
                usageKey             : questionUsageValue,
                workplaces           : workplacesKeys as List,
                currentWorkplaceIndex: 1,
                auditors             : auditors,
                currentAuditorIndex  : 1,
                specialQuestions     : auditOccurrenceMap ?: [:]
        ]

        return questions_usages;
    }*/

    /* private Map<String, Object> createRotationData(String questionUsageValue, String[] workplacesKeys, Map<String, Object> auditOccurrenceMap) {
         List<String> auditors = auditPreparationIssue.getAuditors()

         // Re-map special questions to per-WP rotation
         Map<String, Object> transformedSpecialQuestions = [:]

         auditOccurrenceMap?.each { qKey, value ->
             Map<String, Map> wpRotation = [:]
             workplacesKeys.each { wpKey ->
                 wpRotation[wpKey] = [ nextRotationDate: LocalDate.now().toString() ]
             }
             transformedSpecialQuestions[qKey] = [ wpRotation: wpRotation ]
         }

         return [
                 usageKey             : questionUsageValue,
                 workplaces           : workplacesKeys as List,
                 currentWorkplaceIndex: 1,
                 auditors             : auditors,
                 currentAuditorIndex  : 1,
                 specialQuestions     : transformedSpecialQuestions
         ]
     }*/

    private Map<String, Object> createRotationData(String questionUsageValue,
                                                   String[] workplacesKeys,
                                                   Map<String, Object> auditOccurrenceMap) {
        List<String> auditors = auditPreparationIssue.getAuditors()

        String rotationBucket = (currentAuditLevel == CustomFieldsConstants.AUDIT_LEVEL_3) ? "faRotation" : "wpRotation"

        Map<String, Object> transformedSpecialQuestions = [:]

        auditOccurrenceMap?.each { qKey, value ->
            Map<String, Map> perUnitRotation = [:]
            workplacesKeys.each { wpKey ->
                perUnitRotation[wpKey] = [
                        nextRotationDate: (value?.nextRotationDate ?: LocalDate.now().toString())
                ]
            }
            transformedSpecialQuestions[qKey] = [(rotationBucket): perUnitRotation]
        }

        List<Issue> specialQuestions = jqlSearcher.findSpecialQuestionsByUsage(questionUsageValue)
        specialQuestions?.each { qIssue ->
            if (!transformedSpecialQuestions.containsKey(qIssue.key)) {
                Map<String, Map> perUnitRotation = [:]
                workplacesKeys.each { wpKey ->
                    perUnitRotation[wpKey] = [
                            nextRotationDate: LocalDate.now().toString()
                    ]
                }
                transformedSpecialQuestions[qIssue.key] = [(rotationBucket): perUnitRotation]
            }
        }

        if(currentAuditLevel == CustomFieldsConstants.AUDIT_LEVEL_3){
            return [
                    usageKey             : questionUsageValue,
                    fas                  : workplacesKeys as List,
                    currentFaIndex       : 1,
                    auditors             : auditors,
                    currentAuditorIndex  : 1,
                    specialQuestions     : transformedSpecialQuestions
            ]
        }
        else {
            return [
                    usageKey             : questionUsageValue,
                    workplaces           : workplacesKeys as List,
                    currentWorkplaceIndex: 1,
                    auditors             : auditors,
                    currentAuditorIndex  : 1,
                    specialQuestions     : transformedSpecialQuestions
            ]
        }
    }

    private String parseSubAreaLetterOrNull(String usageKey) {
        //  *_A_Level_4 or *_B_Level_4
        //def m = (usageKey =~ /_(A|B)_Level_4$/)
        //add level 5
        def m = (usageKey =~ /_(A|B)_Level_[45]$/)
        return m ? m[0][1] : null
    }

}