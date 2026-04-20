package behaviours.kvs


import com.onresolve.jira.groovy.user.FormField
import com.atlassian.jira.issue.customfields.option.Option
import static com.atlassian.jira.issue.IssueFieldConstants.ISSUE_TYPE
import com.onresolve.scriptrunner.runner.util.UserMessageUtil
import kvs_audits.issueType.Audit
import kvs_audits.issueType.AuditPreparation

/**
 * kvsRequiredFields
 *
 * @author chabrecek.anton
 * Created on 11. 6. 2025.
 */
String optVal(Object v) {
    if (v == null) return null
    return (v instanceof Option) ? ((Option) v).getValue() : v.toString()
}
boolean eq(String a, String b) { return (a != null && b != null && a.equalsIgnoreCase(b)) }

// --- fields ---
FormField issueTypeF  = getFieldById(ISSUE_TYPE)
FormField levelF      = getFieldByName(AuditPreparation.AUDIT_LEVEL_FIELD_NAME)
FormField auditTypeF  = getFieldByName(Audit.AUDIT_TYPE_FIELD_NAME)
FormField questionsF  = getFieldByName(Audit.QUESTIONS_FIELD_NAME)
FormField questionsFa  = getFieldByName(Audit.FUNCTIONAL_AREA_FIELD_NAME)
FormField questionsWp  = getFieldByName(Audit.WORKPLACES_FIELD_NAME)

final String AUDIT_ISSUE_TYPE_ID = "12700"  
String issueTypeId = optVal(issueTypeF?.getValue())
if (!eq(issueTypeId, AUDIT_ISSUE_TYPE_ID)) {
    return
}

// --- read values ---
String levelVal      = optVal(levelF?.getValue())?.replace(" ", "_") 
String auditTypeVal  = optVal(auditTypeF?.getValue())
boolean isManual     = eq(auditTypeVal, Audit.MANUAL)

// --- core rules ---
switch (levelVal) {
    case "Level_4":
        questionsF.setHidden(!isManual)
        questionsF.setRequired(isManual)
        questionsFa.setHidden(false)
        questionsWp.setHidden(false)
        if (!isManual) { questionsF.setFormValue(null) }
        break

    case "Level_3":
        questionsF.setHidden(!isManual)
        questionsF.setRequired(isManual)
        questionsWp.setHidden(false)
        if (!isManual) { questionsF.setFormValue(null) }
        break

    case "Level_2":
        questionsF.setHidden(!isManual)
        questionsF.setRequired(isManual)
        if (!isManual) { questionsF.setFormValue(null) }  
        break

    default:
        break
}

/*String auiInfo = """
<div class="aui-message aui-message-info">
  <p>This task of issue creation will take place on background</p>
</div>
""".stripIndent()

FormField anchor = getFieldByName("Audit Description") ?: getFieldById("description")

if (id?.equalsIgnoreCase(auditId)) {
    anchor?.setHelpText(auiInfo)
}*/