

riskAnalysis();

public void riskAnalysis() {

    String schadensHoehe = getFieldById("customfield_12252").getValue(); // Customfield ID für Schadenshöhe
    String schadensHaeufighkeit = getFieldById("customfield_12251").getValue(); // Customfield ID für Schadenshäufigkeit
    def resultingRiskLevelField = getFieldById("customfield_12254");

    String resultierendesRisiko = "";

    int riskCalulation = 0;

    if (schadensHoehe == null || schadensHaeufighkeit == null) {
        getFieldById("customfield_12252").setHelpText(null);
        return;
    }

    if (schadensHaeufighkeit.toLowerCase().contains("häufig")) {
        // Stufe 3
        riskCalulation = calculateRisk(7, getSchadenshoeheInt(schadensHoehe))
    } else if (schadensHaeufighkeit.toLowerCase().contains("wahrscheinlich")) {
        // Stufe 2
        riskCalulation = calculateRisk(5, getSchadenshoeheInt(schadensHoehe))
    } else if (schadensHaeufighkeit.toLowerCase().contains("unwahrscheinlich")) {
        // Stufe 1
        riskCalulation = calculateRisk(3, getSchadenshoeheInt(schadensHoehe))
    } else if (schadensHaeufighkeit.toLowerCase().contains("praktisch unmöglich")) {
        // Stufe 0
        riskCalulation = calculateRisk(2, getSchadenshoeheInt(schadensHoehe))
    }

    boolean tragbar = false;

    String textColor = "";

    if (riskCalulation >= 20) {
        resultierendesRisiko = "Red";
        tragbar = false;
        textColor = "red";
        resultingRiskLevelField.setFormValue("15291");
    } else if (riskCalulation >= 7) {
        resultierendesRisiko = "Yellow";
        tragbar = false;
        textColor = "#ffc414";
        resultingRiskLevelField.setFormValue("15290");
    }  else if (riskCalulation > 0) {
        resultierendesRisiko = "Green";
        tragbar = true;
        textColor = "green";
        resultingRiskLevelField.setFormValue("15289");
    }

    getFieldById("customfield_12254").setHelpText("<font style='color:" + textColor + "; font-weight:bold'> Resulting Risk Level = " + resultierendesRisiko + "</font>");

}

public int calculateRisk(int haeufigkeit, int hoehe) {

    if (haeufigkeit == 0 || hoehe == 0) {
        return 0;
    }

    return haeufigkeit * hoehe;
}

public int getSchadenshoeheInt(String schadensHoehe) {

    if (schadensHoehe.toLowerCase().contains("unwesentlich")) {
        return 1;
    } else if (schadensHoehe.toLowerCase().contains("gering")) {
        return 2;
    } else if (schadensHoehe.toLowerCase().contains("kritisch")) {
        return 3;
    } else if (schadensHoehe.toLowerCase().contains("katastrophal")) {
        return 4;
    }

    return 0;

}

return 0;