import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue

class OtherProjectInformationHandler extends ProjectInformationHandler {

    OtherProjectInformationHandler() {
        super(Type.OTHER)
    }

    // Inherit from parent only while the child has not been deliberately overridden.
    // An overridden child keeps its own value and becomes the source for its own subtree.
    @Override
    protected void applyToChild(Issue parent, MutableIssue child) {
        if (!isManualOverride(child)) {
            applyValues(child, currentValues(parent))
        }
    }

    // A user edit on a non-top issue is a deliberate choice: remember it and let the
    // new value flow down to descendants from here.
    @Override
    protected void onNonTopValueChange(MutableIssue issue) {
        markManualOverride(issue)
    }
}
