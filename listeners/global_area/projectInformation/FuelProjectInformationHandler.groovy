import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue

class FuelProjectInformationHandler extends ProjectInformationHandler {

    FuelProjectInformationHandler() {
        super(Type.FUEL)
    }

    // Parent value is authoritative; any existing child value is overwritten.
    @Override
    protected void applyToChild(Issue parent, MutableIssue child) {
        applyValues(child, currentValues(parent))
    }

    // A user edit on a non-top issue is not allowed: revert it to the parent value.
    @Override
    protected void onNonTopValueChange(MutableIssue issue) {
        Issue parent = findParentIssue(issue)
        if (parent) applyValues(issue, currentValues(parent))
    }
}
