package utils

import java.nio.charset.StandardCharsets
import org.apache.log4j.Logger
import java.nio.file.Files
import java.nio.file.Paths

/**
 * OurLoggers
 *
 * @author chabrecek.anton
 * Created on 20. 1. 2026.
 */
public class OurLoggers {

    private static final String LOG_DIR_PATH = "/mnt/jira_shared_home/log/custom-logs/";
    private static final String LOG_FILE_NAME = "ourLogs.log";
    private boolean wasErrorLogged = false;

    private final Logger systemLogger = Logger.getLogger(OurLoggers.class)
    private Logger logger;

    //list of loogers
    public static String PROJECT_INFORMATION_LOGGER = "action.project_information_change";

    OurLoggers(String LOGGER_NAME) {
        if (!isAllowedLoggerName(LOGGER_NAME)) {
            throw new IllegalArgumentException(
                    "Logger name '" + LOGGER_NAME + "' is not defined in OurLoggers class"
            );
        }

        this.logger = Logger.getLogger(LOGGER_NAME)

        ensureLogDirExists();
    }

    private static boolean isAllowedLoggerName(String loggerName) {
        if (loggerName == null || loggerName.trim().isEmpty()) {
            return false;
        }

        for (java.lang.reflect.Field field : OurLoggers.class.getDeclaredFields()) {
            if (field.getType() == String.class &&
                    java.lang.reflect.Modifier.isStatic(field.getModifiers())) {

                try {
                    Object value = field.get(null);
                    if (loggerName.equals(value)) {
                        return true;
                    }
                } catch (IllegalAccessException ignored) {}
            }
        }
        return false;
    }

    private void ensureLogDirExists() {
        try {
            if (!Files.exists(Paths.get(LOG_DIR_PATH))) {
                Files.createDirectories(Paths.get(LOG_DIR_PATH));
                systemLogger.info("Custom log directory created: " + LOG_DIR_PATH);
            }
        } catch (Exception e) {
            systemLogger.error("Failed to create custom log directory: " + LOG_DIR_PATH, e);
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
}