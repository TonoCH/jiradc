package rest
/**
 * removeProjInfo
 *
 * @author chabrecek.anton
 * Created on 18. 11. 2025.
 */
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.user.ApplicationUser
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
import groovy.transform.BaseScript
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import javax.ws.rs.core.MultivaluedMap
import utils.OurLoggers

@BaseScript CustomEndpointDelegate delegate

removeProjInfo(httpMethod: "POST", groups: ["jira-administrators-system", "jira-project-information-admins"]) { MultivaluedMap queryParams, String body ->

    def logBuilder = new StringBuilder()
    def OurLoggers ourLogger = new OurLoggers(OurLoggers.PROJECT_INFORMATION_LOGGER);
    def jsonSlurper = new groovy.json.JsonSlurper()
    def requestBody = jsonSlurper.parseText(body ?: '{}')
    def projectInformation = (requestBody?.projectInformation ?: '').toString().trim()
    final ApplicationUser RUN_AS = ComponentAccessor.jiraAuthenticationContext.loggedInUser

    if (!projectInformation) {
        logBuilder.append("Error: 'projectInformation' not provided in the request.\n")
        ourLogger.setErrorMessage("Error: 'projectInformation' not provided in the request by user: ${RUN_AS.getUsername()}")

        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.TEXT_PLAIN).entity(logBuilder.toString()).build()
    }

    try {
        int rowsDeleted = 0
        DatabaseUtil.withSql('Project Information 2') { Sql sql ->
            def deleteQuery = '''
                DELETE FROM public."AO_601478_COST_CENTRE"
                WHERE "PROJECT_INFORMATION" = ?
            '''
            rowsDeleted = sql.executeUpdate(deleteQuery, [projectInformation]) as int
        }

        if (rowsDeleted > 0) {
            logBuilder.append("Successfully removed ${rowsDeleted} record(s) for projectInformation: ${projectInformation}\n")
            ourLogger.setInfoMessage("Successfully removed ${rowsDeleted} record(s) for projectInformation: ${projectInformation} by user: ${RUN_AS.getUsername()}")
        } else {
            logBuilder.append("No records found with projectInformation: ${projectInformation}\n")
            ourLogger.setInfoMessage("No records found with projectInformation: ${projectInformation} by user: ${RUN_AS.getUsername()}")
        }

        return Response.ok().type(MediaType.TEXT_PLAIN).entity(logBuilder.toString()).build()

    } catch (Exception e) {
        logBuilder.append("Error during deletion: ${e.message}\n")
        ourLogger.setErrorMessage("Error during deletion: ${e.message} by user: ${RUN_AS.getUsername()}\n")
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.TEXT_PLAIN).entity(logBuilder.toString()).build()
    }
}
