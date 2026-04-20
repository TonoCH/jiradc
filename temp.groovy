/*
 * ScriptRunner Scheduled Job (Jira Server/DC)
 * - Reads JQL from a Saved Filter
 * - Builds hierarchy: Sub-task -> Task/Story -> Epic -> Initiative
 * - Aggregates timetracking + Epic Sum + worklogs by author
 * - Exports to CSV and emails it as attachment
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.bc.filter.SearchRequestService
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.worklog.WorklogManager
import com.atlassian.jira.jql.parser.JqlQueryParser
import com.atlassian.jira.web.bean.PagerFilter
import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.issue.IssueManager

import com.atlassian.mail.server.SMTPMailServer
import groovy.transform.Field

import javax.mail.Message
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.activation.DataHandler
import javax.mail.util.ByteArrayDataSource

// -------------------- CONFIG --------------------
final Long SAVED_FILTER_ID = 30951                         // TODO
final String RECIPIENTS = "reporting@firma.sk"              // TODO (comma-separated OK)
final String SUBJECT = "Jira export (Initiative/Epic/Task/Subtask)"

// Custom field names (adjust if needed)
final String EPIC_LINK_CF_NAME = "Epic Link"                // Jira Software
final String PARENT_LINK_CF_NAME = "Parent Link"            // Advanced Roadmaps
final String EPIC_SUM_CF_NAME = "Epic Sum"                  // often exists in JSW; if not, script continues

// If you want only these initiative/epic/task types, you can filter later; default is "whatever JQL returns + expanded"
final int JQL_IN_CHUNK = 50                                 // chunk size for IN (...) JQL queries

// Who runs searches: needs permissions to see all relevant issues
final ApplicationUser RUN_AS = ComponentAccessor.jiraAuthenticationContext.loggedInUser
// ------------------------------------------------

// -------------------- DATA CLASSES --------------------
@groovy.transform.ToString(includeNames = true)
class MySpecialSubtask {
    Issue issue
    Map<String, Object> fields = [:]
    Map<String, Double> worklogHoursByUser = [:]
    Long originalEstimateSec = 0L
    Long remainingEstimateSec = 0L
    Long timeSpentSec = 0L
    MySpecialTask parent
}

@groovy.transform.ToString(includeNames = true)
class MySpecialTask {
    Issue issue
    Map<String, Object> fields = [:]
    Map<String, Double> worklogHoursByUser = [:]
    Long originalEstimateSec = 0L
    Long remainingEstimateSec = 0L
    Long timeSpentSec = 0L
    Set<MySpecialSubtask> subtasks = new LinkedHashSet<>()
    MySpecialEpic parent
}

@groovy.transform.ToString(includeNames = true)
class MySpecialEpic {
    Issue issue
    Map<String, Object> fields = [:]
    Map<String, Double> worklogHoursByUser = [:]
    Long originalEstimateSec = 0L
    Long remainingEstimateSec = 0L
    Long timeSpentSec = 0L
    BigDecimal epicSum = null
    Set<MySpecialTask> tasks = new LinkedHashSet<>()
    MySpecialInitiative parent
}

@groovy.transform.ToString(includeNames = true)
class MySpecialInitiative {
    Issue issue
    Map<String, Object> fields = [:]
    Map<String, Double> worklogHoursByUser = [:]
    Long originalEstimateSec = 0L
    Long remainingEstimateSec = 0L
    Long timeSpentSec = 0L
    Set<MySpecialEpic> epics = new LinkedHashSet<>()
}
// ------------------------------------------------------

// -------------------- HELPERS --------------------
def cfMgr = ComponentAccessor.customFieldManager
def issueMgr = ComponentAccessor.issueManager as IssueManager
def worklogMgr = ComponentAccessor.worklogManager as WorklogManager
def jqlParser = ComponentAccessor.getComponent(JqlQueryParser)
@Field searchService = ComponentAccessor.getComponent(SearchService)
def searchRequestService = ComponentAccessor.getComponent(SearchRequestService)

def getCF(String name) {
    cfMgr.getCustomFieldObjectsByName(name)?.find { it.name == name }
}

def safeLong(def v) { (v instanceof Number) ? ((Number)v).longValue() : 0L }

def issueTimeTracking(Issue i) {
    def tt = i.timeTracking
    [
            originalEstimateSec: safeLong(tt?.originalEstimate),
            remainingEstimateSec: safeLong(tt?.remainingEstimate),
            timeSpentSec: safeLong(tt?.timeSpent)
    ]
}

def worklogHoursByUser(Issue i) {
    def map = new LinkedHashMap<String, Double>()
    worklogMgr.getByIssue(i).each { wl ->
        def userKey = wl.authorObject?.key ?: wl.authorObject?.name ?: "unknown"
        double hours = (wl.timeSpent ?: 0) / 3600.0d
        map[userKey] = (map[userKey] ?: 0.0d) + hours
    }
    map
}

def baseFields(Issue i) {
    // Sem si doplň vlastné polia podľa potreby (priority, components, labels, CFs...)
    [
            key       : i.key,
            summary   : i.summary,
            type      : i.issueType?.name,
            status    : i.status?.name,
            project   : i.projectObject?.key,
            assignee  : i.assignee?.displayName,
            reporter  : i.reporter?.displayName,
            created   : i.created?.toString(),
            updated   : i.updated?.toString()
    ]
}

def searchIssuesByJql(String jql) {
    def parse = searchService.parseQuery(RUN_AS, jql)

    if (!parse.isValid()) {
        // vypíš, čo presne neprešlo
        def errs = parse.errors?.errorMessages
        def jqlDebug = jql
                .replace("\u00A0", "[NBSP]")   // non-breaking space
                .replace("\u201C", "\"").replace("\u201D", "\"") // smart quotes
                .replace("\u2018", "'").replace("\u2019", "'")   // smart apostrophes

        throw new IllegalStateException(
                "Invalid JQL from filter.\n" +
                        "Errors: ${errs}\n" +
                        "JQL: ${jqlDebug}"
        )
    }

    searchService.search(RUN_AS, parse.query, PagerFilter.getUnlimitedFilter())

    return res.issues.collect { it as Issue }
}

def chunked(List<String> keys, int size) {
    def out = []
    for (int i = 0; i < keys.size(); i += size) out << keys.subList(i, Math.min(i + size, keys.size()))
    out
}

def csvEscape(String s) {
    if (s == null) return ""
    def needs = (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r"))
    def v = s.replace("\"", "\"\"")
    needs ? "\"${v}\"" : v
}

def secToHours(Long sec) {
    if (!sec) return 0.0d
    return sec / 3600.0d
}

def mapToCompactString(Map<String, Double> m) {
    // userKey=12.5; user2=3.0
    m.collect { k, v -> "${k}=${String.format(java.util.Locale.US, '%.2f', v)}" }.join("; ")
}
// ------------------------------------------------------

// -------------------- 1) LOAD SAVED FILTER JQL --------------------
def sr = searchRequestService.getSearchRequestById(RUN_AS, SAVED_FILTER_ID)
if (!sr) {
    throw new IllegalStateException("Saved filter ID ${SAVED_FILTER_ID} not found or no permission.")
}
def savedJql = sr.query?.toString()
if (!savedJql) {
    throw new IllegalStateException("Saved filter ${SAVED_FILTER_ID} has empty JQL.")
}

// -------------------- 2) INITIAL SEARCH --------------------
def seedIssues = searchIssuesByJql(savedJql)
def allIssues = new LinkedHashMap<String, Issue>()  // key -> Issue
seedIssues.each { allIssues[it.key] = it }

// Custom fields
def epicLinkCF = getCF(EPIC_LINK_CF_NAME)
def parentLinkCF = getCF(PARENT_LINK_CF_NAME)
def epicSumCF = getCF(EPIC_SUM_CF_NAME) // can be null; OK

// -------------------- 3) EXPAND UPWARDS (parents) --------------------
def addIssue(Issue i) {
    if (i && !allIssues.containsKey(i.key)) allIssues[i.key] = i
}

seedIssues.each { Issue i ->
    // Sub-task -> parent
    if (i.isSubTask()) {
        addIssue(i.parentObject)
    } else {
        // Task/Story -> Epic via Epic Link
        if (epicLinkCF) {
            def epicKey = i.getCustomFieldValue(epicLinkCF)?.toString()
            if (epicKey) addIssue(issueMgr.getIssueByCurrentKey(epicKey))
        }
    }

    // Epic -> Initiative via Parent Link
    if (parentLinkCF && i.issueType?.name?.equalsIgnoreCase("Epic")) {
        def initKey = i.getCustomFieldValue(parentLinkCF)?.toString()
        if (initKey) addIssue(issueMgr.getIssueByCurrentKey(initKey))
    }
}

// -------------------- 4) EXPAND DOWNWARDS (children) in BULK --------------------
// 4a) For each parent Task, include its subtasks (cheap, no JQL)
allIssues.values().findAll { !it.isSubTask() }.each { Issue parent ->
    parent.subTaskObjects?.each { st -> addIssue(st) }
}

// Collect epics and initiatives keys for bulk JQL
def epicKeys = allIssues.values().findAll { it.issueType?.name?.equalsIgnoreCase("Epic") }*.key.unique()
def initKeys = allIssues.values().findAll { it.issueType?.name?.equalsIgnoreCase("Initiative") }*.key.unique()

// 4b) Epic -> tasks (JQL: "Epic Link" in (...))
if (epicLinkCF && epicKeys) {
    chunked(epicKeys, JQL_IN_CHUNK).each { chunk ->
        def jql = "\"${EPIC_LINK_CF_NAME}\" in (${chunk.collect { "\"${it}\"" }.join(",")})"
        searchIssuesByJql(jql).each { addIssue(it) }
    }
}

// 4c) Initiative -> epics (JQL: "Parent Link" in (...) AND issuetype = Epic)
if (parentLinkCF && initKeys) {
    chunked(initKeys, JQL_IN_CHUNK).each { chunk ->
        def jql = "\"${PARENT_LINK_CF_NAME}\" in (${chunk.collect { "\"${it}\"" }.join(",")}) AND issuetype = Epic"
        searchIssuesByJql(jql).each { addIssue(it) }
    }
}

// -------------------- 5) BUILD OBJECT GRAPH WITH CACHES --------------------
def initiatives = new LinkedHashSet<MySpecialInitiative>()

def initByKey = new LinkedHashMap<String, MySpecialInitiative>()
def epicByKey = new LinkedHashMap<String, MySpecialEpic>()
def taskByKey = new LinkedHashMap<String, MySpecialTask>()
def subByKey  = new LinkedHashMap<String, MySpecialSubtask>()

def getOrCreateInitiative = { Issue i ->
    initByKey.computeIfAbsent(i.key) {
        def t = issueTimeTracking(i)
        new MySpecialInitiative(
                issue: i,
                fields: baseFields(i),
                worklogHoursByUser: worklogHoursByUser(i),
                originalEstimateSec: t.originalEstimateSec,
                remainingEstimateSec: t.remainingEstimateSec,
                timeSpentSec: t.timeSpentSec
        )
    }
}

def getOrCreateEpic = { Issue i ->
    epicByKey.computeIfAbsent(i.key) {
        def t = issueTimeTracking(i)
        BigDecimal sumVal = null
        if (epicSumCF) {
            def v = i.getCustomFieldValue(epicSumCF)
            if (v instanceof Number) sumVal = new BigDecimal(v.toString())
        }
        new MySpecialEpic(
                issue: i,
                fields: baseFields(i),
                worklogHoursByUser: worklogHoursByUser(i),
                originalEstimateSec: t.originalEstimateSec,
                remainingEstimateSec: t.remainingEstimateSec,
                timeSpentSec: t.timeSpentSec,
                epicSum: sumVal
        )
    }
}

def getOrCreateTask = { Issue i ->
    taskByKey.computeIfAbsent(i.key) {
        def t = issueTimeTracking(i)
        new MySpecialTask(
                issue: i,
                fields: baseFields(i),
                worklogHoursByUser: worklogHoursByUser(i),
                originalEstimateSec: t.originalEstimateSec,
                remainingEstimateSec: t.remainingEstimateSec,
                timeSpentSec: t.timeSpentSec
        )
    }
}

def getOrCreateSubtask = { Issue i ->
    subByKey.computeIfAbsent(i.key) {
        def t = issueTimeTracking(i)
        new MySpecialSubtask(
                issue: i,
                fields: baseFields(i),
                worklogHoursByUser: worklogHoursByUser(i),
                originalEstimateSec: t.originalEstimateSec,
                remainingEstimateSec: t.remainingEstimateSec,
                timeSpentSec: t.timeSpentSec
        )
    }
}

// -------------------- 6) WIRE RELATIONS --------------------
allIssues.values().each { Issue i ->
    if (i.issueType?.name?.equalsIgnoreCase("Initiative")) {
        initiatives << getOrCreateInitiative(i)
    }
}

allIssues.values().each { Issue i ->
    if (i.issueType?.name?.equalsIgnoreCase("Epic")) {
        def epic = getOrCreateEpic(i)

        // Parent initiative via Parent Link
        if (parentLinkCF) {
            def initKey = i.getCustomFieldValue(parentLinkCF)?.toString()
            if (initKey) {
                def initIssue = allIssues[initKey] ?: issueMgr.getIssueByCurrentKey(initKey)
                if (initIssue) {
                    def initObj = getOrCreateInitiative(initIssue)
                    epic.parent = initObj
                    initObj.epics << epic
                    initiatives << initObj
                }
            }
        } else {
            // If no Parent Link, keep epic unattached; you can still export it
        }
    }
}

allIssues.values().each { Issue i ->
    if (!i.isSubTask() && !i.issueType?.name?.equalsIgnoreCase("Epic") && !i.issueType?.name?.equalsIgnoreCase("Initiative")) {
        def task = getOrCreateTask(i)

        // Parent epic via Epic Link
        if (epicLinkCF) {
            def epicKey = i.getCustomFieldValue(epicLinkCF)?.toString()
            if (epicKey) {
                def epicIssue = allIssues[epicKey] ?: issueMgr.getIssueByCurrentKey(epicKey)
                if (epicIssue) {
                    def epicObj = getOrCreateEpic(epicIssue)
                    task.parent = epicObj
                    epicObj.tasks << task
                }
            }
        }

        // Attach subtasks
        i.subTaskObjects?.each { Issue st ->
            def sub = getOrCreateSubtask(st)
            sub.parent = task
            task.subtasks << sub
        }
    }
}

allIssues.values().findAll { it.isSubTask() }.each { Issue st ->
    def sub = getOrCreateSubtask(st)
    def parent = st.parentObject
    if (parent) {
        def task = getOrCreateTask(parent)
        sub.parent = task
        task.subtasks << sub
    }
}

// -------------------- 7) CSV EXPORT (one row per Sub-task, and Tasks without Sub-tasks too) --------------------
def headers = [
        "InitiativeKey","InitiativeSummary",
        "EpicKey","EpicSummary","EpicSum",
        "TaskKey","TaskSummary","TaskType",
        "SubtaskKey","SubtaskSummary","SubtaskType",
        "OrigEstH","RemEstH","SpentH",
        "WorklogsByUser" // compact string
]

def lines = new StringBuilder()
lines.append(headers.collect { csvEscape(it) }.join(",")).append("\n")

def emitRow = { MySpecialInitiative initObj, MySpecialEpic epicObj, MySpecialTask taskObj, MySpecialSubtask subObj ->
    Issue initI = initObj?.issue
    Issue epicI = epicObj?.issue
    Issue taskI = taskObj?.issue
    Issue subI  = subObj?.issue

    // pick the most specific timetracking level (subtask > task > epic > initiative)
    def oSec = (subObj?.originalEstimateSec ?: taskObj?.originalEstimateSec ?: epicObj?.originalEstimateSec ?: initObj?.originalEstimateSec ?: 0L)
    def rSec = (subObj?.remainingEstimateSec ?: taskObj?.remainingEstimateSec ?: epicObj?.remainingEstimateSec ?: initObj?.remainingEstimateSec ?: 0L)
    def sSec = (subObj?.timeSpentSec ?: taskObj?.timeSpentSec ?: epicObj?.timeSpentSec ?: initObj?.timeSpentSec ?: 0L)

    // worklogs (again most specific)
    def wl = (subObj?.worklogHoursByUser ?: taskObj?.worklogHoursByUser ?: epicObj?.worklogHoursByUser ?: initObj?.worklogHoursByUser ?: [:])

    def row = [
            initI?.key, initI?.summary,
            epicI?.key, epicI?.summary, epicObj?.epicSum?.toString(),
            taskI?.key, taskI?.summary, taskI?.issueType?.name,
            subI?.key,  subI?.summary,  subI?.issueType?.name,
            String.format(java.util.Locale.US, "%.2f", secToHours(oSec)),
            String.format(java.util.Locale.US, "%.2f", secToHours(rSec)),
            String.format(java.util.Locale.US, "%.2f", secToHours(sSec)),
            mapToCompactString(wl)
    ]
    lines.append(row.collect { csvEscape(it?.toString()) }.join(",")).append("\n")
}

// walk: initiatives -> epics -> tasks -> subtasks
initiatives.each { initObj ->
    initObj.epics.each { epicObj ->
        epicObj.tasks.each { taskObj ->
            if (taskObj.subtasks && !taskObj.subtasks.isEmpty()) {
                taskObj.subtasks.each { subObj ->
                    emitRow(initObj, epicObj, taskObj, subObj)
                }
            } else {
                emitRow(initObj, epicObj, taskObj, null)
            }
        }
    }
}

// Also export epics/tasks that are not attached (optional)
def unattachedEpics = epicByKey.values().findAll { it.parent == null }
unattachedEpics.each { epicObj ->
    epicObj.tasks.each { taskObj ->
        if (taskObj.subtasks) {
            taskObj.subtasks.each { subObj -> emitRow(null, epicObj, taskObj, subObj) }
        } else {
            emitRow(null, epicObj, taskObj, null)
        }
    }
}

// -------------------- 8) SEND EMAIL WITH CSV ATTACHMENT --------------------
def smtp = ComponentAccessor.mailServerManager.defaultSMTPMailServer as SMTPMailServer
if (!smtp) throw new IllegalStateException("No default SMTP server configured in Jira.")

byte[] csvBytes = lines.toString().getBytes("UTF-8")
def session = smtp.session
def msg = new MimeMessage(session)

msg.setFrom(new InternetAddress(smtp.defaultFrom))
RECIPIENTS.split(",").collect { it.trim() }.findAll { it }.each { r ->
    msg.addRecipient(Message.RecipientType.TO, new InternetAddress(r))
}
msg.setSubject(SUBJECT, "UTF-8")

def textPart = new MimeBodyPart()
textPart.setText(
        "Ahoj,\n\nv prílohe posielam export z Jira (filter ID ${SAVED_FILTER_ID}).\n",
        "UTF-8"
)

def attachPart = new MimeBodyPart()
attachPart.setDataHandler(new DataHandler(new ByteArrayDataSource(csvBytes, "text/csv; charset=UTF-8")))
attachPart.setFileName("jira_export_${new Date().format('yyyyMMdd_HHmm')}.csv")

def multipart = new MimeMultipart()
multipart.addBodyPart(textPart)
multipart.addBodyPart(attachPart)

msg.setContent(multipart)
Transport.send(msg)

// Log summary for ScriptRunner log
log.warn("Export done. Seed issues=${seedIssues.size()}, total expanded=${allIssues.size()}, initiatives=${initiatives.size()}, CSV bytes=${csvBytes.length}")
