package fields.fuel_area;

import utils.CustomFieldUtil
import java.util.concurrent.ConcurrentHashMap;

public class FieldsUsage {
    // Existing Long fields for Time Budget
  /*  public static final Long EPIC_TIME_BUDGET_FIELD_ID = 16401L; // Epic Time Budget -- (Epic Sum Up) Time
    public static final Long FEATURE_TIME_BUDGET_FIELD_ID = 17100L; // Feature Time Budget -- (Epic Sum Up) Time
    public static final Long LPM_TIME_BUDGET_FIELD_ID = 16603L; // LPM Time Budget -- (Epic Sum Up) Time
    public static final Long EPIC_TIME_REMAINING_FIELD_ID = 16621L; // Epic Time Remaining -- double

    //TODO -- 
    //needs to be checked in other scripts
    public static final Long FEATURE_TIME_REMAINING_FIELD_ID = 16620L; // Feature Time Remaining -- double
    //needs to be checked in other scripts
    public static final Long LPM_TIME_REMAINING_FIELD_ID = 16405L; // LPM Time Remaining --double
    // END OF TODO


    public static final Long LPM_TIME_REMAINING_FIELD_ID_ACTUAL = 16620L; // LPM Time Remaining --double
    public static final Long LPM_TIME_BUTGET_N = 17105L; //LPM Time Budget(N) -- Number searcher 
    public static final Long LPM_TIME_SPENT = 16617L; //LPM Time Spent -- Number searcher 

    public static final Long LPM_TIME_BUDGET_MD_FIELD_ID = 17200L; // LPM Time Budget (MD) 17200 --double
    public static final Long LPM_TIME_SPENT_MD_FIELD_ID = 17201L; // LPM Time Spent (MD) 17201 --double
    public static final Long LPM_TIME_REMAINING_MD_FIELD_ID = 17202L; // LPM Time Remaining (MD) 17202 --double*/

    //field for value set -> field value calculated
    //script runner bug: on LONG / LONG  need to find different solution
    /*public static final Map<Long, Long> FieldValueCounterPairs = [
        LPM_TIME_BUDGET_MD_FIELD_ID : LPM_TIME_BUTGET_N,
        LPM_TIME_SPENT_MD_FIELD_ID : LPM_TIME_SPENT,
        LPM_TIME_REMAINING_MD_FIELD_ID : FEATURE_TIME_REMAINING_FIELD_ID
    ] as Map<Long, Long>

    static Long getSourceFieldId(Long targetFieldId) {
        switch (targetFieldId) {
            case LPM_TIME_BUDGET_MD_FIELD_ID: return LPM_TIME_BUDGET_FIELD_ID
            case LPM_TIME_SPENT_MD_FIELD_ID:  return LPM_TIME_SPENT
            case LPM_TIME_REMAINING_MD_FIELD_ID: return LPM_TIME_REMAINING_FIELD_ID_ACTUAL
            default: return null
        }
    }*/

    public static final String EPIC_TIME_BUDGET_FIELD_NAME       = "Epic Time Budget";
    public static final String FEATURE_TIME_BUDGET_FIELD_NAME    = "Feature Time Budget";
    public static final String LPM_TIME_BUDGET_FIELD_NAME        = "LPM Time Budget";

    public static final String EPIC_TIME_REMAINING_FIELD_NAME    = "Epic Time Remaining";
    public static final String FEATURE_TIME_REMAINING_FIELD_NAME = "Feature Time Remaining";
    public static final String LPM_TIME_REMAINING_FIELD_NAME     = "LPM Time Remaining";

    public static final String LPM_TIME_SPENT_FIELD_NAME         = "LPM Time Spent";
    public static final String LPM_TIME_BUDGET_N_FIELD_NAME      = "LPM Time Budget(N)";

    public static final String LPM_TIME_BUDGET_MD_FIELD_NAME     = "LPM Time Budget (MD)";
    public static final String LPM_TIME_SPENT_MD_FIELD_NAME      = "LPM Time Spent (MD)";
    public static final String LPM_TIME_REMAINING_MD_FIELD_NAME  = "LPM Time Remaining (MD)";

    private static final Map<String,Long> FIELD_ID_CACHE = new ConcurrentHashMap<>();

    static Long getSourceFieldId(Long targetFieldId) {
        switch (targetFieldId) {
            case LPM_TIME_BUDGET_MD_FIELD_ID() : return LPM_TIME_BUDGET_FIELD_ID()
            case LPM_TIME_SPENT_MD_FIELD_ID() :  return LPM_TIME_SPENT_FIELD_ID()
            case LPM_TIME_REMAINING_MD_FIELD_ID() : return LPM_TIME_REMAINING_FIELD_ID()
            default: return null
        }
    }

    private static Long getIdByName(String fieldName) {
        FIELD_ID_CACHE.computeIfAbsent(fieldName) { name ->
            def cf = CustomFieldUtil.getCustomFieldByName(name)
            if (!cf) {
                throw new IllegalStateException("Custom field not found: $name")
            }
            // cf.getId() returns a String like "customfield_16411"
            String raw = cf.getId().toString()
            // strip off "customfield_" and parse the number
            String numPart = raw.replaceFirst(/^customfield_/, '')
            try {
                return Long.parseLong(numPart)
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Unable to parse numeric ID from '$raw'", e)
            }
        }
    }

    static Long EPIC_TIME_BUDGET_FIELD_ID() {
        return getIdByName(EPIC_TIME_BUDGET_FIELD_NAME);
    }

    static Long FEATURE_TIME_BUDGET_FIELD_ID() {
        return getIdByName(FEATURE_TIME_BUDGET_FIELD_NAME);
    }

    static Long LPM_TIME_BUDGET_FIELD_ID() {
        return getIdByName(LPM_TIME_BUDGET_FIELD_NAME);
    }

    static Long EPIC_TIME_REMAINING_FIELD_ID() {
        return getIdByName(EPIC_TIME_REMAINING_FIELD_NAME);
    }

    static Long FEATURE_TIME_REMAINING_FIELD_ID() {
        return getIdByName(FEATURE_TIME_REMAINING_FIELD_NAME);
    }

    static Long LPM_TIME_REMAINING_FIELD_ID() {
        return getIdByName(LPM_TIME_REMAINING_FIELD_NAME);
    }

    static Long LPM_TIME_SPENT_FIELD_ID() {
        return getIdByName(LPM_TIME_SPENT_FIELD_NAME);
    }

    static Long LPM_TIME_BUDGETN_FIELD_ID() {
        return getIdByName(LPM_TIME_BUDGET_N_FIELD_NAME);
    }

    static Long LPM_TIME_BUDGET_MD_FIELD_ID() {
        return getIdByName(LPM_TIME_BUDGET_MD_FIELD_NAME);
    }

    static Long LPM_TIME_SPENT_MD_FIELD_ID() {
        return getIdByName(LPM_TIME_SPENT_MD_FIELD_NAME);
    }

    static Long LPM_TIME_REMAINING_MD_FIELD_ID() {
        return getIdByName(LPM_TIME_REMAINING_MD_FIELD_NAME);
    }
}

   

