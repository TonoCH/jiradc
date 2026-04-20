package jobs

import utils.mail_notifiers.MailNotifierRouter

/**
 * notifierRouter
 *
 * @author chabrecek.anton
 * Created on 6. 11. 2025.
 */

LinkedHashMap<String, List<String>> messages = new MailNotifierRouter().handleAndReturnMessages()

messages.each { key, listOfMessages ->
    listOfMessages.forEach { value ->
        if (key == MailNotifierRouter.Error) {
            log.error(value)
        } else if (key == MailNotifierRouter.Warning) {
            log.warn(value)
        } else if (key == MailNotifierRouter.Info) {
            log.info(value)
        }
    }
}
