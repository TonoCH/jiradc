import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.customfields.manager.OptionsManager
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.user.util.UserManager
import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.bc.EntityPropertyService
import com.atlassian.jira.bc.issue.properties.IssuePropertyService
import com.atlassian.jira.web.bean.PagerFilter
import org.apache.log4j.Logger

abstract class ProjectInformationHandler {

    static enum Type { FUEL, OTHER }

    protected static final Logger log = Logger.getLogger(ProjectInformationHandler)

    protected static final Long PROJECT_INFORMATION_FIELD_ID = 18702L
    protected static final Long EPIC_LINK_FIELD_ID = 10001L
    protected static final Long PARENT_LINK_FIELD_ID = 10301L
    protected static final String SERVICE_USER_NAME = 'jira.bot'
    protected static final String MANUAL_OVERRIDE_KEY = 'fuel.projectInformation.manualOverride'

    protected final Type type
    protected final CustomFieldManager customFieldManager
    protected final IssueManager issueManager
    protected final OptionsManager optionsManager
    protected final SearchService searchService
    protected final IssuePropertyService issuePropertyService
    protected final ApplicationUser serviceUser

    protected final CustomField projectInformationField
    protected final CustomField epicLinkField
    protected final CustomField parentLinkField

    protected Set<Long> processedIssueIds = new HashSet<>()

    protected ProjectInformationHandler(Type type) {
        this.type = type
        customFieldManager = ComponentAccessor.customFieldManager
        issueManager = ComponentAccessor.issueManager
        optionsManager = ComponentAccessor.getComponent(OptionsManager)
        searchService = ComponentAccessor.getComponent(SearchService)
        issuePropertyService = ComponentAccessor.getComponent(IssuePropertyService)

        UserManager userManager = ComponentAccessor.userManager
        serviceUser = userManager.getUserByName(SERVICE_USER_NAME)

        projectInformationField = customFieldManager.getCustomFieldObject(PROJECT_INFORMATION_FIELD_ID)
        epicLinkField = customFieldManager.getCustomFieldObject(EPIC_LINK_FIELD_ID)
        parentLinkField = customFieldManager.getCustomFieldObject(PARENT_LINK_FIELD_ID)
    }

    static ProjectInformationHandler create(Type type) {
        type == Type.FUEL ? new FuelProjectInformationHandler() : new OtherProjectInformationHandler()
    }

    // --- Public entry points -------------------------------------------------

    void handleCreate(Issue issue) {
        if (!isReady() || !fieldApplies(issue)) return
        resetState()
        Issue parent = findParentIssue(issue)
        if (parent) applyToChild(parent, toMutable(issue))
    }

    void handleUpdate(IssueEvent event) {
        if (!isReady()) return
        MutableIssue issue = toMutable(event.issue)
        if (!fieldApplies(issue)) return
        resetState()

        Set<String> changedFields = changedFieldNames(event)
        if (linkFieldChanged(changedFields)) {
            reinheritFromParent(issue)
            return
        }
        if (changedFields.contains(projectInformationField.name)) {
            handleValueChange(issue)
        }
    }

    void handleDelete(Issue issue) {
        if (!isReady() || !fieldApplies(issue)) return
        resetState()
        collectDescendants(issue).each { clearValue(it) }
    }

    // --- Distribution flow ---------------------------------------------------

    protected void handleValueChange(MutableIssue issue) {
        if (isTopIssue(issue)) {
            distributeDown(issue)
            return
        }
        onNonTopValueChange(issue)
        distributeDown(issue)
    }

    protected void reinheritFromParent(MutableIssue issue) {
        Issue parent = findParentIssue(issue)
        if (parent) applyToChild(parent, issue)
        distributeDown(issue)
    }

    protected void distributeDown(Issue parent) {
        findChildren(parent).each { MutableIssue child ->
            if (!processedIssueIds.add(child.id)) return
            applyToChild(parent, child)
            distributeDown(child)
        }
    }

    // Subclasses decide how a child inherits and what a non-top user edit means.
    protected abstract void applyToChild(Issue parent, MutableIssue child)
    protected abstract void onNonTopValueChange(MutableIssue issue)

    // --- Hierarchy traversal -------------------------------------------------

    protected Issue findParentIssue(Issue issue) {
        if (issue.isSubTask()) return issue.parentObject
        Issue epic = readIssueReference(issue, epicLinkField)
        if (epic) return epic
        return readIssueReference(issue, parentLinkField)
    }

    protected boolean isTopIssue(Issue issue) {
        findParentIssue(issue) == null
    }

    protected Issue readIssueReference(Issue issue, CustomField field) {
        if (field == null) return null
        def value = issue.getCustomFieldValue(field)
        if (value == null) return null
        if (value instanceof Issue) return value
        return issueManager.getIssueByCurrentKey(value.toString())
    }

    protected Collection<MutableIssue> findChildren(Issue parent) {
        Map<Long, MutableIssue> children = [:]

        parent.subTaskObjects.each { children[it.id] = toMutable(it) }

        String jql = "(cf[${EPIC_LINK_FIELD_ID}] = \"${parent.key}\" OR cf[${PARENT_LINK_FIELD_ID}] = \"${parent.key}\")"
        def parseResult = searchService.parseQuery(serviceUser, jql)
        if (parseResult.valid) {
            def results = searchService.search(serviceUser, parseResult.query, PagerFilter.unlimitedFilter)
            results.results.each { found ->
                if (found.id != parent.id) children[found.id] = toMutable(found)
            }
        }
        children.values()
    }

    protected Collection<MutableIssue> collectDescendants(Issue root) {
        List<MutableIssue> descendants = []
        Deque<Issue> queue = new ArrayDeque<>(findChildren(root))
        while (!queue.isEmpty()) {
            MutableIssue current = toMutable(queue.poll())
            if (!processedIssueIds.add(current.id)) continue
            descendants << current
            queue.addAll(findChildren(current))
        }
        descendants
    }

    // --- Field IO ------------------------------------------------------------

    protected boolean fieldApplies(Issue issue) {
        projectInformationField != null && projectInformationField.getRelevantConfig(issue) != null
    }

    protected Collection<String> currentValues(Issue issue) {
        def value = issue.getCustomFieldValue(projectInformationField)
        if (value == null) return []
        value.collect { it instanceof Option ? it.value : it.toString() }
    }

    protected void applyValues(MutableIssue target, Collection<String> values) {
        List<Option> resolved = resolveOptions(target, values)
        if (values && !resolved) return

        Set<String> desired = (values ?: []) as Set
        Set<String> existing = currentValues(target) as Set
        if (desired == existing) return

        target.setCustomFieldValue(projectInformationField, resolved ?: null)
        issueManager.updateIssue(serviceUser, target, EventDispatchOption.DO_NOT_DISPATCH, false)
    }

    protected void clearValue(MutableIssue issue) {
        applyValues(issue, [])
    }

    protected List<Option> resolveOptions(Issue issue, Collection<String> values) {
        if (!values) return null
        def config = projectInformationField.getRelevantConfig(issue)
        if (config == null) return null
        optionsManager.getOptions(config).findAll { values.contains(it.value) }
    }

    // --- Manual override flag (entity property) ------------------------------

    protected boolean isManualOverride(Issue issue) {
        def result = issuePropertyService.getProperty(serviceUser, issue.id, MANUAL_OVERRIDE_KEY)
        if (!result.valid || !result.entityProperty.isDefined()) return false
        result.entityProperty.get().value.contains('true')
    }

    protected void markManualOverride(Issue issue) {
        def input = new EntityPropertyService.PropertyInput('{"override":true}', MANUAL_OVERRIDE_KEY)
        def validation = issuePropertyService.validateSetProperty(serviceUser, issue.id, input)
        if (validation.valid) issuePropertyService.setProperty(serviceUser, validation)
    }

    // --- Helpers -------------------------------------------------------------

    protected Set<String> changedFieldNames(IssueEvent event) {
        Set<String> names = new HashSet<>()
        def changeLog = event.changeLog
        changeLog?.getRelated('ChildChangeItem')?.each { item ->
            String field = item.getString('field')
            if (field) names << field
        }
        names
    }

    protected boolean linkFieldChanged(Set<String> changedFields) {
        (epicLinkField && changedFields.contains(epicLinkField.name)) ||
        (parentLinkField && changedFields.contains(parentLinkField.name))
    }

    protected MutableIssue toMutable(Issue issue) {
        issue instanceof MutableIssue ? issue : issueManager.getIssueObject(issue.id)
    }

    protected boolean isReady() {
        if (serviceUser == null) {
            log.error("ProjectInformation: service user '${SERVICE_USER_NAME}' not found")
            return false
        }
        if (projectInformationField == null) {
            log.error("ProjectInformation: field ${PROJECT_INFORMATION_FIELD_ID} not found")
            return false
        }
        true
    }

    protected void resetState() {
        processedIssueIds = new HashSet<>()
    }
}
