package utils.mail_notifiers;

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.mail.MailUtils
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMultipart

import com.atlassian.mail.Email
/**
 * Mailer
 *
 * @author chabrecek.anton
 * Created on 6. 11. 2025.
 */
public class Mailer {

    Mailer() {}
    Mailer(String mimeType) { this.mimeType = mimeType }

    private def userManager = ComponentAccessor.userManager;
    private StringBuilder ErrorMessages = new StringBuilder();
    private String mimeType = "multipart/mixed"; //setting for mails without attachments

    public boolean sendMessage(String recipientUsername, String subject, String message) {
        def recipient = userManager.getUserByName(recipientUsername)

        if (!recipient?.emailAddress) {
            ErrorMessages.append("Email address for ${recipientUsername} not found. Skipping .ics invite.");
            return false;
        }

        def mailServer = ComponentAccessor.mailServerManager.defaultSMTPMailServer
        if (!mailServer) {
            ErrorMessages.append("No SMTP server configured. Cannot send audit invite.");
            return false;
        }

        def email = new Email(recipient.emailAddress)
        email.setSubject(subject)
        email.setBody(message)
        email.setMimeType(mimeType)
        mailServer.send(email)

        return true;
    }

    public boolean sendMessageWithAttachment(String recipientUsername,
                                             String subject,
                                             String message,
                                             List<File> attachments) {
        def recipient = userManager.getUserByName(recipientUsername)
        if (!recipient?.emailAddress) {
            ErrorMessages.append("Email address for ${recipientUsername} not found.")
            return false
        }

        def mailServer = ComponentAccessor.mailServerManager.defaultSMTPMailServer
        if (!mailServer) {
            ErrorMessages.append("No SMTP server configured.")
            return false
        }

        try {
            def email = new Email(recipient.emailAddress)
            email.setSubject(subject)

            def multipart = new MimeMultipart("mixed")

            def textPart = new MimeBodyPart()
            textPart.setText(message ?: "", "UTF-8")
            multipart.addBodyPart(textPart)

            attachments?.each { File f ->
                if (f?.exists()) {
                    def part = MailUtils.createAttachmentMimeBodyPart(f.absolutePath)
                    if (f.name?.toLowerCase()?.endsWith(".csv")) {
                        part.setHeader("Content-Type", "text/csv; charset=UTF-8")
                    }
                    multipart.addBodyPart(part)
                } else {
                    ErrorMessages.append("Attachment ${f?.name} not found. ")
                }
            }

            // Atlassian Email supports multipart payloads
            email.setMultipart(multipart)
            email.setMimeType("multipart/mixed")
            email.setEncoding("UTF-8")

            mailServer.send(email)
            return true
        } catch (Exception e) {
            ErrorMessages.append("sendMessageWithAttachment failed: ${e.class.simpleName}: ${e.message}")
            return false
        }
    }


    StringBuilder getErrorMessages() {
        return ErrorMessages
    }
}
