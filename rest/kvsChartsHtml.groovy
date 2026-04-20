package rest

/**
 * kvsChartsHtml
 *
 * @author chabrecek.anton
 * Created on 27. 2. 2026.
 */

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.transform.BaseScript
import groovy.json.JsonOutput

import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response

@BaseScript CustomEndpointDelegate delegate

kvsChartsHtml(httpMethod: "GET", groups: ["jira-administrators", "kvs-audit-admins"]) { MultivaluedMap queryParams ->

    def html = """

 <style>
        body {
            font-family: Arial, sans-serif;
            font-size: 14px;
            margin: 0;
            padding: 20px;
            background-color: #f4f5f7;
        }
        .container {
            max-width: 1200px;
            margin: auto;
            background: #ffffff;
            padding: 20px;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        .title {
            font-size: 24px;
            font-weight: bold;
            margin-bottom: 10px;
            text-align: center;
        }
        .subtitle {
            font-size: 13px;
            color: #555;
            margin-bottom: 20px;
            text-align: center;
        }
        .chart-grid {
            display: flex;
            flex-wrap: wrap;
            gap: 20px;
            justify-content: center;
        }
        .chart-card {
            flex: 1 1 400px;
            max-width: 500px;
            background: #fafbfc;
            padding: 15px;
            border-radius: 6px;
            box-shadow: 0 1px 2px rgba(0,0,0,0.08);
        }
        .chart-title {
            font-size: 16px;
            font-weight: bold;
            margin-bottom: 8px;
            text-align: center;
        }
        
        .chart-card--wide {
            flex: 1 1 820px;
            max-width: 1020px;
        }
        
        .chart-card--small {
            flex: 1 1 320px;
            max-width: 360px;
        }
        canvas {
            //width: 100% !important;
            //height: 260px !important;
        }
        .footer {
            margin-top: 20px;
            font-size: 11px;
            text-align: center;
            color: #777;
        }
    </style>

        <div class="container">
            <div class="title">KVS Charts</div>
             <br />
             <div class="title" style="color:red">TEST DATA</div>

            
            <button onclick="window.print()"style="padding:8px 16px; background:#0052CC; color:white; border:none; border-radius:4px; cursor:pointer; font-size:14px; float:right;">
                Print / Save as PDF
            </button>

            <div class="chart-grid">
                <div class="chart-card chart-card--small">
                    <canvas id="gaugeChart"></canvas>
                    <div class="gaugeChart-chart-desc">Current 12-week KPI key figure.</div>
                </div>
            
                <div class="chart-card chart-card--wide">
                    <canvas id="trendChart"></canvas>
                    <div class="trendChart-chart-desc">Trend: KPI %, open measures, closed measures.</div>
                </div>
            
                <div class="chart-card">
                    <canvas id="measuresChart"></canvas>
                    <div class="measuresChart-chart-desc">Measures created / resolved / open / done.</div>
                </div>
            
                <div class="chart-card">
                    <canvas id="statusChart"></canvas>
                    <div class="statusChart-chart-desc">Question status distribution.</div>
                </div>
            
                <div class="chart-card">
                    <canvas id="questionsChart"></canvas>
                    <div class="questionsChart-chart-desc">Questions open vs done over time.</div>
                </div>
            
                <div class="chart-card">
                    <canvas id="scatterChart"></canvas>
                    <div class="scatterChart-chart-desc">Relationship open vs done measures.</div>
                </div>
            </div>
            
        </div>
        """

    return Response.ok(html)
            .type(MediaType.TEXT_HTML)
            .build()
}