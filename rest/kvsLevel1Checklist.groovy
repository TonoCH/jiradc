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

/* ═══════════════════════════════════════════════════════════════════════
   PRINT GEOMETRY = CSS CUSTOM PROPERTIES

   Every size that decides how much of the sheet gets used lives here as a
   custom property on .l1-page. Both the on-screen preview and the printout
   read the SAME properties, so what the auditor measures on screen is what
   comes out of the printer.

   JS (l1ApplyMetrics) overwrites them as inline styles on .l1-page after it
   has measured the real, dynamically assembled table (N workplaces x M days
   x K questions). The values below are only the fallback for the moment
   between "table rendered" and "metrics computed".
   ═══════════════════════════════════════════════════════════════════════ */
.l1-page {
    --l1-page-w:      100%;      /* printable width  = paper - 2 x margin */
    --l1-font:        8.7pt;     /* checklist body font                   */
    --l1-lh:          1.15;
    --l1-pad-v:       0.95mm;
    --l1-pad-h:       1.1mm;
    --l1-row-min:     5.2mm;     /* min row height - the "fill" lever     */
    --l1-col-id:      10mm;
    --l1-col-std:     30mm;
    --l1-col-text:    auto;      /* auto = absorb whatever is left        */
    --l1-col-check:   6.2mm;
    --l1-day-font:    0.92em;
    --l1-hdr-font:    8.5pt;     /* report header block                   */
    --l1-hdr-pad-v:   1.1mm;
    --l1-hdr-pad-h:   1.8mm;
    --l1-resp-blank:  7mm;       /* blank line for the responsible person */
}

.l1-filter-bar {
    padding: 14px 20px; margin-bottom: 10px;
    background: #fff; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,.1);
    display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end;
}
.l1-filter-bar-print { padding: 10px 20px; margin-bottom: 16px; background: #f7f8f9; }
.l1-filter-group { display: flex; flex-direction: column; gap: 3px; }
.l1-filter-group label { font-size: 11px; font-weight: 600; color: #555; text-transform: uppercase; }
.l1-filter-group select, .l1-filter-group input {
    padding: 6px 10px; border: 1px solid #ccc; border-radius: 4px;
    font-size: 13px; min-width: 180px; background: #fff;
}
.l1-filter-bar-print .l1-filter-group select { min-width: 140px; }
.l1-btn {
    padding: 7px 18px; border: none; border-radius: 4px; cursor: pointer;
    font-size: 13px; font-weight: 600; color: #fff; background: #0052CC;
}
.l1-btn:hover { background: #0747A6; }
.l1-btn-print { background: #36B37E; }
.l1-btn-print:hover { background: #2D9F6F; }
.l1-spacer { flex: 1; }

/* Live feedback on what auto-fit decided — pages / font / row height / fill */
.l1-fit-info {
    font-size: 12px; color: #42526E; white-space: nowrap;
    padding-bottom: 7px;
}
.l1-fit-info b { color: #172B4D; }
.l1-fit-warn { color: #BF2600; }

/* The scroll host carries the "card" chrome so that .l1-page itself can be
   exactly one printable page wide — no padding, no border — and therefore
   behaves on screen exactly like it does on paper. */
.l1-page-scroll {
    padding: 20px; background: #fff; border-radius: 8px;
    box-shadow: 0 2px 4px rgba(0,0,0,.1); overflow-x: auto;
}
.l1-page {
    width: var(--l1-page-w);
    max-width: 100%;
    background: #fff;
}

/* Print-only report header */
.l1-report-header { display: block; margin-bottom: 8px; }
.l1-report-header table {
    width: 100%; border-collapse: collapse; table-layout: fixed;
    font-size: var(--l1-hdr-font); margin-bottom: 4px;
}
.l1-report-header td {
    padding: var(--l1-hdr-pad-v) var(--l1-hdr-pad-h);
    border: 0.45pt solid #555; vertical-align: middle; line-height: 1.15;
}

.l1-rh-label { font-weight: 700; background: #eee; width: 42mm; }

/* Hint above checklist */
.l1-hint {
    font-size: var(--l1-hdr-font); font-style: italic; color: #444;
    margin: 4px 0 6px 0; padding: 1.2mm 2mm;
    background: #fff8e1; border-left: 3px solid #FFAB00; border-radius: 3px;
}

/* ── Checklist table ── */

.l1-table {
    width: 100%; border-collapse: collapse; table-layout: fixed;
    font-size: var(--l1-font); line-height: var(--l1-lh);
}
.l1-table th,
.l1-table td {
    /* border-box so that --l1-row-min is the FULL row height. With the default
       content-box the padding is added on top of it, and the auto-fit
       simulation (which reasons in measured, border-box millimetres) would
       under-predict every row by 2 x padding and overflow the page. */
    box-sizing: border-box;
    border: 0.45pt solid #666;
    padding: var(--l1-pad-v) var(--l1-pad-h);
    text-align: center; vertical-align: middle; line-height: var(--l1-lh);
}

.l1-table thead th {
    background: #e8e8e8; font-weight: 700;
    /* Workplace names are long and contain words wider than a narrow column
       ("Hochlastregale", "PORTALFRAESMASCHINE"). Without these they overflow
       the cell and print on top of the neighbouring workplace. They must live
       here rather than in @media print, so the measuring pass sees the same
       geometry the printer will produce. */
    white-space: normal;
    overflow-wrap: break-word;
    word-break: break-word;
    hyphens: auto;
}

/* Column widths — <col> tags are emitted by JS; the widths come from the
   custom properties so one JS pass drives screen + print alike. */
col.l1-col-id    { width: var(--l1-col-id); }
col.l1-col-std   { width: var(--l1-col-std); }
col.l1-col-text  { width: var(--l1-col-text); }
col.l1-col-check { width: var(--l1-col-check); }

/* Body cell tuning. The Standard column holds single long compound words
   ("Materialbereitstellung", "Betriebsanweisung") that are wider than the
   column at larger font sizes — without an explicit break they spill over the
   cell border into the question text instead of wrapping. */
.l1-col-std   { text-align: left !important; overflow-wrap: anywhere; word-break: break-word; hyphens: auto; }
.l1-col-text  { text-align: left !important; overflow-wrap: break-word; }
.l1-col-check { padding: 0 0.3mm !important; line-height: 1; }

/* Day sub-header: never break "Mo" into two lines — the column width is
   computed from the font size so the label always fits. */
.l1-table thead th.l1-col-check {
    white-space: nowrap; font-size: var(--l1-day-font); padding: 0.4mm 0.2mm !important;
}

/* THE FILL LEVER: a height on a table cell acts as a minimum. Auto-fit grows
   --l1-row-min until the rows reach the bottom of the last page. */
.l1-table tbody td { height: var(--l1-row-min); }

.l1-text-cell,
.l1-std-cell { vertical-align: top; }

/* Slightly smaller than the question text: the standard names are single long
   compound words ("Materialbereitstellung") and at full body size they no
   longer fit the column, so they get chopped mid-word. */
.l1-std-cell  { text-align: left !important; font-weight: 600; background: #f5f5f5; font-size: 0.86em; }
/* Repeated standard label on the 2nd+ row of a group: quieter, so the column
   reads as "category continues" rather than loud repetition. */
.l1-std-cont  { font-weight: 400 !important; color: #888; }
.l1-text-cell { text-align: left !important; }

/* Print header — Responsible-person sub-table (per workplace) */
.l1-rh-resp        { margin-top: 3px; }
.l1-rh-wp-name     { font-weight: 600; background: #f5f5f5; text-align: center; }
.l1-rh-resp-blank  { height: var(--l1-resp-blank); }

/* Cells for a day this question is not audited on.
   The hatch is a background, and Chrome drops every background when the print
   dialog's "Background graphics" box is unchecked — which would make a cell
   that must NOT be filled in look exactly like one that must. The dash is real
   text, so it prints either way and the form stays unambiguous. */
.l1-cell-disabled {
    background: repeating-linear-gradient(45deg, #ddd, #ddd 2px, #eee 2px, #eee 5px) !important;
}
.l1-cell-disabled::after {
    content: "\2013";
    color: #8c8c8c;
    font-size: 0.85em;
}
.l1-signature-row td { height: 12mm; border-top: 2px solid #555; }
.l1-signature-label  { text-align: left !important; font-weight: 700; }

.l1-report-footer {
    margin-top: 6px; font-size: 8pt; color: #777;
    display: flex; gap: 30px;
}
.l1-state-msg { text-align: center; padding: 40px; color: #888; font-size: 15px; }

.wp-last { border-right: 2.5px solid #333 !important; }

/* Off-screen twin used by the auto-fit measuring pass. Same classes, same
   custom properties, exact printable width → its geometry IS the print
   geometry, so no guessing about how the dynamic table will paginate. */
.l1-measure {
    position: absolute !important;
    left: -30000px !important; top: 0 !important;
    visibility: hidden !important;
    padding: 0 !important; box-shadow: none !important;
    max-width: none !important;
    contain: layout;
}

/* ── Print overrides ── */
@media print {

    /* Jira page furniture must not eat into the sheet. */
    html, body { margin: 0 !important; padding: 0 !important; background: #fff !important; }
    #header, .aui-header, #footer, .aui-banner, #announcement-banner,
    .aui-page-header, .aui-sidebar, #studio-hint-panel, .global-hint {
        display: none !important;
    }
    #main, .aui-page-panel, .aui-page-panel-inner, .aui-page-panel-content,
    #content, .page-content {
        margin: 0 !important; padding: 0 !important; border: 0 !important;
        width: auto !important; background: #fff !important;
    }

    .l1-filter-bar { display: none !important; }

    .l1-page-scroll {
        box-shadow: none; padding: 0; border-radius: 0; overflow: visible;
    }
    .l1-page { max-width: none; }

    .l1-report-header { display: block; }

    .l1-table {
        table-layout: fixed !important;
        width: 100% !important;
    }

    .l1-table thead th {
        background: #e9e9e9 !important;
        border: 0.45pt solid #555 !important;
    }
    .l1-table thead th.l1-col-check { white-space: nowrap !important; }

    .l1-table th,
    .l1-table td {
        border: 0.45pt solid #555 !important;
    }

    /* Repeat the column header on every sheet. */
    .l1-table thead { display: table-header-group; }

    /* A question must never be sliced in half by a page break — that produced
       orphan text with an empty ID/Standard cell on the following page. */
    .l1-table tr {
        break-inside: avoid !important;
        page-break-inside: avoid !important;
    }

    .l1-std-cell {
        background: #f3f3f3 !important;
        -webkit-print-color-adjust: exact;
        print-color-adjust: exact;
    }

    .l1-cell-disabled {
        background: repeating-linear-gradient(45deg,#d7d7d7,#d7d7d7 2px,#ececec 2px,#ececec 5px) !important;
        -webkit-print-color-adjust: exact;
        print-color-adjust: exact;
    }

    .wp-last { border-right: 1.1pt solid #333 !important; }

    .l1-hint {
        background: #fff !important;
        border-left: 1.2pt solid #444 !important;
        -webkit-print-color-adjust: exact;
        print-color-adjust: exact;
    }

    .l1-signature-row,
    .l1-signature-row td {
        break-inside: avoid !important;
        page-break-inside: avoid !important;
    }
}
</style>

<!-- Dynamic @page rule target. JS rewrites this element's content right
     before window.print() to set the chosen paper size, orientation and
     margin. Placed after the main stylesheet so its @page wins the cascade. -->
<style id="l1-page-style"></style>

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
    <div class="l1-filter-group">
        <label>Language</label>
        <select id="l1-selLang">
            <option value="DE">Deutsch</option>
            <option value="EN">English</option>
            <option value="SK">Slovenčina</option>
        </select>
    </div>
    <div class="l1-filter-group">
        <label>Paper / Orientation</label>
        <select id="l1-selPaper">
            <option value="A4-landscape">A4 — on landscape</option>
            <option value="A3-landscape">A3 — on landscape</option>
            <option value="A3-portrait">A3 — on portrait</option>
        </select>
    </div>
    <div class="l1-spacer"></div>
    <button class="l1-btn" id="l1-btnLoad">Load</button>
    <button class="l1-btn l1-btn-print" id="l1-btnPrint" disabled>Print / PDF</button>
</div>

<!-- ═══ Print layout settings ═══ -->
<div class="l1-filter-bar l1-filter-bar-print">
    <div class="l1-filter-group">
        <label>Page fill</label>
        <select id="l1-selFit">
            <option value="auto">Auto-fit — readable text</option>
            <option value="dense">Auto-fit — fewest pages</option>
            <option value="rows">Fill rows only</option>
            <option value="fixed">Fixed (no fitting)</option>
        </select>
    </div>
    <div class="l1-filter-group">
        <label>Font size</label>
        <select id="l1-selFont">
            <option value="auto">Auto</option>
            <option value="7">7 pt</option>
            <option value="7.5">7.5 pt</option>
            <option value="8">8 pt</option>
            <option value="8.5">8.5 pt</option>
            <option value="9">9 pt</option>
            <option value="9.5">9.5 pt</option>
            <option value="10">10 pt</option>
            <option value="11">11 pt</option>
            <option value="12">12 pt</option>
            <option value="13">13 pt</option>
        </select>
    </div>
    <div class="l1-filter-group">
        <label>Row density</label>
        <select id="l1-selDensity">
            <option value="compact">Compact</option>
            <option value="normal" selected>Normal</option>
            <option value="comfort">Comfortable</option>
        </select>
    </div>
    <div class="l1-filter-group">
        <label>Page margin</label>
        <select id="l1-selMargin">
            <option value="5">5 mm</option>
            <option value="6" selected>6 mm</option>
            <option value="8">8 mm</option>
            <option value="10">10 mm</option>
            <option value="12">12 mm</option>
            <option value="25.4">25.4 mm (browser default)</option>
        </select>
    </div>
    <div class="l1-filter-group">
        <label>Question column</label>
        <select id="l1-selTextW">
            <option value="auto">Auto</option>
            <option value="70">70 mm</option>
            <option value="90">90 mm</option>
            <option value="110">110 mm</option>
            <option value="130">130 mm</option>
            <option value="150">150 mm</option>
            <option value="180">180 mm</option>
            <option value="0">As wide as possible</option>
        </select>
    </div>
    <div class="l1-spacer"></div>
    <span class="l1-fit-info" id="l1-fitInfo"></span>
</div>

<!-- ═══ Page Container ═══ -->
<div class="l1-page-scroll">
    <div class="l1-page">
        <div class="l1-state-msg" id="l1-stateMsg">Please select Profit Center and Functional Area.</div>
        <div class="l1-report-header" id="l1-reportHeader"></div>
        <div class="l1-hint" id="l1-hint" style="display:none"></div>
        <div id="l1-tableWrap"></div>
        <div class="l1-report-footer" id="l1-reportFooter" style="display:none">
            <span>KVS Level 1 Checklist</span>
            <span id="l1-footerDate"></span>
        </div>
    </div>
</div>
'''
}
