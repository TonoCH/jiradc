package kvs_audits.common

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import kvs_audits.KVSLogger
import utils.CustomFieldUtil
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import com.atlassian.mail.Email

/**
 * AuditMailer
 *
 * @author chabrecek.anton
 * Created on 16/05/2025.
 */
class AuditMailer {

    private CustomFieldUtil customFieldUtil = new CustomFieldUtil();
    private KVSLogger logger = new KVSLogger()
    private def userManager = ComponentAccessor.userManager;

    public void sendAuditInviteAsICS(Issue auditIssue, String recipientUsername, LocalDate startDate) {
        def recipient = userManager.getUserByName(recipientUsername)

        if (!recipient?.emailAddress) {
            logger.setWarnMessage("Email address for ${recipientUsername} not found. Skipping .ics invite.")
            return
        }

        def subject = "KVS Audit - ${auditIssue.summary}"
        def location = "Online / On-site"
        def start = startDate.atTime(8, 0)
        def end = start.plusHours(1)

        def formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        def uid = UUID.randomUUID().toString()

        def ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KVS//Audit Calendar//EN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:${uid}
            DTSTAMP:${LocalDateTime.now().format(formatter)}
            DTSTART:${start.format(formatter)}
            DTEND:${end.format(formatter)}
            SUMMARY:${subject}
            LOCATION:${location}
            DESCRIPTION:You have been assigned a KVS audit. Please review the Jira ticket ${auditIssue.key}.
            STATUS:CONFIRMED
            SEQUENCE:0            
            
            BEGIN:VALARM
            TRIGGER:-PT30M
            ACTION:DISPLAY
            DESCRIPTION:Reminder
            END:VALARM

            END:VEVENT
            END:VCALENDAR
            """.stripIndent().trim()

        def mailServer = ComponentAccessor.mailServerManager.defaultSMTPMailServer
        if (!mailServer) {
            logger.setErrorMessage("No SMTP server configured. Cannot send audit invite.")
            return
        }

        def email = new Email(recipient.emailAddress)
        email.setSubject(subject)
        email.setMimeType("text/calendar;method=REQUEST;charset=UTF-8")
        email.setBody(ics)

        mailServer.send(email)
        logger.setInfoMessage("ICS invitation sent to ${recipient.emailAddress} for audit ${auditIssue.key}")
    }

}
