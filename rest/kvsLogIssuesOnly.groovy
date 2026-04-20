package rest

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.transform.BaseScript
import java.util.Collections;
import java.util.List;

import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import kvs_audits.KVSLogger

@BaseScript CustomEndpointDelegate delegate

kvsLogsIssuesOnly(httpMethod: "GET", groups: ["jira-administrators", "kvs-audit-admins"])
{ MultivaluedMap queryParams ->

    KVSLogger logger = new KVSLogger(true);
    def sb = new StringBuilder();
    
    sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><title>Dedicated Resource Model</title><style>body {font-family: Arial, sans-serif; font-size: 14px; margin: 0; padding: 20px; background-color: #f9f9f9;} .container {max-width: 1200px; margin: auto; background: white; padding: 20px; box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1); border-radius: 8px;} .title {font-size: 24px; font-weight: bold; margin-bottom: 20px; color: #333; text-align: center;} .log-item {display: flex; align-items: center; padding: 4px 8px; margin-bottom: 5px; border-radius: 4px;} .log-item.info {background-color: #eaf4fe; border-left: 5px solid #007bff;} .log-item.warn {background-color: #fff9e5; border-left: 5px solid #ffc107;} .log-item.error {background-color: #ffe5e5; border-left: 5px solid #dc3545;} .log-item .log-level {font-weight: bold; margin-right: 8px;} .log-item .timestamp {color: #555; margin-right: 8px; font-family: monospace;} .log-item .message {color: #333;} .mixed {background-color: orange;} </style></head><body><div class=\"container\">");
    sb.append("<div class=\"title\">KVS Audit Logs issues only</div>")

    List<String> lines = logger.getAllLogsIssuesOnly();
    Collections.reverse(lines); 
    
String multiLine = ""
boolean isMultiLine = false
String logLevel = ""
String timestamp = ""
boolean logLevelMismatch = false

lines.each { line ->
    def currentLogLevel = ""
    def currentTimestamp = ""
    def message = ""

    // Detect log level
    if (line.contains("INFO")) {
        currentLogLevel = "info"
    } else if (line.contains("WARN")) {
        currentLogLevel = "warn"
    } else if (line.contains("ERROR")) {
        currentLogLevel = "error"
    }

    def parts = line.split(" ", 3) // Split into timestamp, log level, and message
    if (parts.size() >= 3) {
        currentTimestamp = parts[0] + " " + parts[1]
        message = parts[2]
            .replaceAll("\\[.*?\\]", "")
            //.replace("https-jsse-nio-8443-exec-", "")
            .replace(" " + currentLogLevel.toUpperCase(), "")
            .replace("jobs.kvs_logs - ", "")
            .replaceAll("\\s{2,}", " ")
        
    }

    // Handle multi-line accumulation
    if (line.trim().endsWith("+")) {
        if (!isMultiLine) {
            // First line in the multi-line block
            logLevel = currentLogLevel
            timestamp = currentTimestamp
        } else if (currentLogLevel != logLevel) {
            // Detected log level mismatch in multi-line block
            logLevelMismatch = true
        }

        // Remove '+' and accumulate message
        multiLine += "[${currentLogLevel.toUpperCase()}] ${message.replaceAll("\\+\$", "")}<br />"
        isMultiLine = true
    } else {
        if (isMultiLine) {
            // Final line of multi-line message, check log level consistency
            if (currentLogLevel != logLevel) {
                logLevelMismatch = true
            }

            multiLine += "[${currentLogLevel.toUpperCase()}] ${message}"

            if (logLevelMismatch) {
                // If log levels don't match, show all messages with their levels
                sb.append("<div class=\"log-item mixed\">")
                sb.append("<span class=\"log-level\">MIXED</span>")
                sb.append("<span class=\"timestamp\">${timestamp}</span>")
                sb.append("<span class=\"message\">${multiLine.trim()}</span>")
                sb.append("</div>\n")
            } else {
                // If log levels match, show normally
                //sb.append("<div class=\"log-item ${logLevel}\">")
                sb.append("<div class=\"log-item mixed\">")
                sb.append("<span class=\"log-level\">${logLevel.toUpperCase()}</span>")
                sb.append("<span class=\"timestamp\">${timestamp}</span>")
                sb.append("<span class=\"message\">${multiLine.trim()}</span>")
                sb.append("</div>\n")
            }

            // Reset multi-line state
            multiLine = ""
            isMultiLine = false
            logLevelMismatch = false
        } else {
            // Single line message
            sb.append("<div class=\"log-item ${currentLogLevel}\">")
            sb.append("<span class=\"log-level\">${currentLogLevel.toUpperCase()}</span>")
            sb.append("<span class=\"timestamp\">${currentTimestamp}</span>")
            sb.append("<span class=\"message\">${message}</span>")
            sb.append("</div>\n")
        }
    }
}




    //sb.append("</ul>\n")
    //sb.append("</div>\n")
    sb.append("</div></body></html>")
    

    Response.ok().type(MediaType.TEXT_HTML).entity(sb.toString()).build()
}