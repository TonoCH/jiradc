package listeners.fuel_area.worklogs_reporting

// Components + Prio Value Temp Merge Listener
// Source types: "LPM Initiative", "Initiative"

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.bc.project.component.ProjectComponent
import com.atlassian.jira.bc.project.component.ProjectComponentManager
import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.web.bean.PagerFilter
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.issue.link.IssueLinkManager
import org.apache.log4j.Logger
import org.apache.log4j.Level

Logger mylog = Logger.getLogger("scriptrunner.Listener.ComponentsMergeFromInitiatives")
mylog.setLevel(Level.INFO)

/* ===== config ===== */
final Set<String> SOURCE_TYPES = ["LPM Initiative", "Initiative"] as Set
final int MAX_UPWARD_HOPS = 12
final boolean AUTO_CREATE_MISSING_COMPONENTS = false
final boolean LIMIT_SOURCES_TO_CHILD_PROJECT = true

/* ===== guards ===== */
if (!(event instanceof IssueEvent)) return
def ev = (IssueEvent) event
MutableIssue issue = ev.issue as MutableIssue
if (!issue) return

try {
    ApplicationUser runUser = ComponentAccessor.jiraAuthenticationContext?.loggedInUser
    if (!runUser) runUser = ComponentAccessor.userManager.getUserByName("admin")
    if (!runUser) { mylog.info("No run user. Abort."); return }

    def issueManager  = ComponentAccessor.issueManager
    def indexing      = ComponentAccessor.getComponent(IssueIndexingService)
    def searchService = ComponentAccessor.getComponent(SearchService)
    def cfm           = ComponentAccessor.getCustomFieldManager()
    ProjectComponentManager pcm = ComponentAccessor.getProjectComponentManager()
    IssueLinkManager ilm = ComponentAccessor.getIssueLinkManager()

    CustomField cfParentLink = cfm.getCustomFieldObjectByName("Parent Link")
    CustomField cfEpicLink   = cfm.getCustomFieldObjectByName("Epic Link")
    // NEW: Prio Value Temp number field
    CustomField cfPrioTemp   = cfm.getCustomFieldObject("customfield_17627")

    mylog.info("----START ComponentsMerge ${issue.key} <${issue.summary}> type=${issue.issueType?.name} cfParentLinkId=${cfParentLink?.idAsLong} cfEpicLinkId=${cfEpicLink?.idAsLong} cfPrioTempId=${cfPrioTemp?.idAsLong}")

    // ---------------- helpers ----------------
    def compNames       = { Issue i -> ((i?.getComponentObjects() ?: [])*.name as List<String>).toSet() }
    def currentCompIds  = { Issue i -> ((i?.getComponentObjects() ?: [])*.id as List<Long>).toSet() }
    def allProjectComps = { Long pid ->
        List<ProjectComponent> all = []
        try { 
            all = pcm.findAllForProject(pid) ?: [] } 
            catch (Throwable t) { 
                try { all = pcm.getComponents(pid) ?: [] } 
            catch (Throwable ignore) {} 
        }
        
        all
    }
    def mapNamesToIdsForProject = { Set<String> names, Long pid ->
        if (!names || !pid) return [] as Set<Long>
        def all = allProjectComps(pid)
        all.findAll { it && names.contains(it.name) }*.id as Set<Long>
    }
    def ensureComponent = { def proj, String name ->
        if (!AUTO_CREATE_MISSING_COMPONENTS || !proj || !name) return null
        def all = allProjectComps(proj.id)
        def existing = all.find { it?.name == name }
        if (existing) return existing
        try { return pcm.create(name, null, null, null, proj.id) }
        catch (Throwable t) { mylog.info("[COMP] create failed '${name}' in ${proj?.key}: ${t.message}"); return null }
    }

    // --- UPDATE HELPERS (keep structure; separate updates for components and prio) ---
    def mergeComponentsOn = { MutableIssue tgt, Set<String> addFromNames ->
        if (!addFromNames || addFromNames.isEmpty() || !tgt) return false
        def proj = tgt.projectObject; if (!proj) return false

        if (AUTO_CREATE_MISSING_COMPONENTS) {
            def existingNames = (allProjectComps(proj.id) ?: [])*.name as Set
            (addFromNames - existingNames).each { nm -> ensureComponent(proj, nm) }
        }

        Set<Long> addIds = mapNamesToIdsForProject(addFromNames, proj.id)
        if (!addIds || addIds.isEmpty()) {
            def existingNames = (allProjectComps(proj.id) ?: [])*.name as Set
            def skipped = addFromNames - existingNames
            if (skipped) mylog.info("[COMP] ${tgt.key} skipping names not in ${proj.key}: ${skipped}")
            return false
        }

        Set<Long> current = currentCompIds(tgt)
        Set<Long> merged  = (current + addIds) as Set<Long>
        if (merged == current) return false

        def targetComponents = (allProjectComps(proj.id) ?: []).findAll { it?.id in merged } as Collection<ProjectComponent>
        tgt.setComponent(targetComponents)
        issueManager.updateIssue(runUser, tgt, EventDispatchOption.DO_NOT_DISPATCH, false)
        try { indexing?.reIndex(tgt) } catch (Throwable t) { mylog.info("[INDEX] reindex failed ${tgt.key}: ${t.message}") }

        mylog.info("[UPDATE] merged components on ${tgt.key}: was=${current} now=${merged}")
        return true
    }

    def setPrioTempOn = { MutableIssue tgt, Number srcVal ->
        if (!cfPrioTemp || srcVal == null || tgt == null) return false
        def old = tgt.getCustomFieldValue(cfPrioTemp)
        Double oldNum = (old instanceof Number) ? ((Number)old).doubleValue() :
                        (old instanceof String) ? (old as String).isNumber() ? (old as String).toDouble() : null : null
        Double newNum = (srcVal as Number).doubleValue()
        if (oldNum != null && Math.abs(oldNum - newNum) < 1e-9) return false

        tgt.setCustomFieldValue(cfPrioTemp, newNum)
        issueManager.updateIssue(runUser, tgt, EventDispatchOption.DO_NOT_DISPATCH, false)
        try { indexing?.reIndex(tgt) } catch (Throwable t) { mylog.info("[INDEX] reindex failed ${tgt.key}: ${t.message}") }

        mylog.info("[UPDATE] set Prio Value Temp on ${tgt.key}: was=${oldNum} now=${newNum}")
        return true
    }

    def jqlSearch = { String jql ->
        try {
            def pr = searchService.parseQuery(runUser, jql)
            if (!pr.isValid()) { mylog.info("[JQL] invalid: ${jql} -> ${pr.errors}"); return [] }
            def res = searchService.search(runUser, pr.query, PagerFilter.getUnlimitedFilter())
            return res?.results ?: []
        } catch (Throwable t) { mylog.info("[JQL] error '${jql}': ${t.message}"); return [] }
    }

    /* --- DOWN: children helpers --- */
    def epicChildren = { Issue epic ->
        if (cfEpicLink) return jqlSearch("cf[${cfEpicLink.idAsLong}] = ${epic.key}")
        return jqlSearch("parent = ${epic.key}")
    }
    def parentLinkChildren = { Issue parent ->
        if (!cfParentLink) return []
        jqlSearch("cf[${cfParentLink.idAsLong}] = ${parent.key}")
    }
    def subTasksOf = { Issue parent ->
        jqlSearch("issuetype in subTaskIssueTypes() AND parent = ${parent.key}")
    }

    /* --- direct neighbors (both directions) --- */
    def directNeighbors = { Issue node ->
        def nbrs = new LinkedHashSet<Issue>()
        if (!node) return nbrs

        // Up: Parent Link parent
        if (cfParentLink) {
            try {
                def p = node.getCustomFieldValue(cfParentLink)
                if (p instanceof Issue) nbrs.add(p as Issue)
            } catch (ignored) {}
        }
        // Up: Epic (Epic Link CF or “Epic-Story Link”)
        def getEpicForAny = { Issue any ->
            if (cfEpicLink) {
                try {
                    def ep = any.getCustomFieldValue(cfEpicLink)
                    if (ep instanceof Issue && (ep as Issue).issueType?.name == "Epic") return (Issue) ep
                } catch (ignored) {}
            }
            try {
                def inLinks  = ilm.getInwardLinks(any.id) ?: []
                def outLinks = ilm.getOutwardLinks(any.id) ?: []
                def linkIn   = inLinks.find  { it.issueLinkType?.name == "Epic-Story Link" }
                if (linkIn)  return linkIn.getSourceObject()
                def linkOut  = outLinks.find { it.issueLinkType?.name == "Epic-Story Link" }
                if (linkOut) return linkOut.getDestinationObject()
            } catch (ignored) {}
            if (any.issueType?.isSubTask()) {
                def p = any.parentObject
                if (p) {
                    if (cfEpicLink) {
                        try {
                            def ep2 = p.getCustomFieldValue(cfEpicLink)
                            if (ep2 instanceof Issue && (ep2 as Issue).issueType?.name == "Epic") return (Issue) ep2
                        } catch (ignored) {}
                    }
                    try {
                        def inLinksP  = ilm.getInwardLinks(p.id) ?: []
                        def outLinksP = ilm.getOutwardLinks(p.id) ?: []
                        def linkInP   = inLinksP.find  { it.issueLinkType?.name == "Epic-Story Link" }
                        if (linkInP)  return linkInP.getSourceObject()
                        def linkOutP  = outLinksP.find { it.issueLinkType?.name == "Epic-Story Link" }
                        if (linkOutP) return linkOutP.getDestinationObject()
                    } catch (ignored) {}
                }
            }
            return null
        }
        def ep = getEpicForAny(node)
        if (ep) nbrs.add(ep)

        // Up: parent of sub-task
        if (node.issueType?.isSubTask() && node.parentObject) nbrs.add(node.parentObject)

        // Down: Parent Link children
        nbrs.addAll(parentLinkChildren(node))
        // Down: Epic children
        if (node.issueType?.name == "Epic") nbrs.addAll(epicChildren(node))
        // Down: subtasks
        nbrs.addAll(subTasksOf(node))

        return nbrs
    }

    /* --- BFS across the connected hierarchy --- */
    def traverseConnected = { Issue root ->
        def visited = new HashSet<Long>()
        def out = new LinkedHashSet<Issue>()
        def q = new ArrayDeque<Issue>()
        visited.add(root.id); q.add(root)
        while (!q.isEmpty()) {
            def cur = q.removeFirst()
            directNeighbors(cur).each { Issue nb ->
                if (nb && !visited.contains(nb.id)) { visited.add(nb.id); out.add(nb); q.addLast(nb) }
            }
        }
        out
    }

    /* --- fallback scan: find the source that contains the leaf --- */
    def findSourceByScanning = { Issue leaf ->
        String projectFilter = LIMIT_SOURCES_TO_CHILD_PROJECT && leaf.projectObject ? " AND project = ${leaf.projectObject.key}" : ""
        def candidates = jqlSearch("issueType in (\"LPM Initiative\",\"Initiative\")${projectFilter}")
        if (!candidates) return null
        for (Issue src : candidates) {
            def traversed = traverseConnected(src)
            if (traversed.any { it.id == leaf.id }) return src
        }
        return null
    }

    /* --- climb up to nearest source or fallback --- */
    def climbToNearestSource = { Issue start ->
        def cur = start
        int hops = 0
        while (cur && hops < MAX_UPWARD_HOPS) {
            if (cur.issueType?.name in SOURCE_TYPES) return cur

            // try Epic
            def ep = directNeighbors(cur).find { it.issueType?.name == "Epic" }
            if (ep) { cur = ep; hops++; continue }

            // try Parent Link parent
            if (cfParentLink) {
                try {
                    def p = cur.getCustomFieldValue(cfParentLink)
                    if (p instanceof Issue) { cur = (Issue) p; hops++; continue }
                } catch (ignored) {}
            }

            // sub-task parent
            if (cur.issueType?.isSubTask() && cur.parentObject) { cur = cur.parentObject; hops++; continue }

            break
        }
        return findSourceByScanning(start)
    }

    // ---------------- actions ----------------
    def propagateFromSourceTransversal = { Issue source ->
        Set<String> srcNames = compNames(source)
        // NEW: read Prio Value Temp (Number) from the source
        Number srcPrio = null
        if (cfPrioTemp) {
            def v = source.getCustomFieldValue(cfPrioTemp)
            if (v instanceof Number) srcPrio = (Number)v
            else if (v instanceof String && (v as String).isNumber()) srcPrio = (v as String).toBigDecimal()
        }

        if (!srcNames && srcPrio == null) { mylog.info("[SRC] ${source.key} nothing to propagate (no components & no prio)"); return }

        def all = traverseConnected(source)
        mylog.info("[SRC] ${source.key} connectedCount=${all.size()} names=${srcNames} prio=${srcPrio}")

        int changed = 0
        all.each { Issue d ->
            if (d.id == source.id) return
            if (srcNames)  changed += mergeComponentsOn(issueManager.getIssueObject(d.id) as MutableIssue, srcNames) ? 1 : 0
            if (srcPrio != null) changed += setPrioTempOn(issueManager.getIssueObject(d.id) as MutableIssue, srcPrio) ? 1 : 0
        }
        mylog.info("[SRC] ${source.key} mergesApplied=${changed}")
    }

    def ensureChildHasSourceComponentsTransversal = { Issue child ->
        def src = climbToNearestSource(child)
        mylog.info("[CHILD] ${child.key} nearestSource=${src?.key ?: 'none'}")
        if (!src) return
        propagateFromSourceTransversal(src)
    }

    // ---------------- router ----------------
    switch (issue.issueType?.name) {
        case { it in SOURCE_TYPES }:
            propagateFromSourceTransversal(issue)
            break
        default:
            ensureChildHasSourceComponentsTransversal(issue)
    }

} catch (Exception ex) {
    mylog.info("ComponentsMerge Exception: ${ex.class.simpleName}: ${ex.message}")
} finally {
    mylog.info("----ComponentsMerge DONE")
}
