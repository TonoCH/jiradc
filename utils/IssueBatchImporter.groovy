package utils

import org.apache.log4j.Logger
import utils.creator.BaseIssueCreator
import utils.creator.FunctionalAreaIssue
import utils.creator.ProfitCenterIssue
import utils.creator.QuestionIssue
import utils.creator.WorkplaceIssue

/**
 * IssueBatchImporter
 *
 * @author chabrecek.anton
 * Created on 7. 7. 2025.
 */

class IssueBatchImporter {
    def log = Logger.getLogger(IssueBatchImporter)
    static final String SEP = '^'

    /**
     * Import questions from a CSV file using Groovy’s CsvParser.
     */
    /*void importFromCsvFile(String filePath, String destProj, String parentKey) {
        File file = new File(filePath)
        if (!file.exists()) {
            log.error("CSV file does not exist: $filePath"); return
        }

        // parse entire CSV into list of maps (first line = header)
        CsvParser parser = new CsvParser()
        List<Map<String,String>> rows = parser.parse(
                file,                   // CSV file
                'UTF-8',         // charset
                true,          // first line as header
                SEP as char             // separator
        )

        if (!rows) {
            log.error("CSV is empty or only header present"); return
        }

        List<String> headers = parser.headers

        // prepare allowed/must headers
        def creator   = new QuestionIssue(destProj, parentKey)
        Set<String> mandatory = creator.getMandatoryHeaders()
        Set<String> optional  = creator.getOptionalHeaders()
        Set<String> allowed   = mandatory + optional

        // sanity-check header row
        def missingMand = mandatory - headers
        if (missingMand) {

            log.error("CSV missing mandatory columns: $missingMand");
            log.error("Headers: $headers")

            return
        }
        def ignored = headers - allowed
        if (ignored) log.warn("CSV ignoring these extra columns: $ignored")
        log.info("Importing columns: ${headers.intersect(allowed)}")

        // now iterate data rows
        rows.eachWithIndex { Map<String,String> row, int idx ->
            int rowNum = idx + 2  // +2 because idx=0 is second line
            // filter only allowed keys
            def data = row.findAll { key, _ -> allowed.contains(key) }

            if (!data['Summary']) {
                log.warn("Skipped row $rowNum: missing Summary - data contains:$data")
                return
            }

            try {
                def issue = creator.createIssue(data)
                log.info("[$rowNum] OK: ${issue.key}")
            } catch (Exception e) {
                log.error("[$rowNum] NOK: ${e.message}", e)
            }
        }
    }*/

    /*
    void importFromHtmlFile(String filePath, String destProj, String parentKey) {
        File f = new File(filePath)
        if (!f.exists()) {
            log.error("HTML file not found: $filePath"); return
        }

        def parser = new HtmlIssueParser()
        List<Map<String, String>> rows = parser.parse(filePath)
        if (!rows) {
            log.error("No data rows parsed from HTML"); return
        }

        def creator = new QuestionIssue(destProj, parentKey)
        Set<String> mandatory = creator.getMandatoryHeaders()
        Set<String> optional = creator.getOptionalHeaders()
        Set<String> allowed = mandatory + optional

        def headers = rows[0].keySet()
        def missing = mandatory - headers
        if (missing) {
            log.error("HTML missing mandatory columns: $missing - data contains: $headers"); return
        }

        rows.eachWithIndex { Map<String, String> data, int idx ->
            log.error("DATA contains: $data")
            int rowNum = idx + 2
            def filtered = data.findAll { k, _ -> allowed.contains(k) }
            log.error("filtered contains: $filtered")
            if (!filtered['summary'] && !filtered['Summary']) {
                log.warn("Skipped row $rowNum: missing Summary"); return
            }
            try {
                def issue = creator.createIssue(filtered)
                log.info("[$rowNum] OK: ${issue.key}")
            } catch (Exception e) {
                log.error("[$rowNum] NOK: ${e.message}", e)
            }
        }
    }
    */

    void importFromHtmlFile(String filePath, String destProj, String parentKey) {
        File f = new File(filePath)
        if (!f.exists()) {
            log.error("HTML file not found: $filePath"); return
        }

        def parser = new HtmlIssueParser()
        List<Map<String, String>> rows = parser.parse(filePath)
        if (!rows) {
            log.error("No data rows parsed from HTML"); return
        }

        log.warn("importFromHtmlFile 14")

        rows.eachWithIndex { Map<String, String> data, int idx ->
            int rowNum = idx + 2
            log.debug("RAW DATA [$rowNum]: $data")

            String type = data['issuetype']
            BaseIssueCreator creator
            log.warn("issue type is:${type}")
            switch(type) {
                case 'Profit Center':
                    creator = new ProfitCenterIssue(destProj)  //no parent this is on Epic level
                    break
                case 'Functional Areas':
                    creator = new FunctionalAreaIssue(destProj, data['Profit Center'])
                    break
                case 'Workplace':
                    // workplace je sub-task pod story, parentKey = data['parentStoryKey']
                    creator = new WorkplaceIssue(destProj, data['parentStoryKey'])
                    break
                case 'Question':
                    creator = new QuestionIssue(destProj, parentKey)
                    break
                default:
                    log.warn("Row $rowNum: unknown issuetype '$type', skipping")
                    return
            }

            Set<String> mandatory = creator.getMandatoryHeaders()
            Set<String> optional  = creator.getOptionalHeaders()
            Set<String> allowed   = mandatory + optional

            // 3) Filter a sanity‐check
            def filtered = data.findAll { k, _ -> allowed.contains(k) }
            if (!filtered['summary'] && !filtered['Summary']) {
                log.warn("Skipped row $rowNum: missing summary")
                return
            }

            try {
                def issue = creator.createIssue(filtered)
                log.info("[$rowNum] OK: ${issue.key}")
            } catch (Exception e) {
                log.error("[$rowNum] NOK: ${e.message}", e)
            }
        }
    }
}
