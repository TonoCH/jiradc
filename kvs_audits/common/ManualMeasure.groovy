package kvs_audits.common

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.properties.APKeys
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.IssueInputParametersImpl
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.link.IssueLinkTypeManager
import com.atlassian.jira.user.ApplicationUser
import kvs_audits.KVSLogger
import kvs_audits.issueType.Audit
import kvs_audits.issueType.AuditPreparation
import kvs_audits.issueType.BaseIssue
import kvs_audits.issueType.Question
import utils.CustomFieldUtil
import utils.MyBaseUtil

import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ManualMeasure
 *
 * Creates Audit -> Question -> Measure structure from Manual Measure issue.
 *
 * Important:
 * - Audit Location is not copied during SET NOK transition.
 *   It is set by CommonHelper.createQuestion/updateLocationOnQuestion from created Audit.
 * - Questions are not copied to created Audit to avoid duplicate question creation
 *   by AuditManualUnplanned listener.
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

    /**
     * Do not include Question.AUDIT_LOCATION_FIELD_NAME here.
     *
     * Reason:
     * Manual Measure Audit Location can contain a value which does not fit the
     * transition field filter on Question, for example customfield_17519.
     * CommonHelper.createQuestion() already sets Audit Location from Audit:
     * - Level 2 -> Workplaces
     * - Level 3 -> Functional Area
     */
    private static final List<String> MEASURE_TRANSITION_FIELD_NAMES = [
            Question.DEVIATION_FIELD_NAME,
            Question.MEASURE_FIELD_NAME,
            Question.PERSON_RESPONSIBILITY_FIELD_NAME,
            "Notes"
    ]

    void buildNew(MutableIssue eventIssue) {
        if (!eventIssue || eventIssue.issueType?.name != CustomFieldsConstants.MANUAL_MEASURE) {
            return
        }

        MutableIssue mutable = issueManager.getIssueObject(eventIssue.id)
        mutable.setDescription(mutable.description ?: "")
        issueManager.updateIssue(actor(), mutable, EventDispatchOption.DO_NOT_DISPATCH, false)

        List<String> createdKeys = []

        try {
            Issue createdAudit = createAuditFromManualMeasure(eventIssue)
            createdKeys << createdAudit.key

            Issue selectedQuestionTemplate = getSingleSelectedQuestion(eventIssue)
            if (!selectedQuestionTemplate) {
                throw new IllegalStateException("Manual Measure needs exactly 1 Question.")
            }

            String assignee = eventIssue.assignee?.name ?: actor()?.name

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

            // Delete original Manual Measure if needed after successful generation.
            // new CommonHelper().deleteIssue(runAs, eventIssue)

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
        params.setReporterId(mmIssue.reporter?.name ?: actor()?.name)
        params.setAssigneeId(mmIssue.assignee?.name ?: actor()?.name)

        copyAuditFields(mmIssue, params)

        def validation = issueService.validateCreate(actor(), params)
        if (!validation.valid) {
            throw new IllegalStateException("Audit validateCreate failed: ${validation.errorCollection}")
        }

        def result = issueService.create(actor(), validation)
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

    /**
     * Copies only values needed on Audit.
     *
     * Questions are intentionally not copied to created Audit.
     * Otherwise AuditManualUnplanned listener can create question subtasks
     * before this ManualMeasure flow creates its own selected question.
     */
    private void copyAuditFields(Issue mmIssue, IssueInputParametersImpl params) {
        def pc = getManualMeasureField(mmIssue, "Profit Center", Audit.PROFIT_CENTER_FIELD)
        def fa = getManualMeasureField(mmIssue, "Functional Area", Audit.FUNCTIONAL_AREA_FIELD)
        def wpc = getManualMeasureField(mmIssue, "Workplaces", Audit.WORKPLACES_FIELD)

        def auditLevelValue = myBaseUtil.getCustomFieldValueById(mmIssue, AuditPreparation.AUDIT_LEVEL_FIELD.id)

        logger.setInfoMessage("ManualMeasure copyAuditFields PC=${debugIssueValue(pc)}")
        logger.setInfoMessage("ManualMeasure copyAuditFields FA=${debugIssueValue(fa)}")
        logger.setInfoMessage("ManualMeasure copyAuditFields WPC=${debugIssueValue(wpc)}")
        logger.setInfoMessage("ManualMeasure copyAuditFields AuditLevel=${auditLevelValue}")

        String pcKey = firstIssueKey(pc)
        if (pcKey) {
            params.addCustomFieldValue(Audit.PROFIT_CENTER_FIELD.id, pcKey)
        }

        String faKey = firstIssueKey(fa)
        if (faKey) {
            params.addCustomFieldValue(
                    Audit.FUNCTIONAL_AREA_FIELD.id,
                    [faKey] as String[]
            )
        }

        String[] wpKeys = issueKeysArray(wpc)
        if (wpKeys && wpKeys.length > 0) {
            params.addCustomFieldValue(
                    Audit.WORKPLACES_FIELD.id,
                    wpKeys
            )
        }

        def auditLevelOptionId = auditLevelValue
                ? customFieldUtil.getOptionIdByValue(AuditPreparation.AUDIT_LEVEL_FIELD_NAME, auditLevelValue.toString())
                : null

        if (auditLevelOptionId) {
            params.addCustomFieldValue(
                    AuditPreparation.AUDIT_LEVEL_FIELD.id,
                    auditLevelOptionId.toString()
            )
        }

        def auditTypeOptionId = customFieldUtil.getOptionIdByValue(Audit.AUDIT_TYPE_FIELD_NAME, Audit.MANUAL)
        if (!auditTypeOptionId) {
            throw new IllegalStateException("Audit Type option id not found for value: ${Audit.MANUAL}")
        }

        params.addCustomFieldValue(
                Audit.AUDIT_TYPE_FIELD.id,
                auditTypeOptionId.toString()
        )

        def targetStartCf = CustomFieldsConstants.getCustomFieldByName(AuditPreparation.TARGET_START_FIELD_NAME)
        if (!targetStartCf) {
            throw new IllegalStateException("Custom field not found: ${AuditPreparation.TARGET_START_FIELD_NAME}")
        }

        def targetStartRaw = myBaseUtil.getCustomFieldValueById(mmIssue, targetStartCf.id)
        String targetStartValue = toDatePickerValue(targetStartRaw)

        if (!targetStartValue) {
            throw new IllegalStateException("Target start is empty on Manual Measure.")
        }

        params.addCustomFieldValue(
                targetStartCf.id,
                [targetStartValue] as String[]
        )

        params.addCustomFieldValue(
                CustomFieldsConstants.PARENT_LINK_FIELD_ID,
                mmIssue.key
        )
    }

    /**
     * Reads from Audit field id first, then from all custom fields with given name.
     *
     * This is important because in Jira you can have fields with same display name
     * but different customfield ids or contexts. The old getCustomFieldObjectByName()
     * can return the wrong field when names are duplicated.
     */
    private def getManualMeasureField(Issue issue, String fieldName, CustomField auditField) {
        def byAuditFieldId = null

        if (auditField) {
            byAuditFieldId = myBaseUtil.getCustomFieldValue(issue, auditField.id)
            if (hasValue(byAuditFieldId)) {
                return byAuditFieldId
            }

            byAuditFieldId = issue.getCustomFieldValue(auditField)
            if (hasValue(byAuditFieldId)) {
                return byAuditFieldId
            }
        }

        return getFirstNonEmptyCustomFieldValueByName(issue, fieldName)
    }

    private def getFirstNonEmptyCustomFieldValueByName(Issue issue, String fieldName) {
        List<CustomField> fields = customFieldManager.getCustomFieldObjects()
                .findAll { CustomField cf -> cf.name == fieldName }

        for (CustomField cf : fields) {
            def value = issue.getCustomFieldValue(cf)
            if (hasValue(value)) {
                logger.setInfoMessage("ManualMeasure field '${fieldName}' resolved by custom field id ${cf.id}")
                return value
            }
        }

        def fallback = myBaseUtil.getCustomFieldValue(issue, fieldName)
        if (hasValue(fallback)) {
            logger.setInfoMessage("ManualMeasure field '${fieldName}' resolved by MyBaseUtil name fallback")
            return fallback
        }

        return null
    }

    private boolean hasValue(def value) {
        if (value == null) {
            return false
        }

        if (value instanceof Collection) {
            return !value.isEmpty()
        }

        if (value instanceof String) {
            return value.trim().length() > 0
        }

        return true
    }

    private Issue firstIssue(def raw) {
        List<Issue> issues = asIssueList(raw)
        return issues ? issues.first() : null
    }

    private List<Issue> asIssueList(def raw) {
        if (!hasValue(raw)) {
            return []
        }

        if (raw instanceof Issue) {
            return [raw]
        }

        if (raw instanceof Collection) {
            return raw.collect { item ->
                if (item instanceof Issue) {
                    return item
                }

                String key = stringifyForParams(item)
                return key ? issueManager.getIssueObject(key) : null
            }.findAll { it } as List<Issue>
        }

        String key = stringifyForParams(raw)
        Issue issue = key ? issueManager.getIssueObject(key) : null
        return issue ? [issue] : []
    }

    private String firstIssueKey(def raw) {
        Issue issue = firstIssue(raw)
        return issue?.key
    }

    private String[] issueKeysArray(def raw) {
        List<Issue> issues = asIssueList(raw)
        return issues.collect { it.key }.findAll { it } as String[]
    }

    private String debugIssueValue(def raw) {
        List<Issue> issues = asIssueList(raw)
        if (issues) {
            return issues.collect { "${it.key} ${it.summary}" }.join(", ")
        }

        return raw == null ? "null" : raw.toString()
    }

    private Map resolveAuditContextFromMm(Issue mmIssue) {
        Issue pcIssue = firstIssue(getManualMeasureField(mmIssue, "Profit Center", Audit.PROFIT_CENTER_FIELD))
        Issue faIssue = firstIssue(getManualMeasureField(mmIssue, "Functional Area", Audit.FUNCTIONAL_AREA_FIELD))
        List<Issue> wpIssues = asIssueList(getManualMeasureField(mmIssue, "Workplaces", Audit.WORKPLACES_FIELD))

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

        String wpcText = wpIssues
                ? wpIssues.collect { Issue wp -> "${wp.key} ${wp.summary}" }.join(", ")
                : null

        return [
                auditLevel    : auditLevel,
                pcIssue       : pcIssue,
                faIssue       : faIssue,
                wpIssues      : wpIssues,
                pcKey         : pcKey,
                faKey         : faKey,
                subArea       : subArea,
                secondaryLabel: secondaryLabel,
                secondaryValue: secondaryValue,
                usage         : usage,
                wpcText       : wpcText
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
            return subArea
                    ? "${pcKey}_${subArea}_${levelToken}"
                    : "${pcKey}_${levelToken}"
        }

        return new CommonHelper().buildQuestionUsage(pcIssue, faIssue, auditLevel)
    }

    private void applyManualAuditTexts(Issue createdAudit, Issue mmIssue, Map ctx) {
        MutableIssue mutable = issueManager.getIssueObject(createdAudit.id)

        boolean isLevel5 = (ctx.auditLevel == CustomFieldsConstants.AUDIT_LEVEL_5)

        List<String> descLines = []
        if (mmIssue.description) {
            descLines << (mmIssue.description as String)
        }

        descLines << "PC= ${ctx.pcKey ?: "-"}"

        if (!isLevel5) {
            descLines << "${ctx.secondaryLabel}= ${ctx.secondaryValue ?: "-"}"
        }

        if (ctx.wpcText) {
            descLines << "WPC= ${ctx.wpcText}"
        } else {
            descLines << "WPC= -"
        }

        descLines << "Usage= ${ctx.usage ?: "-"}"
        descLines << "Audit Level= ${ctx.auditLevel ?: "-"}"

        mutable.setDescription(descLines.join("\n").trim())

        def auditDescriptionCf = CustomFieldsConstants.getCustomFieldByName("Audit Description")
        if (auditDescriptionCf) {
            List<String> auditLines = []

            auditLines << "{*}PC={*}${ctx.pcKey ?: "-"}"

            if (isLevel5) {
                auditLines << "{*}FA={*}"
                auditLines << "{*}WPC={*}${ctx.wpcText ?: "-"}"
                auditLines << "{*}Audit Level={*}${ctx.auditLevel ?: "-"}"
                auditLines << (ctx.secondaryValue ? "{*}Sub-Area={*} ${ctx.secondaryValue}" : "{*}Sub-Area={*}")
            } else {
                auditLines << "{*}${ctx.secondaryLabel}={*}${ctx.secondaryValue ?: "-"}"
                auditLines << "{*}WPC={*}${ctx.wpcText ?: "-"}"
                auditLines << "{*}Audit Level={*}${ctx.auditLevel ?: "-"}"
            }

            mutable.setCustomFieldValue(auditDescriptionCf, auditLines.join("\n").trim())
        }

        issueManager.updateIssue(actor(), mutable, EventDispatchOption.DO_NOT_DISPATCH, false)

        logger.setInfoMessage("ManualMeasure Audit texts updated on ${createdAudit.key}")
    }

    private Issue getSingleSelectedQuestion(Issue mmIssue) {
        def raw = getFirstNonEmptyCustomFieldValueByName(mmIssue, "Questions")
        List<Issue> selected = asIssueList(raw)

        if (!selected || selected.isEmpty()) {
            return null
        }

        if (selected.size() > 1) {
            String comment = "More than one Question was selected. Only the first Question '${selected.first().key}' was used. The remaining Questions were ignored."

            ComponentAccessor.commentManager.create(
                    mmIssue,
                    actor(),
                    comment,
                    false
            )
        }

        return selected.first()
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
                actor(),
                questionIssue.id,
                QUESTION_SET_NOK_TRANSITION_ID,
                params
        )

        if (!validation.valid) {
            throw new IllegalStateException("SET NOK validate failed: ${validation.errorCollection}")
        }

        def result = issueService.transition(actor(), validation)
        if (!result.valid) {
            throw new IllegalStateException("SET NOK transition failed: ${result.errorCollection}")
        }
    }

    private void copyFieldFromMmToTransition(Issue mmIssue, def params, String fieldName) {
        def cf = CustomFieldUtil.getCustomFieldByName(fieldName)
        if (!cf) {
            logger.setWarnMessage("Transition field not found: ${fieldName}")
            return
        }

        def value = getFirstNonEmptyCustomFieldValueByName(mmIssue, fieldName)
        if (!hasValue(value)) {
            return
        }

        if (fieldName == Question.PERSON_RESPONSIBILITY_FIELD_NAME) {
            if (value instanceof Collection) {
                String[] usernames = value.collect { toUsername(it) }.findAll { it } as String[]
                if (usernames && usernames.length > 0) {
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

        if (value instanceof Collection) {
            String[] arr = value.collect { stringifyForParams(it) }.findAll { it } as String[]
            if (arr && arr.length > 0) {
                params.addCustomFieldValue(cf.id, arr)
            }
        } else {
            String str = stringifyForParams(value)
            if (str) {
                params.addCustomFieldValue(cf.id, str)
            }
        }
    }

    private String toUsername(def value) {
        if (value == null) {
            return null
        }

        if (value instanceof ApplicationUser) {
            return value.username ?: value.name
        }

        if (value.hasProperty('key') && value.key) {
            def byKey = ComponentAccessor.userManager.getUserByKey(value.key as String)
            if (byKey) {
                return byKey.username ?: byKey.name
            }
        }

        if (value.hasProperty('name') && value.name) {
            def byName = ComponentAccessor.userManager.getUserByName(value.name as String)
            if (byName) {
                return byName.username ?: byName.name
            }
        }

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

    private String stringifyForParams(def value) {
        if (value == null) {
            return null
        }

        if (value instanceof Issue) {
            return value.key
        }

        if (value instanceof ApplicationUser) {
            return value.username ?: value.name
        }

        if (value.hasProperty('key') && value.key) {
            return value.key as String
        }

        if (value.hasProperty('name') && value.name) {
            return value.name as String
        }

        String s = value.toString()
        return s?.trim() ? s.trim() : null
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
                actor()
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
            """.trim()

        mutable.setDescription(oldDesc + "\n\n" + appendix)
        issueManager.updateIssue(actor(), mutable, EventDispatchOption.DO_NOT_DISPATCH, false)
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

    private ApplicationUser actor() {
        return loggedInUser ?: runAs
    }
}