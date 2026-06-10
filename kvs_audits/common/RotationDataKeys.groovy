package kvs_audits.common

/**
 * RotationDataKeys
 * @author chabrecek.anton
 * Created on 05/06/2026.
 * Rotation-unit aliases for AuditPreparation.
 *
 * Writes always use the first (canonical) value.
 * Legacy aliases remain supported when reading.
 * Used by Levels 3, 4 and 5.
 */
class RotationDataKeys {

    static final List<String> UNITS_KEYS = ['fas', 'workplaces']
    static final List<String> INDEX_KEYS = ['currentFaIndex', 'currentWorkplaceIndex']

    static List<String> readUnits(def usageObj) {
        String k = UNITS_KEYS.find { usageObj != null && usageObj[it] != null }
        (k ? usageObj[k] : []) as List<String>
    }

    static int readIndex(def usageObj) {
        String k = INDEX_KEYS.find { usageObj != null && usageObj[it] != null }
        ((k ? usageObj[k] : 0) ?: 0) as int
    }

    static void writeUnits(def usageObj, List<String> value) {
        if (usageObj == null) return
        usageObj[UNITS_KEYS[0]] = value
        UNITS_KEYS.drop(1).each { usageObj.remove(it) }
    }

    static void writeIndex(def usageObj, int value) {
        if (usageObj == null) return
        usageObj[INDEX_KEYS[0]] = value
        INDEX_KEYS.drop(1).each { usageObj.remove(it) }
    }
}