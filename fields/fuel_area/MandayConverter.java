package fields.fuel_area;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.Issue;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * MandayConverter
 *
 * @author chabrecek.anton
 * Created on 15. 4. 2026.
 */
class MandayConverter {

    /**
     * Converts a custom field value (in hours) to mandays by dividing by 8 and rounding to 2 decimal places.
     * @param issue The JIRA issue object
     * @param customFieldId The custom field ID where value will be set
     * @return Rounded result in mandays as BigDecimal, or null if value is missing
     */

    BigDecimal convertToMandays(Issue issue, Long customFieldId) {
        //get customField from which field is getting value

        def sourceValueFieldId = FieldsUsage.getSourceFieldId(customFieldId)
        if (sourceValueFieldId == null) {
            throw new IllegalArgumentException("custom field ID: $customFieldId doesnt have pair in FieldsUsage.FieldValueCounterPairs ")
        }

        def customFieldManager = ComponentAccessor.getCustomFieldManager()
        def fieldObject = customFieldManager.getCustomFieldObject(sourceValueFieldId)

        if (fieldObject == null) {
            return BigDecimal.ZERO;
            //throw new NullPointerException("fieldObject == null")
        }

        def fieldValue = issue.getCustomFieldValue(fieldObject) as Double
        if (fieldValue == null) {
            return BigDecimal.ZERO;
            //throw new NullPointerException("fieldValue == null")
        }

        double result = fieldValue / 8.0
        return new BigDecimal(result.toString()).setScale(2, RoundingMode.HALF_UP)
    }
}
