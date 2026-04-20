package rest
/**
 * insertProjInfo
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

insertProjInfo(httpMethod: "POST", groups: ["jira-administrators-system", "jira-project-information-admins"]) { MultivaluedMap queryParams, String body ->

    def logBuilder = new StringBuilder()
    final ApplicationUser RUN_AS = ComponentAccessor.jiraAuthenticationContext.loggedInUser
    def jsonSlurper = new groovy.json.JsonSlurper()
    def requestBody = jsonSlurper.parseText(body ?: '{}')
    def projectInformation = (requestBody?.projectInformation ?: '').toString().trim()
    def OurLoggers ourLogger = new OurLoggers(OurLoggers.PROJECT_INFORMATION_LOGGER);

    if (!projectInformation) {
        logBuilder.append("Error: 'projectInformation' not provided in the request.\n")
        ourLogger.setErrorMessage("Error: 'projectInformation' not provided in the request by user: ${RUN_AS.getUsername()}")

        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.TEXT_PLAIN).entity(logBuilder.toString()).build()
    }

    try {
        DatabaseUtil.withSql('Project Information 2') { Sql sql ->
            sql.executeInsert('''
                INSERT INTO public."AO_601478_COST_CENTRE" ("ID","PROJECT_INFORMATION")
                VALUES (nextval('"AO_601478_COST_CENTRE_ID_seq"'::regclass), ?)
            ''', [projectInformation])
        }

        logBuilder.append("Insert successful for projectInformation: ${projectInformation}\n");
        ourLogger.setInfoMessage("Insert successful for projectInformation: ${projectInformation} by user: ${RUN_AS.getUsername()}")

        return Response.ok().type(MediaType.TEXT_PLAIN).entity(logBuilder.toString()).build()

    } catch (Exception e) {
        logBuilder.append("Error during insert: ${e.message}\n");
        ourLogger.setErrorMessage("Error during insert: ${e.message} by user: ${RUN_AS.getUsername()}")

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.TEXT_PLAIN).entity(logBuilder.toString()).build()
    }
}