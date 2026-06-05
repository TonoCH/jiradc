package rest

/**
 * kvsLevel1Data
 *
 * REST endpoint providing JSON data for KVS Level 1 Checklist.
 * Actions: profitCenters, functionalAreas, checklistData
 *
 * @author chabrecek.anton
 * Created on 18. 3. 2026.
 */

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.transform.BaseScript
import groovy.json.JsonOutput
import kvs_audits.common.JqlSearcher
import kvs_audits.issueType.Audit
import kvs_audits.issueType.Question

import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response

import com.atlassian.jira.issue.Issue
import utils.MyBaseUtil
import utils.CustomFieldUtil
import kvs_audits.common.CustomFieldsConstants

@BaseScript CustomEndpointDelegate delegate

kvsLevel1Data(httpMethod: "GET", groups: ["jira-administrators", "kvs-audit-admins"]) { MultivaluedMap queryParams ->

    def myBaseUtil = new MyBaseUtil()
    def customFieldUtil = new CustomFieldUtil()
    def action = queryParams.getFirst("action") ?: ""

    def result
    try {
        switch (action) {

            case "profitCenters":
                String jql = """
                    project = ${CustomFieldsConstants.PROJECT_KVS_PROFIT_CENTERS}
                    AND issuetype = "Profit Center" AND resolution = Unresolved ORDER BY summary ASC
                """.stripIndent().trim()

                List<Issue> pcIssues = myBaseUtil.findIssues(jql)
                result = pcIssues.collect { pc ->
                    [
                            key  : pc.key,
                            name : pc.summary,
                            pcKey: (myBaseUtil.getCustomFieldValue(pc, CustomFieldsConstants.PROFIT_CENTER_KEY) ?: pc.summary) as String
                    ]
                }
                break

            case "functionalAreas":
                String pcIssueKey = queryParams.getFirst("profitCenter")
                if (!pcIssueKey) {
                    return jsonError(400, "profitCenter parameter required")
                }

                String jql = """
                    project = ${CustomFieldsConstants.PROJECT_KVS_PROFIT_CENTERS}
                    AND issuetype = "Functional Areas"
                    AND 'Profit Center' = ${pcIssueKey}
                    AND resolution = Unresolved
                    ORDER BY summary ASC
                """.stripIndent().trim()

                List<Issue> faIssues = myBaseUtil.findIssues(jql)
                result = faIssues.collect { fa ->
                    [
                            key  : fa.key,
                            name : fa.summary,
                            faKey: (myBaseUtil.getCustomFieldValue(fa, CustomFieldsConstants.FUNCTIONAL_AREA_KEY) ?: "Fa key not exists") as String
                    ]
                }
                break

            case "checklistData":
                String pcIssueKey = queryParams.getFirst("profitCenter")
                String faIssueKey = queryParams.getFirst("functionalArea")
                String lang = (queryParams.getFirst("lang") ?: "DE").toUpperCase()
                if (!(lang in ["DE", "EN", "SK"])) lang = "DE"
                if (!pcIssueKey || !faIssueKey) {
                    return jsonError(400, "profitCenter and functionalArea parameters required")
                }

                // Map language to the Jira fields holding the localized text
                String summaryField = (lang == "EN") ? Question.SUMMARY_EN_FIELD_NAME
                        : (lang == "SK") ? Question.SUMMARY_SK_FIELD_NAME
                        : null // DE → use native issue.summary
                String categoryField = (lang == "EN") ? Question.CATEGORY_EN_FIELD_NAME
                        : (lang == "SK") ? Question.CATEGORY_SK_FIELD_NAME
                        : Question.CATEGORY_DE_FIELD_NAME

                Issue pcIssue = myBaseUtil.getIssueByKey(pcIssueKey)
                Issue faIssue = myBaseUtil.getIssueByKey(faIssueKey)
                if (!pcIssue || !faIssue) {
                    return jsonError(404, "Profit Center or Functional Area not found")
                }

                // If no workplaces exist → fallback to single workplace mode
                def workplaces = new JqlSearcher().getWorkplacesIssues(faIssue).collect({ wp -> [key: wp.key, name: wp.summary] });
                /*def workplaces =
                        faIssue.getSubTaskObjects()
                                .findAll { it.issueType.name == CustomFieldsConstants.WORKPLACE }
                                .findAll { it.status?.statusCategory?.key != 'done' }
                                .collect { wp -> [key: wp.key, name: wp.summary] }*/


                boolean singleWorkplaceMode = false
                if (workplaces.isEmpty()) {
                    workplaces = [[key: faIssue.key, name: faIssue.summary]]
                    singleWorkplaceMode = true
                }

                // Build Question Usage key for Level 2
                String pcKeyValue = myBaseUtil.getCustomFieldValue(pcIssue, CustomFieldsConstants.PROFIT_CENTER_KEY) ?: ""
                String faKeyValue = myBaseUtil.getCustomFieldValue(faIssue, CustomFieldsConstants.FUNCTIONAL_AREA_KEY) ?: ""
                String usageKey = faKeyValue ? "${pcKeyValue}_${faKeyValue}_Level_2" : "${pcKeyValue}_Level_2"

                // Fetch questions from KVSAQ project
                String qJql = """
                    project = ${CustomFieldsConstants.PROJECT_KVS_AUDIT_QUESTION}
                    AND issuetype = ${CustomFieldsConstants.QUESTION}
                    AND resolution = Unresolved
                    AND "Question Usage" in ("${usageKey}")
                    ORDER BY ${Question.QUESTION_ID_FIELD_NAME} ASC
                """.stripIndent().trim()

                List<Issue> questionIssues = myBaseUtil.findIssues(qJql)

                def questions = questionIssues.collect { q ->
                    def qid = myBaseUtil.getCustomFieldValue(q, Question.QUESTION_ID_FIELD_NAME)
                    String idStr = formatQuestionId(qid)

                    def categoryRaw = myBaseUtil.getCustomFieldValue(q, categoryField) ?: ""
                    // Fallback to DE if requested language category is empty
                    if (!categoryRaw && lang != "DE") {
                        categoryRaw = myBaseUtil.getCustomFieldValue(q, Question.CATEGORY_DE_FIELD_NAME) ?: ""
                    }

                    // Text in requested language; DE uses native summary
                    String text
                    if (summaryField) {
                        def localized = myBaseUtil.getCustomFieldValue(q, summaryField)
                        text = (localized ? localized.toString() : (q.summary ?: "")) as String
                    } else {
                        text = q.summary ?: ""
                    }

                    // Audit Day field (Select List single) - not exist yet
                    String dayValue = null
                    try {
                        def auditDayRaw = myBaseUtil.getCustomFieldValue(q, "Audit Day")
                        dayValue = auditDayRaw ? customFieldUtil.getFieldValue_SingleSelect(auditDayRaw) : null
                    } catch (Exception ignored) {
                        // Field does not exist yet — treat as "every day"
                    }

                    [
                            key     : q.key,
                            id      : idStr,
                            standard: categoryRaw.toString(),
                            text    : text,
                            day     : dayValue    // "Mon".."Fri" or null
                    ]
                }

                result = [
                        profitCenter  : [key: pcIssue.key, name: pcIssue.summary, pcKey: pcKeyValue],
                        functionalArea: [key: faIssue.key, name: faIssue.summary, faKey: faKeyValue],
                        usageKey      : usageKey,
                        workplaces    : workplaces,
                        questions     : questions,
                        singleWorkplaceMode: singleWorkplaceMode,
                        lang          : lang
                ]
                break

            default:
                return jsonError(400, "Invalid action. Use: profitCenters, functionalAreas, checklistData")
        }
    } catch (Exception e) {
        return jsonError(500, "Server error: ${e.message}")
    }

    return Response.ok(JsonOutput.toJson(result))
            .type(MediaType.APPLICATION_JSON)
            .build()
}

private static Response jsonError(int status, String message) {
    return Response.status(status)
            .entity(JsonOutput.toJson([error: message]))
            .type(MediaType.APPLICATION_JSON)
            .build()
}

private static String formatQuestionId(def qid) {
    if (qid == null) return ""
    if (qid instanceof Number) {
        return new java.text.DecimalFormat("0.00").format(qid)
    }
    return qid.toString()
}