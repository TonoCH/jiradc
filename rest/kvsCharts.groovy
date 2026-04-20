package rest

/**
 * kvsCharts
 *
 * @author chabrecek.anton
 * Created on 18. 2. 2026.
 */

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.transform.BaseScript
import groovy.json.JsonOutput

import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response

@BaseScript CustomEndpointDelegate delegate

kvsCharts(httpMethod: "GET", groups: ["jira-administrators", "kvs-audit-admins"])
        { MultivaluedMap queryParams ->

            def sb = new StringBuilder();

            // Example labels + single data source (one field of data)
            def labels = ["Jan", "Feb", "Mar", "Apr", "May", "Jun"]
            def values = [5, 9, 3, 7, 4, 8]

            def wineCountries = ["Italy", "France", "Spain", "USA", "Argentina"]
            def wineProd = [55, 49, 44, 24, 15]
            String wineCountriesJson = JsonOutput.toJson(wineCountries)
            String wineProdJson = JsonOutput.toJson(wineProd)

            String labelsJson = JsonOutput.toJson(labels)
            String valuesJson = JsonOutput.toJson(values)


            sb.append(
                    """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>KVS Charts – Test</title>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">

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
        canvas {
            width: 100% !important;
            height: 260px !important;
        }
        .footer {
            margin-top: 20px;
            font-size: 11px;
            text-align: center;
            color: #777;
        }
    </style>

    <!-- Chart.js v2.9.4 from CDN; later you can replace this with local resource -->
    <script src="https://cdnjs.cloudflare.com/ajax/libs/Chart.js/2.9.4/Chart.js"></script>
    <script src="Chart.js"></script>
</head>
<body>
<div class="container">
    <div class="title">KVS Charts</div>
    <div class="subtitle">
        Chart.js Demo (ScriptRunner REST Endpoint). All charts use one simple data set.
    </div>

<button onclick="window.print()"
        style="
            padding:8px 16px;
            background:#0052CC;
            color:white;
            border:none;
            border-radius:4px;
            cursor:pointer;
            font-size:14px;
            float:right;
        ">
    Print / Save as PDF
</button>


    <div class="chart-grid">
        <div class="chart-card">
            <div class="chart-title">Bar Chart (Jan–Jun)</div>
            <canvas id="barChart"></canvas>
        </div>

        <div class="chart-card">
            <div class="chart-title">Line Chart (Jan–Jun)</div>
            <canvas id="lineChart"></canvas>
        </div>

        <div class="chart-card">
            <div class="chart-title">Pie Chart (Jan–Jun)</div>
            <canvas id="pieChart"></canvas>
        </div>

        <div class="chart-card">
            <div class="chart-title">Doughnut Chart (Jan–Jun)</div>
            <canvas id="doughnutChart"></canvas>
        </div>

        <div class="chart-card">
            <div class="chart-title">Scatter Chart (Jan–Jun)</div>
            <canvas id="scatterChart"></canvas>
        </div>

        <div class="chart-card">
            <div class="chart-title">Lines Chart (Jan–Jun)</div>
            <canvas id="linesChart"></canvas>
        </div>

        <div class="chart-card">
            <div class="chart-title">Multi Lines Chart (Jan–Jun)</div>
            <canvas id="mlinesChart"></canvas>
        </div>

        <div class="chart-card">
            <div class="chart-title">Linear Chart</div>
            <canvas id="linearChart"></canvas>
        </div>

        <div class="chart-card">
            <div class="chart-title">Function Chart</div>
            <canvas id="functionChart"></canvas>
        </div>
    </div>

    <div class="footer">
        Generated by ScriptRunner REST endpoint <strong>kvsCharts</strong>. Replace sample data with Jira data later.
    </div>
</div>

<script>
    // Data injected from Groovy
    const labels = ${labelsJson};
    const values = ${valuesJson};

    const baseColors = [
        'rgba(54, 162, 235, 0.7)',
        'rgba(255, 99, 132, 0.7)',
        'rgba(255, 205, 86, 0.7)',
        'rgba(75, 192, 192, 0.7)',
        'rgba(153, 102, 255, 0.7)',
        'rgba(201, 203, 207, 0.7)'
    ];

    const borderColors = baseColors.map(function(c) {
        return c.replace('0.7', '1');
    });

    // ---- Charts using Jan–Jun data (Chart.js 2.x syntax) ----

    // Bar chart
    new Chart(document.getElementById('barChart'), {
        type: 'bar',
        data: {
            labels: labels,
            datasets: [{
                label: 'Values',
                data: values,
                backgroundColor: baseColors,
                borderColor: borderColors,
                borderWidth: 1
            }]
        },
        options: {
            responsive: true,
            legend: { position: 'top' },
            tooltips: { mode: 'index', intersect: false },
            scales: {
                yAxes: [{
                    ticks: { beginAtZero: true }
                }]
            }
        }
    });

    // Line chart
    new Chart(document.getElementById('lineChart'), {
        type: 'line',
        data: {
            labels: labels,
            datasets: [{
                label: 'Values',
                data: values,
                borderColor: 'rgba(54, 162, 235, 1)',
                backgroundColor: 'rgba(54, 162, 235, 0.2)',
                lineTension: 0.2,
                fill: true
            }]
        },
        options: {
            responsive: true,
            legend: { position: 'top' },
            tooltips: { mode: 'index', intersect: false },
            scales: {
                yAxes: [{
                    ticks: { beginAtZero: true }
                }]
            }
        }
    });

    // Pie chart
    new Chart(document.getElementById('pieChart'), {
        type: 'pie',
        data: {
            labels: labels,
            datasets: [{
                label: 'Values',
                data: values,
                backgroundColor: baseColors,
                borderColor: borderColors,
                borderWidth: 1
            }]
        },
        options: {
            responsive: true,
            legend: { position: 'right' }
        }
    });

    // Doughnut chart
    new Chart(document.getElementById('doughnutChart'), {
        type: 'doughnut',
        data: {
            labels: labels,
            datasets: [{
                label: 'Values',
                data: values,
                backgroundColor: baseColors,
                borderColor: borderColors,
                borderWidth: 1
            }]
        },
        options: {
            responsive: true,
            cutoutPercentage: 50,
            legend: { position: 'right' }
        }
    });

    // ----------------- FUNCTION CHART --------------------
    const funcXValues = [];
    const funcYValues = [];
    generateDataFunction("Math.sin(x)", 0, 10, 0.5);

    new Chart(document.getElementById('functionChart'), {
        type: 'line',
        data: {
            labels: funcXValues,
            datasets: [{
                fill: false,
                pointRadius: 2,
                borderColor: "rgba(0,0,255,0.5)",
                data: funcYValues
            }]
        },
        options: {
            legend: { display: false },
            title: {
                display: true,
                text: "y = sin(x)",
                fontSize: 16
            }
        }
    });

    function generateDataFunction(value, i1, i2, step) {
        step = step || 1;
        for (var x = i1; x <= i2; x += step) {
            funcYValues.push(eval(value));
            funcXValues.push(x);
        }
    }

    // ----------------- LINEAR CHART --------------------
    const linXValues = [];
    const linYValues = [];
    generateDataLinear("x * 2 + 7", 0, 10, 0.5);

    new Chart(document.getElementById('linearChart'), {
        type: 'line',
        data: {
            labels: linXValues,
            datasets: [{
                fill: false,
                pointRadius: 1,
                borderColor: "rgba(255,0,0,0.5)",
                data: linYValues
            }]
        },
        options: {
            legend: { display: false },
            title: {
                display: true,
                text: "y = x * 2 + 7",
                fontSize: 16
            }
        }
    });

    function generateDataLinear(value, i1, i2, step) {
        step = step || 1;
        for (var x = i1; x <= i2; x += step) {
            linXValues.push(x);
            linYValues.push(eval(value));
        }
    }

    // ----------------- MULTI LINES CHART --------------------
    const multiXValues = [100,200,300,400,500,600,700,800,900,1000];

    new Chart(document.getElementById('mlinesChart'), {
        type: 'line',
        data: {
            labels: multiXValues,
            datasets: [{
                data: [860,1140,1060,1060,1070,1110,1330,2210,7830,2478],
                borderColor: "red",
                fill: false
            }, {
                data: [1600,1700,1700,1900,2000,2700,4000,5000,6000,7000],
                borderColor: "green",
                fill: false
            }, {
                data: [300,700,2000,5000,6000,4000,2000,1000,200,100],
                borderColor: "blue",
                fill: false
            }]
        },
        options: {
            legend: { display: false }
        }
    });

    // ----------------- LINES CHART --------------------
    const lineXValues2 = [50,60,70,80,90,100,110,120,130,140,150];
    const lineYValues2 = [7,8,8,9,9,9,10,11,14,14,15];

    new Chart(document.getElementById('linesChart'), {
        type: 'line',
        data: {
            labels: lineXValues2,
            datasets: [{
                fill: false,
                lineTension: 0,
                backgroundColor: "rgba(0,0,255,1.0)",
                borderColor: "rgba(0,0,255,0.1)",
                data: lineYValues2
            }]
        },
        options: {
            legend: { display: false },
            scales: {
                yAxes: [{
                    ticks: { min: 6, max: 16 }
                }]
            }
        }
    });

    // ----------------- SCATTER CHART --------------------
    const scatterXY = [
        {x:50, y:7},
        {x:60, y:8},
        {x:70, y:8},
        {x:80, y:9},
        {x:90, y:9},
        {x:100, y:9},
        {x:110, y:10},
        {x:120, y:11},
        {x:130, y:14},
        {x:140, y:14},
        {x:150, y:15}
    ];

    new Chart(document.getElementById('scatterChart'), {
        type: "scatter",
        data: {
            datasets: [{
                pointRadius: 4,
                pointBackgroundColor: "rgb(0,0,255)",
                data: scatterXY
            }]
        },
        options: {
            legend: { display: false },
            scales: {
                xAxes: [{
                    ticks: { min: 40, max: 160 }
                }],
                yAxes: [{
                    ticks: { min: 6, max: 16 }
                }]
            }
        }
    });

</script>

</body>
</html>
""");


            Response.ok().type(MediaType.TEXT_HTML).entity(sb.toString()).build()
        }