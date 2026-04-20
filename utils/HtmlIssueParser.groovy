package utils

//@Grab('org.jsoup:jsoup:1.16.1')
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import utils.creator.QuestionIssue

/**
 * HtmlIssueParser
 *
 * @author chabrecek.anton
 * Created on 30. 7. 2025.
 */
class HtmlIssueParser {

    List<Map<String, String>> parse(String filePath) {
        Document doc = Jsoup.parse(new File(filePath), 'UTF-8')
        List<String> headers = doc.select('#issuetable thead th').collect { Element th ->
            th.attr('data-id') ?: th.text().trim()
        }

        List<Map<String, String>> rows = []
        doc.select('#issuetable tbody tr').each { Element tr ->
            Map<String, String> row = [:]
            tr.select('td').eachWithIndex { Element td, int idx ->
                if (idx >= headers.size()) return
                String key = headers[idx]
                String value
                if (key == 'summary') {
                    Element clone = td.clone()
                    clone.select('span.parentIssue').remove()
                    value = clone.text().trim()
                } else if (key.startsWith('customfield_')) {
                    List<String> spans = td.select('span').collect { it.text().trim() }.findAll { it }
                    value = spans ? spans.join(',') : td.text().trim()
                } else {
                    value = td.text().trim()
                }
                row[key] = value
            }
            rows << row
        }

        return rows
    }
}
    /*List<Map<String, String>> parse(String filePath) {
        Document doc = Jsoup.parse(new File(filePath), 'UTF-8')
        List<String> headers = doc.select('#issuetable thead th').collect { Element th ->
            th.attr('data-id') ?: th.text().trim()
        }

        List<Map<String, String>> rows = []
        doc.select('#issuetable tbody tr').each { Element tr ->
            Map<String, String> row = [:]
            tr.select('td').eachWithIndex { Element td, int idx ->
                if (idx >= headers.size()) return
                String key = headers[idx]
                String value
                if (key.startsWith('customfield_')) {
                    List<String> spans = td.select('span').collect { it.text().trim() }.findAll { it }
                    value = spans ? spans.join(',') : td.text().trim()
                } else {
                    value = td.text().trim()
                }
                row[key] = value
            }
            rows << row
        }
        return rows
    }
}*/
