/**
 * A script that releases groups that take up a license for users who have been logged in for
 * more than INACTIVITY_MONTHS months and have never logged in, or have not logged in for INACTIVITY_MONTHS
 *
 * @author chabrecek.anton
 * Created on 26. 6. 2023.
 * Updated on 11. 3. 2024.
 */

package jobs.licenced_users

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.user.util.UserUtil
import com.atlassian.jira.user.util.UserManager
import com.atlassian.jira.application.ApplicationAuthorizationService
import com.atlassian.jira.application.ApplicationKeys
import com.atlassian.jira.security.login.LoginManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import jobs.licenced_users.MyLogger
import com.atlassian.jira.bc.user.search.UserSearchParams
import com.atlassian.jira.bc.user.search.UserSearchService


handleLicencedUsers();

public String handleLicencedUsers() {
    final int DAYS_NEVER_LOGGED_NEW_USERS = 90
    final int INACTIVITY_DAYS_FOR_USERS = 180

    UserUtil userUtil = ComponentAccessor.getUserUtil()
    LocalDateTime now = LocalDateTime.now()
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    String currentDateTime = now.format(formatter)

    def userSearchService = ComponentAccessor.getComponent(UserSearchService)
    def applicationAuthorizationService = ComponentAccessor.getComponent(ApplicationAuthorizationService)
    UserManager userManager = ComponentAccessor.getUserManager()
    def loginManager = ComponentAccessor.getComponent(LoginManager)
    def groupManager = ComponentAccessor.groupManager
    def logger = new MyLogger()

    int removedUsers = 0
    //a user who belongs to one of these groups will not be processed
    def groupsException = ["jira-administrators", "jira-administrators-system", "special-management"]
    //list of groups that will be removed from user
    def groupsForRemove = ["jira-software-users", "jira-software-users-park", "jira-software-users-petrol", "IT-Global_User", "jira-software-signal", "jira-software-login", "sst-users"]

    //region print setting for header
    logger.setMessage("START JOB -- CLEAN LICENCES --:");
    //logger.setMessage("DAYS_NEVER_LOGGED_NEW_USERS:" + DAYS_NEVER_LOGGED_NEW_USERS);
    logger.setMessage("INACTIVITY_DAYS_FOR_USERS:" + INACTIVITY_DAYS_FOR_USERS);
    logger.setMessage("USERS EXCEPTIONS GROUPS:" + groupsException);
    logger.setMessage("USERS GROUPS FOR REMOVE:" + groupsForRemove);
    //endregion

    // Get the list of users from the second array and convert the list of users from the second array to a map
    def users = ComponentAccessor.getOfBizDelegator().findAll("User")
    def usersMap = users.collectEntries { user -> [user.getString("userName"), user] }

    //Build a search with 10 000 active users results
    final def limitValue = 10000
    def userSearchBuilder = new UserSearchParams.Builder(limitValue)
    def userSearchParams = userSearchBuilder.allowEmptyQuery(true)
        .includeActive(true)
        .includeInactive(false)
        .limitResults(limitValue)
        .build()

    def activeUsers = userSearchService.findUsers('', userSearchParams)
    // Iterate over all users from the first array
    //userUtil.getUsers().each { u ->  
    activeUsers.each { u ->
        // Check if the user is active and licensed for Jira Software app
        if (u.active && applicationAuthorizationService.canUseApplication(u, ApplicationKeys.SOFTWARE)) {
            def loginfo = loginManager.getLoginInfo(u.username)
            boolean isOlderAndNotLogged = false;
            boolean isInactive = false;
            def lastLogOnDate = null
            def lastLogOn = "never"
            def userSIB = usersMap.get(u.getName())
            def userCreated = userSIB ? userSIB.getString("createdDate").toString() : ""

            if (loginfo.getLastLoginTime() != null) {
                lastLogOnDate = new Date(loginfo.getLastLoginTime())
                lastLogOn = lastLogOnDate.format("yyyy-MM-dd HH:mm:ss")
                isInactive = lastLogOnDate.before(new Date().minus(INACTIVITY_DAYS_FOR_USERS))
            } else {
                if (userCreated) {
                    def formatter2 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS][.SS][.S]")
                    def createdDate = LocalDateTime.parse(userCreated, formatter2)
                    if (createdDate.isBefore(LocalDateTime.now().minusDays(DAYS_NEVER_LOGGED_NEW_USERS))) {
                        isOlderAndNotLogged = true;
                    }
                }
            }
            if (isInactive) {// || isOlderAndNotLogged) {
                def userGroups = groupManager.getGroupNamesForUser(u.getName())
                if (userGroups.any { groupsException.contains(it) }) {
                    logger.setMessage("User ;" + u + "; contain some of the groups on list;" + groupsException + "; and therefore no group was taken away from him lastLogOn:;"+lastLogOn);
                } 
                else {
                    def atLeastOneWasRem = false;

                    groupsForRemove.each { groupName ->
                        if (userGroups.contains(groupName)) {
                            try {
                                def group = groupManager.getGroup(groupName)
                                userUtil.removeUserFromGroup(group, u)
                                logger.setMessage("User ;" + u + "; was removed from group name: ;" + groupName + ";lastLogOn:;"+lastLogOn)
                                atLeastOneWasRem = true
                            }
                            catch (Exception e) {
                                logger.setMessage("Error removing user '$u' from group '$groupName': ${e.message}")
                            }

                        }
                    }
                    if (atLeastOneWasRem) {
                        removedUsers++
                    }
                }

                /*logger.setMessage(";" +
                        u.getName() + ";" +
                        u.emailAddress + ";" +
                        String.valueOf(u.isActive()) + ";" +
                        userCreated + ";" +
                        lastLogOn + ";isInactive: " + isInactive + ";isOlderAndNotLogged: " + isOlderAndNotLogged)
                */

            }

        }
    }

    logger.setMessage("The total number of users whose at least one group has been removed " + removedUsers)
    logger.setMessage("END JOB")
}