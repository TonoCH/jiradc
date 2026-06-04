/**
 * rulesAudit
 *
 * KVS audit-rules drift report.
 *
 * Reads the static AUDIT_RULES descriptor declared in each AuditLevel{N}Scheduler,
 * reflects the common base AuditScheduler, snapshots key constants and emits a
 * single HTML email summarizing the current behavior of every audit level so
 * that code drift can be detected by simply comparing two consecutive mails
 * (the subject carries a behavioral fingerprint).
 *
 * @author chabrecek.anton
 * Created on 4. 6. 2026.
 */

import com.atlassian.jira.component.ComponentAccessor
import kvs_audits.audit2.AuditLevel2Scheduler
import kvs_audits.audit3.AuditLevel3Scheduler
import kvs_audits.audit4.AuditLevel4Scheduler
import kvs_audits.audit5.AuditLevel5Scheduler
import kvs_audits.common.AuditScheduler
import kvs_audits.common.CustomFieldsConstants
import utils.mail_notifiers.Mailer

import java.lang.reflect.Modifier
import java.security.MessageDigest

// =============== CONFIG ===============
final String RECIPIENT_USERNAME = "chabrecek.anton"
final String SUBJECT_PREFIX     = "[KVS] Audit-rules drift report"
// =====================================

// Order matters: shapes column order of the comparison matrix.
def schedulers = [
        AuditLevel2Scheduler,
        AuditLevel3Scheduler,
        AuditLevel4Scheduler,
        AuditLevel5Scheduler,
]

// Canonical order of rule keys in the matrix. Anything extra in AUDIT_RULES is appended below.
def ruleKeyOrder = [
        'auditLevel',
        'handlerClass',
        'rotationUnit',
        'subAreaSplit',
        'lookAheadMonths',
        'safetyLimit',
        'intervalSource',
        'fixedIntervalOverride',
        'auditorRotation',
        'usageRotation',
        'auditsPerTick',
        'crossAudits',
        'onePcPerTick',
        'specialQuestions',
        'rotationDataShape',
        'notes',
]

int totalWarnings = 0
def warnings = []

// ---------------- collect descriptors via reflection ----------------

def descriptors = [:]   // className -> Map (rules)
schedulers.each { Class clazz ->
    def field = clazz.declaredFields.find { it.name == 'AUDIT_RULES' && Modifier.isStatic(it.modifiers) }
    if (!field) {
        warnings << "${clazz.name}: missing static AUDIT_RULES descriptor"
        totalWarnings++
        descriptors[clazz.simpleName] = null
        return
    }
    field.accessible = true
    def value = field.get(null)
    if (!(value instanceof Map)) {
        warnings << "${clazz.name}: AUDIT_RULES is not a Map (got ${value?.class?.name})"
        totalWarnings++
        descriptors[clazz.simpleName] = null
        return
    }
    descriptors[clazz.simpleName] = value
}

// merge all observed keys so unexpected new keys still show up
def allKeys = [] as LinkedHashSet
allKeys.addAll(ruleKeyOrder)
descriptors.values().each { d -> if (d instanceof Map) allKeys.addAll(d.keySet()) }

// ---------------- common base reflection ----------------

def baseClass = AuditScheduler
def baseMethods = baseClass.declaredMethods
        .findAll { !it.synthetic && !Modifier.isPrivate(it.modifiers) }
        .collect { m ->
            def params = m.parameterTypes.collect { it.simpleName }.join(', ')
            "${Modifier.toString(m.modifiers)} ${m.returnType.simpleName} ${m.name}(${params})"
        }
        .sort()

// Methods we expect to exist on the base — drift sensors.
def requiredBaseMethods = ['getPreparationIssues', 'logBasicInfo', 'dynamicReconcileRotationUnits', 'getLiveAuditors']
def declaredBaseMethodNames = baseClass.declaredMethods.collect { it.name } as Set
def missingBase = requiredBaseMethods.findAll { !declaredBaseMethodNames.contains(it) }
missingBase.each { warnings << "AuditScheduler base: missing expected method ${it}()" ; totalWarnings++ }

// ---------------- constants snapshot ----------------

def constantNames = [
        'DEFAULT_PROJECT_FOR_JOBS',
        'PROJECT_KVS_AUDIT',
        'PROJECT_KVS_AUDIT_TEST',
        'PROJECT_KVS_AUDIT_QUESTION',
        'PROJECT_KVS_PROFIT_CENTERS',
        'PROJECT_KPI',
        'AUDIT_LEVEL_2',
        'AUDIT_LEVEL_3',
        'AUDIT_LEVEL_4',
        'AUDIT_LEVEL_5',
        'PARENT_LINK_FIELD_ID',
        'PROFIT_CENTER_KEY',
        'FUNCTIONAL_AREA_KEY',
        'CROSS_AUDITORS_POOL',
        'NUM_OF_DAYS_FOR_TARGET_END',
]
def constants = [:]
constantNames.each { name ->
    try {
        def f = CustomFieldsConstants.declaredFields.find { it.name == name }
        if (f) {
            f.accessible = true
            constants[name] = String.valueOf(f.get(null))
        } else {
            constants[name] = '<not declared>'
            warnings << "CustomFieldsConstants.${name} is referenced by report but not declared"
            totalWarnings++
        }
    } catch (Throwable t) {
        constants[name] = "<error: ${t.class.simpleName}>"
        warnings << "CustomFieldsConstants.${name}: ${t.message}"
        totalWarnings++
    }
}

// ---------------- behavioral fingerprint ----------------

def canonicalForHash = new StringBuilder()
schedulers.each { Class c ->
    canonicalForHash << c.name << "\n"
    def d = descriptors[c.simpleName]
    if (d instanceof Map) {
        allKeys.each { k -> canonicalForHash << "  ${k}=${String.valueOf(d[k])}\n" }
    } else {
        canonicalForHash << "  <missing>\n"
    }
}
constants.each { k, v -> canonicalForHash << "const ${k}=${v}\n" }
baseMethods.each { canonicalForHash << "base ${it}\n" }

def digestBytes = MessageDigest.getInstance("SHA-256").digest(canonicalForHash.toString().bytes)
def fingerprint = digestBytes.encodeHex().toString().substring(0, 12)

// ---------------- HTML rendering ----------------

def html = new StringBuilder()
def th = 'style="background:#F4F5F7;text-align:left;padding:6px 10px;border-bottom:1px solid #DFE1E6;vertical-align:top;"'
def td = 'style="padding:6px 10px;border-bottom:1px solid #EBECF0;vertical-align:top;"'
def keyTd = 'style="padding:6px 10px;border-bottom:1px solid #EBECF0;vertical-align:top;font-weight:600;background:#FAFBFC;width:180px;"'

def esc = { Object v ->
    if (v == null) return '<span style="color:#5E6C84;">—</span>'
    String s = String.valueOf(v)
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

html << """
<html><body style="font-family:Arial,Helvetica,sans-serif;font-size:13px;color:#172B4D;">
<h2 style="margin-bottom:4px;">KVS audit-rules drift report</h2>
<div style="color:#5E6C84;margin-bottom:16px;">
  Generated: ${new Date().format("yyyy-MM-dd HH:mm")} &nbsp;|&nbsp;
  Host: ${ComponentAccessor.applicationProperties.getString('jira.baseurl') ?: '-'} &nbsp;|&nbsp;
  Fingerprint: <code style="background:#F4F5F7;padding:2px 6px;border-radius:3px;">${fingerprint}</code>
</div>
<p>
  This report is derived purely from code (static <code>AUDIT_RULES</code> descriptors and reflection).
  Compare the fingerprint against the previous run to detect any rule drift at a glance.
  When the fingerprint changes, scan the matrix below for the affected row.
</p>
"""

// Warnings block (if any)
if (warnings) {
    html << "<h3 style=\"margin-top:24px;color:#DE350B;border-bottom:2px solid #DE350B;padding-bottom:4px;\">Warnings (${warnings.size()})</h3>"
    html << '<ul>'
    warnings.each { w -> html << "<li style=\"color:#DE350B;\">${esc(w)}</li>" }
    html << '</ul>'
}

// Comparison matrix
html << '<h3 style="margin-top:24px;border-bottom:2px solid #0052CC;padding-bottom:4px;">Comparison matrix</h3>'
html << '<p style="color:#5E6C84;">Rows = behavioral rule, columns = audit level. Cells come straight from each scheduler\'s AUDIT_RULES descriptor.</p>'
html << '<table style="border-collapse:collapse;width:100%;table-layout:fixed;"><tr>'
html << "<th ${th}>Rule</th>"
schedulers.each { Class c -> html << "<th ${th}>${esc(c.simpleName)}</th>" }
html << '</tr>'

allKeys.each { String k ->
    html << "<tr><td ${keyTd}>${esc(k)}</td>"
    def rowValues = schedulers.collect { Class c ->
        def d = descriptors[c.simpleName]
        return (d instanceof Map) ? d[k] : null
    }
    def distinct = rowValues.collect { String.valueOf(it) } as Set
    boolean uniform = (distinct.size() == 1)
    def bg = uniform ? '' : ' background:#FFFAE6;'
    rowValues.each { v ->
        html << "<td ${td.replace('vertical-align:top;', 'vertical-align:top;' + bg)}>${esc(v)}</td>"
    }
    html << '</tr>'
}
html << '</table>'
html << '<p style="color:#5E6C84;font-size:12px;">Highlighted rows differ across levels — that is normal for level-specific rules, but it is the place to start reading.</p>'

// Per-level detail
html << '<h3 style="margin-top:24px;border-bottom:2px solid #0052CC;padding-bottom:4px;">Per-level detail</h3>'
schedulers.each { Class c ->
    def d = descriptors[c.simpleName]
    html << "<h4 style=\"margin-top:18px;\">${esc(c.name)}</h4>"
    if (!(d instanceof Map)) {
        html << '<p style="color:#DE350B;">No AUDIT_RULES descriptor declared.</p>'
        return
    }
    html << '<table style="border-collapse:collapse;width:100%;">'
    allKeys.each { String k ->
        html << "<tr><td ${keyTd}>${esc(k)}</td><td ${td}>${esc(d[k])}</td></tr>"
    }
    html << '</table>'
}

// Common base reflection
html << '<h3 style="margin-top:24px;border-bottom:2px solid #0052CC;padding-bottom:4px;">Common base — AuditScheduler</h3>'
html << '<p style="color:#5E6C84;">Public / protected methods declared on the shared base. New / removed entries are a strong signal that level behavior may have shifted.</p>'
html << '<table style="border-collapse:collapse;width:100%;"><tr><th ' + th + '>Method</th></tr>'
baseMethods.each { String m -> html << "<tr><td ${td}><code>${esc(m)}</code></td></tr>" }
html << '</table>'

// Constants snapshot
html << '<h3 style="margin-top:24px;border-bottom:2px solid #0052CC;padding-bottom:4px;">KVS constants snapshot</h3>'
html << '<table style="border-collapse:collapse;width:100%;"><tr>'
html << "<th ${th}>Constant</th><th ${th}>Value</th></tr>"
constants.each { String k, String v ->
    html << "<tr><td ${keyTd}>${esc(k)}</td><td ${td}>${esc(v)}</td></tr>"
}
html << '</table>'

html << """
<hr style="margin-top:24px;border:none;border-top:1px solid #DFE1E6;"/>
<p style="color:#5E6C84;font-size:12px;">
  Summary: <b>${totalWarnings}</b> warning(s). Fingerprint <code>${fingerprint}</code>.<br/>
  Source: AUDIT_RULES descriptors in AuditLevel{2..5}Scheduler + reflection over AuditScheduler + selected CustomFieldsConstants fields.<br/>
  Job script: KVS audit-rules drift report.
</p>
</body></html>
"""

String subject = "${SUBJECT_PREFIX} — fp:${fingerprint} — ${totalWarnings} warning(s)"

Mailer mailer = new Mailer("text/html")
boolean ok = mailer.sendMessage(RECIPIENT_USERNAME, subject, html.toString())

if (!ok) {
    log.error("KVS audit-rules drift report: mail send failed: ${mailer.errorMessages}")
    return "FAILED: ${mailer.errorMessages}"
}

return "Sent to ${RECIPIENT_USERNAME}, fingerprint=${fingerprint}, warnings=${totalWarnings}"
