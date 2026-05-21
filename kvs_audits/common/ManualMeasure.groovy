package kvs_audits.common;

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.user.ApplicationUser;
import kvs_audits.KVSLogger;
import utils.CustomFieldUtil;
import utils.MyBaseUtil;

/**
 * ManualMeasure
 *
 * @author chabrecek.anton
 * Created on 19. 5. 2026.
 */
public class ManualMeasure {
    protected KVSLogger logger = new KVSLogger();
    protected CustomFieldUtil customFieldUtil = new CustomFieldUtil();
    protected MyBaseUtil myBaseUtil = new MyBaseUtil();
    protected CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager();
    protected ApplicationUser runAs = ComponentAccessor.userManager.getUserByKey("jira.bot")

    public void buildNew(MutableIssue eventIssue) {
        if (!eventIssue || eventIssue.issueType?.name != CustomFieldsConstants.AUDIT) {
            return
        }

        //create new Audit with parameters set on this issue question below will be subtask of this Audit

        //create Question same as select by user set on this issue will be linked to measure below

        //create Measure with parameters set on this issue above question as relates to links to this measure

        // check if all was created and set/ report in by logger than delete this issue
        new CommonHelper().deleteIssue(runAs, eventIssue)
    }


}
