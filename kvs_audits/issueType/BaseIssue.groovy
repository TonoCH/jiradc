package kvs_audits.issueType

import com.atlassian.jira.event.type.EventDispatchOption
import utils.CustomFieldUtil
import utils.MyBaseUtil
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.component.ComponentAccessor;
import kvs_audits.KVSLogger
import com.atlassian.jira.issue.MutableIssue

/**
 * BaseIssue
 *
 * @author chabrecek.anton
 * Created on 14/03/2025.
 */
public class BaseIssue {
    protected Issue issue;
    protected MyBaseUtil myBaseUtil;
    protected CustomFieldUtil customFieldUtil;
    protected def loggedInUser;
    protected KVSLogger logger;

    public static final String KVS_PC_SUB_AREA_FIELD_NAME = "KVS PC Sub-Area" //Select List (single choice)
    public static final String KVS_PC_SUB_AREA_FIELD_ID = CustomFieldUtil.getCustomFieldByName(KVS_PC_SUB_AREA_FIELD_NAME)?.getId()

    public static final String AUDITORS_EXTERNAL_FIELD_NAME = "Auditors External" //User Picker (multiple users)
    public static final String AUDITORS_EXTERNAL_FIELD_ID = CustomFieldUtil.getCustomFieldByName(AUDITORS_EXTERNAL_FIELD_NAME)?.getId()

    public static final String KVS_SETTING = "KVS Setting" //User Picker (multiple users)

    public BaseIssue(Issue issue) {
        this.issue = issue;
        this.myBaseUtil = new MyBaseUtil();
        this.customFieldUtil = new CustomFieldUtil();
        this.loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
        this.logger = new KVSLogger();
    }

    public Issue getIssue() {
        return issue
    }

    public MutableIssue getMutableIssue(){
        return ComponentAccessor.issueManager.getIssueObject(issue.id)
    }

    void commitIssueUpdate(EventDispatchOption dispatchOption = EventDispatchOption.ISSUE_UPDATED) {
        ComponentAccessor.issueManager.updateIssue(loggedInUser, issue, dispatchOption, false)
    }
}
