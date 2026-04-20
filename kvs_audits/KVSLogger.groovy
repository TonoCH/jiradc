/**
 * KVSLogger
 *
 * @author chabrecek.anton
 * Created on 20. 1. 2025
 */
package kvs_audits

import java.nio.charset.StandardCharsets
import org.apache.log4j.Logger
import java.nio.file.Files
import java.nio.file.Paths

class KVSLogger {
    private final Logger systemLogger = Logger.getLogger(KVSLogger.class)
    private Logger logger

    static final String LOG_DIR_PATH = "/mnt/jira_shared_home/log/custom-logs/"
    static final String LOG_FILE_NAME = "kvsLogfile.log"
    static final String LOGGER_NAME = "jobs.kvs_logs"

    static final String LOG_FILE_NAME_ISSUES_ONLY = "kvsLogfile_issuesOnly.log"
    static final String LOGGER_NAME_ISSUES_ONLY = "jobs.kvs_logs_issues_only"

    private boolean wasErrorLogged = false;

    KVSLogger() {
        this.logger = Logger.getLogger(LOGGER_NAME)

        if (!Files.exists(Paths.get(LOG_DIR_PATH))) {
            try {
                Files.createDirectories(Paths.get(LOG_DIR_PATH))
                logger.info("Custom log directory created: ${LOG_DIR_PATH}")
            } catch (Exception e) {
                logger.error("Failed to create custom log directory: ${LOG_DIR_PATH}")
            }
        }
    }

    KVSLogger(boolean issues_only) {
        if(!issues_only) {
            new KVSLogger();
        }
        else{
            this.logger = Logger.getLogger(LOGGER_NAME_ISSUES_ONLY)

            if (!Files.exists(Paths.get(LOG_DIR_PATH))) {
                try {
                    Files.createDirectories(Paths.get(LOG_DIR_PATH))
                    logger.info("Custom log directory created: ${LOG_DIR_PATH}")
                } catch (Exception e) {
                    logger.error("Failed to create custom log directory: ${LOG_DIR_PATH}")
                }
            }
        }
    }

    void setInfoMessage(String message) {
        logger.info(message)
        systemLogger.info(message)
    }

    void setWarnMessage(String message) {
        logger.warn(message)
        systemLogger.warn(message)
    }

    void setErrorMessage(String message) {
        logger.error(message)
        systemLogger.error(message)

        wasErrorLogged = true;
    }

    private List<String> getLogs(String logFileName) {
        def logFilePath = Paths.get(LOG_DIR_PATH, logFileName)

        if (!Files.exists(logFilePath)) {
            logger.warn("Log file does not exist: ${logFilePath}")
            systemLogger.error("Log file does not exist: ${logFilePath}")
            return []
        }

        try {
            return Files.readAllLines(logFilePath, StandardCharsets.UTF_8)
        } catch (Exception e) {
            logger.error("Failed to read log file: ${logFilePath}", e)
            systemLogger.error("Failed to read log file: ${logFilePath}", e)
            return []
        }
    }

    public List<String> getAllLogs() {
        getLogs(LOG_FILE_NAME);
    }
    public List<String> getAllLogsIssuesOnly() {
        getLogs(LOG_FILE_NAME_ISSUES_ONLY);
    }

    public boolean getWasErrorLogged() {
        return wasErrorLogged
    }
}