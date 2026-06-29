package kvs_audits.common

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.IssueInputParametersImpl
import com.atlassian.jira.user.ApplicationUser
import kvs_audits.KVSLogger
import kvs_audits.issueType.Audit
import kvs_audits.issueType.AuditPreparation
import kvs_audits.issueType.Question
import utils.CustomFieldUtil
import utils.MyBaseUtil
import com.atlassian.jira.config.properties.APKeys
import com.atlassian.jira.issue.link.IssueLinkTypeManager
import kvs_audits.issueType.BaseIssue

import java.sql.Timestamp
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ManualMeasure
 *
 * @author chabrecek.anton
 * Created on 23/06/2026.
 */
class ManualMeasure {

    protected KVSLogger logger = new KVSLogger()
    protected CustomFieldUtil customFieldUtil = new CustomFieldUtil()
    protected MyBaseUtil myBaseUtil = new MyBaseUtil()
    protected CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager()
    protected ApplicationUser runAs = ComponentAccessor.userManager.getUserByKey("jira.bot")
    protected def issueService = ComponentAccessor.getIssueService()
    protected def issueManager = ComponentAccessor.getIssueManager()
    protected def issueLinkManager = ComponentAccessor.getIssueLinkManager()
    protected def subTaskManager = ComponentAccessor.getSubTaskManager()
    protected ApplicationUser loggedInUser = ComponentAccessor.jiraAuthenticationContext.loggedInUser

    private static final int QUESTION_SET_NOK_TRANSITION_ID = 71
    private static final List<String> MEASURE_TRANSITION_FIELD_NAMES = [
            Question.DEVIATION_FIELD_NAME,
            Question.MEASURE_FIELD_NAME,
            Question.AUDIT_LOCATION_FIELD_NAME,
            Question.PERSON_RESPONSIBILITY_FIELD_NAME,
            "Notes"
    ]

    void buildNew(MutableIssue eventIssue) {
        if (!eventIssue || eventIssue.issueType?.name != CustomFieldsConstants.MANUAL_MEASURE) {
            return
        }

        /*if (eventIssue.description?.contains("MM_LOCK")) {
            return
        }*/

        MutableIssue mutable = issueManager.getIssueObject(eventIssue.id)
        mutable.setDescription(mutable.description ?: "");//+ "\nMM_LOCK")
        issueManager.updateIssue(loggedInUser, mutable, EventDispatchOption.DO_NOT_DISPATCH, false)

        List<String> createdKeys = []
        try {
            Issue createdAudit = createAuditFromManualMeasure(eventIssue)
            createdKeys << createdAudit.key

            Issue selectedQuestionTemplate = getSingleSelectedQuestion(eventIssue)
            if (!selectedQuestionTemplate) {
                throw new IllegalStateException("Manual Measure needs exactly 1 Question.")
            }

            String assignee = eventIssue.assignee?.name ?: loggedInUser?.name

            CommonHelper helper = new CommonHelper()
            Issue createdQuestion = helper.createQuestion(createdAudit, selectedQuestionTemplate, assignee)
            if (!createdQuestion) {
                throw new IllegalStateException("Question was not created.")
            }
            createdKeys << createdQuestion.key

            transitionQuestionToNok(createdQuestion, eventIssue)

            Issue createdMeasure = findCreatedMeasure(createdQuestion)
            if (!createdMeasure) {
                throw new IllegalStateException("Measure was not created by SET NOK post-function.")
            }
            createdKeys << createdMeasure.key

            appendAuditLinkToDescription(eventIssue, createdAudit, createdQuestion, createdMeasure)

            logger.setInfoMessage("Manual Measure DONE: ${createdAudit.key}, ${createdQuestion.key}, ${createdMeasure.key}")
// check if all was created and set/ report in by logger than delete this issue
            //new CommonHelper().deleteIssue(runAs, eventIssue)
        } catch (Exception e) {
            logger.setErrorMessage("Manual Measure failed: ${e.message}")
            rollback(createdKeys)
            throw e
        }
    }

    private Issue createAuditFromManualMeasure(Issue mmIssue) {
        IssueInputParametersImpl params = new IssueInputParametersImpl()
        params.setProjectId(mmIssue.projectObject.id)
        params.setIssueTypeId(getIssueTypeIdByName(CustomFieldsConstants.AUDIT))
        params.setSummary(mmIssue.summary ?: "Manual audit for ${mmIssue.key}")
        params.setDescription(mmIssue.description)
        params.setReporterId(mmIssue.reporter?.name ?: loggedInUser?.name)
        params.setAssigneeId(mmIssue.assignee?.name ?: loggedInUser?.name)

        copyAuditFields(mmIssue, params)

        def validation = issueService.validateCreate(loggedInUser, params)
        if (!validation.valid) {
            throw new IllegalStateException("Audit validateCreate failed: ${validation.errorCollection}")
        }

        def result = issueService.create(loggedInUser, validation)
        if (!result.valid) {
            throw new IllegalStateException("Audit create failed: ${result.errorCollection}")
        }

        Issue createdAudit = result.issue

        def audit = new Audit(createdAudit)
        audit.setAuditId()

        Map ctx = resolveAuditContextFromMm(mmIssue)
        applyManualAuditTexts(createdAudit, mmIssue, ctx)

        audit.commitIssueUpdate(EventDispatchOption.DO_NOT_DISPATCH)

        return createdAudit
    }

    private Issue firstIssue(def raw) {
        if (raw == null) {
            return null
        }
        if (raw instanceof List) {
            return raw ? raw.first() as Issue : null
        }
        return raw as Issue
    }

    private Map resolveAuditContextFromMm(Issue mmIssue) {
        Issue pcIssue = firstIssue(myBaseUtil.getCustomFieldValue(mmIssue, Audit.PROFIT_CENTER_FIELD.id))
        Issue faIssue = firstIssue(myBaseUtil.getCustomFieldValue(mmIssue, Audit.FUNCTIONAL_AREA_FIELD.id))

        String auditLevel = (myBaseUtil.getCustomFieldValueById(mmIssue, AuditPreparation.AUDIT_LEVEL_FIELD.id) ?: "") as String

        String pcKey = pcIssue
                ? ((myBaseUtil.getCustomFieldValue(pcIssue, CustomFieldsConstants.PROFIT_CENTER_KEY) ?: pcIssue.key) as String)
                : null

        String faKey = faIssue
                ? ((myBaseUtil.getCustomFieldValue(faIssue, CustomFieldsConstants.FUNCTIONAL_AREA_KEY) ?: faIssue.key) as String)
                : null

        String subArea = null
        if (faIssue) {
            def rawSubArea = myBaseUtil.getCustomFieldValue(faIssue, BaseIssue.KVS_PC_SUB_AREA_FIELD_NAME)
            subArea = customFieldUtil.getFieldValue_SingleSelect(rawSubArea)
        }

        boolean isL4orL5 = auditLevel in [
                CustomFieldsConstants.AUDIT_LEVEL_4,
                CustomFieldsConstants.AUDIT_LEVEL_5
        ]

        String secondaryLabel = isL4orL5 ? "KVS PC Sub-Area" : "FA"
        String secondaryValue = isL4orL5 ? subArea : faKey
        String usage = buildManualMeasureUsage(pcIssue, faIssue, pcKey, faKey, subArea, auditLevel)

        return [
                auditLevel    : auditLevel,
                pcIssue       : pcIssue,
                faIssue       : faIssue,
                pcKey         : pcKey,
                faKey         : faKey,
                subArea       : subArea,
                secondaryLabel: secondaryLabel,
                secondaryValue: secondaryValue,
                usage         : usage
        ]
    }

    private String buildManualMeasureUsage(
            Issue pcIssue,
            Issue faIssue,
            String pcKey,
            String faKey,
            String subArea,
            String auditLevel) {

        if (!pcKey || !auditLevel) {
            return null
        }

        String levelToken = auditLevel.trim().replace(' ', '_')

        if (auditLevel in [CustomFieldsConstants.AUDIT_LEVEL_4, CustomFieldsConstants.AUDIT_LEVEL_5]) {
            return subArea ? "${pcKey}_${subArea}_${levelToken}" : "${pcKey}_${levelToken}"
        }

        return new CommonHelper().buildQuestionUsage(pcIssue, faIssue, auditLevel)
    }

    private void applyManualAuditTexts(Issue createdAudit, Issue mmIssue, Map ctx) {
        MutableIssue mutable = issueManager.getIssueObject(createdAudit.id)

        boolean isLevel5 = (ctx.auditLevel == CustomFieldsConstants.AUDIT_LEVEL_5)

        // Plain Description: L5 leaves out FA / secondary line entirely (consistent with scheduled L5 audits).
        List<String> descLines = []
        if (mmIssue.description) descLines << (mmIssue.description as String)
        descLines << "PC= ${ctx.pcKey ?: "-"}"
        if (!isLevel5) descLines << "${ctx.secondaryLabel}= ${ctx.secondaryValue ?: "-"}"
        descLines << "Usage= ${ctx.usage ?: "-"}"
        descLines << "Audit Level= ${ctx.auditLevel ?: "-"}"
        String issueDescription = descLines.join("\n").trim()

        mutable.setDescription(issueDescription)

        def auditDescriptionCf = CustomFieldsConstants.getCustomFieldByName("Audit Description")
        if (auditDescriptionCf) {
            List<String> auditLines = []
            auditLines << "{*}PC={*}${ctx.pcKey ?: "-"}"
            if (isLevel5) {
                // L5: FA key stays empty; KVS PC Sub-Area is exposed as a separate Sub-Area line.
                auditLines << "{*}FA={*}"
                auditLines << "{*}WPC={*}-"
                auditLines << "{*}Audit Level={*}${ctx.auditLevel ?: "-"}"
                auditLines << (ctx.secondaryValue ? "{*}Sub-Area={*} ${ctx.secondaryValue}" : "{*}Sub-Area={*}")
            } else {
                auditLines << "{*}${ctx.secondaryLabel}={*}${ctx.secondaryValue ?: "-"}"
                auditLines << "{*}WPC={*}-"
                auditLines << "{*}Audit Level={*}${ctx.auditLevel ?: "-"}"
            }
            String auditDescriptionText = auditLines.join("\n").trim()

            mutable.setCustomFieldValue(auditDescriptionCf, auditDescriptionText)
        }

        issueManager.updateIssue(loggedInUser, mutable, EventDispatchOption.DO_NOT_DISPATCH, false)
    }

    private Issue getSingleSelectedQuestion(Issue mmIssue) {
        def raw = myBaseUtil.getCustomFieldValue(mmIssue, "Questions")
        List<Issue> selected = (raw instanceof List) ? raw as List<Issue> : []

        if (!selected || selected.isEmpty()) {
            return null
        }

        if (selected.size() > 1) {
            String comment = "More than one Question was selected. Only the first Question '${selected.first().key}' was used. The remaining Questions were ignored."

            // add comment to Manual Measure issue
            ComponentAccessor.commentManager.create(
                    mmIssue,
                    loggedInUser,
                    comment,
                    false
            )
        }

        return selected.first()
    }

    private String toUsername(def value) {
        if (value == null) {
            return null
        }

        if (value instanceof ApplicationUser) {
            return value.username ?: value.name
        }

        // try by key first
        if (value.hasProperty('key') && value.key) {
            def byKey = ComponentAccessor.userManager.getUserByKey(value.key as String)
            if (byKey) {
                return byKey.username ?: byKey.name
            }
        }

        // try by name
        if (value.hasProperty('name') && value.name) {
            def byName = ComponentAccessor.userManager.getUserByName(value.name as String)
            if (byName) {
                return byName.username ?: byName.name
            }
        }

        // plain string fallback
        String raw = value.toString()
        def byKeyRaw = ComponentAccessor.userManager.getUserByKey(raw)
        if (byKeyRaw) {
            return byKeyRaw.username ?: byKeyRaw.name
        }

        def byNameRaw = ComponentAccessor.userManager.getUserByName(raw)
        if (byNameRaw) {
            return byNameRaw.username ?: byNameRaw.name
        }

        logger.setWarnMessage("User '${raw}' could not be resolved for transition field.")
        return null
    }

    private void transitionQuestionToNok(Issue questionIssue, Issue mmIssue) {
        def params = issueService.newIssueInputParameters()

        MEASURE_TRANSITION_FIELD_NAMES.each { String fieldName ->
            copyFieldFromMmToTransition(mmIssue, params, fieldName)
        }

        String comment = getLastCommentBody(mmIssue)
        if (comment) {
            params.setComment(comment)
        }

        def validation = issueService.validateTransition(
                loggedInUser, questionIssue.id, QUESTION_SET_NOK_TRANSITION_ID, params)
        if (!validation.valid) {
            throw new IllegalStateException("SET NOK validate failed: ${validation.errorCollection}")
        }

        def result = issueService.transition(loggedInUser, validation)
        if (!result.valid) {
            throw new IllegalStateException("SET NOK transition failed: ${result.errorCollection}")
        }
    }

    private void copyFieldFromMmToTransition(Issue mmIssue, def params, String fieldName) {
        def cf = CustomFieldUtil.getCustomFieldByName(fieldName)
        if (!cf) {
            return
        }

        def value = myBaseUtil.getCustomFieldValue(mmIssue, fieldName)
        if (value == null) {
            return
        }

        // user picker / multi user picker
        if (fieldName == Question.PERSON_RESPONSIBILITY_FIELD_NAME) {
            if (value instanceof List) {
                String[] usernames = value.collect { toUsername(it) }.findAll { it } as String[]
                if (usernames) {
                    params.addCustomFieldValue(cf.id, usernames)
                }
            } else {
                String username = toUsername(value)
                if (username) {
                    params.addCustomFieldValue(cf.id, username)
                }
            }
            return
        }

        // default branch for other fields
        if (value instanceof List) {
            String[] arr = value.collect { stringifyForParams(it) }.findAll { it } as String[]
            if (arr) {
                params.addCustomFieldValue(cf.id, arr)
            }
        } else {
            String str = stringifyForParams(value)
            if (str) {
                params.addCustomFieldValue(cf.id, str)
            }
        }
    }

    private String stringifyForParams(def value) {
        if (value == null) return null

        if (value instanceof ApplicationUser) {
            return value.username ?: value.name
        }

        if (value.hasProperty('key') && value.key) return value.key as String
        if (value.hasProperty('name') && value.name) return value.name as String

        return value.toString()
    }

    private String getLastCommentBody(Issue mmIssue) {
        def comments = ComponentAccessor.commentManager.getComments(mmIssue)
        return comments ? comments.last().body : null
    }

    private Issue findCreatedMeasure(Issue questionIssue) {
        String measureTypeId = getIssueTypeIdByName(CustomFieldsConstants.MEASURE)

        def measures = issueLinkManager.getOutwardLinks(questionIssue.id)
                .findAll { it.issueLinkType.name == "Relates" }
                .collect { it.destinationObject }
                .findAll { it.issueType.id == measureTypeId }

        return measures ? measures.max { it.created } : null
    }

    private void linkIssues(Issue source, Issue destination) {
        def linkTypeId = ComponentAccessor.getComponent(IssueLinkTypeManager)
                .issueLinkTypes
                .find { it.name == "Relates" }?.id

        if (!linkTypeId) {
            throw new IllegalStateException("Link type 'Relates' not found")
        }

        issueLinkManager.createIssueLink(
                source.id,
                destination.id,
                linkTypeId,
                0,
                loggedInUser
        )
    }

    private void appendAuditLinkToDescription(Issue mmIssue, Issue auditIssue, Issue questionIssue, Issue measureIssue) {
        MutableIssue mutable = issueManager.getIssueObject(mmIssue.id)
        String oldDesc = mutable.description ?: ""

        String appendix = """
            Automatically created:
            - Audit: ${auditIssue.key}
            - Question: ${questionIssue.key}
            - Measure: ${measureIssue.key}
            """

        mutable.setDescription(oldDesc + "\n" + appendix)
        issueManager.updateIssue(loggedInUser, mutable, EventDispatchOption.DO_NOT_DISPATCH, false)
    }

    private void rollback(List<String> keys) {
        new CommonHelper().rollbackCreatedIssues(keys)
    }

    private String getIssueTypeIdByName(String issueTypeName) {
        def type = ComponentAccessor.constantsManager.allIssueTypeObjects.find { it.name == issueTypeName }
        if (!type) {
            throw new IllegalStateException("Issue type not found: ${issueTypeName}")
        }
        return type.id
    }

    private void copyAuditFields(Issue mmIssue, IssueInputParametersImpl params) {
        def pc = myBaseUtil.getCustomFieldValue(mmIssue, Audit.PROFIT_CENTER_FIELD.id)
        def fa = myBaseUtil.getCustomFieldValue(mmIssue, Audit.FUNCTIONAL_AREA_FIELD.id)
        def wpc = myBaseUtil.getCustomFieldValue(mmIssue, Audit.WORKPLACES_FIELD.id)
        def questions = myBaseUtil.getCustomFieldValue(mmIssue, Audit.QUESTIONS_FIELD.id)

        def auditLevelValue = myBaseUtil.getCustomFieldValueById(mmIssue, AuditPreparation.AUDIT_LEVEL_FIELD.id)
        def auditLevelOptionId = auditLevelValue
                ? customFieldUtil.getOptionIdByValue(AuditPreparation.AUDIT_LEVEL_FIELD_NAME, auditLevelValue.toString())
                : null

        def auditTypeOptionId = customFieldUtil.getOptionIdByValue(Audit.AUDIT_TYPE_FIELD_NAME, Audit.MANUAL)

        def targetStartCf = CustomFieldsConstants.getCustomFieldByName(AuditPreparation.TARGET_START_FIELD_NAME)
        def targetStartRaw = targetStartCf ? myBaseUtil.getCustomFieldValueById(mmIssue, targetStartCf.id) : null
        String targetStartValue = toDatePickerValue(targetStartRaw)

        if (pc) {
            params.addCustomFieldValue(Audit.PROFIT_CENTER_FIELD.id, pc.toString())
        }

        if (fa) {
            params.addCustomFieldValue(
                    Audit.FUNCTIONAL_AREA_FIELD.id,
                    (fa instanceof List ? fa*.key : [fa.key]) as String[]
            )
        }

        if (wpc) {
            params.addCustomFieldValue(
                    Audit.WORKPLACES_FIELD.id,
                    (wpc instanceof List ? wpc*.key : []) as String[]
            )
        }

        if (questions) {
            params.addCustomFieldValue(
                    Audit.QUESTIONS_FIELD.id,
                    (questions instanceof List ? questions*.key : []) as String[]
            )
        }

        if (auditLevelOptionId) {
            params.addCustomFieldValue(
                    AuditPreparation.AUDIT_LEVEL_FIELD.id,
                    auditLevelOptionId.toString()
            )
        }

        if (!targetStartCf) {
            throw new IllegalStateException("Custom field not found: ${AuditPreparation.TARGET_START_FIELD_NAME}")
        }

        if (!targetStartValue) {
            throw new IllegalStateException("Target start is empty on Manual Measure.")
        }

        params.addCustomFieldValue(
                targetStartCf.id,
                [targetStartValue] as String[]
        )

        if (!auditTypeOptionId) {
            throw new IllegalStateException("Audit Type option id not found for value: ${Audit.MANUAL}")
        }

        params.addCustomFieldValue(
                Audit.AUDIT_TYPE_FIELD.id,
                auditTypeOptionId.toString()
        )

        params.addCustomFieldValue(
                CustomFieldsConstants.PARENT_LINK_FIELD_ID,
                mmIssue.key
        )
    }

    private String toDatePickerValue(def raw) {
        if (!raw) {
            return null
        }

        String dateFormat = ComponentAccessor.applicationProperties.getString(APKeys.JIRA_DATE_PICKER_JAVA_FORMAT)
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(dateFormat)

        if (raw instanceof Timestamp) {
            return raw.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().format(fmt)
        }

        if (raw instanceof java.sql.Date) {
            return raw.toLocalDate().format(fmt)
        }

        if (raw instanceof Date) {
            return raw.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().format(fmt)
        }

        if (raw instanceof LocalDate) {
            return raw.format(fmt)
        }

        return raw.toString()
    }
}