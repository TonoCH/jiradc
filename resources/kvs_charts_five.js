AJS.toInit(function () {

  console.log("binding kvs charts click");

  AJS.$(document).on(
    'click',
    'a[data-link-id="com.onresolve.jira.groovy.groovyrunner:kvs-charts-ten"]',
    function (e) {
      e.preventDefault();
      e.stopPropagation();

      console.log("chart click detected");

      fetch(AJS.contextPath() + '/rest/scriptrunner/latest/custom/kvsChartsHtml', {
        method: 'GET',
        credentials: 'include'
      })
        .then(r => r.text())
        .then(html => {
          const main = document.querySelector('#main');
          if (!main) return;

          main.innerHTML = html;
          loadCharts();
        })
        .catch(err => console.error(err));
    }
  );

  function loadCharts() {
    console.log("loadCharts called");

    ensureChartJsLoaded()
      .then(() => fetch(AJS.contextPath() + '/rest/scriptrunner/latest/custom/kvsChartsData', {
        method: 'GET',
        credentials: 'include'
      }))
      .then(r => r.json())
      .then(data => {
        console.log("kvsChartsData", data);

        if (!window.Chart) {
          console.error("Chart.js not loaded");
          return;
        }

        destroyAllCharts();

        const trend = Array.isArray(data.trend) ? data.trend : [];
        const labels = Array.isArray(data.weeks) ? data.weeks : [];
        const keyFigureValues = Array.isArray(data.keyFigure) ? data.keyFigure : [];
        const pieLabels = data.pie && Array.isArray(data.pie.labels) ? data.pie.labels : [];
        const pieValues = data.pie && Array.isArray(data.pie.values) ? data.pie.values : [];
        const scatter = Array.isArray(data.scatter) ? data.scatter : [];

        const trendLabels = trend.map(x => x.week ? ('W' + x.week) : '');
        const performance = trend.map(x => toNumber(x.performancePct));
        const measuresOpen = trend.map(x => toNumber(x.measuresOpen));
        const measuresDone = trend.map(x => toNumber(x.measuresDone));
        const measuresCreated = trend.map(x => toNumber(x.measuresCreated));
        const measuresResolved = trend.map(x => toNumber(x.measuresResolved));
        const questionsOpen = trend.map(x => toNumber(x.questionsOpen));
        const questionsDone = trend.map(x => toNumber(x.questionsDone));

        const currentAvg = clamp(toNumber(data.keyFigureAvg12), 0, 9999);
        const pcLabel = resolvePcLabel(data);
        const latestWeekLabel = trendLabels.length ? trendLabels[trendLabels.length - 1] : (labels.length ? labels[labels.length - 1] : '-');

        renderKpiSummaryCard('gaugeChart', currentAvg, pcLabel, latestWeekLabel);
        renderTrendChart('trendChart', trendLabels, performance, measuresOpen, measuresDone);
        renderMeasuresChart('measuresChart', trendLabels, measuresCreated, measuresResolved, measuresOpen, measuresDone);
        renderStatusChart('statusChart', pieLabels, pieValues);
        renderQuestionsChart('questionsChart', trendLabels, questionsOpen, questionsDone);
        renderScatterChart('scatterChart', scatter, trendLabels, measuresOpen, measuresDone);

        fillGaugeDescription({
          pcLabel,
          currentAvg,
          latestWeekLabel,
          labels,
          keyFigureValues
        });

        fillTrendDescription({
          pcLabel,
          trendLabels,
          performance,
          measuresOpen,
          measuresDone
        });

        fillMeasuresDescription({
          pcLabel,
          trendLabels,
          measuresCreated,
          measuresResolved,
          measuresOpen,
          measuresDone
        });

        fillStatusDescription({
          pcLabel,
          pieLabels,
          pieValues
        });

        fillQuestionsDescription({
          pcLabel,
          trendLabels,
          questionsOpen,
          questionsDone
        });

        fillScatterDescription({
          pcLabel,
          scatter,
          trendLabels,
          measuresOpen,
          measuresDone
        });
      })
      .catch(err => console.error(err));
  }

  function destroyAllCharts() {
    if (!window.Chart || !Chart.instances) return;

    Object.keys(Chart.instances).forEach(function (key) {
      const chart = Chart.instances[key];
      if (chart && typeof chart.destroy === 'function') {
        chart.destroy();
      }
    });
  }

  function renderKpiSummaryCard(canvasId, currentAvg, pcLabel, latestWeekLabel) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) return;

    const container = canvas.parentElement;
    if (!container) return;

    canvas.style.display = 'none';

    let summary = container.querySelector('.kpi-summary-box');
    if (!summary) {
      summary = document.createElement('div');
      summary.className = 'kpi-summary-box';
      summary.style.padding = '22px 18px';
      summary.style.minHeight = '240px';
      summary.style.display = 'flex';
      summary.style.flexDirection = 'column';
      summary.style.justifyContent = 'center';
      summary.style.gap = '14px';
      summary.style.boxSizing = 'border-box';
      container.insertBefore(summary, canvas.nextSibling);
    }

    const normalized = clamp(currentAvg, 0, 100);
    const color = normalized >= 90 ? '#36B37E' : normalized >= 75 ? '#FFAB00' : '#FF5630';

    summary.innerHTML = ''
      + '<div style="font-size:12px;color:#6B778C;font-weight:600;">'
      + escapeHtml(pcLabel) + ' · 12-week KPI'
      + '</div>'
      + '<div style="font-size:42px;line-height:1;font-weight:700;color:#172B4D;">'
      + formatPercent(currentAvg)
      + '</div>'
      + '<div style="font-size:13px;color:#6B778C;">'
      + 'Current KPI average for ' + escapeHtml(latestWeekLabel || '-') + '.'
      + '</div>'
      + '<div style="width:100%;height:18px;background:#DFE1E6;border-radius:999px;overflow:hidden;">'
      + '  <div style="width:' + normalized + '%;height:100%;background:' + color + ';"></div>'
      + '</div>'
      + '<div style="display:flex;justify-content:space-between;font-size:12px;color:#6B778C;">'
      + '  <span>0%</span><span>Target 100%</span>'
      + '</div>';
  }

  function renderTrendChart(canvasId, labels, performance, measuresOpen, measuresDone) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) return;

    new Chart(canvas, {
      type: 'bar',
      data: {
        labels: labels,
        datasets: [
          {
            type: 'line',
            label: 'KPI Performance %',
            data: performance,
            borderColor: '#172B4D',
            backgroundColor: 'transparent',
            fill: false,
            lineTension: 0,
            borderWidth: 2,
            pointRadius: 3,
            yAxisID: 'yPerf'
          },
          {
            type: 'bar',
            label: 'Open measures',
            data: measuresOpen,
            backgroundColor: 'rgba(255,99,132,0.55)',
            borderColor: 'rgba(255,99,132,0.9)',
            borderWidth: 1,
            yAxisID: 'yCount'
          },
          {
            type: 'bar',
            label: 'Done measures',
            data: measuresDone,
            backgroundColor: 'rgba(54,162,235,0.55)',
            borderColor: 'rgba(54,162,235,0.9)',
            borderWidth: 1,
            yAxisID: 'yCount'
          }
        ]
      },
      options: commonChartOptions({
        legendPosition: 'top',
        yAxes: [
          {
            id: 'yPerf',
            position: 'left',
            ticks: {
              beginAtZero: true,
              min: 0,
              max: Math.max(100, roundUp(maxOf(performance), 10))
            },
            scaleLabel: {
              display: true,
              labelString: 'KPI Performance [%]'
            }
          },
          {
            id: 'yCount',
            position: 'right',
            ticks: {
              beginAtZero: true,
              max: roundUp(maxOf([].concat(measuresOpen, measuresDone)), 5)
            },
            scaleLabel: {
              display: true,
              labelString: 'Measures [#]'
            },
            gridLines: {
              drawOnChartArea: false
            }
          }
        ]
      })
    });
  }

  function renderMeasuresChart(canvasId, labels, created, resolved, open, done) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) return;

    new Chart(canvas, {
      type: 'line',
      data: {
        labels: labels,
        datasets: [
          {
            label: 'Created',
            data: created,
            borderColor: '#FFAB00',
            backgroundColor: 'transparent',
            fill: false,
            lineTension: 0,
            borderWidth: 2,
            pointRadius: 3
          },
          {
            label: 'Resolved',
            data: resolved,
            borderColor: '#36B37E',
            backgroundColor: 'transparent',
            fill: false,
            lineTension: 0,
            borderWidth: 2,
            pointRadius: 3
          },
          {
            label: 'Open',
            data: open,
            borderColor: '#FF5630',
            backgroundColor: 'transparent',
            fill: false,
            lineTension: 0,
            borderWidth: 2,
            pointRadius: 3
          },
          {
            label: 'Done',
            data: done,
            borderColor: '#0052CC',
            backgroundColor: 'transparent',
            fill: false,
            lineTension: 0,
            borderWidth: 2,
            pointRadius: 3
          }
        ]
      },
      options: commonChartOptions({
        legendPosition: 'top',
        yAxes: [{
          ticks: {
            beginAtZero: true,
            max: roundUp(maxOf([].concat(created, resolved, open, done)), 5)
          },
          scaleLabel: {
            display: true,
            labelString: 'Measures [#]'
          }
        }]
      })
    });
  }

  function renderStatusChart(canvasId, labels, values) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) return;

    const palette = [
      '#36B37E',
      '#FFAB00',
      '#FF5630',
      '#00B8D9',
      '#6554C0',
      '#DFE1E6',
      '#0052CC',
      '#8777D9',
      '#79F2C0'
    ];

    new Chart(canvas, {
      type: 'doughnut',
      data: {
        labels: labels,
        datasets: [{
          data: values,
          backgroundColor: labels.map((_, i) => palette[i % palette.length]),
          borderColor: '#FFFFFF',
          borderWidth: 1
        }]
      },
      options: Object.assign(
        {},
        commonChartOptions({ legendPosition: 'top', cutoutPercentage: 55 }),
        {
          tooltips: {
            callbacks: {
              label: function (tooltipItem, chartData) {
                const label = chartData.labels[tooltipItem.index] || '';
                const value = toNumber(chartData.datasets[0].data[tooltipItem.index]);
                const total = sum(chartData.datasets[0].data);
                const percent = total > 0 ? ((value / total) * 100) : 0;
                return label + ': ' + value + ' (' + formatPercent(percent) + ')';
              }
            }
          }
        }
      )
    });
  }

  function renderQuestionsChart(canvasId, labels, open, done) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) return;

    new Chart(canvas, {
      type: 'line',
      data: {
        labels: labels,
        datasets: [
          {
            label: 'Questions open',
            data: open,
            borderColor: '#FF5630',
            backgroundColor: 'transparent',
            fill: false,
            lineTension: 0,
            borderWidth: 2,
            pointRadius: 3
          },
          {
            label: 'Questions done',
            data: done,
            borderColor: '#36B37E',
            backgroundColor: 'transparent',
            fill: false,
            lineTension: 0,
            borderWidth: 2,
            pointRadius: 3
          }
        ]
      },
      options: commonChartOptions({
        legendPosition: 'top',
        yAxes: [{
          ticks: {
            beginAtZero: true,
            max: roundUp(maxOf([].concat(open, done)), 100)
          },
          scaleLabel: {
            display: true,
            labelString: 'Questions [#]'
          }
        }]
      })
    });
  }

  function renderScatterChart(canvasId, scatter, trendLabels, measuresOpen, measuresDone) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) return;

    let points = [];

    if (scatter.length) {
      points = scatter
        .map(function (p, idx) {
          return {
            x: toNumber(p.x),
            y: toNumber(p.y),
            label: trendLabels[idx] || ('P' + (idx + 1))
          };
        })
        .filter(function (p) {
          return Number.isFinite(p.x) && Number.isFinite(p.y);
        });
    }

    if (!points.length && trendLabels.length) {
      points = trendLabels.map(function (label, idx) {
        return {
          x: toNumber(measuresOpen[idx]),
          y: toNumber(measuresDone[idx]),
          label: label
        };
      }).filter(function (p) {
        return Number.isFinite(p.x) && Number.isFinite(p.y);
      });
    }

    new Chart(canvas, {
      type: 'scatter',
      data: {
        datasets: [{
          label: 'Open vs Done',
          data: points,
          pointRadius: 5,
          pointHoverRadius: 6,
          backgroundColor: 'rgba(0,82,204,0.70)',
          borderColor: 'rgba(0,82,204,1)',
          showLine: false
        }]
      },
      options: Object.assign(
        {},
        commonChartOptions({
          legendPosition: 'top',
          xAxes: [{
            ticks: {
              beginAtZero: true,
              max: roundUp(maxOf(points.map(p => p.x)), 5)
            },
            scaleLabel: {
              display: true,
              labelString: 'Open measures'
            }
          }],
          yAxes: [{
            ticks: {
              beginAtZero: true,
              max: roundUp(maxOf(points.map(p => p.y)), 5)
            },
            scaleLabel: {
              display: true,
              labelString: 'Done measures'
            }
          }]
        }),
        {
          tooltips: {
            callbacks: {
              label: function (tooltipItem, chartData) {
                const point = chartData.datasets[tooltipItem.datasetIndex].data[tooltipItem.index] || {};
                const label = point.label ? point.label + ': ' : '';
                return label + 'open=' + toNumber(point.x) + ', done=' + toNumber(point.y);
              }
            }
          }
        }
      )
    });
  }

  function fillGaugeDescription(ctx) {
    const desc = findDescNode('gaugeChart');
    if (!desc) return;

    const latestValue = ctx.keyFigureValues.length ? ctx.keyFigureValues[ctx.keyFigureValues.length - 1] : ctx.currentAvg;

    desc.innerHTML = ''
      + '<strong>' + escapeHtml(ctx.pcLabel) + '</strong><br>'
      + '12-week KPI average: <strong>' + formatPercent(ctx.currentAvg) + '</strong><br>'
      + 'Latest visible week: <strong>' + escapeHtml(ctx.latestWeekLabel || '-') + '</strong><br>'
      + 'Latest weekly KPI value: <strong>' + formatPercent(latestValue) + '</strong><br>'
      + 'Explanation: This value is the current KPI average over the rolling 12-week window for the selected PC.';
  }

  function fillTrendDescription(ctx) {
    const desc = findDescNode('trendChart');
    if (!desc) return;

    const lastPerf = lastOf(ctx.performance);
    const lastOpen = lastOf(ctx.measuresOpen);
    const lastDone = lastOf(ctx.measuresDone);

    desc.innerHTML = ''
      + '<strong>' + escapeHtml(ctx.pcLabel) + '</strong><br>'
      + 'Weeks: <strong>' + escapeHtml(ctx.trendLabels.join(', ') || '-') + '</strong><br>'
      + 'KPI Performance %: <strong>' + formatSeries(ctx.trendLabels, ctx.performance, '%') + '</strong><br>'
      + 'Open measures: <strong>' + formatSeries(ctx.trendLabels, ctx.measuresOpen) + '</strong><br>'
      + 'Done measures: <strong>' + formatSeries(ctx.trendLabels, ctx.measuresDone) + '</strong><br>'
      + 'Latest week summary: KPI <strong>' + formatPercent(lastPerf) + '</strong>, open <strong>' + toNumber(lastOpen) + '</strong>, done <strong>' + toNumber(lastDone) + '</strong>.<br>'
      + 'Explanation: The line shows KPI performance, bars show the count of open and done <strong>Measures</strong> in the same week.';
  }

  function fillMeasuresDescription(ctx) {
    const desc = findDescNode('measuresChart');
    if (!desc) return;

    desc.innerHTML = ''
      + '<strong>' + escapeHtml(ctx.pcLabel) + '</strong><br>'
      + 'Created: <strong>' + formatSeries(ctx.trendLabels, ctx.measuresCreated) + '</strong><br>'
      + 'Resolved: <strong>' + formatSeries(ctx.trendLabels, ctx.measuresResolved) + '</strong><br>'
      + 'Open: <strong>' + formatSeries(ctx.trendLabels, ctx.measuresOpen) + '</strong><br>'
      + 'Done: <strong>' + formatSeries(ctx.trendLabels, ctx.measuresDone) + '</strong><br>'
      + 'Explanation: <strong>Measures</strong> created and resolved show weekly movement; open and done show the current state recorded in each weekly snapshot.';
  }

  function fillStatusDescription(ctx) {
    const desc = findDescNode('statusChart');
    if (!desc) return;

    const total = sum(ctx.pieValues);
    const items = ctx.pieLabels.map(function (label, idx) {
      const value = toNumber(ctx.pieValues[idx]);
      const pct = total > 0 ? (value / total) * 100 : 0;
      return escapeHtml(label) + '=' + value + ' (' + formatPercent(pct) + ')';
    });

    desc.innerHTML = ''
      + '<strong>' + escapeHtml(ctx.pcLabel) + '</strong><br>'
      + 'Status totals: <strong>' + (items.join('; ') || '-') + '</strong><br>'
      + 'Total questions in chart: <strong>' + total + '</strong><br>'
      + 'Explanation: The donut shows the aggregated distribution of <strong>Questions</strong> statuses across the visible weekly KPI snapshots.';
  }

  function fillQuestionsDescription(ctx) {
    const desc = findDescNode('questionsChart');
    if (!desc) return;

    desc.innerHTML = ''
      + '<strong>' + escapeHtml(ctx.pcLabel) + '</strong><br>'
      + 'Questions open: <strong>' + formatSeries(ctx.trendLabels, ctx.questionsOpen) + '</strong><br>'
      + 'Questions done: <strong>' + formatSeries(ctx.trendLabels, ctx.questionsDone) + '</strong><br>'
      + 'Explanation: This chart compares how many <strong>Questions</strong> remained open versus done in each visible weekly snapshot.';
  }

  function fillScatterDescription(ctx) {
    const desc = findDescNode('scatterChart');
    if (!desc) return;

    let pairs = [];

    if (ctx.scatter && ctx.scatter.length) {
      pairs = ctx.scatter.map(function (p, idx) {
        const label = ctx.trendLabels[idx] || ('P' + (idx + 1));
        return label + '(open=' + toNumber(p.x) + ', done=' + toNumber(p.y) + ')';
      });
    } else if (ctx.trendLabels && ctx.trendLabels.length) {
      pairs = ctx.trendLabels.map(function (label, idx) {
        return label + '(open=' + toNumber(ctx.measuresOpen[idx]) + ', done=' + toNumber(ctx.measuresDone[idx]) + ')';
      });
    }

    desc.innerHTML = ''
      + '<strong>' + escapeHtml(ctx.pcLabel) + '</strong><br>'
      + 'Open vs done pairs: <strong>' + escapeHtml(pairs.join('; ') || '-') + '</strong><br>'
      + 'Explanation: <strong>Measures</strong> each point represents one week. X-axis is open measures, Y-axis is done measures. Points farther right mean more open measures; points higher mean more completed measures.';
  }

  function findDescNode(chartId) {
    return document.querySelector('.' + chartId + '-chart-desc');
  }

  function resolvePcLabel(data) {
    const raw = data && (data.pcKey || data.scopeIssueKey || data.pc || data.scope);
    if (!raw) return 'Selected PC';
    return String(raw);
  }

  function formatSeries(labels, values, suffix) {
    if (!Array.isArray(values) || !values.length) return '-';

    const safeSuffix = suffix || '';
    return values.map(function (value, idx) {
      const label = labels[idx] || ('P' + (idx + 1));
      return label + '=' + formatValue(value, safeSuffix);
    }).join(', ');
  }

  function formatValue(value, suffix) {
    const num = toNumber(value);
    if (suffix === '%') return formatPercent(num);
    return num + (suffix || '');
  }

  function formatPercent(value) {
    const num = toNumber(value);
    return num.toFixed(2) + '%';
  }

  function toNumber(value) {
    const num = Number(value);
    return Number.isFinite(num) ? num : 0;
  }

  function lastOf(arr) {
    return Array.isArray(arr) && arr.length ? arr[arr.length - 1] : 0;
  }

  function maxOf(arr) {
    if (!Array.isArray(arr) || !arr.length) return 0;
    return arr.reduce(function (max, val) {
      const n = toNumber(val);
      return n > max ? n : max;
    }, 0);
  }

  function sum(arr) {
    if (!Array.isArray(arr) || !arr.length) return 0;
    return arr.reduce(function (acc, val) {
      return acc + toNumber(val);
    }, 0);
  }

  function roundUp(value, step) {
    const safeStep = step || 1;
    const num = Math.max(0, toNumber(value));
    if (num === 0) return safeStep;
    return Math.ceil(num / safeStep) * safeStep;
  }

  function clamp(value, min, max) {
    return Math.min(Math.max(toNumber(value), min), max);
  }

  function escapeHtml(str) {
    if (str === null || str === undefined) {
      return '';
    }
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function commonChartOptions(extra) {
    const base = {
      responsive: true,
      maintainAspectRatio: true,
      legend: {
        display: true,
        position: 'top'
      },
      animation: {
        duration: 0
      },
      elements: {
        line: {
          tension: 0
        }
      },
      scales: {
        xAxes: [{
          ticks: {
            autoSkip: false
          },
          gridLines: {
            color: 'rgba(9,30,66,0.08)'
          }
        }],
        yAxes: [{
          ticks: {
            beginAtZero: true
          },
          gridLines: {
            color: 'rgba(9,30,66,0.08)'
          }
        }]
      }
    };

    if (!extra) return base;

    if (extra.legendPosition) {
      base.legend.position = extra.legendPosition;
      delete extra.legendPosition;
    }

    if (extra.xAxes) {
      base.scales.xAxes = extra.xAxes;
      delete extra.xAxes;
    }

    if (extra.yAxes) {
      base.scales.yAxes = extra.yAxes;
      delete extra.yAxes;
    }

    return Object.assign(base, extra);
  }

  function logChartSource(stage) {
    console.log("---- Chart.js check (" + stage + ") ----");

    if (!window.Chart) {
      console.warn("Chart.js NOT loaded");
      return;
    }

    console.log("Chart.js version:", Chart.version);

    const scripts = Array.from(document.getElementsByTagName("script"));
    const chartScripts = scripts.filter(s => s.src && s.src.toLowerCase().includes("chart"));

    if (chartScripts.length === 0) {
      console.log("Chart loaded but script source not detectable (possibly bundled)");
    } else {
      chartScripts.forEach(s => {
        console.log("Chart script source:", s.src);
      });
    }

    console.log("----------------------------------------");
  }

  function ensureChartJsLoaded() {
    logChartSource("before ensure");

    return new Promise((resolve, reject) => {
      if (window.Chart) {
        console.log("Chart.js already present");
        logChartSource("already loaded");
        return resolve();
      }

      const s = document.createElement('script');
      s.src = 'https://cdnjs.cloudflare.com/ajax/libs/Chart.js/2.9.4/Chart.js';
      s.async = true;

      s.onload = () => {
        console.log("Chart.js loaded from CDN");
        logChartSource("after CDN load");
        resolve();
      };

      s.onerror = () => {
        console.error("CDN load failed");
        reject(new Error("Failed to load Chart.js from CDN"));
      };

      document.head.appendChild(s);
    });
  }

  // ═══════════════════════════════════════════════════════════════════
  // ══  KVS Level 1 Checklist
  // ═══════════════════════════════════════════════════════════════════

  console.log("binding kvs level1 checklist click");

  AJS.$(document).on(
    'click',
    'a[data-link-id="com.onresolve.jira.groovy.groovyrunner:kvs-level1-checklist"]',
    function (e) {
      e.preventDefault();
      e.stopPropagation();

      console.log("level1 checklist click detected");

      fetch(AJS.contextPath() + '/rest/scriptrunner/latest/custom/kvsLevel1Checklist', {
        method: 'GET',
        credentials: 'include'
      })
        .then(r => r.text())
        .then(html => {
          const main = document.querySelector('#main');
          if (!main) return;

          main.innerHTML = html;
          initLevel1Checklist();
        })
        .catch(err => console.error(err));
    }
  );

  // ── Level 1 Checklist: constants & helpers ──

  var L1_DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri'];

  function l1Api(params) {
    var qs = Object.keys(params).map(function (k) {
      return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]);
    }).join('&');
    return AJS.contextPath() + '/rest/scriptrunner/latest/custom/kvsLevel1Data?' + qs;
  }

  function l1FetchJson(url) {
    return fetch(url, { credentials: 'same-origin' })
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      });
  }

  function l1GetKW(dateStr) {
    var d = new Date(dateStr);
    d.setHours(0, 0, 0, 0);
    d.setDate(d.getDate() + 3 - ((d.getDay() + 6) % 7));
    var y1 = new Date(d.getFullYear(), 0, 4);
    return 1 + Math.round(((d - y1) / 86400000 - 3 + ((y1.getDay() + 6) % 7)) / 7);
  }

  function l1CurrentMonday() {
    var d = new Date();
    var dow = d.getDay();
    var diff = (dow === 0 ? -6 : 1) - dow;
    d.setDate(d.getDate() + diff);
    return d.toISOString().slice(0, 10);
  }

  // ── Level 1 Checklist: init ──

  function initLevel1Checklist() {
    console.log("initLevel1Checklist called");

    var selPC       = document.getElementById('l1-selPC');
    var selFA       = document.getElementById('l1-selFA');
    var selDay      = document.getElementById('l1-selDay');
    var inpDate     = document.getElementById('l1-inpDate');
    var btnLoad     = document.getElementById('l1-btnLoad');
    var btnPrint    = document.getElementById('l1-btnPrint');
    var stateMsg    = document.getElementById('l1-stateMsg');
    var tableWrap   = document.getElementById('l1-tableWrap');
    var reportHeader = document.getElementById('l1-reportHeader');
    var reportFooter = document.getElementById('l1-reportFooter');

    if (!selPC || !selFA) {
      console.error("Level1 checklist DOM elements not found");
      return;
    }

    var lastChecklistData = null; // cached data to avoid re-fetch on day filter change

    // set default Monday date
    inpDate.value = l1CurrentMonday();

    // load PCs
    l1FetchJson(l1Api({ action: 'profitCenters' })).then(function (data) {
      var h = '<option value="">-- Profit Center --</option>';
      data.forEach(function (pc) {
        h += '<option value="' + escapeHtml(pc.key) + '">'
          + escapeHtml(pc.pcKey + ' - ' + pc.name)
          + '</option>';
      });
      selPC.innerHTML = h;
    });

    // PC change -> load FAs
    selPC.addEventListener('change', function () {
      selFA.disabled = true;
      selFA.innerHTML = '<option value="">-- loading --</option>';
      lastChecklistData = null;
      var pcKey = selPC.value;
      if (!pcKey) {
        selFA.innerHTML = '<option value="">-- select PC --</option>';
        return;
      }
      l1FetchJson(l1Api({ action: 'functionalAreas', profitCenter: pcKey }))
        .then(function (data) {
          var h = '<option value="">-- Functional Area --</option>';
          data.forEach(function (fa) {
            h += '<option value="' + escapeHtml(fa.key) + '">'
              + escapeHtml(fa.faKey + ' - ' + fa.name)
              + '</option>';
          });
          selFA.innerHTML = h;
          selFA.disabled = false;
        });
    });

    // FA change -> auto-load (fetch from server)
    selFA.addEventListener('change', function () {
      if (selFA.value) l1LoadChecklist();
    });

    // Day filter change -> re-render from cached data (no re-fetch)
    selDay.addEventListener('change', function () {
      if (lastChecklistData) {
        l1RenderTable(lastChecklistData);
      }
    });

    btnLoad.addEventListener('click', l1LoadChecklist);
    btnPrint.addEventListener('click', function () { window.print(); });

    // ── Load & render checklist ──
    function l1LoadChecklist() {
      var pc = selPC.value;
      var fa = selFA.value;
      if (!pc || !fa) {
        stateMsg.style.display = 'block';
        stateMsg.textContent = 'Please select Profit Center and Functional Area.';
        tableWrap.innerHTML = '';
        reportFooter.style.display = 'none';
        btnPrint.disabled = true;
        lastChecklistData = null;
        return;
      }

      stateMsg.style.display = 'block';
      stateMsg.textContent = 'Loading data...';
      tableWrap.innerHTML = '';

      l1FetchJson(l1Api({ action: 'checklistData', profitCenter: pc, functionalArea: fa }))
        .then(function (data) {
          stateMsg.style.display = 'none';
          lastChecklistData = data;
          l1RenderTable(data);
          btnPrint.disabled = false;
        })
        .catch(function (e) {
          stateMsg.textContent = 'Error: ' + e.message;
        });
    }

    // ── Render checklist table ──
    function l1RenderTable(data) {
      var dayFilter = selDay.value;
      var dateStr   = inpDate.value;
      var kw        = dateStr ? l1GetKW(dateStr) : '';
      var wp        = data.workplaces || [];
      var allQ      = data.questions || [];

      // filter questions by selected day
      var questions = allQ;
      if (dayFilter) {
        questions = allQ.filter(function (q) {
          return !q.day || q.day === dayFilter;
        });
      }

      var visDays = dayFilter ? [dayFilter] : L1_DAYS;

      // build print header
      l1BuildPrintHeader(data, kw, dateStr);


        if (data.singleWorkplaceMode) {
            wp = [{ name: data.functionalArea.name }];
        }


      if (questions.length === 0) {
        tableWrap.innerHTML = '<div class="l1-state-msg">No questions found (' + escapeHtml(data.usageKey) + ')</div>';
        reportFooter.style.display = 'none';
        return;
      }

      // group by standard (Category EN / DE mapped to "Standard")
      var groups = [];
      var curStd = null;
      var curGrp = null;
      questions.forEach(function (q) {
        var s = q.standard || 'Other';
        if (s !== curStd) {
          curGrp = { standard: s, items: [] };
          groups.push(curGrp);
          curStd = s;
        }
        curGrp.items.push(q);
      });

      var h = '<table class="l1-table">';

      // THEAD row 1
      h += '<thead><tr>';
      h += '<th rowspan="2" class="l1-col-id">ID</th>';
      h += '<th rowspan="2" class="l1-col-std">Standard</th>';
      h += '<th rowspan="2" class="l1-col-text">Check criterion</th>';
      h += '<th rowspan="2" class="l1-col-day">Weekday</th>';
      for (var wi = 0; wi < wp.length; wi++) {
        h += '<th colspan="' + visDays.length + '" style="font-size:10px">'
          + escapeHtml(wp[wi].name) + '</th>';
      }
      h += '</tr>';

      // THEAD row 2 - day sub-headers
      h += '<tr>';
      for (var wi = 0; wi < wp.length; wi++) {
        for (var di = 0; di < visDays.length; di++) {
          h += '<th class="l1-col-check">' + visDays[di] + '</th>';
        }
      }
      h += '</tr></thead>';

      // TBODY
      h += '<tbody>';
      for (var gi = 0; gi < groups.length; gi++) {
        var grp = groups[gi];
        var stdSpan = grp.items.length * 2;

        for (var qi = 0; qi < grp.items.length; qi++) {
          var q = grp.items[qi];
          var qActive = q.day ? [q.day] : L1_DAYS;

          // i.O. row
          h += '<tr class="l1-row-io">';
          h += '<td rowspan="2" class="l1-col-id">' + escapeHtml(q.id) + '</td>';
          if (qi === 0) {
            h += '<td rowspan="' + stdSpan + '" class="l1-std-cell l1-col-std">'
              + escapeHtml(grp.standard) + '</td>';
          }
          h += '<td rowspan="2" class="l1-text-cell l1-col-text">' + escapeHtml(q.text) + '</td>';
          h += '<td class="l1-day-label">i.O. \u2192 \u2713</td>';
          for (var wi = 0; wi < wp.length; wi++) {
            for (var di = 0; di < visDays.length; di++) {
              var cls = (qActive.indexOf(visDays[di]) === -1) ? ' l1-cell-disabled' : '';
              h += '<td class="l1-col-check' + cls + '"></td>';
            }
          }
          h += '</tr>';

          // n.i.O. row
          h += '<tr class="l1-row-nio">';
          h += '<td class="l1-day-label">n.i.O.\u2192 X</td>';
          for (var wi = 0; wi < wp.length; wi++) {
            for (var di = 0; di < visDays.length; di++) {
              var cls2 = (qActive.indexOf(visDays[di]) === -1) ? ' l1-cell-disabled' : '';
              h += '<td class="l1-col-check' + cls2 + '"></td>';
            }
          }
          h += '</tr>';
        }
      }

      // Signature row
      h += '<tr class="l1-signature-row">';
      h += '<td colspan="4" style="text-align:left;font-weight:700">Signature</td>';
      for (var wi = 0; wi < wp.length; wi++) {
        h += '<td colspan="' + visDays.length + '"></td>';
      }
      h += '</tr>';

      h += '</tbody></table>';
      tableWrap.innerHTML = h;

      reportFooter.style.display = 'flex';
      document.getElementById('l1-footerDate').textContent =
        'Generated: ' + new Date().toLocaleDateString('en-GB');
    }

    // ── Print header (visible only in @media print) ──
    function l1BuildPrintHeader(data, kw, dateStr) {
      var pcTxt  = selPC.options[selPC.selectedIndex] ? selPC.options[selPC.selectedIndex].text : '';
      var faTxt  = selFA.options[selFA.selectedIndex] ? selFA.options[selFA.selectedIndex].text : '';
      var dayTxt = selDay.options[selDay.selectedIndex] ? selDay.options[selDay.selectedIndex].text : 'All';
      var dateFmt = dateStr
        ? new Date(dateStr + 'T00:00:00').toLocaleDateString('en-GB')
        : '';

      var h = '<table>';
      h += '<tr><td class="l1-rh-label">Profit Center:</td><td>' + escapeHtml(pcTxt) + '</td>';
      h += '<td class="l1-rh-label">Calendar week:</td><td>CW ' + kw + '</td></tr>';
      h += '<tr><td class="l1-rh-label">Functional Area:</td><td>' + escapeHtml(faTxt) + '</td>';
      h += '<td class="l1-rh-label">Monday date:</td><td>' + dateFmt + '</td></tr>';
      h += '<tr><td class="l1-rh-label">KVS Level:</td><td>1</td>';
      h += '<td class="l1-rh-label">Weekday filter:</td><td>' + escapeHtml(dayTxt) + '</td></tr>';
      h += '<tr><td class="l1-rh-label">Filter by Question Usage:</td><td colspan="3">' + escapeHtml(data.usageKey) + '</td></tr>';
      h += '</table>';
      reportHeader.innerHTML = h;
    }
  }

});