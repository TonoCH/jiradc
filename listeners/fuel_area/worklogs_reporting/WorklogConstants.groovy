package listeners.fuel_area.worklogs_reporting

/*
 * FieldsUsage — ID-driven for V Time Spent buckets; name-based for defect rollups (unless IDs provided).

/*
 * Updated: 2025-08-27
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.fields.CustomField
import utils.CustomFieldUtil
import java.util.concurrent.ConcurrentHashMap

public class WorklogConstants {

    /* ---------- Helper type ---------- */
    static class FieldRef {
        final String id   // e.g. "customfield_17602" (may be null)
        final String name // e.g. "V Time Spent …"   (may be null)
        FieldRef(String id, String name) { this.id = id; this.name = name }
        String toString() { return "FieldRef(id=${id}, name=${name})" }
    }
    private static FieldRef FR(String id, String name) { new FieldRef(id, name) }

    /* ---------- Resolver: ID primary, name fallback, cached ---------- */
    private static final Map<String, CustomField> CF_CACHE_BY_ID = new ConcurrentHashMap<>()
    private static final Map<String, CustomField> CF_CACHE_BY_NAME = new ConcurrentHashMap<>()

    static CustomField getCF(FieldRef ref) {
        if (ref == null) return null
        def cfm = ComponentAccessor.customFieldManager
        if (ref.id) {
            def cached = CF_CACHE_BY_ID[ref.id]
            if (cached != null) return cached
            def byId = cfm.getCustomFieldObject(ref.id)
            if (byId != null) { CF_CACHE_BY_ID[ref.id] = byId; return byId }
        }
        if (ref.name) {
            def cachedN = CF_CACHE_BY_NAME[ref.name]
            if (cachedN != null) return cachedN
            def byName = CustomFieldUtil.getCustomFieldByName(ref.name)
            if (byName != null) { CF_CACHE_BY_NAME[ref.name] = byName; return byName }
        }
        return null
    }

    /* ---------- Sources (budgets in seconds) ---------- */
    public static final FieldRef EPIC_TIME_BUDGET_FIELD    = FR("customfield_16401", "Epic Time Budget")
    public static final FieldRef FEATURE_TIME_BUDGET_FIELD = FR("customfield_17100", "Feature Time Budget")
    public static final FieldRef LPM_TIME_BUDGET_FIELD     = FR("customfield_16603", "LPM Time Budget")

    /* ---------- Targets (Numbers written by listener) ---------- */
    // Epic (hours)
    public static final FieldRef EPIC_TIME_SPENT_FIELD     = FR("customfield_17803", "Epic Time Spent V")
    public static final FieldRef EPIC_TIME_REMAINING_FIELD = FR("customfield_17806", "Epic Time Remaining V")
    public static final FieldRef EPIC_SIGNAL_RATIO_FIELD   = FR("customfield_17809", "Epic Signal Ratio V")
    public static final FieldRef EPIC_TIME_BUDGET_N_FIELD  = FR("customfield_17800", "Epic Time Budget V")

    // Feature (hours)
    public static final FieldRef FEATURE_TIME_SPENT_FIELD     = FR("customfield_17804", "Feature Time Spent V")
    public static final FieldRef FEATURE_TIME_REMAINING_FIELD = FR("customfield_17807", "Feature Time Remaining V")
    public static final FieldRef FEATURE_SIGNAL_RATIO_FIELD   = FR("customfield_17810", "Feature Signal Ratio V")
    public static final FieldRef FEATURE_TIME_BUDGET_N_FIELD  = FR("customfield_17801", "Feature Time Budget V")

    // LPM (mandays)
    public static final FieldRef LPM_TIME_SPENT_FIELD     = FR("customfield_17805", "LPM Time Spent (MD) V")
    public static final FieldRef LPM_TIME_REMAINING_FIELD = FR("customfield_17808", "LPM Time Remaining (MD) V")
    public static final FieldRef LPM_SIGNAL_RATIO_FIELD   = FR("customfield_17811", "LPM Signal Ratio V")
    public static final FieldRef LPM_TIME_BUDGET_N_FIELD  = FR("customfield_17802", "LPM Time Budget (MD) V")

    // LPM vs Epic Budget Difference (mandays, Number)
    public static final FieldRef LPM_EPIC_BUDGET_DIFF_MANDAYS = FR("customfield_17701", "Budget Difference V")

    // Shared “Budget Usage (%)” number field across Epic/Feature/LPM
    public static final FieldRef BUDGET_USAGE_PERCENT_FIELD = FR("customfield_17900", "Budget Usage (%)")

    /* ----Prio Value Temp (Number)---------- */
    public static final FieldRef PRIO_VALUE_TEMP = FR("customfield_14414", "FRS LPM Priority Roadmap V")

    /* ---------- “V Time Spent …” numeric fields (kept for other listeners/reports) ---------- */
    public static final String V_TIME_SPENT_PREFIX = "V Time Spent "

    public static final FieldRef V_TS_ENTW_FRS_SW            = FR("customfield_17601", "V Time Spent Entwicklung FRS SW")
    public static final FieldRef V_TS_ERS_UA_PLATFORM_TEAMS  = FR("customfield_17602", "V Time Spent ERS - Ukraine (Platform Teams)")
    public static final FieldRef V_TS_ERS_UA_ALLG            = FR("customfield_17603", "V Time Spent ERS - Ukraine Allgemein")
    public static final FieldRef V_TS_ERS_UA_OPERATIONS      = FR("customfield_17604", "V Time Spent ERS - Ukraine Operations")
    public static final FieldRef V_TS_ERS_UA_RND             = FR("customfield_17605", "V Time Spent ERS - Ukraine R&D")
    public static final FieldRef V_TS_ERS_CT_SALES           = FR("customfield_17606", "V Time Spent ERS CT & Sales")
    public static final FieldRef V_TS_ERS_ENTW_HW            = FR("customfield_17607", "V Time Spent ERS ENTWICKLUNG - HW")
    public static final FieldRef V_TS_ERS_ENTW_SW            = FR("customfield_17608", "V Time Spent ERS ENTWICKLUNG - SW")
    public static final FieldRef V_TS_ERS_INTEGRATION        = FR("customfield_17609", "V Time Spent ERS Integration-Teams")
    public static final FieldRef V_TS_ERS_LEITUNG_ZD         = FR("customfield_17610", "V Time Spent ERS Leitung / Zentrale Dienste")
    public static final FieldRef V_TS_ERS_MARKETING          = FR("customfield_17611", "V Time Spent ERS MARKETING")
    public static final FieldRef V_TS_ERS_OPER_SONST         = FR("customfield_17612", "V Time Spent ERS OPERATIONS SONSTIGES")
    public static final FieldRef V_TS_ERS_PRODMGMT           = FR("customfield_17613", "V Time Spent ERS PRODUKTMANAGEMENT")
    public static final FieldRef V_TS_ERS_SW_QUALITAET       = FR("customfield_17614", "V Time Spent ERS SW QUALITÄT")
    public static final FieldRef V_TS_ERS_TECH_TEAMS         = FR("customfield_17615", "V Time Spent ERS Technical-Teams")
    public static final FieldRef V_TS_OPER_MGMT_FRS          = FR("customfield_17616", "V Time Spent Operations Management FRS")
    public static final FieldRef V_TS_SW_QUALITAET_FRS       = FR("customfield_17617", "V Time Spent SW Qualität FRS")
    public static final FieldRef V_TS_UNKNOWN_USER           = FR("customfield_17618", "V Time Spent Unknown User")
    public static final FieldRef V_UNKNOWN_CONTRIBUTORS      = FR("customfield_17619", "V Time Spent Unknown Contributors") // multiline

    public static final List<FieldRef> V_TIME_SPENT_FIELDS = [
        V_TS_ERS_UA_PLATFORM_TEAMS, V_TS_ERS_UA_ALLG, V_TS_ERS_UA_OPERATIONS, V_TS_ERS_UA_RND,
        V_TS_ERS_CT_SALES, V_TS_ERS_ENTW_HW, V_TS_ERS_ENTW_SW, V_TS_ERS_INTEGRATION,
        V_TS_ERS_LEITUNG_ZD, V_TS_ERS_MARKETING, V_TS_ERS_OPER_SONST, V_TS_ERS_PRODMGMT,
        V_TS_ERS_SW_QUALITAET, V_TS_ERS_TECH_TEAMS, V_TS_ENTW_FRS_SW, V_TS_OPER_MGMT_FRS,
        V_TS_SW_QUALITAET_FRS, V_TS_UNKNOWN_USER
    ]

    static String groupNameForField(FieldRef ref) {
        if (ref == null || ref.name == null) return null
        if (!ref.name.startsWith(V_TIME_SPENT_PREFIX)) return null
        return ref.name.substring(V_TIME_SPENT_PREFIX.length())
    }
    static List<Map<String, Object>> vTimeSpentFieldSpecs() {
        V_TIME_SPENT_FIELDS.collect { ref ->
            [ ref: ref, groupName: (ref == V_TS_UNKNOWN_USER ? null : groupNameForField(ref)) ]
        }
    }

    /* ---------- Bug/Problem rollups ---------- */
    public static final FieldRef NUM_BUGS_FIELD                   = FR("customfield_17620", "Number of Bugs")
    public static final FieldRef NUM_PROBLEMS_FIELD               = FR("customfield_17621", "Number of Problems")
    public static final FieldRef NUM_BUGS_AND_PROBLEMS_FIELD      = FR("customfield_17622", "Number of Bugs and Problems")
    public static final FieldRef LIST_BUGS_AND_PROBLEMS_FIELD     = FR("customfield_17623", "List of Bugs and Problems") // multiline text
    public static final FieldRef NUM_BUGS_AND_PROBLEMS_OPEN_FIELD = FR("customfield_17624", "Number of Bugs and Problems (Open)")
}