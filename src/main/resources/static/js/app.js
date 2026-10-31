  function bindActivityChart() {
    var card = document.getElementById('activity-chart');
    if (!card) return;
    var slot = card.querySelector('.chart-slot');
    if (!slot) return;
    var totalEl = card.querySelector('.chart-total');
    var emptyEl = card.querySelector('.chart-empty');
    var url = card.getAttribute('data-series-url') || '/dashboard/activity-series';
    var labels = {
      sync: card.getAttribute('data-sync-label') || 'Sync',
      etl: card.getAttribute('data-etl-label') || 'ETL'
    };
    var metric = 'sync'; // Default to show both sync and etl
    var data = null;
    var tip = null;

    function cssVar(name, fallback) {
      var value = getComputedStyle(document.documentElement).getPropertyValue(name);
      return (value && value.replace(/^\s+|\s+$/g, '')) || fallback;
    }

    function group3(n) {
      var text = String(n);
      return text.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    }

    function el(tag, attrs) {
      var node = document.createElementNS('http://www.w3.org/2000/svg', tag);
      for (var key in attrs) {
        if (Object.prototype.hasOwnProperty.call(attrs, key)) {
          node.setAttribute(key, attrs[key]);
        }
      }
      return node;
    }

    // 轴顶取整齐的刻度：先把最大值除以四格，再向上取到 1/2/5 × 10^n，
    // 这样四条刻度线是 0/10/20/30/40，而不是 13/25/38 这种除不尽的值。
    function niceTop(value) {
      if (value <= 0) return 0;
      var raw = value / 4;
      var exp = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10));
      var steps = [1, 2, 5, 10];
      for (var i = 0; i < steps.length; i++) {
        if (steps[i] * exp >= raw) return steps[i] * exp * 4;
      }
      return 10 * exp * 4;
    }

    function clearChart() {
      var svg = slot.querySelector('svg');
      if (svg) slot.removeChild(svg);
    }

    function draw() {
      if (!data) return;
      var points = data.points || [];
      var syncValues = [];
      var etlValues = [];
      var max = 0;

      // 计算每个系列的值
      for (var i = 0; i < points.length; i++) {
        var syncChanges = Number(points[i].syncChanges) || 0;
        var etlChanges = Number(points[i].etlChanges) || 0;

        syncValues.push(syncChanges);
        etlValues.push(etlChanges);

        if (syncChanges > max) max = syncChanges;
        if (etlChanges > max) max = etlChanges;
      }

      // 计算总和
      var totalSync = syncValues.reduce(function(a, b) { return a + b; }, 0);
      var totalEtl = etlValues.reduce(function(a, b) { return a + b; }, 0);
      if (totalEl) totalEl.textContent = group3(totalSync) + ' / ' + group3(totalEtl);

      clearChart();
      if (!points.length || max <= 0) {
        if (emptyEl) emptyEl.hidden = false;
        return;
      }
      if (emptyEl) emptyEl.hidden = true;

      var width = slot.clientWidth || 720;
      var height = slot.clientHeight || 232;
      var top = niceTop(max);
      var tickStep = top / 4;
      // 左边距跟着最宽的那条刻度走：同步行数到十万级时，"100,000" 比默认的 46px 还宽，
      // 写死就会把开头的数字切掉。
      var padL = Math.max(46, 14 + group3(top).length * 6.6);
      var padR = 12;
      var padT = 12;
      var padB = 26;
      var plotW = Math.max(1, width - padL - padR);
      var plotH = Math.max(1, height - padT - padB);
      var scale = plotH / top;

      // 双系列颜色配置
      var colors = {
        sync: cssVar('--brand', '#4f46e5'),
        etl: cssVar('--accent', '#ec4899')
      };

      var svg = el('svg', { width: width, height: height, viewBox: '0 0 ' + width + ' ' + height });
      var defs = el('defs', {});

      // 同步渐变
      var syncGradient = el('linearGradient', { id: 'sync-fill', x1: '0', y1: '0', x2: '0', y2: '1' });
      syncGradient.appendChild(el('stop', { offset: '0%', 'stop-color': colors.sync, 'stop-opacity': '0.28' }));
      syncGradient.appendChild(el('stop', { offset: '100%', 'stop-color': colors.sync, 'stop-opacity': '0' }));
      defs.appendChild(syncGradient);

      // ETL渐变
      var etlGradient = el('linearGradient', { id: 'etl-fill', x1: '0', y1: '0', x2: '0', y2: '1' });
      etlGradient.appendChild(el('stop', { offset: '0%', 'stop-color': colors.etl, 'stop-opacity': '0.28' }));
      etlGradient.appendChild(el('stop', { offset: '100%', 'stop-color': colors.etl, 'stop-opacity': '0' }));
      defs.appendChild(etlGradient);

      svg.appendChild(defs);

      // 横向网格与纵轴刻度
      for (var g = 0; g <= 4; g++) {
        var gy = padT + (plotH / 4) * g;
        svg.appendChild(el('line', {
          x1: padL, y1: gy, x2: padL + plotW, y2: gy,
          stroke: cssVar('--line', '#e2e8f0'), 'stroke-width': 1, 'stroke-dasharray': g === 4 ? '' : '3 5'
        }));
        var label = el('text', {
          x: padL - 8, y: gy + 4, 'text-anchor': 'end',
          'font-size': '11', fill: cssVar('--ink-mute', '#94a3b8')
        });
        label.textContent = group3(top - tickStep * g);
        svg.appendChild(label);
      }

      var step = points.length > 1 ? plotW / (points.length - 1) : plotW;

      // 创建坐标点
      var syncCoords = [];
      var etlCoords = [];
      for (var p = 0; p < syncValues.length; p++) {
        syncCoords.push({
          x: padL + step * p,
          y: padT + plotH - syncValues[p] * scale
        });
        etlCoords.push({
          x: padL + step * p,
          y: padT + plotH - etlValues[p] * scale
        });
      }

      // 绘制曲线函数
      function buildPath(coords) {
        if (coords.length === 0) return '';
        var d = 'M' + coords[0].x + ' ' + coords[0].y;
        for (var c = 0; c < coords.length - 1; c++) {
          var p0 = coords[c - 1] || coords[c];
          var p1 = coords[c];
          var p2 = coords[c + 1];
          var p3 = coords[c + 2] || p2;
          var c1x = p1.x + (p2.x - p0.x) / 6;
          var c1y = p1.y + (p2.y - p0.y) / 6;
          var c2x = p2.x - (p3.x - p1.x) / 6;
          var c2y = p2.y - (p3.y - p1.y) / 6;
          d += ' C' + c1x + ' ' + c1y + ' ' + c2x + ' ' + c2y + ' ' + p2.x + ' ' + p2.y;
        }
        return d;
      }

      var baseY = padT + plotH;

      // 绘制同步曲线和填充
      if (syncCoords.length > 0) {
        var syncPath = buildPath(syncCoords);
        svg.appendChild(el('path', {
          d: syncPath + ' L' + syncCoords[syncCoords.length - 1].x + ' ' + baseY
            + ' L' + syncCoords[0].x + ' ' + baseY + ' Z',
          fill: 'url(#sync-fill)', stroke: 'none'
        }));
        svg.appendChild(el('path', {
          d: syncPath, fill: 'none', stroke: colors.sync, 'stroke-width': 2.4,
          'stroke-linecap': 'round', 'stroke-linejoin': 'round'
        }));
      }

      // 绘制ETL曲线和填充
      if (etlCoords.length > 0) {
        var etlPath = buildPath(etlCoords);
        svg.appendChild(el('path', {
          d: etlPath + ' L' + etlCoords[etlCoords.length - 1].x + ' ' + baseY
            + ' L' + etlCoords[0].x + ' ' + baseY + ' Z',
          fill: 'url(#etl-fill)', stroke: 'none'
        }));
        svg.appendChild(el('path', {
          d: etlPath, fill: 'none', stroke: colors.etl, 'stroke-width': 2.4,
          'stroke-linecap': 'round', 'stroke-linejoin': 'round'
        }));
      }

      // 横轴时间：每 4 个小时一个刻度
      for (var x = 0; x < points.length; x += 4) {
        addLabel(points[x].label, syncCoords[x].x, 'middle');
      }
      if ((points.length - 1) % 4 !== 0) {
        addLabel(points[points.length - 1].label,
          syncCoords[syncCoords.length - 1].x, 'end');
      }
      function addLabel(text, cx, anchor) {
        var t = el('text', {
          x: cx, y: padT + plotH + 17, 'text-anchor': anchor,
          'font-size': '11', fill: cssVar('--ink-mute', '#94a3b8')
        });
        t.textContent = text;
        svg.appendChild(t);
      }

      // 悬停：显示两个系列的值和指示线
      var guide = el('line', {
        x1: 0, y1: padT, x2: 0, y2: baseY,
        stroke: cssVar('--ink-mute', '#94a3b8'), 'stroke-width': 1, 'stroke-dasharray': '3 4', opacity: 0
      });
      svg.appendChild(guide);

      var syncDot = el('circle', { r: 4, fill: colors.sync, stroke: cssVar('--bg', '#fff'), 'stroke-width': 2, opacity: 0 });
      var etlDot = el('circle', { r: 4, fill: colors.etl, stroke: cssVar('--bg', '#fff'), 'stroke-width': 2, opacity: 0 });
      svg.appendChild(syncDot);
      svg.appendChild(etlDot);

      var overlay = el('rect', {
        x: padL, y: padT, width: plotW, height: plotH,
        fill: 'transparent', style: 'cursor: crosshair'
      });
      svg.appendChild(overlay);

      overlay.addEventListener('mousemove', function (e) {
        var rect = overlay.getBoundingClientRect();
        var x = e.clientX - rect.left;
        if (x < 0 || x > plotW) {
          guide.style.opacity = '0';
          syncDot.style.opacity = '0';
          etlDot.style.opacity = '0';
          return;
        }
        var at = Math.round((x / plotW) * (points.length - 1));
        at = Math.max(0, Math.min(at, points.length - 1));

        var syncVal = syncValues[at];
        var etlVal = etlValues[at];
        var px = padL + step * at;
        var syncPy = padT + plotH - syncVal * scale;
        var etlPy = padT + plotH - etlVal * scale;

        guide.setAttribute('x1', px);
        guide.setAttribute('x2', px);
        syncDot.setAttribute('cx', px);
        syncDot.setAttribute('cy', syncPy);
        etlDot.setAttribute('cx', px);
        etlDot.setAttribute('cy', etlPy);
        guide.style.opacity = '1';
        syncDot.style.opacity = '1';
        etlDot.style.opacity = '1';

        // 显示两个系列的值
        var tip = document.getElementById('activity-chart-tip');
        if (!tip) {
          tip = el('div', {
            id: 'activity-chart-tip',
            style: 'position:absolute; pointer-events:none; background:var(--card); border:1px solid var(--line); border-radius:6px; padding:8px 12px; font-size:12px; color:var(--ink); z-index:1000; line-height:1.5'
          });
          slot.appendChild(tip);
        }
        tip.innerHTML =
          '<div><span style="display:inline-block;width:10px;height:10px;background:' + colors.sync + ';border-radius:2px;margin-right:6px"></span>'
          + labels.sync + ': <b>' + syncVal + '</b></div>'
          + '<div><span style="display:inline-block;width:10px;height:10px;background:' + colors.etl + ';border-radius:2px;margin-right:6px"></span>'
          + labels.etl + ': <b>' + etlVal + '</b></div>';
        tip.style.left = (e.clientX - rect.left + 15) + 'px';
        tip.style.top = (Math.min(syncPy, etlPy) - 40) + 'px';
      });

      overlay.addEventListener('mouseleave', function () {
        guide.style.opacity = '0';
        syncDot.style.opacity = '0';
        etlDot.style.opacity = '0';
        var tip = document.getElementById('activity-chart-tip');
        if (tip) tip.style.opacity = '0';
      });

      slot.appendChild(svg);
    }

    var segButtons = card.querySelectorAll('.seg-btn');
    for (var b = 0; b < segButtons.length; b++) {
      (function (button) {
        button.addEventListener('click', function () {
          var next = button.getAttribute('data-series');
          if (!next || next === metric) return;
          metric = next;
          for (var i = 0; i < segButtons.length; i++) {
            segButtons[i].classList.toggle('is-active', segButtons[i] === button);
          }
          draw();
        });
      })(segButtons[b]);
    }

    var inFlight = false;
    function load() {
      if (inFlight) return;
      inFlight = true;
      fetch(url, { cache: 'no-store', headers: { Accept: 'application/json' } })
        .then(function (response) {
          if (!response.ok) throw new Error('HTTP ' + response.status);
          return response.json();
        })
        .then(function (json) {
          data = json;
          draw();
        })
        .catch(function () {
          // 图表留上一帧：一次网络抖动不该把看板清空
        })
        .then(function () { inFlight = false; });
    }

    var resizeTimer = null;
    window.addEventListener('resize', function () {
      if (resizeTimer) clearTimeout(resizeTimer);
      resizeTimer = setTimeout(draw, 150);
    });
    if (window.MutationObserver) {
      new MutationObserver(draw).observe(document.documentElement, {
        attributes: true, attributeFilter: ['data-theme']
      });
    }

    var timer = null;
    function apply() {
      if (timer) {
        clearInterval(timer);
        timer = null;
      }
      if (!document.hidden) {
        timer = setInterval(load, SERIES_REFRESH_INTERVAL);
      }
    }
    document.addEventListener('visibilitychange', function () {
      var wasOn = timer !== null;
      apply();
      if (!document.hidden && !wasOn) load();
    });

    apply();
    load();
  }

  /**
   * 内存趋势图：显示应用 JVM 内存使用率，近 24 小时，单线图。
   */
  function bindMemoryChart() {
    var card = document.getElementById('memory-chart');
    if (!card) return;
    var slot = card.querySelector('.chart-slot');
    if (!slot) return;
    var totalEl = card.querySelector('.chart-total');
    var emptyEl = card.querySelector('.chart-empty');
    var url = card.getAttribute('data-series-url') || '/dashboard/memory-series';
    var label = card.getAttribute('data-memory-label') || 'Memory usage';
    var data = null;

    function cssVar(name, fallback) {
      var value = getComputedStyle(document.documentElement).getPropertyValue(name);
      return (value && value.replace(/^\s+|\s+$/g, '')) || fallback;
    }

    function group3(n) {
      var text = String(n);
      return text.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    }

    function el(tag, attrs) {
      var node = document.createElementNS('http://www.w3.org/2000/svg', tag);
      for (var key in attrs) {
        if (Object.prototype.hasOwnProperty.call(attrs, key)) {
          node.setAttribute(key, attrs[key]);
        }
      }
      return node;
    }

    function niceTop(value) {
      if (value <= 0) return 0;
      var raw = value / 4;
      var exp = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10));
      var steps = [1, 2, 5, 10];
      for (var i = 0; i < steps.length; i++) {
        if (steps[i] * exp >= raw) return steps[i] * exp * 4;
      }
      return 10 * exp * 4;
    }

    function clearChart() {
      var svg = slot.querySelector('svg');
      if (svg) slot.removeChild(svg);
    }

    function draw() {
      if (!data) return;
      var points = data.points || [];
      var values = [];
      var max = 0;

      for (var i = 0; i < points.length; i++) {
        var v = Number(points[i].memoryPercent) || 0;
        values.push(v);
        if (v > max) max = v;
      }

      // 计算总计：平均内存使用率
      var total = values.length > 0 ? values.reduce(function(a, b) { return a + b; }, 0) / values.length : 0;
      if (totalEl) totalEl.textContent = Math.round(total) + '%';

      clearChart();
      if (!points.length || max <= 0) {
        if (emptyEl) emptyEl.hidden = false;
        return;
      }
      if (emptyEl) emptyEl.hidden = true;

      var width = slot.clientWidth || 360;
      var height = slot.clientHeight || 232;
      var top = niceTop(max);
      var tickStep = top / 4;
      var padL = 46;
      var padR = 12;
      var padT = 12;
      var padB = 26;
      var plotW = Math.max(1, width - padL - padR);
      var plotH = Math.max(1, height - padT - padB);
      var scale = plotH / top;

      // 颜色配置 - 内存用紫色
      var color = cssVar('--accent', '#ec4899');
      var from = color;
      var to = color;

      var svg = el('svg', { width: width, height: height, viewBox: '0 0 ' + width + ' ' + height });
      var defs = el('defs', {});
      var gradient = el('linearGradient', { id: 'memory-fill', x1: '0', y1: '0', x2: '0', y2: '1' });
      gradient.appendChild(el('stop', { offset: '0%', 'stop-color': from, 'stop-opacity': '0.28' }));
      gradient.appendChild(el('stop', { offset: '100%', 'stop-color': from, 'stop-opacity': '0' }));
      defs.appendChild(gradient);
      svg.appendChild(defs);

      // 横向网格与纵轴刻度
      for (var g = 0; g <= 4; g++) {
        var gy = padT + (plotH / 4) * g;
        svg.appendChild(el('line', {
          x1: padL, y1: gy, x2: padL + plotW, y2: gy,
          stroke: cssVar('--line', '#e2e8f0'), 'stroke-width': 1, 'stroke-dasharray': g === 4 ? '' : '3 5'
        }));
        var label = el('text', {
          x: padL - 8, y: gy + 4, 'text-anchor': 'end',
          'font-size': '11', fill: cssVar('--ink-mute', '#94a3b8')
        });
        label.textContent = group3(Math.round(top - tickStep * g)) + '%';
        svg.appendChild(label);
      }

      var step = points.length > 1 ? plotW / (points.length - 1) : plotW;
      var coords = [];
      for (var p = 0; p < values.length; p++) {
        coords.push({
          x: padL + step * p,
          y: padT + plotH - values[p] * scale
        });
      }

      // 平滑曲线
      var d = 'M' + coords[0].x + ' ' + coords[0].y;
      for (var c = 0; c < coords.length - 1; c++) {
        var p0 = coords[c - 1] || coords[c];
        var p1 = coords[c];
        var p2 = coords[c + 1];
        var p3 = coords[c + 2] || p2;
        var c1x = p1.x + (p2.x - p0.x) / 6;
        var c1y = p1.y + (p2.y - p0.y) / 6;
        var c2x = p2.x - (p3.x - p1.x) / 6;
        var c2y = p2.y - (p3.y - p1.y) / 6;
        d += ' C' + c1x + ' ' + c1y + ' ' + c2x + ' ' + c2y + ' ' + p2.x + ' ' + p2.y;
      }
      var baseY = padT + plotH;
      svg.appendChild(el('path', {
        d: d + ' L' + coords[coords.length - 1].x + ' ' + baseY
          + ' L' + coords[0].x + ' ' + baseY + ' Z',
        fill: 'url(#memory-fill)', stroke: 'none'
      }));
      svg.appendChild(el('path', {
        d: d, fill: 'none', stroke: from, 'stroke-width': 2.4,
        'stroke-linecap': 'round', 'stroke-linejoin': 'round'
      }));

      // 横轴时间
      for (var x = 0; x < points.length; x += 4) {
        addLabel(points[x].label, coords[x].x, 'middle');
      }
      if ((points.length - 1) % 4 !== 0) {
        addLabel(points[points.length - 1].label,
          coords[coords.length - 1].x, 'end');
      }
      function addLabel(text, cx, anchor) {
        var t = el('text', {
          x: cx, y: padT + plotH + 17, 'text-anchor': anchor,
          'font-size': '11', fill: cssVar('--ink-mute', '#94a3b8')
        });
        t.textContent = text;
        svg.appendChild(t);
      }

      // 悬停
      var guide = el('line', {
        x1: 0, y1: padT, x2: 0, y2: baseY,
        stroke: from, 'stroke-width': 1, 'stroke-dasharray': '3 4', opacity: 0
      });
      var dot = el('circle', {
        r: 4, fill: from, stroke: cssVar('--bg', '#fff'), 'stroke-width': 2, opacity: 0
      });
      svg.appendChild(guide);
      svg.appendChild(dot);

      var overlay = el('rect', {
        x: padL, y: padT, width: plotW, height: plotH,
        fill: 'transparent', style: 'cursor: crosshair'
      });
      svg.appendChild(overlay);

      overlay.addEventListener('mousemove', function (e) {
        var rect = overlay.getBoundingClientRect();
        var x = e.clientX - rect.left;
        if (x < 0 || x > plotW) {
          guide.style.opacity = '0';
          dot.style.opacity = '0';
          return;
        }
        var at = Math.round((x / plotW) * (points.length - 1));
        at = Math.max(0, Math.min(at, points.length - 1));
        var point = points[at];
        var px = padL + step * at;
        var py = padT + plotH - point.memoryPercent * scale;

        guide.setAttribute('x1', px);
        guide.setAttribute('x2', px);
        dot.setAttribute('cx', px);
        dot.setAttribute('cy', py);
        guide.style.opacity = '1';
        dot.style.opacity = '1';

        var valueText = Math.round(point.memoryPercent) + '%';
        var tip = document.getElementById('memory-chart-tip');
        if (!tip) {
          tip = el('div', {
            id: 'memory-chart-tip',
            style: 'position:absolute; pointer-events:none; background:var(--card); border:1px solid var(--line); border-radius:6px; padding:6px 10px; font-size:12px; color:var(--ink); z-index:1000'
          });
          slot.appendChild(tip);
        }
        tip.innerHTML = '<b>' + valueText + '</b> ' + label;
        tip.style.left = (e.clientX - rect.left + 10) + 'px';
        tip.style.top = (py - 30) + 'px';
      });

      overlay.addEventListener('mouseleave', function () {
        guide.style.opacity = '0';
        dot.style.opacity = '0';
        var tip = document.getElementById('memory-chart-tip');
        if (tip) tip.style.opacity = '0';
      });

      slot.appendChild(svg);
    }

    var inFlight = false;
    function load() {
      if (inFlight) return;
      inFlight = true;

      // 添加加载动画
      slot.classList.add('loading');
      var emptyEl = card.querySelector('.chart-empty');
      if (emptyEl) emptyEl.textContent = '加载中...';

      fetch(url, { cache: 'no-store', headers: { Accept: 'application/json' } })
        .then(function (response) {
          if (!response.ok) throw new Error('HTTP ' + response.status);
          return response.json();
        })
        .then(function (json) {
          data = json;
          draw();
          // 数据加载完成后添加动画类
          slot.classList.add('loaded');
          setTimeout(function() {
            slot.classList.remove('loading');
            slot.classList.add('fade-in');
          }, 300);
        })
        .catch(function () {
          // 图表留上一帧，显示错误
          if (emptyEl) emptyEl.textContent = '加载失败';
          slot.classList.remove('loading');
        })
        .then(function () { inFlight = false; });
    }

    var resizeTimer = null;
    window.addEventListener('resize', function () {
      if (resizeTimer) clearTimeout(resizeTimer);
      resizeTimer = setTimeout(draw, 150);
    });
    if (window.MutationObserver) {
      new MutationObserver(draw).observe(document.documentElement, {
        attributes: true, attributeFilter: ['data-theme']
      });
    }

    apply();
    load();
  }  // 初始化所有图表
  bindActivityChart();
  bindMemoryChart();
  bindProjectOverview();
  bindActivityFeed();

  // 项目概览跑马灯效果
  function bindProjectOverview() {
    const container = document.querySelector('#project-overview .tbl-wrap');
    if (!container) return;

    const table = container.querySelector('table');
    if (!table) return;

    const rows = table.querySelectorAll('tbody tr');
    if (rows.length <= 5) return; // 行数少于等于5不需要滚动

    // 克隆前几行到末尾，实现无缝滚动
    const cloneCount = Math.min(rows.length, 5);
    for (let i = 0; i < cloneCount; i++) {
      const clone = rows[i].cloneNode(true);
      table.querySelector('tbody').appendChild(clone);
    }

    let scrollPos = 0;
    const rowHeight = rows[0].getBoundingClientRect().height;
    const containerHeight = container.clientHeight;
    const totalRows = rows.length;

    function scroll() {
      scrollPos += 1;

      // 当滚动到克隆行位置时，重置位置
      if (scrollPos >= totalRows * rowHeight) {
        scrollPos = 0;
      }

      container.scrollTop = scrollPos;
      requestAnimationFrame(() => setTimeout(scroll, 50));
    }

    // 开始滚动
    let scrollInterval = requestAnimationFrame(scroll);

    // 悬停暂停
    container.addEventListener('mouseenter', () => {
      cancelAnimationFrame(scrollInterval);
    });

    container.addEventListener('mouseleave', () => {
      scrollInterval = requestAnimationFrame(scroll);
    });
  }

  // 最近活动跑马灯效果
  function bindActivityFeed() {
    const feed = document.getElementById('activity-feed-list');
    if (!feed) return;

    const items = feed.querySelectorAll('.feed-live li');
    if (items.length <= 5) return; // 少于等于5个条目不需要滚动

    const itemHeight = items[0].getBoundingClientRect().height;
    const containerHeight = feed.clientHeight;
    let currentIndex = 0;

    // 自动滚动效果
    function scrollFeed() {
      currentIndex++;

      if (currentIndex >= items.length) {
        currentIndex = 0;
      }

      // 平滑滚动
      feed.scrollTo({
        top: currentIndex * itemHeight,
        behavior: 'smooth'
      });
    }

    // 每3秒滚动一次
    let scrollTimer = setInterval(scrollFeed, 3000);

    // 悬停暂停
    feed.addEventListener('mouseenter', () => {
      clearInterval(scrollTimer);
    });

    feed.addEventListener('mouseleave', () => {
      scrollTimer = setInterval(scrollFeed, 3000);
    });

    // 支持手动滚动
    let isScrolling = false;
    let startY = 0;

    feed.addEventListener('touchstart', (e) => {
      isScrolling = true;
      startY = e.touches[0].clientY;
      clearInterval(scrollTimer);
    });

    feed.addEventListener('touchmove', (e) => {
      if (!isScrolling) return;
      const deltaY = startY - e.touches[0].clientY;
      feed.scrollTop += deltaY;
      startY = e.touches[0].clientY;
    });

    feed.addEventListener('touchend', () => {
      isScrolling = false;
      scrollTimer = setInterval(scrollFeed, 3000);
    });
  }
  bindMemoryChart();
