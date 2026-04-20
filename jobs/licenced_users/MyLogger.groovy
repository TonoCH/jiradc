/**
 * MyLogger
 *
 * @author chabrecek.anton
 * Created on 26. 6. 2023.
 */

package jobs.licenced_users

import java.nio.charset.StandardCharsets
import org.apache.log4j.FileAppender
import org.apache.log4j.Logger
import java.nio.file.Files
import java.nio.file.Paths

class MyLogger {
    private Logger logger
    static final String LOG_DIR_PATH = "/mnt/jira_shared_home/log/custom-logs/"//"/home/custom-logs/"
    static final String LOG_FILE_NAME = "myLogfile.log"
    static final String LOGGER_NAME = "jobs.licenced_users"

    MyLogger() {
        this.logger = Logger.getLogger(LOGGER_NAME)

        // Create custom log directory if it doesn't exist
        if (!Files.exists(Paths.get(LOG_DIR_PATH))) {
            try {
                Files.createDirectories(Paths.get(LOG_DIR_PATH))
                logger.info("Custom log directory created: ${LOG_DIR_PATH}")
            } catch (Exception e) {
                logger.error("Failed to create custom log directory: ${LOG_DIR_PATH}")
            }
        }
    }

    void setMessage(String message) {
        logger.info(message)
    }

    List<String> getAllLogs() {
        String logFilePath = LOG_DIR_PATH + LOG_FILE_NAME
        try {
            if (Files.exists(Paths.get(logFilePath))) {
                List<String> lines = Files.readAllLines(Paths.get(logFilePath), StandardCharsets.UTF_8)

                return lines;
                
            } else {
                println("Log file does not exist: ${logFilePath}")
            }
        } catch (Exception e) {
            println("Failed to read log file: ${e.message}")
        }
    }
}