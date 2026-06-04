package jobs.kvs

/**
 * fieldsCheck
 *
 * @author chabrecek.anton
 * Created on 4. 6. 2026.
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.fields.CustomField
import utils.mail_notifiers.Mailer
import java.lang.reflect.Modifier

// =============== CONFIG ===============
final String RECIPIENT_USERNAME = "chabrecek.anton"
final String SUBJECT_PREFIX     = "[KVS] Weekly custom-field constants audit"
// =====================================

def cfm = ComponentAccessor.customFieldManager

def classes = [
        kvs_audits.issueType.Audit,
        kvs_audits.issueType.AuditPreparation,
        kvs_audits.issueType.Question,
        kvs_audits.issueType.BaseIssue,
        kvs_audits.common.CustomFieldsConstants,
]

int totalIssues = 0

def html = new StringBuilder()
html << """
<html><body style="font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#172B4D;">
<h2 style="margin-bottom:4px;">KVS custom-field constants — weekly audit</h2>
<div style="color:#5E6C84;margin-bottom:16px;">
  Generated: ${new Date().format("yyyy-MM-dd HH:mm")} &nbsp;|&nbsp;
  Host: ${ComponentAccessor.applicationProperties.getString('jira.baseurl') ?: '-'}
</div>
<p>Skript for static constant fields check in KVS.<br/>
lines marked <b style="color:#DE350B;">ERROR</b> or <b style="color:#FF8B00;">WARN</b> need attention.</p>
"""

String th = 'style="background:#F4F5F7;text-align:left;padding:6px 10px;border-bottom:1px solid #DFE1E6;"'
String td = 'style="padding:6px 10px;border-bottom:1px solid #EBECF0;"'

def statusCell = { String s ->
    String color
    switch (s) {
        case 'OK':            color = '#006644'; break
        case ~/.*WARN.*/:     color = '#FF8B00'; break
        default:              color = '#DE350B'; break
    }
    return "<td ${td}><b style=\"color:${color};\">${s}</b></td>"
}

classes.each { Class clazz ->
    html << "<h3 style=\"margin-top:24px;border-bottom:2px solid #0052CC;padding-bottom:4px;\">${clazz.name}</h3>"

    def cfFields = clazz.declaredFields.findAll {
        Modifier.isStatic(it.modifiers) && CustomField.isAssignableFrom(it.type)
    }
    if (cfFields) {
        html << "<h4>CustomField constants (resolved by ID)</h4>"
        html << "<table style=\"border-collapse:collapse;width:100%;\"><tr>"
        html << "<th ${th}>Constant</th><th ${th}>Resolved ID</th><th ${th}>Real name in Jira</th><th ${th}>Type</th><th ${th}>Other fields with same name</th><th ${th}>Status</th></tr>"
        cfFields.each { f ->
            f.accessible = true
            CustomField cf = (CustomField) f.get(null)
            if (cf == null) {
                html << "<tr><td ${td}>${f.name}</td><td ${td}>&lt;null&gt;</td><td ${td}>—</td><td ${td}>—</td><td ${td}>—</td>"
                html << statusCell('ERROR: static init failed') << "</tr>"
                totalIssues++
            } else {
                String type = cf.customFieldType?.name ?: '?'
                Collection<CustomField> sameName = cfm.getCustomFieldObjectsByName(cf.name)
                Collection<CustomField> others = sameName.findAll { it.id != cf.id }
                String otherCol, status
                if (others.isEmpty()) {
                    otherCol = '—'
                    status = 'OK'
                } else {
                    otherCol = others.collect { "${it.id} (${it.customFieldType?.name ?: '?'})" }.join(', ')
                    status = "WARN: ${others.size()} duplicate name(s)"
                    totalIssues++
                }
                html << "<tr><td ${td}>${f.name}</td><td ${td}>${cf.id}</td><td ${td}>${cf.name}</td><td ${td}>${type}</td><td ${td}>${otherCol}</td>"
                html << statusCell(status) << "</tr>"
            }
        }
        html << "</table>"
    }

    def nameFields = clazz.declaredFields.findAll {
        Modifier.isStatic(it.modifiers) && it.type == String && it.name.endsWith('_FIELD_NAME')
    }
    if (nameFields) {
        html << "<h4>String <code>*_FIELD_NAME</code> constants (resolved by name)</h4>"
        html << "<table style=\"border-collapse:collapse;width:100%;\"><tr>"
        html << "<th ${th}>Constant</th><th ${th}>Declared name</th><th ${th}>Resolved ID</th><th ${th}>Actual name</th><th ${th}>Status</th></tr>"
        nameFields.each { f ->
            f.accessible = true
            String declaredName
            try { declaredName = (String) f.get(null) }
            catch (Throwable t) { declaredName = null }

            if (declaredName == null) {
                html << "<tr><td ${td}>${f.name}</td><td ${td}>&lt;null&gt;</td><td ${td}>—</td><td ${td}>—</td>"
                html << statusCell('ERROR: constant is null') << "</tr>"
                totalIssues++
                return
            }
            Collection<CustomField> matches = cfm.getCustomFieldObjectsByName(declaredName)
            if (!matches) {
                html << "<tr><td ${td}>${f.name}</td><td ${td}>${declaredName}</td><td ${td}>&lt;not found&gt;</td><td ${td}>—</td>"
                html << statusCell('ERROR: no match') << "</tr>"
                totalIssues++
            } else if (matches.size() > 1) {
                String ids   = matches.collect { it.id }.join(', ')
                String names = matches.collect { it.name }.join(', ')
                html << "<tr><td ${td}>${f.name}</td><td ${td}>${declaredName}</td><td ${td}>${ids}</td><td ${td}>${names}</td>"
                html << statusCell("WARN: ${matches.size()} fields share this name") << "</tr>"
                totalIssues++
            } else {
                CustomField cf = matches.iterator().next()
                String status = (cf.name == declaredName) ? 'OK' : 'WARN: name mismatch'
                if (status != 'OK') totalIssues++
                html << "<tr><td ${td}>${f.name}</td><td ${td}>${declaredName}</td><td ${td}>${cf.id}</td><td ${td}>${cf.name}</td>"
                html << statusCell(status) << "</tr>"
            }
        }
        html << "</table>"
    }

    def idFields = clazz.declaredFields.findAll {
        Modifier.isStatic(it.modifiers) && it.type == String && it.name.endsWith('_FIELD_ID')
    }
    if (idFields) {
        html << "<h4>String <code>*_FIELD_ID</code> constants (resolved by ID)</h4>"
        html << "<table style=\"border-collapse:collapse;width:100%;\"><tr>"
        html << "<th ${th}>Constant</th><th ${th}>Stored ID</th><th ${th}>Real name in Jira</th><th ${th}>Other fields with same name</th><th ${th}>Status</th></tr>"
        idFields.each { f ->
            f.accessible = true
            String storedId
            try { storedId = (String) f.get(null) }
            catch (Throwable t) { storedId = null }

            if (storedId == null || storedId == 'false') {
                html << "<tr><td ${td}>${f.name}</td><td ${td}>${String.valueOf(storedId)}</td><td ${td}>—</td><td ${td}>—</td>"
                html << statusCell('ERROR: null / false') << "</tr>"
                totalIssues++
                return
            }
            String norm = storedId.startsWith('customfield_') ? storedId : "customfield_${storedId}"
            CustomField cf = cfm.getCustomFieldObject(norm)
            if (cf == null) {
                html << "<tr><td ${td}>${f.name}</td><td ${td}>${storedId}</td><td ${td}>&lt;not found&gt;</td><td ${td}>—</td>"
                html << statusCell('ERROR: no match') << "</tr>"
                totalIssues++
            } else {
                Collection<CustomField> sameName = cfm.getCustomFieldObjectsByName(cf.name)
                Collection<CustomField> others = sameName.findAll { it.id != cf.id }
                String otherCol, status
                if (others.isEmpty()) {
                    otherCol = '—'
                    status = 'OK'
                } else {
                    otherCol = others.collect { "${it.id} (${it.customFieldType?.name ?: '?'})" }.join(', ')
                    status = "WARN: ${others.size()} duplicate name(s)"
                    totalIssues++
                }
                html << "<tr><td ${td}>${f.name}</td><td ${td}>${cf.id}</td><td ${td}>${cf.name}</td><td ${td}>${otherCol}</td>"
                html << statusCell(status) << "</tr>"
            }
        }
        html << "</table>"
    }
}

html << """
<hr style="margin-top:24px;border:none;border-top:1px solid #DFE1E6;"/>
<p style="color:#5E6C84;font-size:12px;">
  Summary: <b>${totalIssues}</b> issue(s) detected.<br/>
  Job script: KVS weekly custom-field constants audit.
</p>
</body></html>
"""

String subject = "${SUBJECT_PREFIX} — ${totalIssues} issue(s)"

Mailer mailer = new Mailer("text/html")
boolean ok = mailer.sendMessage(RECIPIENT_USERNAME, subject, html.toString())

if (!ok) {
    log.error("KVS weekly constants audit: mail send failed: ${mailer.errorMessages}")
    return "FAILED: ${mailer.errorMessages}"
}

return "Sent to ${RECIPIENT_USERNAME}, issues=${totalIssues}"