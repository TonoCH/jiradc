package utils;

/**
 * IssueTypeHierarchy
 *
 * @author chabrecek.anton
 * Created on 15. 1. 2026.
 */

class IssueTypeHierarchy {

    static final String INITIATIVE = "Initiative"
    static final String LPM_INITIATIVE = "LPM Initiative"
    static final String BID_PROJECT = "Bid Project"
    static final String OBJECTIVE = "Objective"

    static final String FEATURE = "Feature"
    static final String WORK_PACKAGE = "Work Package"
    static final String DEVELOPMENT_FORECAST = "Development Forecast"
    static final String KEY_RESULT = "Key Result"

    static final String EPIC = "Epic"
    static final String REQUIREMENT = "Requirement"
    static final String DESIGN_AND_ESTIMATE_SOLUTION = "Design & Estimate Solution"
    static final String PROJECT = "Project"

    static final String TASK = "Task"
    static final String STORY = "Story"
    static final String BUG = "Bug"
    static final String SUB_TASK = "Sub-task"
    static final String MONITORING_TASK = "Monitoring Task"

    static final String PROJECT_PHASE = "Project Phase"
    static final String WORKING_PACKAGE = "Working Package/Milestone"

    final Map<String, Set<String>> parentToChildren
    final Map<String, String> childToParent

    IssueTypeHierarchy() {
        def initiativeTypes = [INITIATIVE, LPM_INITIATIVE, BID_PROJECT, OBJECTIVE] as Set<String>
        def featureTypes = [FEATURE, WORK_PACKAGE, DEVELOPMENT_FORECAST, KEY_RESULT] as Set<String>
        def epicTypes = [EPIC, REQUIREMENT, DESIGN_AND_ESTIMATE_SOLUTION, PROJECT] as Set<String>
        def executionTypes = [TASK, STORY, BUG, MONITORING_TASK] as Set<String>

        this.parentToChildren = ([
                (PROJECT_PHASE)  : ([WORKING_PACKAGE] as Set<String>),
                (WORKING_PACKAGE): (initiativeTypes),

                (INITIATIVE)     : (featureTypes),
                (LPM_INITIATIVE) : (featureTypes),
                (BID_PROJECT)    : (featureTypes),
                (OBJECTIVE)      : (featureTypes),

                (FEATURE)        : (epicTypes),
                (WORK_PACKAGE)   : (epicTypes),
                (DEVELOPMENT_FORECAST): (epicTypes),
                (KEY_RESULT)     : (epicTypes),

                (EPIC)           : (executionTypes),
                (REQUIREMENT)    : (executionTypes),
                (DESIGN_AND_ESTIMATE_SOLUTION): (executionTypes),
                (PROJECT)        : (executionTypes),

                (TASK)           : ([SUB_TASK] as Set<String>),
                (STORY)          : ([SUB_TASK] as Set<String>),
                (BUG)            : ([SUB_TASK] as Set<String>),
                (MONITORING_TASK): ([SUB_TASK] as Set<String>)
        ] as Map<String, Set<String>>)

        this.childToParent = buildReverseMap(parentToChildren)
    }

    Set<String> getChildTypes(String parentType) {
        parentToChildren.get(parentType) ?: ([] as Set<String>)
    }

    String getParentType(String childType) {
        childToParent.get(childType)
    }

    boolean isDirectChildOf(String parentType, String childType) {
        getChildTypes(parentType).contains(childType)
    }

    Set<String> getDescendantTypes(String parentType) {
        def visited = [] as Set<String>
        def stack = [parentType] as ArrayDeque<String>

        while (!stack.isEmpty()) {
            def cur = stack.removeLast()
            def kids = getChildTypes(cur)
            kids.each { k ->
                if (visited.add(k)) stack.add(k)
            }
        }
        visited
    }

    List<String> getAncestryTypes(String childType) {
        def out = [] as List<String>
        def cur = childType
        while (cur != null) {
            out << cur
            cur = getParentType(cur)
        }
        out
    }

    protected Map<String, String> buildReverseMap(Map<String, Set<String>> p2c) {
        def out = [:] as Map<String, String>
        p2c.each { parent, kids ->
            kids.each { child ->
                if (!out.containsKey(child)) out[child] = parent
            }
        }
        out
    }
}