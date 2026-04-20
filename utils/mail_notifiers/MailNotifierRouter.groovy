package utils.mail_notifiers

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.properties.APKeys
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.user.ApplicationUser
import utils.MyBaseUtil

import java.time.*
import java.time.temporal.ChronoUnit

/**
 * MailNotifierRouter
 *
 * @author chabrecek.anton
 * Created on 6. 11. 2025.
 * Router for projects
 */
class MailNotifierRouter {
    public static String Info = "Info"
    public static String Warning = "Warning"//"Warning"
    public static String Error = "Error"//"Error"

    // --- config keys
    static final String ConfigIssueType = "issueType"
    static final String ConfigIssueValue = "all"
    static final String ConfigFieldName = "fieldName"
    static final String ConfigRule = "rule"
    static final String ConfigInterval = "intervalDays"
    static final String ConfigNotify = "notify"
    static final String ConfigJql = "jql"
    static final String ConfigTemplateKey = "templateKey"

    // --- rule keys
    static final String RuleDueModulo = "due_modulo"       // TargetDate <= today && days%interval==0

    static final String RuleNoWorklog = "no_worklog"
    static final String RuleStalled = "status_stalled"

    // --- templates
    private static final Map<String, String> SUBJECTS = [
            "overdue": "Jira overdue issue notice",
            "generic": "Jira issue notification"
    ]
    private static final Map<String, String> BODIES = [
            "overdue": """Dear Jira user,<br/><br/>
        One or more of your issues are overdue.<br/>
        Please review the issue details below and take the necessary action.<br/><br/>
        """,

            "generic": """Dear Jira user,<br/><br/>
        You have received a Jira issue notification.<br/><br/>
        """
    ]


    static final HashMap<String, HashMap<String, Object>> projectConfig = new HashMap<>()
    private static final Map<String, Closure<Boolean>> RULES = new HashMap<>()
    private final LinkedHashMap<String, List<String>> messages = new HashMap<>()

    MailNotifierRouter() {
        HashMap configForQS = new HashMap<String, Object>() {
            {
                put(ConfigIssueType, ConfigIssueValue)
                put(ConfigFieldName, "Target end")
                put(ConfigRule, RuleDueModulo)
                put(ConfigInterval, 14)
                put(ConfigNotify, ["reporter", "assignee"])
                put(ConfigTemplateKey, "overdue")
                // put(ConfigJql,      "...") // special jql for project not implemented
            }
        }

        projectConfig.put("QPL", new HashMap<>(configForQS))
        projectConfig.put("EVB", new HashMap<>(configForQS))
        projectConfig.put("QLAB", new HashMap<>(configForQS))
        projectConfig.put("MT", new HashMap<>(configForQS))
        //projectConfig.put("ESD", new HashMap<>(configForQS))
        projectConfig.put("IA", new HashMap<>(configForQS))
        projectConfig.put("ANTONSCRUM", new HashMap<>(configForQS))

        RULES.put(RuleDueModulo, { Issue issue, Map cfg, LocalDate today ->
            String fieldName = (String) cfg.get(ConfigFieldName)
            Integer interval = (cfg.get(ConfigInterval) instanceof Number) ? ((Number) cfg.get(ConfigInterval)).intValue() : 14
            LocalDate target = getLocalDateByFieldName(issue, fieldName)
            if (target == null){
                return false
            }

            if (!target.isBefore(today)) {
                return false
            }

            if (target.isEqual(today.minusDays(1))){
                return true;
            }

            long days = ChronoUnit.DAYS.between(target, today)
            return interval > 0 && (days % interval == 0L)
        })

        // RULES.put(RuleNoWorklog, { Issue i, Map cfg, LocalDate t -> ... })
        // RULES.put(RuleStalled,   { Issue i, Map cfg, LocalDate t -> ... })
    }

    // return messages for log (Info/Warning/Error)
    LinkedHashMap<String, List<String>> handleAndReturnMessages() {
        def projects = new ArrayList<>(projectConfig.keySet())

        if (projects.isEmpty()) {
            addMessage(Warning, "No configured projects.")

            return messages
        }

        String inList = projects.collect { "\"${it}\"" }.join(",")
        String defaultJql = "project in (${inList}) AND resolution = Unresolved ORDER BY priority DESC, updated DESC"

        List<Issue> issues = getIssues(defaultJql)
        if (issues.isEmpty()) {
            addMessage(Info, "No issues to check for ${projects}.")

            return messages
        }

        LocalDate today = LocalDate.now(ZoneId.systemDefault())

        int checked = 0
        int eligible = 0
        int sent = 0

        // for check duplicity so same user doésnt receive mail twice (issueKey@username)
        Set<String> duplicityList = new HashSet<>()

        for (Issue issue : issues) {
            checked++

            String pKey = issue.getProjectObject()?.getKey()
            Map cfg = projectConfig.get(pKey)
            if (cfg == null) {
                addMessage(Warning, "Warnings: For ${pKey} missing config.")

                continue
            }

            String ruleKey = (String) cfg.get(ConfigRule)
            def rule = RULES.get(ruleKey)
            if (rule == null) {
                addMessage(Warning, "Warning: Rule for ${pKey} and issue: ${issue} is null")

                continue
            }

            boolean shouldNotify = false
            try {
                shouldNotify = rule.call(issue, cfg, today)
            } catch (Throwable t) {
                addMessage(Error, "Error: For ${issue} shouldNotify throw ${t.toString()}")

                continue
            }
            if (!shouldNotify) {
                addMessage(Info, "Warning: For ${pKey} ans issue: ${issue} rule.call(issue, cfg, today) return false so no notify.")

                continue;
            }

            eligible++

            String templateKey = (String) cfg.getOrDefault(ConfigTemplateKey, "generic")
            String subject = SUBJECTS.getOrDefault(templateKey, SUBJECTS.get("generic"))
            String bodyPrefix = BODIES.getOrDefault(templateKey, BODIES.get("generic"))
            String link = buildIssueLink(issue)
            String moreData = getMoreData(issue);
            String mailBody = bodyPrefix + moreData + link

            List<String> targets = (cfg.get(ConfigNotify) instanceof Collection) ? (List<String>) cfg.get(ConfigNotify) : ["reporter"]

            // reporter / assignee
            for (String who : targets) {
                String username = recipientUsername(issue, who)

                if (username == null) {
                    addMessage(Warning, "Warnings: For ${issue} username is null ${who}")
                    continue
                }

                String key = issue.getKey() + "@" + username
                if (duplicityList.add(key)) {
                    if(sendMessage(username, subject, mailBody))
                        sent++
                }
            }
        }

        addMessage(Info, "Checked: ${checked}, Eligible: ${eligible}, Emails sent: ${sent}")

        return messages
    }

    private void addMessage(String type, String text){
        messages.computeIfAbsent(type, k -> new ArrayList<>()).add(text);
    }

    // ----------------- helpers -----------------

    private static String buildIssueLink(Issue issue) {
        def baseUrl = ComponentAccessor.applicationProperties.getString(APKeys.JIRA_BASEURL)
        return """<a href="${baseUrl}/browse/${issue.key}">link</a>"""
    }

    private static LocalDate toLocalDate(Date d) {
        return d?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDate()
    }

    private static LocalDate getLocalDateByFieldName(Issue issue, String fieldName) {
        CustomFieldManager cfm = ComponentAccessor.customFieldManager
        def cf = cfm.getCustomFieldObjects(issue).find { it.name == fieldName }
        if (cf) {
            def v = issue.getCustomFieldValue(cf)
            if (v instanceof Date) return toLocalDate((Date) v)
            if (v instanceof java.sql.Timestamp) return toLocalDate(new Date(v.time))
        }

        if ("Due Date".equalsIgnoreCase(fieldName) || "duedate".equalsIgnoreCase(fieldName)) {
            def d = issue.getDueDate()
            if (d != null) return toLocalDate(d)
        }
        return null
    }

    private static String recipientUsername(Issue issue, String who) {
        ApplicationUser u = null
        switch (who?.toLowerCase()) {
            case "reporter":
                u = issue.getReporter()
                break
            case "assignee":
                u = issue.getAssignee()
                break
        }
        return usernameOf(u)
    }

    private static String usernameOf(ApplicationUser u) {
        if (u == null) return null
        try {
            return u.getName()
        } catch (MissingMethodException ignore) {
            try {
                return u.getUsername()
            } catch (MissingMethodException ignore2) {
                return null
            }
        }
    }

    private List<Issue> getIssues(String jqlQuery) {
        try {
            return new MyBaseUtil().findIssues(jqlQuery)
        } catch (IllegalArgumentException ignored) {
            return new ArrayList<Issue>()
        }
    }

    private boolean sendMessage(String recipientUsername, String subject, String message) {
        Mailer mailer = new Mailer("text/html")
        boolean status = mailer.sendMessage(recipientUsername, subject, message)
        if (!status) addMessage(Error, String.valueOf(mailer.errorMessages))

        return status;
    }

    void addProjectConfig(String projectKey, Map<String, Object> cfg) {
        projectConfig.put(projectKey, new HashMap<>(cfg))
    }

    String getMoreData(Issue issue) {
        return """
                <table style="border-collapse: collapse; margin-top: 10px;">
                    <tr>
                        <td style="padding: 4px 8px; font-weight: bold;">Project:</td>
                        <td style="padding: 4px 8px;">${issue.projectObject.name}</td>
                    </tr>
                    <tr>
                        <td style="padding: 4px 8px; font-weight: bold;">Summary:</td>
                        <td style="padding: 4px 8px;">${issue.summary}</td>
                    </tr>
                </table>
                <br/>
            """
    }
}