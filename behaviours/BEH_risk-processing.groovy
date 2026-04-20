package behaviours

import org.apache.log4j.Level
import risk_management.RiskManagement

riskMatrix();

public void riskMatrix() {

    //region declaration and init part

    //Contains name of colors: Not Rated, Green, Yellow, Orange, Red
    def field_ResultingRiskLevel = getFieldById("customfield_12254")
    //Contains extend of damage values: neglible, minor, significant, extensive, catastrophic
    String extendOfDamageVal = getFieldValue("customfield_12252");
    //Contains Likelihood of occurance : low, moderate, frequent, certainly to be expected
    String riskAcceptanceVal = getFieldValue("customfield_12251");

    //endregion

    String colorName = RiskManagement.getColorName(extendOfDamageVal, riskAcceptanceVal); 

    //for test purposes
    if(colorName == null){
            /*printTextUnderField(field_ResultingRiskLevel,'<div class="error">'+ 
            'NONE One of the values or all of fields(*Extend of damage*, *Likelihood of occurance*) contains null or value which is not in the matrix table </div>');*/

            field_ResultingRiskLevel.setFormValue(null);
    }
    else {
            /*printTextUnderField(field_ResultingRiskLevel,'<div class="error">'+ 
        "Extend of damage: " + extendOfDamageVal + "; Risk acceptance: " + riskAcceptanceVal+' colorname:'+ colorName +'</div>');*/

        printTextUnderField(field_ResultingRiskLevel,"");

        field_ResultingRiskLevel.setFormValue(colorName);
    }
}

protected void printTextUnderField(def field, String text){
        field.setDescription('<div class="error">'+ text+'</div>');
}

protected String getFieldValue(String fieldId){
         return getFieldById(fieldId).getValue();
}