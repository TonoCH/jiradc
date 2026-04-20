package kvs_audits.audit4

import kvs_audits.common.AuditScheduler
import kvs_audits.common.CustomFieldsConstants
import kvs_audits.common.IAuditScheduler
import com.atlassian.jira.issue.Issue
import kvs_audits.issueType.AuditPreparation
import kvs_audits.issueType.Question

import java.time.LocalDate
import java.time.ZoneId
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import kvs_audits.common.CommonHelper
import java.time.temporal.IsoFields

/**
 * AuditLevel4Scheduler
 *
 * @author chabrecek.anton
 * Created on 27/05/2025.
 */
class AuditLevel4Scheduler extends AuditScheduler implements IAuditScheduler {

    public static final String auditLevel = CustomFieldsConstants.AUDIT_LEVEL_4

    AuditLevel4Scheduler() {
        super(auditLevel)
    }

    @Override
    void execute() {
        List<Issue> prepIssues = getPreparationIssues()
        logBasicInfo(prepIssues)

        prepIssues.each { prepIssue ->
            scheduleRotationsFor(prepIssue)
        }
    }

    private void processRotation(Issue prepIssue,
                                 AuditLevel4Handler handler,
                                 AuditPreparation prep,
                                 LocalDate rotationDay) {
        logger.setInfoMessage("Level 4 rotation for ${prepIssue.key} on ${rotationDay}")

        //Parse rotation_data JSON from AuditPreparation.
        def data = parseRotationData(prep)
        //Extract questions_usages
        Map usages = extractUsagesOrSkip(data, prepIssue)
        if (usages == null) return

        List<String> usageKeys = getSortedUsageKeysOrSkip(usages, prepIssue)
        if (usageKeys == null) return

        int turnIdx = normalizeTurnIndex(data, usageKeys.size())
        String usageKey = usageKeys[turnIdx]
        def u = usages[usageKey]

        logDebugUsageState(usageKey, u, data)

        // If auditors missing then update and persist, then stop
        if (skipWhenNoAuditors(handler, data, usageKeys, turnIdx, usageKey, u)) return

        // Resolve PC and sub-area letter (A/B)
        Issue pc = resolvePcOrSkip(handler, data, usageKeys, turnIdx, usageKey)
        if (!pc) return

        String subAreaLetter = resolveSubAreaOrSkip(handler, data, usageKeys, turnIdx, usageKey, pc)
        if (!subAreaLetter) return

        List<String> liveSubAreas = loadLiveSubAreasOrSkip(handler, data, usageKeys, turnIdx, usageKey, pc, subAreaLetter)
        if (liveSubAreas == null) return

        // Reconcile workplaces; early exit preserves json index update behavior
        if (!reconcileWorkplacesOrSkip(handler, data, usageKeys, turnIdx, usageKey, u, liveSubAreas)) return

        // Rotation count and cross calculation
        u.rotationCount = (u.rotationCount ?: 0) + 1
        boolean crossCandidate = (u.rotationCount % 3 == 0)

        //TODO temporary disabled crossAudit
        List<String> globalCrossPool = [] //getGlobalCrossPool()
        boolean hasCross = globalCrossPool && !globalCrossPool.isEmpty()
        boolean isCross = false// crossCandidate && hasCross

        // Auditor resolution (cross → internal fallback); same behavior
        String auditor = pickAuditor(prepIssue, prep, rotationDay, usageKey, u, isCross, globalCrossPool, data)
        if (!auditor) {
            // internal auditors could be empty after reconciliation; already update json index there
            return
        }

        //TODO temporary disabled crossAudit
        isCross = false//(auditor && isCross)

        // Pick FA for this usage; early exit update json index as before
        String faKey = pickFunctionalAreaOrSkip(handler, data, usageKeys, turnIdx, usageKey, u)
        if (!faKey) return

        // Special due calculation (only for non-cross)
        List<String> specialDueForThisAudit = computeSpecialDueForFa(u, faKey, rotationDay, isCross)

        logger.setInfoMessage("RotationJob: usage=${usageKey}, subArea=${faKey}, auditor=${auditor}, cross=${isCross}")
        handler.rotateOneAudit(usageKey, faKey, auditor, isCross, rotationDay, specialDueForThisAudit)

        // Commit special due (advance nextRotationDate per question)
        if (!isCross && specialDueForThisAudit) {
            commitSpecialDue(u, faKey, specialDueForThisAudit, rotationDay)
        }

        // update json index and persist rotation_data (unchanged)
        updateJsonIndexAndPersist(handler, data, usages, usageKeys, turnIdx)

        // Move next date exactly like before
        LocalDate next = CommonHelper.getNextDate(rotationDay, prep.getInterval())
        logger.setWarnMessage("Date of next rotation: nextRotation = ${next}")
        handler.updateDateOfNextRotation(next)
        logger.setWarnMessage("AFTER SET Date of next rotation: timestamp = $next")
    }

// ===================== HELPERS (pure refactor, no logic change) =====================

    private Map parseRotationData(AuditPreparation prep) {
        Map m = new JsonSlurper().parseText(prep.getRotation_data()) as Map
        if (!(m.globalAuditorIndex instanceof Number)) {
            m.globalAuditorIndex = 0  // ensure presence for old data
        }
        return m
    }

    private Map extractUsagesOrSkip(Map data, Issue prepIssue) {
        Map usages = data.questions_usages as Map
        if (!usages) {
            logger.setWarnMessage("No 'questions_usages' found for ${prepIssue.key}; skipping.")
            return null
        }
        return usages
    }

    private List<String> getSortedUsageKeysOrSkip(Map usages, Issue prepIssue) {
        List<String> usageKeys = (usages.keySet() as List<String>).sort()
        if (usageKeys.isEmpty()) {
            logger.setWarnMessage("No questions_usages for ${prepIssue.key}; skipping.")
            return null
        }
        return usageKeys
    }

    //Normalize the round-robin turn index.
    private int normalizeTurnIndex(Map data, int usageCount) {
        int turnIdx = (data.usageTurnIndex instanceof Number) ? (int) data.usageTurnIndex : 0
        return turnIdx % usageCount
    }

    private void logDebugUsageState(String usageKey, Map u, Map data) {
        logger.setInfoMessage(">> [DEBUG] usage=${usageKey}, workplaceIndex=${u.currentWorkplaceIndex}, workplace=${(u.workplaces ? u.workplaces[Math.min((u.currentWorkplaceIndex ?: 0), (u.workplaces.size() - 1))] : 'n/a')}")
        logger.setInfoMessage(">> [DEBUG] usage=${usageKey}, auditorIndex=${u.currentAuditorIndex}, auditor=${(u.auditors ? u.auditors[Math.min((u.currentAuditorIndex ?: 0), (u.auditors.size() - 1))] : 'n/a')}")
        int gIdx = (data?.globalAuditorIndex instanceof Number) ? ((Number) data.globalAuditorIndex).intValue() : 0
        logger.setInfoMessage(">> [DEBUG] globalAuditorIndex=${gIdx}")
    }

    // If auditors missing, update json index then skip (return true).
    private boolean skipWhenNoAuditors(AuditLevel4Handler handler, Map data, List<String> usageKeys, int turnIdx,
                                       String usageKey, Map u) {
        if (!u.auditors || u.auditors.isEmpty()) {
            logger.setErrorMessage("No internal auditors defined for ${usageKey}; skipping this usage")
            data.usageTurnIndex = (turnIdx + 1) % usageKeys.size()
            handler.updateRotationData(JsonOutput.toJson(data))
            return true
        }
        return false
    }

    // -- sub-area and PC resolution --
    private Issue resolvePcOrSkip(AuditLevel4Handler handler, Map data, List<String> usageKeys, int turnIdx,
                                  String usageKey) {
        Issue pc = jqlSearcher.getPCFromQuestionUsage(usageKey, auditLevel)
        if (!pc) {
            logger.setErrorMessage("Rotation failed: invalid usageKey '${usageKey}', profit center not found")
            data.usageTurnIndex = (turnIdx + 1) % usageKeys.size()
            handler.updateRotationData(JsonOutput.toJson(data))
            return null
        }
        return pc
    }

    // Resolve A/B letter; on ambiguous case update json index and skip.
    private String resolveSubAreaOrSkip(AuditLevel4Handler handler, Map data, List<String> usageKeys, int turnIdx,
                                        String usageKey, Issue pc) {
        String subAreaLetter = parseSubAreaLetterOrNull(usageKey)
        if (!subAreaLetter) {
            List<String> aKeys = jqlSearcher.getFunctionalAreasKeys(pc, "A") ?: []
            List<String> bKeys = jqlSearcher.getFunctionalAreasKeys(pc, "B") ?: []

            boolean onlyA = aKeys && !bKeys
            boolean onlyB = bKeys && !aKeys

            if (onlyA) subAreaLetter = "A"
            else if (onlyB) subAreaLetter = "B"
            else {
                logger.setErrorMessage("Level 4 usage '${usageKey}' no A/B and PC ${pc.key} has A and B – skip it.")
                data.usageTurnIndex = (turnIdx + 1) % usageKeys.size()
                handler.updateRotationData(JsonOutput.toJson(data))
                return null
            }
        }
        return subAreaLetter
    }

/** Load live sub-areas or update json index and skip. */
    private List<String> loadLiveSubAreasOrSkip(AuditLevel4Handler handler, Map data, List<String> usageKeys, int turnIdx,
                                                String usageKey, Issue pc, String subAreaLetter) {
        List<String> liveSubAreas = jqlSearcher.getFunctionalAreasKeys(pc, subAreaLetter)
        if (!liveSubAreas) {
            logger.setErrorMessage("No Functional Areas for ${pc.key} with sub-area '${subAreaLetter}' for usage ${usageKey}")
            data.usageTurnIndex = (turnIdx + 1) % usageKeys.size()
            handler.updateRotationData(JsonOutput.toJson(data))
            return null
        }
        return liveSubAreas
    }

/** Reconcile configured vs live FA keys; on empty result update json index and skip. */
    private boolean reconcileWorkplacesOrSkip(AuditLevel4Handler handler, Map data, List<String> usageKeys, int turnIdx,
                                              String usageKey, Map u, List<String> liveSubAreas) {
        u.workplaces = dynamicReconcileRotationUnits(usageKey, u.workplaces as List<String>, liveSubAreas)
        if (!u.workplaces) {
            logger.setErrorMessage("No sub-areas remain for ${usageKey} after reconciliation; skipping.")
            data.usageTurnIndex = (turnIdx + 1) % usageKeys.size()
            handler.updateRotationData(JsonOutput.toJson(data))
            return false
        }
        return true
    }

    // Decide the auditor: cross if possible, else internal; return null if internal not available.
    private String pickAuditor(Issue prepIssue,
                               AuditPreparation prep,
                               LocalDate rotationDay,
                               String usageKey,
                               Map u,
                               boolean isCross,
                               List<String> globalCrossPool,
                               Map data) {
        String auditor = null

        if (isCross) {
            String picked = pickEligibleCrossAuditor(rotationDay, globalCrossPool)
            if (picked) {
                auditor = picked
                int quarter = rotationDay.get(IsoFields.QUARTER_OF_YEAR)
                u.crossAuditHistory = (u.crossAuditHistory ?: [])
                u.crossAuditHistory << [year: rotationDay.year, quarter: quarter, auditor: auditor]
            } else {
                logger.setWarnMessage("No eligible cross-auditor available for ${rotationDay} (Q${rotationDay.get(IsoFields.QUARTER_OF_YEAR)} ${rotationDay.year}); fallback to internal auditor for ${prepIssue.key}")
                isCross = false
            }
        }

        if (!auditor) {
            String internalAuditor = getLiveAuditors(prep, usageKey, u)
            if (!internalAuditor) {
                logger.setErrorMessage("No internal auditors available for ${usageKey} after reconciliation – skipping usage.")
                return null
            }

            List<String> curAuditors = (u.auditors as List<String>) ?: []
            if (!curAuditors) {
                logger.setErrorMessage("No internal auditors on usage ${usageKey} – skipping.")
                return null
            }

            int gIdx = 0
            if (data?.globalAuditorIndex instanceof Number) {
                gIdx = ((Number) data.globalAuditorIndex).intValue()
            }

            auditor = curAuditors[gIdx % curAuditors.size()]

            // globalAuditorIndex advanced in updateJsonIndexAndPersist after full area cycle
        }

        return auditor
    }

    // Pick FA key (and advance index); on empty list update json index and skip (returns null).
    private String pickFunctionalAreaOrSkip(AuditLevel4Handler handler, Map data, List<String> usageKeys, int turnIdx,
                                            String usageKey, Map u) {
        List<String> wps = (u.workplaces as List<String>) ?: []
        if (wps.isEmpty()) {
            logger.setErrorMessage("No sub-areas remain for ${usageKey} after reconciliation; skipping.")
            data.usageTurnIndex = (turnIdx + 1) % usageKeys.size()
            handler.updateRotationData(JsonOutput.toJson(data))
            return null
        }

        int cur = Math.max(0, (u.currentWorkplaceIndex ?: 0))
        int idx = cur % wps.size()
        String faKey = wps[idx]
        u.currentWorkplaceIndex = (idx + 1) % wps.size()
        return faKey
    }

    // Compute the list of special questions due for this FA (only for non-cross).
    private List<String> computeSpecialDueForFa(Map u, String faKey, LocalDate rotationDay, boolean isCross) {
        if (isCross) return []
        List<String> specialDueForThisAudit = []
        Map specials = (u.specialQuestions ?: [:]) as Map
        specials.each { aqKey, detail ->
            def nextStr = (detail?.wpRotation ?: [:])?[faKey]?.nextRotationDate
            if (nextStr) {
                LocalDate due = LocalDate.parse(nextStr as String)
                if (!due.isAfter(rotationDay)) { // due <= rotationDay
                    specialDueForThisAudit << (aqKey as String)
                }
            }
        }
        return specialDueForThisAudit
    }

    // Persist nextRotationDate for special questions (same logic).
    private void commitSpecialDue(Map u, String faKey, List<String> specials, LocalDate rotationDay) {
        specials.each { aqKey ->
            String occ = getQuestionOccurrence(aqKey)
            int months = monthsForOccurrence(occ)
            if (months > 0) {
                def curStr = u.specialQuestions[aqKey]?.wpRotation?[faKey]?.nextRotationDate
                LocalDate curD = curStr ? LocalDate.parse(curStr as String) : rotationDay
                LocalDate nxt = curD.plusMonths(months)

                if (!u.specialQuestions[aqKey]) u.specialQuestions[aqKey] = [wpRotation: [:]]
                if (!u.specialQuestions[aqKey].wpRotation) u.specialQuestions[aqKey].wpRotation = [:]
                u.specialQuestions[aqKey].wpRotation[faKey] = [nextRotationDate: nxt.toString()]

                logger.setInfoMessage("Advanced special ${aqKey} for ${faKey} → ${nxt}")
            } else {
                logger.setWarnMessage("Special ${aqKey}: unsupported occurrence '${occ}' – skipping advance.")
            }
        }
    }

    // Advance the global round-robin update json index and persist the rotation_data map. */
    private void updateJsonIndexAndPersist(AuditLevel4Handler handler,
                                           Map data,
                                           Map usages,
                                           List<String> usageKeys,
                                           int turnIdx) {
        data.usageTurnIndex = (turnIdx + 1) % usageKeys.size()
        // Advance auditor only after cycling through all areas (area rotation priority)
        if (data.usageTurnIndex == 0) {
            int gIdx = (data.globalAuditorIndex instanceof Number) ? ((Number) data.globalAuditorIndex).intValue() : 0
            data.globalAuditorIndex = gIdx + 1
        }
        data.questions_usages = usages
        handler.updateRotationData(JsonOutput.toJson(data))
    }


    //*******************************************************

    private void scheduleRotationsFor(Issue prepIssue) {
        AuditPreparation auditPreparation = new AuditPreparation(prepIssue)
        AuditLevel4Handler auditHandler = new AuditLevel4Handler(auditPreparation.getIssue(), kvsProjectKey, auditLevel)
        Date dateOfNextRotation = auditPreparation.getDate_of_next_rotation()

        if (!dateOfNextRotation) {
            logger.setWarnMessage("Date Of Next Rotation missing on ${prepIssue.key}, skipping.")
            return
        }

        LocalDate today = LocalDate.now()
        LocalDate rotationDay = dateOfNextRotation.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()

        int safetyCounter = 0
        final int SAFETY_LIMIT = 100

        while (!rotationDay.isAfter(today.plusMonths(3)) && safetyCounter < SAFETY_LIMIT) {
            safetyCounter++
            LocalDate before = rotationDay

            processRotation(prepIssue, auditHandler, auditPreparation, rotationDay)

            auditPreparation = new AuditPreparation(myBaseUtil.getIssueByKey(prepIssue.getKey()))
            dateOfNextRotation = auditPreparation.getDate_of_next_rotation()
            rotationDay = dateOfNextRotation.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()

            if (rotationDay.equals(before)) {
                logger.setErrorMessage("${prepIssue.getKey()} Rotation date did NOT advance (still ${rotationDay}). before date: ${before} Breaking to avoid infinite loop.")
                break
            }
        }

        if (safetyCounter == 0) {
            logger.setInfoMessage("No rotation for today")
        }

        if (safetyCounter >= SAFETY_LIMIT) {
            logger.setErrorMessage("Safety limit of ${SAFETY_LIMIT} rotations reached for ${prepIssue.key}. Exiting to prevent infinite loop.")
        }
    }

    // Read global cross-auditor pool from the single “KVS Setting” issue
    private List<String> getGlobalCrossPool() {
        def setting = jqlSearcher.getKvsSetting()
        if (!setting) {
            logger.setErrorMessage("KVS Setting issue not found in project ${CustomFieldsConstants.PROJECT_KVS_PROFIT_CENTERS}; cross-audits will fallback to internal auditors.")
            return []
        }
        def raw = myBaseUtil.getCustomFieldValue(setting, CustomFieldsConstants.CROSS_AUDITORS_POOL)
        def listOfCrossAuditors = customFieldUtil.getFieldValue_MultiUsersSearcher(raw) ?: []

        return listOfCrossAuditors  // list of usernames of cross auditors
    }

    private Set<String> getGloballyUsedCrossAuditors(int year, int quarter) {
        Set<String> used = [] as Set
        jqlSearcher.getAllAuditsPreparation().each { prepIssue ->
            def dataRaw = myBaseUtil.getCustomFieldValue(prepIssue, AuditPreparation.ROTATION_DATA_FIELD_NAME)
            if (!dataRaw) return
            def data = new JsonSlurper().parseText(dataRaw)
            (data?.questions_usages ?: [:])?.values()?.each { entry ->
                (entry.crossAuditHistory ?: [])?.each { hist ->
                    if ((hist.year == year) && (hist.quarter == quarter) && hist.auditor) {
                        used << hist.auditor as String
                    }
                }
            }
        }
        return used
    }

    private String pickEligibleCrossAuditor(LocalDate rotationDay, List<String> pool) {
        int year = rotationDay.year
        int q = rotationDay.get(IsoFields.QUARTER_OF_YEAR)
        Set<String> used = getGloballyUsedCrossAuditors(year, q)

        def eligible = pool.findAll { !(it in used) }
        if (!eligible) return null

        // Simple rotation: pick the first eligible; we can implement smarter cycling later
        return eligible[0]
    }

    private String parseSubAreaLetterOrNull(String usageKey) {
        //  *_A_Level_4 or *_B_Level_4
        def m = (usageKey =~ /_(A|B)_Level_4$/)
        return m ? m[0][1] : null
    }

    private int monthsForOccurrence(String occ) {
        if (!occ) return 0
        occ = occ.toLowerCase()
        if (occ.contains("semi")) return 6
        if (occ.contains("year")) return 12
        return 0 // 'always' or 'none' -> treat as non-special here
    }

    private String getQuestionOccurrence(String questionKey) {
        def qIssue = myBaseUtil.getIssueByKey(questionKey)
        if (!qIssue) return null
        // Use your real CF name/constant if you have it in Question.groovy/CustomFieldsConstants:
        def raw = myBaseUtil.getCustomFieldValue(qIssue, Question.AUDIT_INTERVAL_OCCURANCE_FIELD_NAME)
        // exact field label from your UI
        return (raw ?: "") as String
    }
}