/**
 * exportPrjInfOptions
 *
 * @author chabrecek.anton
 * Created on 21. 4. 2026.
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.fields.config.FieldConfig

def customFieldManager = ComponentAccessor.getCustomFieldManager()
def optionsManager     = ComponentAccessor.getOptionsManager()

def fieldName = "Project Information n"

CustomField cf = customFieldManager.getCustomFieldObjects()
        .find { it.name == fieldName }
if (!cf) return "❌ Field '${fieldName}' nebol nájdený!"

List<String> allOptions = []
cf.getConfigurationSchemes().each { scheme ->
    scheme.getConfigs().values().each { FieldConfig config ->
        optionsManager.getOptions(config)?.each { option ->
            if (!option.parentOption) allOptions << option.getValue()
        }
    }
}
allOptions = allOptions.unique().sort()

// Base64 encode – bezpečné znaky (A-Z a-z 0-9 + / =), zjednotené pomocou |
def fullData = allOptions.collect {
    it.getBytes("UTF-8").encodeBase64().toString()
}.join("|")

// Rozdelíme na kusy po 45 000 znakov (JVM limit je 65 535 na string literal)
final int CHUNK = 45000
def parts = []
for (int i = 0; i < fullData.length(); i += CHUNK) {
    parts << fullData.substring(i, Math.min(i + CHUNK, fullData.length()))
}

def sb = new StringBuilder()
sb.append("// Total: ${allOptions.size()} options | Parts: ${parts.size()} | Bytes: ${fullData.length()}\n")
sb.append("// ═══ SKOPIRUJ VSETKO OD SEM PO RIADOK 'data.split...' VRATANE ═══\n\n")

parts.eachWithIndex { part, i ->
    sb.append("def p${i + 1} = '${part}';\n")
}

sb.append("def data = ")
parts.eachWithIndex { part, i ->
    if (i > 0) sb.append(" + ")
    sb.append("p${i + 1}")
}
sb.append(";\n")

sb.append("Set<String> optionsToAdd = new LinkedHashSet<String>();\n")
sb.append("data.split(\"\\\\|\").each { b64 -> if (b64) optionsToAdd.add(new String(b64.decodeBase64(), \"UTF-8\")) };\n")

return sb.toString()
