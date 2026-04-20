package utils

import com.atlassian.mail.Email
import com.atlassian.mail.server.MailServerManager
import com.atlassian.mail.server.SMTPMailServer
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.attachment.Attachment
import groovy.text.GStringTemplateEngine
import javax.mail.util.ByteArrayDataSource
import javax.activation.DataSource

/**
 * MailOperations - Utility class for email operations via ScriptRunner
 *
 * Provides methods to send emails, HTML emails, emails to watchers,
 * emails with attachments, and templated emails using Groovy templates.
 */
class MailOperations {

    def mailServerManager = ComponentAccessor.getMailServerManager()
    def watchersManager = ComponentAccessor.watchersManager

    /**
     * Return the default SMTP mail server
     */
    SMTPMailServer getDefaultMailServer() {
        return mailServerManager.getDefaultSMTPMailServer()
    }

    /**
     * Return all configured SMTP mail servers
     */
    List<SMTPMailServer> getAllMailServers() {
        return mailServerManager.getSMTPMailServers()
    }

    /**
     * Send a simple text email
     */
    void sendEmail(String to, String subject, String body, String from = null) {
        SMTPMailServer mailServer = getDefaultMailServer()
        if (!mailServer) {
            throw new IllegalStateException("No default mail server configured")
        }
        Email email = new Email(to)
        if (from) {
            email.setFrom(from)
        }
        email.setSubject(subject)
        email.setBody(body)
        mailServer.send(email)
    }

    /**
     * Send an HTML email
     */
    void sendHtmlEmail(String to, String subject, String htmlBody) {
        SMTPMailServer mailServer = getDefaultMailServer()
        if (!mailServer) {
            throw new IllegalStateException("No default mail server configured")
        }
        Email email = new Email(to)
        email.setMimeType("text/html")
        email.setSubject(subject)
        email.setBody(htmlBody)
        mailServer.send(email)
    }

    /**
     * Send email to all watchers of an issue
     */
    void sendEmailToWatchers(Issue issue, String subject, String body) {
        def watcherNames = watchersManager.getWatchers(issue).collect { it.name }
        if (watcherNames) {
            sendEmail(watcherNames.join(','), subject, body)
        }
    }

    /**
     * Send email with attachments from a Jira issue
     */
    void sendEmailWithAttachments(Issue issue, String to, String subject, String body) {
        SMTPMailServer mailServer = getDefaultMailServer()
        if (!mailServer) {
            throw new IllegalStateException("No default mail server configured")
        }
        Email email = new Email(to)
        email.setSubject(subject)
        email.setBody(body)
        issue.getAttachments().each { Attachment att ->
            DataSource ds = new ByteArrayDataSource(att.getAttachmentStream().bytes, att.getMimetype())
            email.addAttachment(ds, att.getFilename())
        }
        mailServer.send(email)
    }

    /**
     * Send email using GString templates for subject and body
     */
    void sendEmailUsingTemplate(String to, String subjectTemplate, String bodyTemplate, Map<String, Object> model) {
        def engine = new GStringTemplateEngine()
        String subject = engine.createTemplate(subjectTemplate).make(model).toString()
        String body = engine.createTemplate(bodyTemplate).make(model).toString()
        sendEmail(to, subject, body)
    }
}
