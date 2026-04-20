package fields.fuel_area

import fields.fuel_area.MandayConverter
import fields.fuel_area.FieldsUsage

// Goal: This script will be used to derive the value of LPM Time Spent in mandays. Vishal Choure 17.04.2025. 
return new MandayConverter().convertToMandays(issue, FieldsUsage.LPM_TIME_SPENT_MD_FIELD_ID())