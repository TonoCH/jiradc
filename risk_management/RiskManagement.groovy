package risk_management;

import risk_management.CustomRiskProjectsConstants as project;

class RiskManagement {

public static final String GREEN = "green";
public static final String YELLOW = "yellow";
public static final String ORANGE = "orange";
public static final String RED = "red";

     /*
    get color name from combination of extendOfDamageVal, riskAcceptanceVal depends on matrix table located in:
    https://fcs-conflu-01.ads.local/pages/viewpage.action?spaceKey=SBISO&title=ISMS+Risk+Classification+Criteria
 */
public static String getColorName(String extendOfDamageVal, String riskAcceptanceVal) {

    def colorMap = [
            "negligible": [
                    "low": GREEN,
                    "moderate": GREEN,
                    "frequent": YELLOW,
                    "certainly to be expected": YELLOW
            ],
            "minor": [
                    "low": GREEN,
                    "moderate": YELLOW,
                    "frequent": YELLOW,
                    "certainly to be expected": ORANGE
            ],
            "significant": [
                    "low": YELLOW,
                    "moderate": YELLOW,
                    "frequent": ORANGE,
                    "certainly to be expected": ORANGE
            ],
            "extensive": [
                    "low": YELLOW,
                    "moderate": ORANGE,
                    "frequent": ORANGE,
                    "certainly to be expected": RED
            ],
            "catastrophic": [
                    "low": ORANGE,
                    "moderate": ORANGE,
                    "frequent": RED,
                    "certainly to be expected": RED
            ]
    ]

    def color = colorMap[extendOfDamageVal]?.get(riskAcceptanceVal)
    return color ?: null
}

// Get projectID or null in Long object.Key is project and value is his _action equivalent
public static Long getActionProject(long projectId){
        def map = [:] as HashMap<Long, Long>

        map.put(project.RISK_MANAGEMENT , project.PROCESS_RISKS );
        map.put(project.RISK_MANAGEMENT_FCS_GMBH , project.RISK_ACTIONS_FCS_GMBH );
        map.put(project.RISK_MANAGEMENT_FRS_GMBH , project.RISK_ACTIONS_FRS_GMBH );
        map.put(project.RISK_MANAGEMENT_IOT_GMBH , project.RISK_ACTIONS_IOT_GMBH );
        map.put(project.RISK_MANAGEMENT_PS_GMBH , project.RISK_ACTIONS_PS_GMBH );
        map.put(project.RISK_MANAGEMENT_SB_GMBH , project.RISK_ACTIONS_SB_GMBH );
        map.put(project.RISK_MANAGEMENT_SIS_GMBH , project.RISK_ACTIONS_SIS_GMBH );
        map.put(project.RISK_MANAGEMENT_SST_GMBH_KIEL  , project.RISK_ACTIONS_SST_GMBH_KIEL );
        map.put(project.RISK_MANAGEMENT_PSG_GMBH , project.RISK_ACTIONS_PSG_GMBH);
        map.put(project.RISK_TEST_PROJECT , project.RISK_TEST_ACTION_PROJECT);
        map.put(project.RISK_MANAGEMENT_EVOPARK_GMBH, project.RISK_ACTIONS_EVOPARK_GMBH);
        //map.put(project.RISK_MANAGEMENT_PSG_GMBH , ?); Have a SIBLING?

        return map.get(projectId);
  
}

}