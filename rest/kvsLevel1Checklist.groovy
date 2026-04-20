package rest

/**
 * kvsLevel1Checklist
 *
 * REST endpoint serving HTML fragment for KVS Level 1 Checklist.
 *
 * @author chabrecek.anton
 * Created on 24. 3. 2026.
 */

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.transform.BaseScript

import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response

@BaseScript CustomEndpointDelegate delegate

kvsLevel1Checklist(httpMethod: "GET", groups: ["jira-administrators", "kvs-audit-admins"]) { MultivaluedMap queryParams ->

    def html = buildLevel1Html()

    return Response.ok(html)
            .type(MediaType.TEXT_HTML)
            .build()
}

private static String buildLevel1Html() {
    return '''
<style>
/* ── KVS Level 1 Checklist ── */

.l1-filter-bar {
    padding: 14px 20px; margin-bottom: 16px;
    background: #fff; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,.1);
    display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end;
}
.l1-filter-group { display: flex; flex-direction: column; gap: 3px; }
.l1-filter-group label { font-size: 11px; font-weight: 600; color: #555; text-transform: uppercase; }
.l1-filter-group select, .l1-filter-group input {
    padding: 6px 10px; border: 1px solid #ccc; border-radius: 4px;
    font-size: 13px; min-width: 180px; background: #fff;
}
.l1-btn {
    padding: 7px 18px; border: none; border-radius: 4px; cursor: pointer;
    font-size: 13px; font-weight: 600; color: #fff; background: #0052CC;
}
.l1-btn:hover { background: #0747A6; }
.l1-btn-print { background: #36B37E; }
.l1-btn-print:hover { background: #2D9F6F; }
.l1-spacer { flex: 1; }

.l1-page {
    padding: 20px; background: #fff; border-radius: 8px;
    box-shadow: 0 2px 4px rgba(0,0,0,.1); overflow-x: auto;
}

/* Print-only report header */
.l1-report-header { display: block; margin-bottom: 10px; }

.l1-report-header table { width: 100%;  border-collapse: separate;  table-layout: fixed; font-size: 11px; margin-bottom: 8px;}
.l1-report-header td { padding: 2px 4px; border: 0.6px solid #444; vertical-align: middle; }

.l1-rh-label { font-weight: 700; background: #eee; width: 160px; }

/* Checklist table */
.l1-table { /*width: 100%;*/ border-collapse: separate; font-size: 11px; }
.l1-table th, .l1-table td {
    border: 1px solid #999; padding: 3px 4px; text-align: center; vertical-align: middle;
}
.l1-table thead th { background: #e8e8e8; font-weight: 700; }
.l1-col-id    { width: 20px; min-width: 10px;}
.l1-col-std   { width: 80px; text-align: left !important; font-size: 10px; min-width: 60px;}
.l1-col-text  { width: 300px; text-align: left !important; font-size: 10px; min-width: 250px;}
.l1-col-day   { width: 48px; font-size: 10px; min-width: 30px;}
.l1-col-check { width: 20px; min-width: 20px; }

.l1-std-cell  { text-align: left !important; font-weight: 600; font-size: 10px; background: #f5f5f5; }
.l1-text-cell { text-align: left !important; font-size: 10px; }
.l1-day-label { font-size: 10px; white-space: nowrap; }
.l1-row-nio .l1-day-label { color: #888; }

.l1-cell-disabled {
    background: repeating-linear-gradient(45deg, #ddd, #ddd 2px, #eee 2px, #eee 5px) !important;
}
.l1-signature-row td { height: 34px; border-top: 2px solid #555; }

.l1-report-footer {
    margin-top: 10px; font-size: 10px; color: #777;
    display: flex; gap: 30px;
}
.l1-state-msg { text-align: center; padding: 40px; color: #888; font-size: 15px; }

.wp-last { border-right: 2.5px solid #333 !important;}


/* Print overrides */
@media print {
    .l1-filter-bar { display: none !important; }
    .l1-page { box-shadow: none; padding: 0; border-radius: 0; }
    .l1-report-header { display: block; }
    
    .l1-table { table-layout: fixed !important; width: 100% !important; }

    .l1-table thead th {
        background: #e0e0e0 !important; 
        white-space: normal !important;   /* enables wrapping */
        word-break: break-word !important; /* splits very long tokens */
        max-width: 120px !important;       /* constrain width of WP headers */
        overflow-wrap: break-word !important;
        border: 0.6px solid #444 !important; 
        font-weight: 700; 
    }


    .l1-std-cell {
        background: #f5f5f5 !important;
        -webkit-print-color-adjust: exact; print-color-adjust: exact;
    }
    .l1-cell-disabled {
        background: repeating-linear-gradient(45deg,#ccc,#ccc 2px,#e0e0e0 2px,#e0e0e0 5px) !important;
        -webkit-print-color-adjust: exact; print-color-adjust: exact;
    }

    .wp-last {
        border-right: 2.5px solid #000 !important;
    }


    .l1-col-check { width: 22px !important; padding: 1px !important; }

    @page { size: landscape; margin: 8mm; }
}
</style>

<!-- ═══ Filter Bar ═══ -->
<div class="l1-filter-bar">
    <div class="l1-filter-group">
        <label>Profit Center</label>
        <select id="l1-selPC"><option value="">-- loading --</option></select>
    </div>
    <div class="l1-filter-group">
        <label>Functional Area</label>
        <select id="l1-selFA" disabled><option value="">-- select PC first --</option></select>
    </div>
    <div class="l1-filter-group">
        <label>Weekday</label>
        <select id="l1-selDay">
            <option value="">All (Mon-Fri)</option>
            <option value="Mon">Monday</option>
            <option value="Tue">Tuesday</option>
            <option value="Wed">Wednesday</option>
            <option value="Thu">Thursday</option>
            <option value="Fri">Friday</option>
        </select>
    </div>
    <div class="l1-filter-group">
        <label>Monday date</label>
        <input type="date" id="l1-inpDate" />
    </div>
    <div class="l1-spacer"></div>
    <button class="l1-btn" id="l1-btnLoad">Load</button>
    <button class="l1-btn l1-btn-print" id="l1-btnPrint" disabled>Print / PDF</button>
</div>

<!-- ═══ Page Container ═══ -->
<div class="l1-page">
    <div class="l1-state-msg" id="l1-stateMsg">Please select Profit Center and Functional Area.</div>
    <div class="l1-report-header" id="l1-reportHeader"></div>
    <div id="l1-tableWrap"></div>
    <div class="l1-report-footer" id="l1-reportFooter" style="display:none">
        <span>KVS Level 1 Checklist</span>
        <span id="l1-footerDate"></span>
    </div>
</div>
'''
}