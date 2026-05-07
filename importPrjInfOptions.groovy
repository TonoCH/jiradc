/**
 * importPrjInfOptions
 *
 * @author chabrecek.anton
 * Created on 27. 4. 2026.
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.fields.config.FieldConfig

def customFieldManager = ComponentAccessor.getCustomFieldManager()
def optionsManager     = ComponentAccessor.getOptionsManager()

Set<String> optionsToAdd = new LinkedHashSet();
// ═══ Base 64 insertion ═══════════════════════════════════
//def p1 = ''; def data = p1 + p2 + p3;  data.split("\\|").each { b64 -> if (b64) optionsToAdd.add(new String(b64.decodeBase64(), "UTF-8")) };
// ═══════════════════════════════════════════════════════════════════
optionsToAdd.addAll([
        "PC2_FA1_Level_1","PC2_FA2_Level_1","PC2_FA3_Level_1","PC2_FA4_Level_1","PC2_FA5_Level_1","PC2_FA6_Level_1","PC2_FA7_Level_1",
        "PC2_FA8_Level_1","PC3_FA1_Level_1","PC3_FA2_Level_1","PC3_FA3_Level_1","PC3_FA4_Level_1","PC3_FA5_Level_1","PC3_FA6_Level_1",
        "PC3_FA7_Level_1","PC3_FA8_Level_1","PC3_FA9_Level_1","PC3_FA10_Level_1","PC3_FA11_Level_1","PC3_FA12_Level_1","PC3_FA13_Level_1",
        "PC3_FA14_Level_1","PC4_FA1_Level_1","PC4_FA2_Level_1","PC4_FA3_Level_1","PC4_FA4_Level_1","PC6_FA1_Level_1","PC6_FA2_Level_1",
        "PC6_FA3_Level_1","PC6_FA4_Level_1","PC6_FA5_Level_1","PC6_FA6_Level_1","PC6_FA7_Level_1","PC6_FA8_Level_1","PC6_FA9_Level_1",
        "PC9_FA1_Level_1","PC9_FA2_Level_1","PC9_FA3_Level_1","PC9_FA4_Level_1","PC9_FA5_Level_1","PC9_FA6_Level_1","PC9_FA7_Level_1",
        "PC9_FA8_Level_1","PC9_FA9_Level_1","PC9_FA10_Level_1","PC9_FA11_Level_1","PCKL_FA1_Level_1","PCWEQS_FA1_Level_1","PCWK_FA1_Level_1",
        "PCWK_FA2_Level_1","PCWK_FA3_Level_1","PCLA_FA1_Level_1","PCLA_FA2_Level_1","PCLA_FA3_Level_1","PCLA_FA4_Level_1","PCLA_FA5_Level_1",
        "PCLA_FA6_Level_1","PCLA_FA7_Level_1","PCLA_FA8_Level_1","PCVersand_FA1_Level_1","PCVersand_FA2_Level_1","PCVersand_FA3_Level_1",
        "PCVersand_FA4_Level_1","PCVersand_FA5_Level_1","PCVersand_FA6_Level_1","PCVersand_FA7_Level_1","PCVersand_FA8_Level_1",
        "PCVersand_FA9_Level_1","SKERS_FA1_Level_1","SKERS_FA2_Level_1","SKERS_FA3_Level_1","SKFCS_FA1_Level_1","SKFCS_FA2_Level_1",
        "SKFCS_FA3_Level_1","SKFCS_FA4_Level_1","SKFCS_FA5_Level_1","SKFCS_FA6_Level_1","SKFCS_FA7_Level_1","SKFCS_FA8_Level_1",
        "SKFCS_FA9_Level_1","SKPS_FA1_Level_1","SKPS_FA2_Level_1","SKPS_FA3_Level_1","SKPS_FA4_Level_1","SKPS_FA5_Level_1",
        "SKPS_FA6_Level_1","SKCABLE_FA1_Level_1","SKCABLE_FA2_Level_1","SKFM_FA1_Level_1","SKFM_FA2_Level_1","SKFM_FA3_Level_1",
        "SKLPL_FA1_Level_1","SKLPL_FA2_Level_1","SKLPL_FA3_Level_1","SKLPL_FA4_Level_1","SKLPL_FA5_Level_1","SKRZ_FA1_Level_1","SKRZ_FA2_Level_1"
])


def fieldName = "Question Usage"
CustomField cf = customFieldManager.getCustomFieldObjects().find { it.name == fieldName }
if (!cf) return "❌ Field '${fieldName}' nebol nájdený!"

int added = 0
int skipped = 0

cf.getConfigurationSchemes().each { scheme ->
    scheme.getConfigs().values().each { FieldConfig config ->
        def existing = (optionsManager.getOptions(config)
                ?.collect { it.getValue() } ?: []) as Set<String>
        optionsToAdd.each { newVal ->
            if (existing.contains(newVal)) {
                skipped++
            } else {
                optionsManager.createOption(config, null, -1L, newVal)
                added++
            }
        }
    }
}

return "📊 Pridané: ${added} | Preskočené: ${skipped} | Načítaných: ${optionsToAdd.size()}"