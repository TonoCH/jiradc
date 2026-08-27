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
          initChartsUI();
        })
        .catch(err => console.error(err));
    }
  );

  // ═══════════════════════════════════════════════════════════════════
  //  Charts UI init – runs after HTML is injected
  // ═══════════════════════════════════════════════════════════════════

  function initChartsUI() {
    const pcSel = document.getElementById('kvs-pc-select');
    const weeksSel = document.getElementById('kvs-weeks-input');
    const refreshBtn = document.getElementById('kvs-refresh-btn');
    const printBtn = document.getElementById('kvs-print-btn');

    if (pcSel) pcSel.addEventListener('change', loadChartsData);
    if (weeksSel) weeksSel.addEventListener('change', loadChartsData);
    if (refreshBtn) refreshBtn.addEventListener('click', loadChartsData);
    if (printBtn) printBtn.addEventListener('click', function () { window.print(); });

    var liveBtn = document.getElementById('kvs-live-refresh-btn');
    if (liveBtn) liveBtn.addEventListener('click', loadLiveOverlay);

    ensureChartJsLoaded()
      .then(loadChartsData)
      .catch(err => console.error(err));
  }

  function loadChartsData() {
    const pcSel = document.getElementById('kvs-pc-select');
    const weeksSel = document.getElementById('kvs-weeks-input');

    const pcKey = pcSel ? pcSel.value : 'PC9';
    const weeks = weeksSel ? parseInt(weeksSel.value, 10) || 12 : 12;

    const url = AJS.contextPath()
      + '/rest/scriptrunner/latest/custom/kvsChartsData'
      + '?pcKey=' + encodeURIComponent(pcKey)
      + '&weeks=' + encodeURIComponent(weeks);

    const subtitle = document.getElementById('kvs-subtitle');
    if (subtitle) subtitle.textContent = 'Loading ' + pcKey + ' (' + weeks + ' weeks)…';

    fetch(url, { method: 'GET', credentials: 'include' })
      .then(r => r.json())
      .then(data => {
        if (data && data.error) {
          if (subtitle) subtitle.textContent = 'Error: ' + data.error + (data.pcKey ? ' (' + data.pcKey + ')' : '');
          return;
        }
        renderAll(data || {});
      })
      .catch(err => {
        console.error(err);
        if (subtitle) subtitle.textContent = 'Failed to load data.';
      });
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Live overlay (prototype) — recompute current week from Jira state
  //  WITHOUT touching the snapshot-based charts.
  // ═══════════════════════════════════════════════════════════════════

  function loadLiveOverlay() {
    var pcSel = document.getElementById('kvs-pc-select');
    var pcKey = pcSel ? pcSel.value : 'overall';
    var btn = document.getElementById('kvs-live-refresh-btn');
    var line = document.getElementById('kvs-latest-week-line');

    var originalBtnText = btn ? btn.textContent : null;
    if (btn) { btn.disabled = true; btn.textContent = 'Loading…'; }
    if (line) line.innerHTML = 'Loading live (today)…';

    var url = AJS.contextPath()
      + '/rest/scriptrunner/latest/custom/kvsChartsLive'
      + '?pcKey=' + encodeURIComponent(pcKey);

    fetch(url, { method: 'GET', credentials: 'include' })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data || data.error) {
          var errLine = document.getElementById('kvs-latest-week-line');
          if (errLine) {
            errLine.innerHTML = '<span style="color:#FF5630;">Live error: '
              + escapeHtml((data && data.error) || 'unknown') + '</span>';
          }
          return;
        }
        var pct = toNumber(data.performancePct);

        // Build a status breakdown (count + %) from the live statusCounts,
        // the same shape the normal snapshot payload uses, then re-render the
        // WHOLE Overall Performance panel (arc + latest-week line + status
        // breakdown) in-place — no separate/duplicate gauge.
        var statusCounts = data.statusCounts || {};
        var totalQuestions = 0;
        Object.keys(statusCounts).forEach(function (k) { totalQuestions += toNumber(statusCounts[k]); });
        var statusBreakdown = Object.keys(statusCounts).map(function (k) {
          var n = toNumber(statusCounts[k]);
          return {
            status : k,
            count  : n,
            percent: totalQuestions > 0 ? (n / totalQuestions * 100) : 0
          };
        }).sort(function (a, b) { return b.count - a.count; });

        renderOverallGauge({
          pcKey  : data.scope || pcKey,
          overall: {
            latestWeek     : 'W' + (data.week || '-'),
            latestWeekPct  : pct,
            totalQuestions : totalQuestions,
            statusBreakdown: statusBreakdown,
            elapsedMs      : data.elapsedMs
          }
        }, true);

        // Refresh the other charts that reflect current scope state (not history):
        // category grid + status donut. History/trend charts stay snapshot-based.
        if (Array.isArray(data.categories) && data.categories.length) {
          renderCategoryGrid({ categories: data.categories });
        }
        if (data.statusCounts) {
          var labels = Object.keys(data.statusCounts);
          var values = labels.map(function (k) { return data.statusCounts[k]; });
          renderStatusChart({ pie: { labels: labels, values: values } });
        }
      })
      .catch(function (err) {
        var errLine = document.getElementById('kvs-latest-week-line');
        if (errLine) {
          errLine.innerHTML = '<span style="color:#FF5630;">Live fetch failed: ' + escapeHtml(String(err)) + '</span>';
        }
      })
      .then(function () {
        if (btn) { btn.disabled = false; btn.textContent = originalBtnText || '🔄 Live'; }
      });
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Top-level render
  // ═══════════════════════════════════════════════════════════════════

  function renderAll(data) {
    destroyAllCharts();

    const pcLabel = resolvePcLabel(data);
    const trend = Array.isArray(data.trend) ? data.trend : [];
    const trendLabels = trend.map(x => (x.week ? ('W' + x.week) : ''));

    // Subtitle + data source badge
    const subtitle = document.getElementById('kvs-subtitle');
    if (subtitle) {
      subtitle.textContent = pcLabel
        + ' · ' + (data.from || '?') + ' → ' + (data.to || '?')
        + ' · ' + (data.weeksRequested || trend.length) + ' weeks';
    }
    const badge = document.getElementById('kvs-ds-badge');
    if (badge) {
      const strategy = data.dataSource && data.dataSource.strategy ? data.dataSource.strategy : 'unknown';
      badge.textContent = 'source: ' + strategy;
      badge.className = 'ds-badge ' + strategy;
    }

    renderOverallGauge(data);
    renderCategoryGrid(data);
    renderTrendPerfChart(trendLabels, trend);
    renderMeasureHistoryCharts(trendLabels, trend);
    renderAuditExecChart(trendLabels, trend);
    renderStatusChart(data);
    renderOpenMeasuresTable(data.openMeasures || []);
    renderClosedMeasuresTable(data.closedMeasures || []);
    renderOpenAuditsTable(data.openAudits || []);
    renderSourceDetails(data.sources || {});

    const footer = document.getElementById('kvs-footer');
    if (footer) {
      footer.textContent = 'Generated: ' + new Date().toLocaleString();
    }
  }

  // Chart.js instances keyed by canvas id, so any renderer (full reload or a
  // targeted live update) can safely replace its own chart without touching
  // the others and without hitting "Canvas is already in use".
  var chartRegistry = {};

  function destroyChart(canvasId) {
    var existing = chartRegistry[canvasId];
    if (existing && typeof existing.destroy === 'function') {
      try { existing.destroy(); } catch (e) { /* ignore */ }
    }
    chartRegistry[canvasId] = null;
  }

  function registerChart(canvasId, chart) {
    chartRegistry[canvasId] = chart;
  }

  function destroyAllCharts() {
    Object.keys(chartRegistry).forEach(destroyChart);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Source data collapsibles — populate every <details data-src-key="…">
  //  from the backend-provided `sources` object.
  // ═══════════════════════════════════════════════════════════════════

  function renderSourceDetails(sources) {
    // Jira base URL for deep-link to issue search (best-effort detection)
    const jiraBase = (function () {
      // Try to detect: the dashboard is served under /plugins/servlet/... or similar
      // Fall back to current origin.
      try { return window.location.origin; } catch (e) { return ''; }
    })();

    function jiraSearchLink(jql) {
      if (!jql || !jiraBase) return null;
      return jiraBase + '/issues/?jql=' + encodeURIComponent(jql);
    }

    function esc(s) {
      return String(s == null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function renderOne(elem, src) {
      if (!elem) return;
      if (!src) {
        elem.innerHTML = '<summary>Source data</summary>'
          + '<div class="src-desc">No source metadata available.</div>';
        return;
      }

      const type = src.type || 'info';
      const badgeClass = (type === 'jql' || type === 'snapshot' || type === 'formula') ? type : '';
      const desc = src.description || '';

      let jqlBlocks = '';

      // Different JQL-ish fields depending on source type
      if (src.jql) {
        const link = jiraSearchLink(src.jql);
        jqlBlocks += '<pre>' + esc(src.jql) + '</pre>';
        if (link) {
          jqlBlocks += '<a class="src-open-jira" target="_blank" rel="noopener" href="'
            + esc(link) + '">🔎 Open this JQL in Jira</a>';
        }
      }
      if (src.questionsJql) {
        const link = jiraSearchLink(src.questionsJql);
        jqlBlocks += '<div class="src-desc" style="margin-top:6px"><strong>Questions JQL:</strong></div>'
          + '<pre>' + esc(src.questionsJql) + '</pre>';
        if (link) {
          jqlBlocks += '<a class="src-open-jira" target="_blank" rel="noopener" href="'
            + esc(link) + '">🔎 Open in Jira</a>';
        }
      }
      if (src.jqlTemplate) {
        jqlBlocks += '<div class="src-desc" style="margin-top:6px"><strong>Per-week JQL template:</strong></div>'
          + '<pre>' + esc(src.jqlTemplate) + '</pre>';
      }
      if (src.jqlTemplateOpen) {
        jqlBlocks += '<div class="src-desc" style="margin-top:6px"><strong>Open audits per week:</strong></div>'
          + '<pre>' + esc(src.jqlTemplateOpen) + '</pre>';
      }
      if (src.jqlTemplateClosed) {
        jqlBlocks += '<div class="src-desc" style="margin-top:6px"><strong>Closed audits per week:</strong></div>'
          + '<pre>' + esc(src.jqlTemplateClosed) + '</pre>';
      }

      const badgeHtml = badgeClass
        ? '<span class="src-badge ' + badgeClass + '">' + esc(type) + '</span>'
        : '';

      elem.innerHTML =
        '<summary>Source data' + badgeHtml + '</summary>'
        + (desc ? '<div class="src-desc">' + esc(desc) + '</div>' : '')
        + jqlBlocks;
    }

    const nodes = document.querySelectorAll('details.src-details[data-src-key]');
    nodes.forEach(function (el) {
      const key = el.getAttribute('data-src-key');
      renderOne(el, sources[key]);
    });
  }

  // ═══════════════════════════════════════════════════════════════════
  //  SVG Gauge helper
  // ═══════════════════════════════════════════════════════════════════

  /**
   * Build a half-circle gauge SVG.
   * Zones: red [0,75), yellow [75,90), green [90,100].
   *
   * @param {number} value      0..100
   * @param {object} opts       {width, showValueText}
   * @returns {string} SVG markup
   */
  function buildGaugeSvg(value, opts) {
    opts = opts || {};
    const width = opts.width || 260;
    const height = Math.round(width * 0.62);
    const cx = width / 2;
    const cy = height * 0.90;            // center of the arc, near bottom
    const r = width * 0.38;
    const strokeW = Math.max(12, width * 0.08);
    const showText = opts.showValueText !== false;

    const v = clamp(toNumber(value), 0, 100);

    // arc endpoints: 0% at 180°, 100% at 0°
    // angle(value) = 180 - value*1.8   (degrees, standard math)
    function pt(pct) {
      const angRad = (180 - pct * 1.8) * Math.PI / 180;
      return {
        x: cx + r * Math.cos(angRad),
        y: cy - r * Math.sin(angRad)
      };
    }

    const p0   = pt(0);
    const p75  = pt(75);
    const p90  = pt(90);
    const p100 = pt(100);
    const pNow = pt(v);

    const needleBaseR = strokeW * 0.35;
    const needleLen = r - strokeW * 0.35;
    const angRadNow = (180 - v * 1.8) * Math.PI / 180;
    const needleTipX = cx + needleLen * Math.cos(angRadNow);
    const needleTipY = cy - needleLen * Math.sin(angRadNow);

    // value color
    const valueColor = v >= 90 ? '#36B37E' : (v >= 75 ? '#FFAB00' : '#FF5630');

    let svg = '';
    svg += '<svg viewBox="0 0 ' + width + ' ' + height + '" xmlns="http://www.w3.org/2000/svg">';
    // Red zone (0..75)
    svg += '<path d="M ' + p0.x + ' ' + p0.y + ' A ' + r + ' ' + r + ' 0 0 1 ' + p75.x + ' ' + p75.y + '" '
         + 'fill="none" stroke="#FF5630" stroke-width="' + strokeW + '" stroke-linecap="butt"/>';
    // Yellow zone (75..90)
    svg += '<path d="M ' + p75.x + ' ' + p75.y + ' A ' + r + ' ' + r + ' 0 0 1 ' + p90.x + ' ' + p90.y + '" '
         + 'fill="none" stroke="#FFAB00" stroke-width="' + strokeW + '" stroke-linecap="butt"/>';
    // Green zone (90..100)
    svg += '<path d="M ' + p90.x + ' ' + p90.y + ' A ' + r + ' ' + r + ' 0 0 1 ' + p100.x + ' ' + p100.y + '" '
         + 'fill="none" stroke="#36B37E" stroke-width="' + strokeW + '" stroke-linecap="butt"/>';

    // Needle
    svg += '<line x1="' + cx + '" y1="' + cy + '" x2="' + needleTipX + '" y2="' + needleTipY + '" '
         + 'stroke="#172B4D" stroke-width="3" stroke-linecap="round"/>';
    svg += '<circle cx="' + cx + '" cy="' + cy + '" r="' + needleBaseR + '" fill="#172B4D"/>';

    // Value text
    if (showText) {
      svg += '<text x="' + cx + '" y="' + (cy - 12) + '" text-anchor="middle" '
           + 'font-family="Arial, sans-serif" font-size="' + Math.round(width * 0.13) + '" '
           + 'font-weight="700" fill="' + valueColor + '">'
           + formatPercent(v) + '</text>';
    }

    svg += '</svg>';
    return svg;
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Section renderers
  // ═══════════════════════════════════════════════════════════════════

  function renderOverallGauge(data, isLive) {
    const host = document.getElementById('overallGauge');
    const breakdownHost = document.getElementById('overallBreakdown');
    if (!host) return;

    const overall = data.overall || {};
    // In live mode the big arc itself shows today's recomputed value, not the
    // rolling multi-week average.
    const avg = toNumber(isLive
      ? overall.latestWeekPct
      : (overall.averagePct != null ? overall.averagePct : data.keyFigureAvg12));
    const latestWeek = overall.latestWeek || '-';
    const latestPct = toNumber(overall.latestWeekPct != null ? overall.latestWeekPct : 0);
    const labelSuffix = isLive ? ' · Live (today)' : ' · Rolling KPI';
    const subPrefix = isLive ? 'Live now ' : 'Latest week ';
    const liveMeta = isLive
      ? ' <span style="color:#6B778C;">(' + toNumber(overall.elapsedMs) + ' ms)</span>'
      : '';

    host.innerHTML = ''
      + '<div class="kvs-gauge-label">' + escapeHtml(resolvePcLabel(data)) + labelSuffix + '</div>'
      + buildGaugeSvg(avg, { width: 260 })
      + '<div class="kvs-gauge-sub" id="kvs-latest-week-line">' + subPrefix + '<strong>' + escapeHtml(latestWeek) + '</strong>: '
          + formatPercent(latestPct) + liveMeta + '</div>';

    if (breakdownHost) {
      const bd = Array.isArray(overall.statusBreakdown) ? overall.statusBreakdown : [];
      if (!bd.length) {
        breakdownHost.innerHTML = '';
      } else {
        let h = '<div style="width:100%;font-weight:600;color:#42526E;margin-top:4px;">Questions: '
              + toNumber(overall.totalQuestions) + '</div>';
        bd.forEach(function (row) {
          h += '<div class="row">'
            + '<span class="k">' + escapeHtml(row.status || '-') + '</span>'
            + '<span class="v">' + toNumber(row.count) + ' (' + formatPercent(row.percent) + ')</span>'
            + '</div>';
        });
        breakdownHost.innerHTML = h;
      }
    }
  }

  function renderCategoryGrid(data) {
    const host = document.getElementById('categoryGrid');
    if (!host) return;

    const cats = Array.isArray(data.categories) ? data.categories : [];
    if (!cats.length) {
      host.innerHTML = '<div style="color:#6B778C;font-size:12px;padding:10px;">No per-category data available in the latest snapshot.</div>';
      return;
    }

    host.innerHTML = cats.map(function (c) {
      const v = toNumber(c.percent);
      const color = v >= 90 ? '#36B37E' : (v >= 75 ? '#FFAB00' : '#FF5630');
      return ''
        + '<div class="kvs-category-item">'
        + '  <div class="cat-name" title="' + escapeHtml(c.category || '') + '">' + escapeHtml(c.category || '-') + '</div>'
        +    buildGaugeSvg(v, { width: 160, showValueText: false })
        + '  <div class="cat-value" style="color:' + color + '">' + formatPercent(v) + '</div>'
        + '</div>';
    }).join('');
  }

  function renderTrendPerfChart(labels, trend) {
    const canvas = document.getElementById('trendPerfChart');
    if (!canvas) return;
    const perf = trend.map(x => toNumber(x.performancePct));

    destroyChart('trendPerfChart');
    registerChart('trendPerfChart', new Chart(canvas, {
      type: 'line',
      data: {
        labels: labels,
        datasets: [{
          label: 'KPI Performance %',
          data: perf,
          borderColor: '#172B4D',
          backgroundColor: 'rgba(23,43,77,0.08)',
          fill: true,
          lineTension: 0,
          borderWidth: 2,
          pointRadius: 3,
          pointBackgroundColor: '#172B4D'
        }]
      },
      options: commonChartOptions({
        legendPosition: 'top',
        yAxes: [{
          ticks: {
            beginAtZero: true,
            min: 0,
            max: 100,
            callback: function (v) { return v + '%'; }
          },
          scaleLabel: { display: true, labelString: 'KPI [%]' }
        }]
      })
    }));

    // Description fill
    const desc = document.querySelector('.trendPerfChart-chart-desc');
    if (desc) {
      desc.innerHTML = 'Weekly KPI values: <strong>'
        + formatSeries(labels, perf, '%') + '</strong>';
    }
  }

  function renderMeasureHistoryCharts(labels, trend) {
    const openArr = trend.map(x => toNumber(x.measuresOpen));
    const closedArr = trend.map(x => toNumber(x.measuresResolved));
    const createdArr = trend.map(x => toNumber(x.measuresCreated));

    renderSimpleLineChart('histOpenChart', labels, openArr, 'Open', '#FF5630');
    renderSimpleLineChart('histClosedChart', labels, closedArr, 'Closed', '#36B37E');
    renderSimpleLineChart('histCreatedChart', labels, createdArr, 'Created', '#FFAB00');

    setDesc('histOpenChart', 'Values: <strong>' + formatSeries(labels, openArr) + '</strong>');
    setDesc('histClosedChart', 'Values: <strong>' + formatSeries(labels, closedArr) + '</strong>');
    setDesc('histCreatedChart', 'Values: <strong>' + formatSeries(labels, createdArr) + '</strong>');
  }

  function renderSimpleLineChart(canvasId, labels, values, label, color) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) return;
    destroyChart(canvasId);
    registerChart(canvasId, new Chart(canvas, {
      type: 'line',
      data: {
        labels: labels,
        datasets: [{
          label: label,
          data: values,
          borderColor: color,
          backgroundColor: color + '22',
          fill: true,
          lineTension: 0,
          borderWidth: 2,
          pointRadius: 3
        }]
      },
      options: commonChartOptions({
        legendPosition: 'top',
        yAxes: [{
          ticks: {
            beginAtZero: true,
            max: roundUp(maxOf(values), 5) || 5
          },
          scaleLabel: { display: true, labelString: 'Measures [#]' }
        }]
      })
    }));
  }

  function renderAuditExecChart(labels, trend) {
    const canvas = document.getElementById('auditExecChart');
    if (!canvas) return;

    const openArr = trend.map(x => toNumber(x.auditsOpen));
    const closedArr = trend.map(x => toNumber(x.auditsClosed));
    const rateArr = trend.map(x => toNumber(x.auditRatePct));

    destroyChart('auditExecChart');
    registerChart('auditExecChart', new Chart(canvas, {
      type: 'bar',
      data: {
        labels: labels,
        datasets: [
          {
            type: 'bar',
            label: 'Open audits',
            data: openArr,
            backgroundColor: 'rgba(255,86,48,0.65)',
            borderColor: 'rgba(255,86,48,0.9)',
            borderWidth: 1,
            stack: 'audits',
            yAxisID: 'yCount'
          },
          {
            type: 'bar',
            label: 'Closed audits',
            data: closedArr,
            backgroundColor: 'rgba(54,179,126,0.65)',
            borderColor: 'rgba(54,179,126,0.9)',
            borderWidth: 1,
            stack: 'audits',
            yAxisID: 'yCount'
          },
          {
            type: 'line',
            label: 'Audit rate %',
            data: rateArr,
            borderColor: '#0052CC',
            backgroundColor: 'transparent',
            fill: false,
            lineTension: 0,
            borderWidth: 2,
            pointRadius: 3,
            yAxisID: 'yRate'
          }
        ]
      },
      options: commonChartOptions({
        legendPosition: 'top',
        yAxes: [
          {
            id: 'yCount',
            position: 'left',
            stacked: true,
            ticks: {
              beginAtZero: true,
              max: roundUp(maxOf([].concat(openArr, closedArr)) * 1.5, 5) || 5
            },
            scaleLabel: { display: true, labelString: 'Audits [#]' }
          },
          {
            id: 'yRate',
            position: 'right',
            ticks: {
              beginAtZero: true,
              min: 0,
              max: 100,
              callback: function (v) { return v + '%'; }
            },
            scaleLabel: { display: true, labelString: 'Audit rate [%]' },
            gridLines: { drawOnChartArea: false }
          }
        ],
        xAxes: [{
          stacked: true,
          ticks: { autoSkip: false },
          gridLines: { color: 'rgba(9,30,66,0.08)' }
        }]
      })
    }));

    setDesc('auditExecChart',
      'Open: <strong>' + formatSeries(labels, openArr) + '</strong><br>' +
      'Closed: <strong>' + formatSeries(labels, closedArr) + '</strong><br>' +
      'Audit rate: <strong>' + formatSeries(labels, rateArr, '%') + '</strong>'
    );
  }

  function renderStatusChart(data) {
    const canvas = document.getElementById('statusChart');
    if (!canvas) return;

    const pieLabels = data.pie && Array.isArray(data.pie.labels) ? data.pie.labels : [];
    const pieValues = data.pie && Array.isArray(data.pie.values) ? data.pie.values : [];

    if (!pieLabels.length) {
      destroyChart('statusChart');
      const ctx = canvas.getContext('2d');
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      return;
    }

    const palette = [
      '#36B37E', '#FFAB00', '#FF5630', '#00B8D9', '#6554C0',
      '#DFE1E6', '#0052CC', '#8777D9', '#79F2C0'
    ];

    destroyChart('statusChart');
    registerChart('statusChart', new Chart(canvas, {
      type: 'doughnut',
      data: {
        labels: pieLabels,
        datasets: [{
          data: pieValues,
          backgroundColor: pieLabels.map((_, i) => palette[i % palette.length]),
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
    }));

    const total = sum(pieValues);
    const items = pieLabels.map(function (label, idx) {
      const value = toNumber(pieValues[idx]);
      const pct = total > 0 ? (value / total) * 100 : 0;
      return escapeHtml(label) + '=' + value + ' (' + formatPercent(pct) + ')';
    });
    setDesc('statusChart', 'Totals: ' + (items.join('; ') || '-') + ' · Total: <strong>' + total + '</strong>');
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Tables
  // ═══════════════════════════════════════════════════════════════════

  function renderOpenMeasuresTable(rows) {
    const countNode = document.getElementById('openMeasuresCount');
    if (countNode) countNode.textContent = rows.length;
    fillMeasuresTable('openMeasuresTable', rows);
  }

  function renderClosedMeasuresTable(rows) {
    const countNode = document.getElementById('closedMeasuresCount');
    if (countNode) countNode.textContent = rows.length;
    fillMeasuresTable('closedMeasuresTable', rows);
  }

  function fillMeasuresTable(tableId, rows) {
    const tbl = document.getElementById(tableId);
    if (!tbl) return;
    const tbody = tbl.querySelector('tbody');
    if (!tbody) return;

    if (!rows.length) {
      tbody.innerHTML = '<tr><td colspan="11" class="count-empty">No rows.</td></tr>';
      return;
    }

    tbody.innerHTML = rows.map(function (r) {
      return '<tr>'
        + '<td>' + escapeHtml(r.date || '') + '</td>'
        + '<td class="key-cell">' + issueLink(r.measureKey) + '</td>'
        + '<td>' + escapeHtml(r.measure || '') + '</td>'
        + '<td class="key-cell">' + issueLink(r.questionKey) + '</td>'
        + '<td>' + escapeHtml(r.deviation || '') + '</td>'
        + '<td>' + escapeHtml(r.auditLocation || '') + '</td>'
        + '<td>' + escapeHtml(r.profitCenter || '') + '</td>'
        + '<td>' + escapeHtml(r.responsible || '') + '</td>'
        + '<td>' + escapeHtml(r.personResponsibility || '') + '</td>'
        + '<td><span class="status-pill">' + escapeHtml(r.status || '-') + '</span></td>'
        + '<td>' + escapeHtml(r.level || '') + '</td>'
        + '</tr>';
    }).join('');
  }

  function renderOpenAuditsTable(rows) {
    const countNode = document.getElementById('openAuditsCount');
    if (countNode) countNode.textContent = rows.length;

    const tbl = document.getElementById('openAuditsTable');
    if (!tbl) return;
    const tbody = tbl.querySelector('tbody');
    if (!tbody) return;

    if (!rows.length) {
      tbody.innerHTML = '<tr><td colspan="10" class="count-empty">No rows.</td></tr>';
      return;
    }

    tbody.innerHTML = rows.map(function (r) {
      return '<tr>'
        + '<td class="key-cell">' + issueLink(r.auditKey) + '</td>'
        + '<td>' + escapeHtml(r.auditId || '') + '</td>'
        + '<td>' + escapeHtml(r.targetEnd || '') + '</td>'
        + '<td>' + escapeHtml(r.week || '') + '</td>'
        + '<td>' + escapeHtml(r.level || '') + '</td>'
        + '<td>' + escapeHtml(r.profitCenter || '') + '</td>'
        + '<td>' + escapeHtml(r.functionalArea || '') + '</td>'
        + '<td>' + escapeHtml(r.workplaces || '') + '</td>'
        + '<td>' + escapeHtml(r.assignee || '') + '</td>'
        + '<td>' + escapeHtml(r.auditType || '') + '</td>'
        + '</tr>';
    }).join('');
  }

  function issueLink(key) {
    if (!key) return '';
    const url = AJS.contextPath() + '/browse/' + encodeURIComponent(key);
    return '<a href="' + url + '" target="_blank">' + escapeHtml(key) + '</a>';
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Generic helpers
  // ═══════════════════════════════════════════════════════════════════

  function setDesc(chartId, html) {
    const n = document.querySelector('.' + chartId + '-chart-desc');
    if (n) n.innerHTML = html;
  }

  function resolvePcLabel(data) {
    const raw = data && (data.pcKey || data.scopeIssueKey);
    return raw ? String(raw) : 'Selected PC';
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

  function maxOf(arr) {
    if (!Array.isArray(arr) || !arr.length) return 0;
    return arr.reduce(function (max, val) {
      const n = toNumber(val);
      return n > max ? n : max;
    }, 0);
  }

  function sum(arr) {
    if (!Array.isArray(arr) || !arr.length) return 0;
    return arr.reduce(function (acc, val) { return acc + toNumber(val); }, 0);
  }

  function roundUp(value, step) {
    const safeStep = step || 1;
    const num = Math.max(0, toNumber(value));
    if (num === 0) return 0;
    return Math.ceil(num / safeStep) * safeStep;
  }

  function clamp(value, min, max) {
    return Math.min(Math.max(toNumber(value), min), max);
  }

  function escapeHtml(str) {
    if (str === null || str === undefined) return '';
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
      legend: { display: true, position: 'top' },
      animation: { duration: 0 },
      elements: { line: { tension: 0 } },
      scales: {
        xAxes: [{
          ticks: { autoSkip: false },
          gridLines: { color: 'rgba(9,30,66,0.08)' }
        }],
        yAxes: [{
          ticks: { beginAtZero: true },
          gridLines: { color: 'rgba(9,30,66,0.08)' }
        }]
      }
    };

    if (!extra) return base;
    if (extra.legendPosition) { base.legend.position = extra.legendPosition; delete extra.legendPosition; }
    if (extra.xAxes) { base.scales.xAxes = extra.xAxes; delete extra.xAxes; }
    if (extra.yAxes) { base.scales.yAxes = extra.yAxes; delete extra.yAxes; }
    return Object.assign(base, extra);
  }

  function logChartSource(stage) {
    console.log("---- Chart.js check (" + stage + ") ----");
    if (!window.Chart) { console.warn("Chart.js NOT loaded"); return; }
    console.log("Chart.js version:", Chart.version);
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

      s.onload = () => { console.log("Chart.js loaded from CDN"); logChartSource("after CDN load"); resolve(); };
      s.onerror = () => { console.error("CDN load failed"); reject(new Error("Failed to load Chart.js from CDN")); };

      document.head.appendChild(s);
    });
  }

  // ═══════════════════════════════════════════════════════════════════
  // ══  KVS Level 1 Checklist   (UNCHANGED – preserved from original)
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

  // Static-label translations for the Level-1 checklist printout
  var L1_I18N = {
    DE: {
      id: 'ID', standard: 'Standard', criterion: 'Prüfkriterium',
      signature: 'Unterschrift',
      profitCenter: 'Profit Center', functionalArea: 'Funktionsbereich',
      kvsLevel: 'KVS Stufe', calendarWeek: 'Kalenderwoche',
      mondayDate: 'Montag (Datum)', dayFilter: 'Wochentag-Filter',
      questionUsage: 'Filter nach Question Usage',
      responsiblePerson: 'Verantwortlich',
      days: { Mon: 'Mo', Tue: 'Di', Wed: 'Mi', Thu: 'Do', Fri: 'Fr' },
      hint: 'Jede Frage mit "i.O. → ✓" oder "n.i.O. → X" bewerten. Falls die Frage nicht beantwortet werden kann, "" verwenden.'
    },
    EN: {
      id: 'ID', standard: 'Standard', criterion: 'Check criterion',
      signature: 'Signature',
      profitCenter: 'Profit Center', functionalArea: 'Functional Area',
      kvsLevel: 'KVS Level', calendarWeek: 'Calendar week',
      mondayDate: 'Monday date', dayFilter: 'Weekday filter',
      questionUsage: 'Filter by Question Usage',
      responsiblePerson: 'Responsible person',
      days: { Mon: 'Mo', Tue: 'Tu', Wed: 'We', Thu: 'Th', Fri: 'Fr' },
      hint: 'Evaluate each question with "i.O. → ✓" or "n.i.O. → X". In case the question can’t be answered use "".'
    },
    SK: {
      id: 'ID', standard: 'Štandard', criterion: 'Kontrolné kritérium',
      signature: 'Podpis',
      profitCenter: 'Profit centrum', functionalArea: 'Funkčná oblasť',
      kvsLevel: 'KVS úroveň', calendarWeek: 'Kalendárny týždeň',
      mondayDate: 'Pondelok (dátum)', dayFilter: 'Filter dňa',
      questionUsage: 'Filter podľa Question Usage',
      responsiblePerson: 'Zodpovedná osoba',
      days: { Mon: 'Po', Tue: 'Ut', Wed: 'St', Thu: 'Št', Fri: 'Pi' },
      hint: 'Každú otázku vyhodnoťte ako "i.O. → ✓" alebo "n.i.O. → X". Ak otázku nie je možné zodpovedať, použite "".'
    }
  };
  function l1T(lang) { return L1_I18N[lang] || L1_I18N.DE; }
  function l1DayLabel(t, key) { return (t.days && t.days[key]) || key; }

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

  // Local calendar date as YYYY-MM-DD. toISOString() converts to UTC first,
  // which in any positive offset rolls the date back one day - that shifted
  // every generated calendar week by one and made the CW column headers
  // disagree with the "Kalenderwoche" line in the report header.
  function l1LocalDate(d) {
    var m = d.getMonth() + 1, day = d.getDate();
    return d.getFullYear() + '-' + (m < 10 ? '0' : '') + m + '-' + (day < 10 ? '0' : '') + day;
  }

  function l1CurrentMonday() {
    var d = new Date();
    var dow = d.getDay();
    var diff = (dow === 0 ? -6 : 1) - dow;
    d.setDate(d.getDate() + diff);
    return l1LocalDate(d);
  }

  // ═══════════════════════════════════════════════════════════════════
  // ══  Level 1 Checklist: print geometry engine
  // ══
  // ══  The checklist is assembled dynamically — the number of workplaces,
  // ══  the number of visible days (1 or 5), the number of questions and
  // ══  the length of every question text all change per PC/FA. So nothing
  // ══  here is a fixed number tuned for one report: the engine derives the
  // ══  column widths from the paper, then MEASURES the real, fully built
  // ══  table off-screen at the exact printable width and searches for the
  // ══  largest font / tallest rows that still fit the minimum page count.
  // ═══════════════════════════════════════════════════════════════════

  // Physical paper, in mm, already in the printed orientation.
  var L1_PAPER = {
    'A4-landscape': { w: 297, h: 210 },
    'A3-landscape': { w: 420, h: 297 },
    'A3-portrait':  { w: 297, h: 420 }
  };

  // Row density presets. `min` is the floor the rows start from, `max` is how
  // far auto-fit may stretch them to reach the bottom of the last page.
  var L1_DENSITY = {
    compact: { min: 4.6, max: 8,  padV: 0.7 },
    normal:  { min: 5.2, max: 13, padV: 0.95 },
    comfort: { min: 6.5, max: 20, padV: 1.4 }
  };

  var L1_FONT_MIN = 6.5;   // absolute floor, only used to squeeze page count
  var L1_FONT_READ = 8.5;  // readability floor — the size auto-fit refuses to
                           // go below just to save a sheet of paper
  var L1_FONT_MAX = 13;

  // A question column narrower than this is not usable for reading.
  var L1_TEXT_USABLE = 35;

  var _l1MmProbe = null;

  // CSS mm and CSS pt are absolute units in both media, so a probe measured
  // on screen converts print millimetres just as well.
  function l1PxPerMm() {
    if (!_l1MmProbe || !_l1MmProbe.parentNode) {
      _l1MmProbe = document.createElement('div');
      _l1MmProbe.style.cssText =
        'position:absolute;left:-30000px;top:0;width:100mm;height:0;visibility:hidden;';
      document.body.appendChild(_l1MmProbe);
    }
    var w = _l1MmProbe.getBoundingClientRect().width / 100;
    return w > 0 ? w : 96 / 25.4;
  }

  function l1Clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }

  /**
   * Column widths for one candidate font size.
   *
   * Order of claims on the printable width:
   *   1. ID and Standard scale with the font (they hold short, fixed strings).
   *   2. Every check column gets at least enough room for a two-letter day
   *      label at that font size — that is what stops "Mo" from being broken
   *      into three stacked characters and inflating the table header.
   *   3. The question column is capped, so long questions wrap over 2-3 lines
   *      the way the original Excel sheet did, instead of stretching into one
   *      thin line across a wide A3.
   *   4. Whatever is still left goes back to the check columns (up to a sane
   *      cap) and only then back to the question column.
   *
   * Works for 1 check column and for 50 — with many columns the check block
   * simply squeezes down to its font-derived minimum and the question column
   * takes the hit, which is the only thing that can give.
   */
  function l1ColumnWidths(printableW, nCheck, fontPt, textPref) {
    var idW  = l1Clamp(fontPt * 1.05, 7.5, 14);
    // Wide enough for the longest standard name ("Materialbereitstellung") at
    // the reduced size the .l1-std-cell rule renders it in.
    var stdW = l1Clamp(fontPt * 3.70, 20, 46);

    // A two-letter bold label plus cell padding, in mm.
    var checkMin = l1Clamp(fontPt * 0.3528 * 1.25 + 0.9, 4.2, 12);
    var checkMax = Math.max(checkMin, l1Clamp(fontPt * 1.15, 7, 14));

    var avail = printableW - idW - stdW;
    if (avail < 20) {                       // pathological: shrink the fixed pair
      var over = 20 - avail;
      stdW = Math.max(14, stdW - over);
      avail = printableW - idW - stdW;
    }

    // Start with the check block at its minimum, question column gets the rest.
    var checkW = checkMin;
    var textW  = avail - nCheck * checkW;

    if (textW < 25) {
      // Not enough width for a usable question column — take it out of the
      // check columns down to an absolute floor, then accept the overflow.
      var need = 25 - textW;
      checkW = Math.max(3.6, checkW - need / Math.max(1, nCheck));
      textW  = avail - nCheck * checkW;
    }

    // Cap the question column so questions wrap instead of running as one line.
    var textCap;
    if (textPref === 0)        textCap = Infinity;                 // "as wide as possible"
    else if (textPref > 0)     textCap = textPref;                 // explicit mm
    else                       textCap = l1Clamp(printableW * 0.42, 70, 190); // auto

    if (textW > textCap) {
      var surplus = textW - textCap;
      textW = textCap;
      if (nCheck > 0) {
        checkW = Math.min(checkMax, checkW + surplus / nCheck);
        textW  = avail - nCheck * checkW;   // any remainder after the cap
      } else {
        textW = avail;
      }
    }

    textW = Math.max(18, textW);

    return {
      id: idW, std: stdW, check: checkW, text: textW,
      // Enough width left for a readable question column at this font?
      fits: (idW + stdW + nCheck * checkW + L1_TEXT_USABLE) <= printableW + 0.5,
      usable: textW >= L1_TEXT_USABLE
    };
  }

  function l1Metrics(fontPt, cols, density, rowMin) {
    var d = L1_DENSITY[density] || L1_DENSITY.normal;
    return {
      font:    fontPt,
      cols:    cols,
      density: density,
      rowMin:  rowMin,
      padV:    d.padV,
      padH:    l1Clamp(fontPt * 0.13, 0.7, 1.6),
      hdrFont: l1Clamp(fontPt * 0.96, 6.5, 11)
    };
  }

  function l1ApplyMetrics(el, m, pageW) {
    var s = el.style;
    s.setProperty('--l1-page-w',    pageW ? (pageW + 'mm') : '100%');
    s.setProperty('--l1-font',      m.font.toFixed(2) + 'pt');
    s.setProperty('--l1-pad-v',     m.padV.toFixed(2) + 'mm');
    s.setProperty('--l1-pad-h',     m.padH.toFixed(2) + 'mm');
    s.setProperty('--l1-row-min',   m.rowMin.toFixed(2) + 'mm');
    s.setProperty('--l1-col-id',    m.cols.id.toFixed(2) + 'mm');
    s.setProperty('--l1-col-std',   m.cols.std.toFixed(2) + 'mm');
    s.setProperty('--l1-col-text',  m.cols.text.toFixed(2) + 'mm');
    s.setProperty('--l1-col-check', m.cols.check.toFixed(2) + 'mm');
    s.setProperty('--l1-hdr-font',  m.hdrFont.toFixed(2) + 'pt');
  }

  /**
   * Lay the real report out off-screen at the exact printable width and read
   * back every height that pagination depends on. Returns millimetres.
   */
  function l1MeasureLayout(sourcePage, m, pageW) {
    var pxmm = l1PxPerMm();
    var probe = document.createElement('div');
    probe.className = 'l1-page l1-measure';
    l1ApplyMetrics(probe, m, pageW);

    var kids = sourcePage.children;
    for (var i = 0; i < kids.length; i++) {
      if (kids[i].id === 'l1-stateMsg') continue;
      var c = kids[i].cloneNode(true);
      c.removeAttribute('id');
      var withId = c.querySelectorAll ? c.querySelectorAll('[id]') : [];
      for (var j = 0; j < withId.length; j++) withId[j].removeAttribute('id');
      probe.appendChild(c);
    }

    document.body.appendChild(probe);

    var res = null;
    try {
      var tbl = probe.querySelector('.l1-table');
      if (!tbl) return null;

      var thead = tbl.querySelector('thead');
      var rows  = tbl.querySelectorAll('tbody tr');

      // offsetTop of the table inside the absolutely positioned probe folds in
      // the report header, the hint and every margin between them.
      var preTable = tbl.offsetTop / pxmm;
      var theadH   = (thead ? thead.getBoundingClientRect().height : 0) / pxmm;

      var rowH = [];
      for (var r = 0; r < rows.length; r++) {
        rowH.push(rows[r].getBoundingClientRect().height / pxmm);
      }

      // Everything after the table — the footer plus the gap above it. Taken
      // from the table's bottom edge so the footer's own top margin is
      // included; leaving it out was enough to push the footer onto a sheet
      // of its own.
      var footerH = Math.max(0,
        (probe.offsetHeight - (tbl.offsetTop + tbl.offsetHeight)) / pxmm);

      res = {
        preTable: preTable,
        thead:    theadH,
        rows:     rowH,
        footer:   footerH,
        natural:  rowH.length ? Math.min.apply(null, rowH) : 0,
        tableW:   tbl.getBoundingClientRect().width / pxmm
      };
    } finally {
      if (probe.parentNode) probe.parentNode.removeChild(probe);
    }
    return res;
  }

  /**
   * Simulate pagination for a given minimum row height. Rows carry
   * `break-inside: avoid`, so the browser packs them exactly like this greedy
   * walk does — no row is ever split. Pure arithmetic, so the row-height
   * search below costs nothing.
   */
  // Sub-millimetre rounding differences between the measured layout and the
  // printer's own layout pass can spill one row onto an extra sheet.
  //  - a flat hold-back for the page itself, and
  //  - a per-row allowance, because that rounding accumulates: a 31-row
  //    checklist drifted far enough to push its footer onto a third sheet.
  var L1_PAGE_SAFETY = 1.5;
  var L1_ROW_SAFETY  = 0.10;

  function l1PackPages(meas, pageH, rowMin) {
    pageH -= L1_PAGE_SAFETY;
    // The footer is reserved on every sheet rather than only checked at the
    // end. Costs ~4 mm a page and removes the failure mode where everything
    // fits but the footer alone lands on a sheet of its own.
    var availFirst = pageH - meas.preTable - meas.thead - meas.footer;
    var availRest  = pageH - meas.thead - meas.footer;
    if (availFirst < 5 || availRest < 5) return { pages: 999, remain: 0, avail: 1 };

    var pages = 1, y = availFirst, avail = availFirst;
    for (var i = 0; i < meas.rows.length; i++) {
      var h = Math.max(meas.rows[i], rowMin) + L1_ROW_SAFETY;
      if (h > y + 0.01) { pages++; y = availRest; avail = availRest; }
      y -= h;
    }

    return { pages: pages, remain: Math.max(0, y), avail: avail };
  }

  /** Largest row height that still keeps the report inside `targetPages`. */
  function l1FitRowHeight(meas, pageH, targetPages, lo, hi) {
    if (l1PackPages(meas, pageH, hi).pages <= targetPages) return hi;
    for (var it = 0; it < 24; it++) {
      var mid = (lo + hi) / 2;
      if (l1PackPages(meas, pageH, mid).pages <= targetPages) lo = mid; else hi = mid;
    }
    return lo;
  }

  // ── Level 1 Checklist: init ──

  function initLevel1Checklist() {
    console.log("initLevel1Checklist called");

    var selPC       = document.getElementById('l1-selPC');
    var selFA       = document.getElementById('l1-selFA');
    var selDay      = document.getElementById('l1-selDay');
    var selLang     = document.getElementById('l1-selLang');
    var selPaper    = document.getElementById('l1-selPaper');
    var inpDate     = document.getElementById('l1-inpDate');
    var btnLoad     = document.getElementById('l1-btnLoad');
    var btnPrint    = document.getElementById('l1-btnPrint');
    var stateMsg    = document.getElementById('l1-stateMsg');
    var tableWrap   = document.getElementById('l1-tableWrap');
    var reportHeader = document.getElementById('l1-reportHeader');
    var reportFooter = document.getElementById('l1-reportFooter');
    var hintBox     = document.getElementById('l1-hint');

    // Print layout knobs
    var selFit      = document.getElementById('l1-selFit');
    var selFont     = document.getElementById('l1-selFont');
    var selDensity  = document.getElementById('l1-selDensity');
    var selMargin   = document.getElementById('l1-selMargin');
    var selTextW    = document.getElementById('l1-selTextW');
    var fitInfo     = document.getElementById('l1-fitInfo');
    var pageEl      = document.querySelector('.l1-page');

    if (!selPC || !selFA) {
      console.error("Level1 checklist DOM elements not found");
      return;
    }

    var lastChecklistData = null; // cached data to avoid re-fetch on day filter change
    var lastCheckCols     = 0;    // workplaces x visible days of the current render

    if (selPaper) {
      selPaper.addEventListener('change', function () {
        l1ApplyPaperClass();
        l1FitPrint();
      });
    }

    // Any layout knob only re-fits — the data and the markup stay untouched.
    [selFit, selFont, selDensity, selMargin, selTextW].forEach(function (sel) {
      if (sel) sel.addEventListener('change', function () { l1FitPrint(); });
    });

    l1ApplyPaperClass();

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

    // Language change -> re-fetch (text + category come from server)
    if (selLang) {
      selLang.addEventListener('change', function () {
        if (selPC.value && selFA.value) l1LoadChecklist();
      });
    }

    btnLoad.addEventListener('click', l1LoadChecklist);

    // Set the @page rule from the Paper/Orientation + margin selectors.
    function l1ApplyPageStyle() {
      var val = (selPaper && selPaper.value) || 'A4-landscape';
      var parts = val.split('-');              // e.g. ["A3","portrait"]
      var size = parts[0] || 'A4';             // A4 | A3
      var orient = parts[1] || 'landscape';    // landscape | portrait
      var st = document.getElementById('l1-page-style');
      if (st) {
        st.textContent =
          '@media print { @page { size: ' + size + ' ' + orient
          + '; margin: ' + l1Margin() + 'mm; } }';
      }
    }

    function l1Margin() {
      var v = selMargin ? parseFloat(selMargin.value) : 6;
      return isNaN(v) ? 6 : v;
    }

    function l1PageBox() {
      var paper = L1_PAPER[(selPaper && selPaper.value) || 'A4-landscape']
        || L1_PAPER['A4-landscape'];
      var m = l1Margin();
      return { w: paper.w - 2 * m, h: paper.h - 2 * m };
    }

    /**
     * Decide the print geometry for whatever is currently rendered.
     *
     * 1. Column widths follow from paper + check-column count + font.
     * 2. Font: the smallest allowed size tells us the minimum number of sheets
     *    the report can possibly occupy. Then binary-search the LARGEST font
     *    that still fits that sheet count — bigger text for free.
     * 3. Row height: with the font fixed, stretch the rows until the last
     *    sheet is full. This is what removes the "one lonely row on page 2"
     *    and what makes the grid reach the bottom edge like the Excel sheet.
     *
     * Everything is measured on the actual DOM, so a 1-workplace/1-day report
     * and a 10-workplace/5-day report are handled by the same code path.
     */
    function l1FitPrint() {
      if (!pageEl) return;
      var tbl = pageEl.querySelector('.l1-table');
      if (!tbl) { if (fitInfo) fitInfo.textContent = ''; return; }

      var box     = l1PageBox();
      var mode    = selFit ? selFit.value : 'auto';
      var density = selDensity ? selDensity.value : 'normal';
      var dens    = L1_DENSITY[density] || L1_DENSITY.normal;
      var nCheck  = lastCheckCols;

      var textPref = -1;
      if (selTextW && selTextW.value !== 'auto') {
        var tp = parseFloat(selTextW.value);
        textPref = isNaN(tp) ? -1 : tp;
      }

      function metricsFor(fontPt, rowMin) {
        return l1Metrics(fontPt, l1ColumnWidths(box.w, nCheck, fontPt, textPref),
                         density, rowMin);
      }

      var manualFont = (selFont && selFont.value !== 'auto')
        ? parseFloat(selFont.value) : null;
      if (manualFont !== null && isNaN(manualFont)) manualFont = null;

      // ── Fixed mode: honour the knobs verbatim, no searching ──
      if (mode === 'fixed') {
        var fm = metricsFor(manualFont || 9, dens.min);
        l1ApplyMetrics(pageEl, fm, box.w);
        var fMeas = l1MeasureLayout(pageEl, fm, box.w);
        l1ReportFit(fm, fMeas ? l1PackPages(fMeas, box.h, fm.rowMin) : null);
        l1ApplyPageStyle();
        return;
      }

      // ── Step 1: pick the floor the page count is derived from ──
      //
      // "auto"  starts from the readability floor: the report is allowed to
      //         take one more sheet rather than shrink to 7 pt — which was the
      //         original complaint about the printout.
      // "dense" starts from the absolute floor: fewest sheets wins, text may
      //         get small.
      // "rows"  keeps the chosen (or default) size and only stretches rows.
      var floorFont;
      if (manualFont !== null)      floorFont = manualFont;
      else if (mode === 'auto')     floorFont = L1_FONT_READ;
      else if (mode === 'dense')    floorFont = L1_FONT_MIN;
      else                          floorFont = 9;

      // Too many check columns to keep a readable question column at that
      // size? Then readability is not on offer — drop to the absolute floor.
      if (manualFont === null && !l1ColumnWidths(box.w, nCheck, floorFont, textPref).fits) {
        floorFont = L1_FONT_MIN;
      }

      var baseMeas = l1MeasureLayout(pageEl, metricsFor(floorFont, dens.min), box.w);
      if (!baseMeas) return;

      var targetPages = l1PackPages(baseMeas, box.h, dens.min).pages;

      // ── Step 2: largest font that still fits that many sheets ──
      var bestFont = floorFont;
      var bestMeas = baseMeas;

      if (manualFont === null && mode !== 'rows') {
        var lo = floorFont, hi = L1_FONT_MAX;
        var loMeas = baseMeas;
        for (var it = 0; it < 7; it++) {
          var mid = Math.round(((lo + hi) / 2) * 4) / 4;   // quarter-point steps
          if (mid <= lo + 0.01 || mid >= hi - 0.01) break;
          var mCand = metricsFor(mid, dens.min);
          var sCand = mCand.cols.fits ? l1MeasureLayout(pageEl, mCand, box.w) : null;
          if (sCand && sCand.tableW <= box.w + 0.6
                    && l1PackPages(sCand, box.h, dens.min).pages <= targetPages) {
            lo = mid; loMeas = sCand;
          } else {
            hi = mid;
          }
        }
        bestFont = lo;
        bestMeas = loMeas;
      }

      // ── Step 3: stretch the rows so the last sheet is full ──
      var rowMin = l1FitRowHeight(bestMeas, box.h, targetPages, dens.min, dens.max);
      var finalM = metricsFor(bestFont, rowMin);
      l1ApplyMetrics(pageEl, finalM, box.w);

      l1ReportFit(finalM, l1PackPages(bestMeas, box.h, rowMin));
      l1ApplyPageStyle();
    }

    function l1ReportFit(m, pack) {
      if (!fitInfo) return;
      if (!pack) { fitInfo.textContent = ''; return; }
      var fill = pack.avail > 0 ? (1 - pack.remain / pack.avail) : 1;
      var warn = (m.cols.usable === false)
        ? ' <span class="l1-fit-warn">— too many check columns for this sheet;'
          + ' use A3 landscape or filter a single weekday</span>'
        : '';
      fitInfo.innerHTML =
        '<b>' + pack.pages + '</b> page' + (pack.pages === 1 ? '' : 's')
        + ' · ' + parseFloat(m.font.toFixed(2)) + ' pt'
        + ' · row ' + m.rowMin.toFixed(1) + ' mm'
        + ' · question col ' + Math.round(m.cols.text) + ' mm'
        + ' · last page ' + Math.round(fill * 100) + '% full'
        + warn;
    }

    btnPrint.addEventListener('click', function () {
      l1ApplyPaperClass();
      l1FitPrint();
      window.print();
    });


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

      var lang = selLang ? selLang.value : 'DE';
      l1FetchJson(l1Api({ action: 'checklistData', profitCenter: pc, functionalArea: fa, lang: lang }))
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
      var lang      = (data && data.lang) || (selLang ? selLang.value : 'DE');
      var t         = l1T(lang);
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

      if (data.singleWorkplaceMode) {
        wp = [{ name: data.functionalArea.name }];
      }

      // Single-workplace mode: expand the lone WP into 5 consecutive weeks
      // (starting from the picked Monday). Each "virtual" WP becomes one
      // calendar week so the same printout covers 5 weeks of audits.
      // Everything downstream (colgroup/thead/tbody/signature/responsible row)
      // iterates over `wp` and so adapts automatically.
      var weekModeWpName = null;
      if (wp.length === 1 && dateStr) {
        weekModeWpName = wp[0].name;
        var baseDate = new Date(dateStr + 'T00:00:00');
        var expanded = [];
        for (var wkI = 0; wkI < 5; wkI++) {
          var d = new Date(baseDate);
          d.setDate(d.getDate() + wkI * 7);
          var cwNum = l1GetKW(l1LocalDate(d));
          expanded.push({ name: 'CW ' + cwNum });
        }
        wp = expanded;
      }

      // print header (after wp finalized so Responsible-person row aligns)
      l1BuildPrintHeader(data, kw, dateStr, wp, visDays, t, weekModeWpName);

      // hint above the checklist (visible on screen + printout)
      if (hintBox) {
        hintBox.style.display = 'block';
        hintBox.textContent = t.hint;
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

      // Column count drives every width decision downstream; l1FitPrint()
      // reads it back after the markup is in the DOM.
      var totalCheckCols = wp.length * visDays.length;
      lastCheckCols = totalCheckCols;

      var h = '<table class="l1-table">';

      // <colgroup> classes are styled in CSS — JS only emits the markup.
      // l1-col-text has no width rule → absorbs leftover space.
      h += '<colgroup>';
      h += '<col class="l1-col-id">';
      h += '<col class="l1-col-std">';
      h += '<col class="l1-col-text">';
      for (var ci = 0; ci < totalCheckCols; ci++) {
        h += '<col class="l1-col-check">';
      }
      h += '</colgroup>';

      // THEAD row 1
      h += '<thead><tr>';
      h += '<th rowspan="2" class="l1-col-id">' + escapeHtml(t.id) + '</th>';
      h += '<th rowspan="2" class="l1-col-std">' + escapeHtml(t.standard) + '</th>';
      h += '<th rowspan="2" class="l1-col-text">' + escapeHtml(t.criterion) + '</th>';
      for (var wi = 0; wi < wp.length; wi++) {
        h += '<th colspan="' + visDays.length + '">'
          + escapeHtml(wp[wi].name) + '</th>';
      }
      h += '</tr>';

      // THEAD row 2 - day sub-headers
      h += '<tr>';
      for (var wi = 0; wi < wp.length; wi++) {
        for (var di = 0; di < visDays.length; di++) {
          h += '<th class="l1-col-check">' + escapeHtml(l1DayLabel(t, visDays[di])) + '</th>';
        }
      }
      h += '</tr></thead>';

      // TBODY — single row per question. The Standard label is repeated in
      // every row (instead of one merged rowspan cell) so it stays visible on
      // every printed page, no matter how large the group is or where the page
      // breaks. Each group sits in its own <tbody class="l1-grp"> so it stays
      // together when it happens to fit on one page.
      for (var gi = 0; gi < groups.length; gi++) {
        var grp = groups[gi];

        h += '<tbody class="l1-grp">';
        for (var qi = 0; qi < grp.items.length; qi++) {
          var q = grp.items[qi];
          var qActive = q.day ? [q.day] : L1_DAYS;

          // Standard label repeated on every row (so it never vanishes at a
          // page break). First row of the group leads in bold; the rest are
          // shown quietly via .l1-std-cont so the column reads as one block.
          var stdContCls = (qi === 0) ? '' : ' l1-std-cont';

          h += '<tr>';
          h += '<td class="l1-col-id">' + escapeHtml(q.id) + '</td>';
          h += '<td class="l1-std-cell l1-col-std' + stdContCls + '">'
            + escapeHtml(grp.standard) + '</td>';
          h += '<td class="l1-text-cell l1-col-text">' + escapeHtml(q.text) + '</td>';
          for (var wi = 0; wi < wp.length; wi++) {
            for (var di = 0; di < visDays.length; di++) {
              var cls = (qActive.indexOf(visDays[di]) === -1) ? ' l1-cell-disabled' : '';
              h += '<td class="l1-col-check' + cls + '"></td>';
            }
          }
          h += '</tr>';
        }
        h += '</tbody>';
      }

      // Signature row in its own tbody so it never splits across a page break.
      h += '<tbody>';
      h += '<tr class="l1-signature-row">';
      h += '<td colspan="3" class="l1-signature-label">' + escapeHtml(t.signature) + '</td>';
      for (var wi = 0; wi < wp.length; wi++) {
        h += '<td colspan="' + visDays.length + '"></td>';
      }
      h += '</tr>';
      h += '</tbody></table>';
      tableWrap.innerHTML = h;
      l1ApplyPaperClass();

      reportFooter.style.display = 'flex';
      document.getElementById('l1-footerDate').textContent =
        'Generated: ' + new Date().toLocaleDateString('en-GB');

      // Markup is in the DOM — now size it to the sheet.
      l1FitPrint();
    }

    // ── Print header (visible only in @media print) ──
    function l1BuildPrintHeader(data, kw, dateStr, wp, visDays, t, weekModeWpName) {
      var pcTxt  = selPC.options[selPC.selectedIndex] ? selPC.options[selPC.selectedIndex].text : '';
      var faTxt  = selFA.options[selFA.selectedIndex] ? selFA.options[selFA.selectedIndex].text : '';
      var dayTxt = selDay.options[selDay.selectedIndex] ? selDay.options[selDay.selectedIndex].text : 'All';
      var dateFmt = dateStr
        ? new Date(dateStr + 'T00:00:00').toLocaleDateString('en-GB')
        : '';

      // In 5-week mode the column headers carry CWs of the whole range,
      // so show the range in the "Calendar week" field instead of a single CW.
      var kwTxt = 'CW ' + kw;
      if (weekModeWpName && dateStr) {
        var endDate = new Date(dateStr + 'T00:00:00');
        endDate.setDate(endDate.getDate() + 4 * 7);
        var kwEnd = l1GetKW(l1LocalDate(endDate));
        kwTxt = 'CW ' + kw + ' – CW ' + kwEnd;
      }

      // Three rows instead of four/five: "KVS Stufe" and the usage filter share
      // one line. Every millimetre saved up here is a millimetre the checklist
      // rows get back on the sheet.
      var lvlTxt = '1';
      if (data.usageKey) {
        lvlTxt += '   ·   ' + escapeHtml(t.questionUsage) + ': ' + escapeHtml(data.usageKey);
      }

      var h = '<table>';
      h += '<tr><td class="l1-rh-label">' + escapeHtml(t.profitCenter) + ':</td><td>' + escapeHtml(pcTxt) + '</td>';
      h += '<td class="l1-rh-label">' + escapeHtml(t.calendarWeek) + ':</td><td>' + escapeHtml(kwTxt) + '</td></tr>';
      h += '<tr><td class="l1-rh-label">' + escapeHtml(t.functionalArea) + ':</td><td>' + escapeHtml(faTxt) + '</td>';
      h += '<td class="l1-rh-label">' + escapeHtml(t.mondayDate) + ':</td><td>' + dateFmt + '</td></tr>';
      h += '<tr><td class="l1-rh-label">' + escapeHtml(t.kvsLevel) + ':</td><td>' + lvlTxt + '</td>';
      h += '<td class="l1-rh-label">' + escapeHtml(t.dayFilter) + ':</td><td>' + escapeHtml(dayTxt) + '</td></tr>';
      if (weekModeWpName) {
        h += '<tr><td class="l1-rh-label">Workplace:</td><td colspan="3">' + escapeHtml(weekModeWpName) + '</td></tr>';
      }
      h += '</table>';

      // Per-workplace Responsible-person row — filled in by hand after printout
      if (wp && wp.length) {
        h += '<table class="l1-rh-resp"><tr>';
        h += '<td class="l1-rh-label">' + escapeHtml(t.responsiblePerson) + ':</td>';
        for (var wi = 0; wi < wp.length; wi++) {
          h += '<td class="l1-rh-wp-name">' + escapeHtml(wp[wi].name) + '</td>';
        }
        h += '</tr><tr>';
        h += '<td class="l1-rh-label l1-rh-resp-blank"></td>';
        for (var wi2 = 0; wi2 < wp.length; wi2++) {
          h += '<td></td>';
        }
        h += '</tr></table>';
      }

      reportHeader.innerHTML = h;
    }

    function l1ApplyPaperClass() {
        var page = pageEl;
        if (!page) return;

        page.classList.remove(
            'l1-paper-a4-landscape',
            'l1-paper-a3-landscape',
            'l1-paper-a3-portrait'
        );

        var val = (selPaper && selPaper.value) || 'A4-landscape';

        if (val === 'A4-landscape') {
            page.classList.add('l1-paper-a4-landscape');
        } else if (val === 'A3-landscape') {
            page.classList.add('l1-paper-a3-landscape');
        } else if (val === 'A3-portrait') {
            page.classList.add('l1-paper-a3-portrait');
        }
    }

  }

});