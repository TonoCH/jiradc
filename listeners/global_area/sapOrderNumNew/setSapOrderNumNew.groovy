package listeners.global_area.sapOrderNumNew

/**
 * setSapOrderNumNew
 *
 * @author chabrecek.anton
 * Created on 23. 1. 2026. SAP-Order-No-New - 18500
 */

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager
import org.ofbiz.core.entity.GenericValue;
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.event.type.EventDispatchOption
import utils.MyBaseUtil
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.FieldException
import com.atlassian.jira.issue.link.IssueLink
import com.atlassian.jira.issue.link.IssueLinkManager
import com.atlassian.jira.user.ApplicationUser
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import groovy.transform.Field;
import groovy.json.JsonSlurper;


@Field CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager();
@Field CustomField cf_SapOrderNumberNew = customFieldManager.getCustomFieldObject("customfield_18500")
@Field CustomField cf_ProjectInformation = customFieldManager.getCustomFieldObject("customfield_15600"); // Project Information type Advanced Database Row Searcher
@Field CustomField cf_EpicLink = customFieldManager.getCustomFieldObject("customfield_10001"); // EPIC Link CF ID
@Field ApplicationUser jiraBot = ComponentAccessor.getUserManager().getUserByName("jira.bot");
@Field IssueManager issueManager = ComponentAccessor.getIssueManager();

IssueEvent issueEvent = event
GenericValue changeLog  = issueEvent.changeLog;
def items = changeLog?.getRelated("ChildChangeItem")

boolean prjInfChange = items?.find {
    it.field == cf_ProjectInformation.getFieldName()
}

if(prjInfChange){
    log.info("project information was changed:")
    def prjInformationJson = issue.getCustomFieldValue(cf_ProjectInformation)

    String projectInformationValue = getProjectInformationValue(prjInformationJson)
    if(projectInformationValue != MyClass.ERROR_MESSAGE){
        checkAndSetInHierarchy(issue, projectInformationValue)
    }
    else {
        log.error(MyClass.ERROR_MESSAGE);
        throw new FieldException(MyClass.ERROR_MESSAGE)
    }
}
else{
    log.info("project information was not change, so nothing set")
}

class MyClass {
    static def ERROR_MESSAGE = "Value not found or in unexpected format"
}

// if ok return poject information value, otherwise return error message
def String getProjectInformationValue(Object o) {
    try {
        def jsonSlurper = new JsonSlurper()
        def parsedJson = jsonSlurper.parseText(jsonString)
        def rows = parsedJson.rows

        if (rows && rows.size() > 0) {
            def row = rows[0]
            if (row && row.size() > 0) {
                def value = row[0]
                return value.toString()
            }
        }

    } catch (Exception e) {
        return MyClass.ERROR_MESSAGE
    }

    return MyClass.ERROR_MESSAGE
}

void checkAndSetInHierarchy(def issue, String projectInformationValue) {

    def prjInformation = getSAPNoFromProjectInformation(projectInformationValue);
    Set<String> visited = new HashSet<>()
    Deque<Issue> stack = new ArrayDeque<>()
    stack.push(issue)

    while (!stack.isEmpty()) {
        Issue current = stack.pop()
        if (!current) continue
        if (!visited.add(current.key)) continue

        setSapOrderNumberNew(current as MutableIssue, prjInformation)

        getAllChildrenIssues(current).each { child ->
            if (child && !visited.contains(child.key)) {
                stack.push(child)
            }
        }
    }
}

Set<Issue> getAllChildrenIssues(Issue parent) {
    Set<Issue> children = new LinkedHashSet<>()

    //subtask
    try {  parent.subTaskObjects?.each { Issue st -> children.add(st) }
    } catch (ignored) {}

    children.addAll(getEpicChildren(parent))
    children.addAll(getChildrenByParentLink(parent, "Parent Link"))
    children.addAll(getChildrenByParentLink(parent, "Parent"))
    children.addAll(getLinkedChildren(parent))

    return children
}

Set<Issue> getEpicChildren(Issue epic) {
    Set<Issue> out = new LinkedHashSet<>()
    MyBaseUtil util = new MyBaseUtil();
    out.addAll(util.findIssues("project = ${epic.projectObject.key} AND \"Epic Link\" = ${epic.key}"))

    if (out.isEmpty()) {
        out.addAll(util.findIssues("project = ${epic.projectObject.key} AND parentEpic = ${epic.key}"))
    }

    return out
}

List<Issue> getChildrenByParentLink(Issue parent, String fieldName) {
    return new MyBaseUtil().findIssues("project = ${parent.projectObject.key} AND \"${fieldName}\" = ${parent.key}")
}

Set<Issue> getLinkedChildren(Issue parent) {
    IssueLinkManager issueLinkManager = ComponentAccessor.getIssueLinkManager()
    Set<Issue> out = new LinkedHashSet<>()

    def outward = issueLinkManager.getOutwardLinks(parent.id) ?: []
    outward.each { IssueLink link ->
        def lt = link.issueLinkType
        def name = (lt?.name ?: "").toLowerCase()
        def outwardDesc = (lt?.outward ?: "").toLowerCase()

        boolean looksLikeParentToChild =
                name.contains("parent") ||
                        name.contains("hierarch") ||
                        name.contains("contain") ||
                        outwardDesc.contains("parent") ||
                        outwardDesc.contains("contain") ||
                        outwardDesc.contains("child")

        if (looksLikeParentToChild) {
            out.add(link.destinationObject)
        }
    }

    return out
}

private String getSAPNoFromProjectInformation(String projectInformation) {

    String[] splitted = projectInformation.split("\\[");
    if (splitted.length > 1) {
        return splitted[1].split("]")[0];
    }
}

void setSapOrderNumberNew(MutableIssue issue, String sapValue){

    //set only if diff
    def sapOrderNumActualValue = issue.getCustomFieldValue(cf_SapOrderNumberNew)
    if(sapOrderNumActualValue != sapValue) {
        issue.setCustomFieldValue(cf_SapOrderNumberNew, sapValue)
        ComponentAccessor.issueManager.updateIssue(jiraBot, issue, EventDispatchOption.ISSUE_UPDATED, false)
    }
}