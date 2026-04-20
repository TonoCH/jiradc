package jobs.global

/**
 * projectInformationListUpdate
 *
 * @author chabrecek.anton
 * Created on 4. 2. 2026.
 * DB is source of it, field just act as mirror
 */
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.fields.config.FieldConfig
import com.atlassian.jira.issue.customfields.manager.OptionsManager
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.customfields.option.Options;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme
import com.onresolve.scriptrunner.db.DatabaseUtil
import org.apache.log4j.Logger

// ====== CONFIG ======
final String CF_NAME     = "Project Information n"
final String CF_ID    =   "customfield_18702"
final String DB_RESOURCE = "Project Information 2"

final String SQL_QUERY = '''
    select distinct "PROJECT_INFORMATION" as val
    from public."AO_601478_COST_CENTRE"
    where "PROJECT_INFORMATION" is not null
    order by 1
'''

// Safety limits (optional)
final int MAX_DB_VALUES = 50000
final int MAX_VALUE_LEN = 255
// ====================

def log = Logger.getLogger("scriptrunner.jobs.project-info-sync")

def customFieldManager = ComponentAccessor.getCustomFieldManager()
OptionsManager optionsManager = ComponentAccessor.getOptionsManager()
CustomField cf = customFieldManager.getCustomFieldObject(CF_ID)?.find()

if (!cf) {
    log.error("Custom field not found registere name and id is: '${CF_NAME} - ${CF_ID}'")
    return
}


Set<String> dbValues = new LinkedHashSet<>()

DatabaseUtil.withSql(DB_RESOURCE) { sql ->
    sql.rows(SQL_QUERY).each { row ->
        def raw = row?.val
        if (raw == null) return

        String v = raw.toString().trim()
        if (!v) return

        if (v.length() > MAX_VALUE_LEN) {
            v = v.substring(0, MAX_VALUE_LEN)
        }

        dbValues.add(v)
        if (dbValues.size() >= MAX_DB_VALUES) return
    }
}
if (!dbValues) {
    log.warn("DB returned 0 values. Nothing to sync.")
    return
}

log.warn("Loaded ${dbValues.size()} distinct DB values for '${CF_NAME}'")

def schemes = (cf.getConfigurationSchemes() ?: [])
def configs = schemes
        .collectMany { it?.getConfigs()?.values() ?: [] }
        .findAll { it != null }
        .unique(false) { it.id }

if (!configs) {
    log.error("No FieldConfig found for custom field '${CF_NAME}'. Check field contexts.")
    return
}

log.warn("Found FieldConfigs: ${configs*.id.join(', ')}")

int totalAdded = 0

configs.each { fc ->
    def existing = optionsManager.getOptions(fc)
            .collect { it?.value }
            .findAll { it != null }
            .collect { it.toString().trim() }
            .toSet()

    def missing = dbValues.findAll { !existing.contains(it) }

    missing.each { v ->
        optionsManager.createOption(fc, null, null, v)
        totalAdded++
    }

    Options refreshed = optionsManager.getOptions(fc)

    refreshed.each { opt ->
        def value = opt?.value?.toString()
        if (value == null) return

        if (dbValues.contains(value)) {
            if (opt.getDisabled()) {
                optionsManager.enableOption(opt)
                log.warn(opt+" was enabled");
            }
        } else {
            if (!opt.getDisabled()) {
                optionsManager.disableOption(opt)
                log.warn(opt+" was disabled");
            }
        }
    }

    def sorted = refreshed.sort { (it?.value ?: "") as String }
    sorted.eachWithIndex { opt, i -> opt.setSequence(i as long) }
    optionsManager.updateOptions(sorted)

    log.warn("Config '${fc.id}': added ${missing.size()} new")
}

log.warn("DONE. Total added: ${totalAdded}")