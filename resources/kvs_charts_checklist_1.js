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
    var host = document.getElementById('overallLiveGauge');
    var btn = document.getElementById('kvs-live-refresh-btn');
    if (!host) return;

    host.style.display = 'block';
    host.innerHTML = '<div style="font-size:12px;color:#6B778C;text-align:center;padding:8px;">Loading live (today)…</div>';
    if (btn) { btn.disabled = true; btn.textContent = '⏳ Live'; }

    var url = AJS.contextPath()
      + '/rest/scriptrunner/latest/custom/kvsChartsLive'
      + '?pcKey=' + encodeURIComponent(pcKey);

    fetch(url, { method: 'GET', credentials: 'include' })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data || data.error) {
          host.innerHTML = '<div style="font-size:12px;color:#FF5630;text-align:center;padding:8px;">Live error: '
            + escapeHtml((data && data.error) || 'unknown') + '</div>';
          return;
        }
        var pct = toNumber(data.performancePct);
        host.innerHTML =
          '<div class="kvs-gauge-label">Today (live) · W' + escapeHtml(data.week || '-') + '</div>'
          + buildGaugeSvg(pct, { width: 220 })
          + '<div class="kvs-gauge-sub">Questions: <strong>' + toNumber(data.questionsCount) + '</strong>'
          + ' · computed in ' + toNumber(data.elapsedMs) + ' ms</div>';
      })
      .catch(function (err) {
        host.innerHTML = '<div style="font-size:12px;color:#FF5630;text-align:center;padding:8px;">Live fetch failed: '
          + escapeHtml(String(err)) + '</div>';
      })
      .then(function () {
        if (btn) { btn.disabled = false; btn.textContent = '🔄 Live'; }
      });
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Top-level render
  // ═══════════════════════════════════════════════════════════════════

  function renderAll(data) {
    destroyAllCharts();

    // Hide stale live overlay — values were computed for the previous scope
    var liveHost = document.getElementById('overallLiveGauge');
    if (liveHost) { liveHost.style.display = 'none'; liveHost.innerHTML = ''; }

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

  function destroyAllCharts() {
    if (!window.Chart || !Chart.instances) return;
    Object.keys(Chart.instances).forEach(function (key) {
      const chart = Chart.instances[key];
      if (chart && typeof chart.destroy === 'function') {
        try { chart.destroy(); } catch (e) { /* ignore */ }
      }
    });
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

  function renderOverallGauge(data) {
    const host = document.getElementById('overallGauge');
    const breakdownHost = document.getElementById('overallBreakdown');
    if (!host) return;

    const overall = data.overall || {};
    const avg = toNumber(overall.averagePct != null ? overall.averagePct : data.keyFigureAvg12);
    const latestWeek = overall.latestWeek || '-';
    const latestPct = toNumber(overall.latestWeekPct != null ? overall.latestWeekPct : 0);

    host.innerHTML = ''
      + '<div class="kvs-gauge-label">' + escapeHtml(resolvePcLabel(data)) + ' · Rolling KPI</div>'
      + buildGaugeSvg(avg, { width: 260 })
      + '<div class="kvs-gauge-sub">Latest week <strong>' + escapeHtml(latestWeek) + '</strong>: '
          + formatPercent(latestPct) + '</div>';

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

    new Chart(canvas, {
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
    });

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
    new Chart(canvas, {
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
    });
  }

  function renderAuditExecChart(labels, trend) {
    const canvas = document.getElementById('auditExecChart');
    if (!canvas) return;

    const openArr = trend.map(x => toNumber(x.auditsOpen));
    const closedArr = trend.map(x => toNumber(x.auditsClosed));
    const rateArr = trend.map(x => toNumber(x.auditRatePct));

    new Chart(canvas, {
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
    });

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
      const ctx = canvas.getContext('2d');
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      return;
    }

    const palette = [
      '#36B37E', '#FFAB00', '#FF5630', '#00B8D9', '#6554C0',
      '#DFE1E6', '#0052CC', '#8777D9', '#79F2C0'
    ];

    new Chart(canvas, {
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
    });

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
      hint: 'Každú otázku vyhodnoťte ako "i.O. → ✓" alebo "n.i.O. → X". Ak otázku nie je možné zodpovedať, použite "".'
    }
  };
  function l1T(lang) { return L1_I18N[lang] || L1_I18N.DE; }

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

    if (!selPC || !selFA) {
      console.error("Level1 checklist DOM elements not found");
      return;
    }

    var lastChecklistData = null; // cached data to avoid re-fetch on day filter change

    if (selPaper) {
      selPaper.addEventListener('change', function () {
        l1ApplyPaperClass();
      });
    }

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

    // Set the @page rule from the Paper/Orientation selector, then print.
    function l1ApplyPageStyle() {
      var val = (selPaper && selPaper.value) || 'A4-landscape';
      var parts = val.split('-');              // e.g. ["A3","portrait"]
      var size = parts[0] || 'A4';             // A4 | A3
      var orient = parts[1] || 'landscape';    // landscape | portrait
      var st = document.getElementById('l1-page-style');
      if (st) {
        st.textContent =
          '@media print { @page { size: ' + size + ' ' + orient + '; margin: 6mm; } }';
      }
    }

    btnPrint.addEventListener('click', function () {
      l1ApplyPaperClass();
      l1ForcePrintSize();
      l1ApplyPageStyle();
      window.print();
    });


    window.addEventListener('afterprint', function () {
      l1RestorePrintSize();
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
          var cwNum = l1GetKW(d.toISOString().slice(0, 10));
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

      // Pick a size class based on check-column pressure. All actual widths
      // and font sizes live in the CSS (.l1-size-xl/lg/md/sm rules).
      var totalCheckCols = wp.length * visDays.length;
      var sizeCls;
      if (totalCheckCols <= 5)        sizeCls = 'l1-size-xl';
      else if (totalCheckCols <= 10)  sizeCls = 'l1-size-lg';
      else if (totalCheckCols <= 20)  sizeCls = 'l1-size-md';
      else                             sizeCls = 'l1-size-sm';

      var h = '<table class="l1-table ' + sizeCls + '">';

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
          h += '<th class="l1-col-check">' + visDays[di] + '</th>';
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
        var kwEnd = l1GetKW(endDate.toISOString().slice(0, 10));
        kwTxt = 'CW ' + kw + ' – CW ' + kwEnd;
      }

      var h = '<table>';
      h += '<tr><td class="l1-rh-label">' + escapeHtml(t.profitCenter) + ':</td><td>' + escapeHtml(pcTxt) + '</td>';
      h += '<td class="l1-rh-label">' + escapeHtml(t.calendarWeek) + ':</td><td>' + escapeHtml(kwTxt) + '</td></tr>';
      h += '<tr><td class="l1-rh-label">' + escapeHtml(t.functionalArea) + ':</td><td>' + escapeHtml(faTxt) + '</td>';
      h += '<td class="l1-rh-label">' + escapeHtml(t.mondayDate) + ':</td><td>' + dateFmt + '</td></tr>';
      h += '<tr><td class="l1-rh-label">' + escapeHtml(t.kvsLevel) + ':</td><td>1</td>';
      h += '<td class="l1-rh-label">' + escapeHtml(t.dayFilter) + ':</td><td>' + escapeHtml(dayTxt) + '</td></tr>';
      h += '<tr><td class="l1-rh-label">' + escapeHtml(t.questionUsage) + ':</td><td colspan="3">' + escapeHtml(data.usageKey) + '</td></tr>';
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

    function l1ForcePrintSize() {
        var tbl = document.querySelector('.l1-table');
        if (!tbl) return;

        var original =
          tbl.classList.contains('l1-size-xl') ? 'l1-size-xl' :
          tbl.classList.contains('l1-size-lg') ? 'l1-size-lg' :
          tbl.classList.contains('l1-size-md') ? 'l1-size-md' :
          tbl.classList.contains('l1-size-sm') ? 'l1-size-sm' : '';

        tbl.setAttribute('data-orig-size', original);

        tbl.classList.remove('l1-size-xl', 'l1-size-lg', 'l1-size-md', 'l1-size-sm');
        tbl.classList.add('l1-size-md');
    }

    function l1RestorePrintSize() {
        var tbl = document.querySelector('.l1-table');
        if (!tbl) return;

        var original = tbl.getAttribute('data-orig-size');
        tbl.classList.remove('l1-size-xl', 'l1-size-lg', 'l1-size-md', 'l1-size-sm');
        if (original) tbl.classList.add(original);
    }

    function l1ApplyPaperClass() {
        var page = document.querySelector('.l1-page');
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