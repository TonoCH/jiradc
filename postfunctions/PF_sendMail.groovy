import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.mail.Email
import com.atlassian.jira.mail.settings.MailSettings
import com.atlassian.mail.MailException
import com.atlassian.mail.server.SMTPMailServer
import com.atlassian.plugin.util.ContextClassLoaderSwitchingUtil
import org.apache.log4j.Level
import org.apache.log4j.Logger

String sendEmail(String emailAddr, String subject, String body) {
    def logger = Logger.getLogger(getClass())
    logger.setLevel(Level.DEBUG)

    // Stop emails being sent if the outgoing mail server gets disabled (useful if you start a script sending emails and need to stop it)
    def mailSettings = ComponentAccessor.getComponent(MailSettings)
    if (mailSettings?.send()?.disabled) {
        return 'Your outgoing mail server has been disabled'
    }

    def mailServer = ComponentAccessor.mailServerManager.defaultSMTPMailServer
    if (!mailServer) {
        logger.debug('Your mail server Object is Null, make sure to set the SMTP Mail Server Settings Correctly on your Server')
        return 'Failed to Send Mail. No SMTP Mail Server Defined'
    }

    def email = new Email(emailAddr)
    email.setMimeType('text/html')
    email.setSubject(subject)
    email.setBody(body)
    try {
        // This is needed to avoid the exception about IMAPProvider
        ContextClassLoaderSwitchingUtil.runInContext(SMTPMailServer.classLoader) {
            mailServer.send(email)
        }
        logger.debug('Mail sent')
        'Success'
    } catch (MailException e) {
        logger.debug("Send mail failed with error: ${e.message}")
        'Failed to Send Mail, Check Logs for error'
    }
}

sendEmail('medveczky.ladislav@scheidt-bachmann.sk','[jira] groovy script to send email', 'hi laci :)')