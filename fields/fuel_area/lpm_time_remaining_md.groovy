package fields.fuel_area

// Goal: This script will be used to derive the value of LPM Time Remaining in mandays. Vishal Choure 17.04.2025. 
return new MandayConverter().convertToMandays(issue, FieldsUsage.LPM_TIME_REMAINING_MD_FIELD_ID())