package migration.veniture

//project = ZNREAL AND issueFunction in hasLinkType("Upper Project") AND issuetype = Projekt

/*
Parents sind aktuell über has upper project zu finden -> auf Work Package

Kinder von Issue Type "Projekt" is upper project to
iteration -> move to story
-> parentLink Projekt
*/

return true;