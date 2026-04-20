package kvs_audits.audit5

import com.atlassian.jira.issue.Issue
import kvs_audits.audit4.AuditLevel4Handler

class AuditLevel5Handler extends AuditLevel4Handler {

    public AuditLevel5Handler(Issue auditPrepIssue, String kvsProjectKey, String auditLevel) {
        super(auditPrepIssue, auditLevel, kvsProjectKey);
    }

}