package scriptfields

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.RendererManager
import com.atlassian.jira.issue.fields.renderer.IssueRenderContext
import com.atlassian.jira.issue.fields.renderer.wiki.AtlassianWikiRenderer
import constants.CustomFieldIdConstants

Issue issue = issue
//Issue issue = ComponentAccessor.issueManager.getIssueByCurrentKey("NLTLSIDBT-544")

def renderContext = new IssueRenderContext(issue)

def cfReq = ComponentAccessor.customFieldManager.getCustomFieldObject(CustomFieldIdConstants.REQUIREMENTS)
def cfAC = ComponentAccessor.customFieldManager.getCustomFieldObject(CustomFieldIdConstants.ACCEPTANCE_CRITERIA)

def reqs = issue.getCustomFieldValue(cfReq)

if (!reqs) return null

def desc = ""

reqs.each {
    desc += "${it.key}: \"+*${it.summary}*+\"\n"
    desc += "${it.description}\n\n"
    
    if (it.getCustomFieldValue(cfAC)) {
        desc += "*acceptance criteria:*\n"
        desc += "${it.getCustomFieldValue(cfAC)}\n\n"
    }
}

return ComponentAccessor.getComponent(RendererManager).getRenderedContent(AtlassianWikiRenderer.RENDERER_TYPE, desc, renderContext)