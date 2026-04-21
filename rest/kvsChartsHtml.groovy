package rest

/**
 * kvsChartsHtml
 *
 * @author chabrecek.anton
 * Created on 27. 2. 2026.
 *
 * Extended layout supporting:
 *  - PC scope picker (PC2 / PC3 / PC4 / PC6 / PC9 / Overall)
 *  - SVG half-circle gauge (R/Y/G zones) for overall KPI
 *  - per-category gauges grid
 *  - trend performance line chart
 *  - 3 separate history line charts (open / closed / created measures)
 *  - audit execution stacked bar + audit-rate line overlay
 *  - status donut (kept)
 *  - tables: open measures, closed measures (last 30 days), open audits
 */

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.transform.BaseScript

import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response

@BaseScript CustomEndpointDelegate delegate

kvsChartsHtml(httpMethod: "GET", groups: ["jira-administrators", "kvs-audit-admins"]) {
    MultivaluedMap queryParams ->

        def html = """
<style>
    .kvs-dashboard { font-family: Arial, sans-serif; font-size: 14px; margin: 0; padding: 20px; background-color: #f4f5f7; }
    .kvs-dashboard .container { max-width: 1400px; margin: auto; background: #ffffff; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
    .kvs-dashboard .title { font-size: 24px; font-weight: bold; margin: 0; }
    .kvs-dashboard .subtitle { font-size: 13px; color: #555; margin-bottom: 10px; }

    .kvs-topbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 18px; flex-wrap: wrap; }
    .kvs-topbar .left, .kvs-topbar .right { display: flex; align-items: center; gap: 10px; }
    .kvs-topbar label { font-weight: 600; color: #42526E; font-size: 12px; }
    .kvs-topbar select { padding: 6px 10px; border: 1px solid #dfe1e6; border-radius: 3px; background: #fafbfc; font-size: 13px; }
    .kvs-topbar button { padding: 6px 14px; border: none; border-radius: 3px; cursor: pointer; font-size: 13px; background: #0052CC; color: #fff; }
    .kvs-topbar button:hover { background: #0747A6; }
    .kvs-topbar .ds-badge { font-size: 11px; padding: 3px 8px; border-radius: 10px; background: #DFE1E6; color: #42526E; }
    .kvs-topbar .ds-badge.snapshot { background: #E3FCEF; color: #006644; }
    .kvs-topbar .ds-badge.onthefly { background: #FFF0B3; color: #974F0C; }
    .kvs-topbar .ds-badge.mixed    { background: #DEEBFF; color: #0747A6; }

    .chart-grid { display: flex; flex-wrap: wrap; gap: 16px; justify-content: flex-start; }
    .chart-card { flex: 1 1 400px; max-width: 520px; background: #fafbfc; padding: 14px; border-radius: 6px; box-shadow: 0 1px 2px rgba(0,0,0,0.08); box-sizing: border-box; }
    .chart-card--wide  { flex: 1 1 820px; max-width: 1060px; }
    .chart-card--full  { flex: 1 1 100%; max-width: none; }
    .chart-card--small { flex: 1 1 320px; max-width: 360px; }
    .chart-card--tiny  { flex: 1 1 180px; max-width: 220px; }
    .chart-card h3 { font-size: 14px; font-weight: 700; margin: 0 0 8px 0; color: #172B4D; }
    .chart-card .chart-desc { font-size: 11px; color: #6B778C; margin-top: 6px; line-height: 1.35; }

    /* SVG gauge */
    .kvs-gauge-wrap { display: flex; flex-direction: column; align-items: center; gap: 6px; }
    .kvs-gauge-wrap svg { width: 100%; height: auto; max-width: 260px; }
    .kvs-gauge-label { font-size: 12px; color: #6B778C; font-weight: 600; }
    .kvs-gauge-value { font-size: 28px; font-weight: 700; color: #172B4D; line-height: 1; }
    .kvs-gauge-sub { font-size: 11px; color: #6B778C; }

    .kvs-category-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 10px; }
    .kvs-category-item { background: #fff; padding: 10px 8px 8px 8px; border-radius: 4px; border: 1px solid #ebecf0; text-align: center; }
    .kvs-category-item .cat-name { font-size: 11px; font-weight: 600; color: #172B4D; margin-bottom: 4px; min-height: 28px; display: flex; align-items: center; justify-content: center; }
    .kvs-category-item .cat-value { font-size: 18px; font-weight: 700; }

    .kvs-overall-breakdown { display: flex; flex-wrap: wrap; gap: 8px 16px; font-size: 12px; margin-top: 8px; }
    .kvs-overall-breakdown .row { min-width: 120px; display: flex; justify-content: space-between; gap: 8px; }
    .kvs-overall-breakdown .row .k { color: #6B778C; }
    .kvs-overall-breakdown .row .v { color: #172B4D; font-weight: 600; }

    /* Tables */
    .kvs-table-wrap { overflow-x: auto; }
    .kvs-table { width: 100%; border-collapse: collapse; font-size: 12px; background: #fff; }
    .kvs-table th, .kvs-table td { border: 1px solid #ebecf0; padding: 6px 8px; text-align: left; vertical-align: top; }
    .kvs-table th { background: #f4f5f7; color: #42526E; font-weight: 600; position: sticky; top: 0; }
    .kvs-table tbody tr:nth-child(odd) { background: #fafbfc; }
    .kvs-table .key-cell a { color: #0052CC; text-decoration: none; }
    .kvs-table .count-empty { font-style: italic; color: #6B778C; padding: 10px; }
    .kvs-table .status-pill { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; background: #dfe1e6; color: #42526E; }

    canvas { max-width: 100%; }

    .footer { margin-top: 20px; font-size: 11px; text-align: center; color: #777; }

    /* Print tweaks */
    @media print {
        .kvs-topbar button, .kvs-topbar select { display: none; }
        .chart-card { page-break-inside: avoid; }
    }
</style>

<div class="kvs-dashboard">
  <div class="container">

    <div class="kvs-topbar">
      <div class="left">
        <h1 class="title">KVS Charts</h1>
        <span id="kvs-ds-badge" class="ds-badge">–</span>
      </div>
      <div class="right">
        <label for="kvs-pc-select">Profit Center:</label>
        <select id="kvs-pc-select">
          <option value="overall">Overall</option>
          <option value="PC2">PC2</option>
          <option value="PC3">PC3</option>
          <option value="PC4">PC4</option>
          <option value="PC6">PC6</option>
          <option value="PC9" selected>PC9</option>
        </select>
        <label for="kvs-weeks-input">Weeks:</label>
        <select id="kvs-weeks-input">
          <option>4</option>
          <option>8</option>
          <option selected>12</option>
          <option>24</option>
          <option>52</option>
        </select>
        <button id="kvs-refresh-btn" type="button">Refresh</button>
        <button id="kvs-print-btn" type="button">Print / PDF</button>
      </div>
    </div>

    <div class="subtitle" id="kvs-subtitle">–</div>

    <!-- Row 1: Overall KPI gauge + per-category grid + trend (3 cards side-by-side) -->
    <div class="chart-grid">

      <div class="chart-card">
        <h3>Overall Performance</h3>
        <div id="overallGauge" class="kvs-gauge-wrap"></div>
        <div id="overallBreakdown" class="kvs-overall-breakdown"></div>
        <div class="chart-desc">Rolling KPI across the selected window. Red &lt; 75%, Yellow 75–90%, Green &ge; 90%.</div>
      </div>

      <div class="chart-card">
        <h3>Performance per Category</h3>
        <div id="categoryGrid" class="kvs-category-grid"></div>
        <div class="chart-desc">Per-category KPI from the latest weekly snapshot (based on "Category EN" on Question issues).</div>
      </div>

      <div class="chart-card">
        <h3>Trend – Overall Performance</h3>
        <canvas id="trendPerfChart"></canvas>
        <div class="trendPerfChart-chart-desc chart-desc">Overall KPI % per week over the selected window.</div>
      </div>

    </div>

    <!-- Row 2: History of open / closed / created measures -->
    <div class="chart-grid" style="margin-top: 16px;">
      <div class="chart-card">
        <h3>History – Open Measures</h3>
        <canvas id="histOpenChart"></canvas>
        <div class="histOpenChart-chart-desc chart-desc">Count of open measures per week.</div>
      </div>
      <div class="chart-card">
        <h3>History – Closed Measures</h3>
        <canvas id="histClosedChart"></canvas>
        <div class="histClosedChart-chart-desc chart-desc">Count of resolved measures per week.</div>
      </div>
      <div class="chart-card">
        <h3>History – Created Measures</h3>
        <canvas id="histCreatedChart"></canvas>
        <div class="histCreatedChart-chart-desc chart-desc">Count of newly created measures per week.</div>
      </div>
    </div>

    <!-- Row 3: Audit execution + status donut -->
    <div class="chart-grid" style="margin-top: 16px;">
      <div class="chart-card chart-card--wide">
        <h3>Audit Execution</h3>
        <canvas id="auditExecChart"></canvas>
        <div class="auditExecChart-chart-desc chart-desc">Stacked bars: open vs. closed audits per week. Line overlay: audit rate (closed / total) in %.</div>
      </div>
      <div class="chart-card">
        <h3>Question Status Distribution</h3>
        <canvas id="statusChart"></canvas>
        <div class="statusChart-chart-desc chart-desc">Aggregated Question statuses from the latest snapshot.</div>
      </div>
    </div>

    <!-- Row 4: Open Measures table -->
    <div class="chart-grid" style="margin-top: 16px;">
      <div class="chart-card chart-card--full">
        <h3>Open Measures <span id="openMeasuresCount" class="ds-badge">0</span></h3>
        <div class="kvs-table-wrap">
          <table class="kvs-table" id="openMeasuresTable">
            <thead>
              <tr>
                <th>Date</th>
                <th>Measure Key</th>
                <th>Measure</th>
                <th>Question</th>
                <th>Deviation</th>
                <th>Audit Location</th>
                <th>Profit Center</th>
                <th>Responsible</th>
                <th>Person Responsibility</th>
                <th>Status</th>
                <th>Level</th>
              </tr>
            </thead>
            <tbody></tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Row 5: Closed Measures (last 30 days) table -->
    <div class="chart-grid" style="margin-top: 16px;">
      <div class="chart-card chart-card--full">
        <h3>Closed Measures (last 30 days) <span id="closedMeasuresCount" class="ds-badge">0</span></h3>
        <div class="kvs-table-wrap">
          <table class="kvs-table" id="closedMeasuresTable">
            <thead>
              <tr>
                <th>Date</th>
                <th>Measure Key</th>
                <th>Measure</th>
                <th>Question</th>
                <th>Deviation</th>
                <th>Audit Location</th>
                <th>Profit Center</th>
                <th>Responsible</th>
                <th>Person Responsibility</th>
                <th>Status</th>
                <th>Level</th>
              </tr>
            </thead>
            <tbody></tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Row 6: Open Audits table -->
    <div class="chart-grid" style="margin-top: 16px;">
      <div class="chart-card chart-card--full">
        <h3>Open Audits <span id="openAuditsCount" class="ds-badge">0</span></h3>
        <div class="kvs-table-wrap">
          <table class="kvs-table" id="openAuditsTable">
            <thead>
              <tr>
                <th>Audit Key</th>
                <th>Audit ID</th>
                <th>Target End</th>
                <th>Week</th>
                <th>Level</th>
                <th>Profit Center</th>
                <th>Functional Area</th>
                <th>Workplaces</th>
                <th>Assignee</th>
                <th>Audit Type</th>
              </tr>
            </thead>
            <tbody></tbody>
          </table>
        </div>
      </div>
    </div>

    <div class="footer" id="kvs-footer">–</div>

  </div>
</div>
"""

        return Response.ok(html)
                .type(MediaType.TEXT_HTML)
                .build()
}