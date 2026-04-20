package fields.fuel_area

// Goal: This is a scripted field to derive the LPM Time Budget in mandays. Vishal Choure 17.04.2025.
return new MandayConverter().convertToMandays(issue, FieldsUsage.LPM_TIME_BUDGET_MD_FIELD_ID()) / 3600