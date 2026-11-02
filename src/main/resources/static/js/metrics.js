/* ==========================================================================
   性能页图表：折线、环形占比、横向条形。与看板趋势图同一套做法 ——
   不引图表库（内网离线、老内核），配色取 CSS 变量，深浅主题自动跟随。

   约定：服务端给的点 value 为 null 表示「这个时间段没有采样」，
   不是 0。null 处折线断开，绝不连成一条假装有数据的线。
   ========================================================================== */
(function () {
  'use strict';

  /* 采样是后台定时写的，页面 10 秒拉一次；隐藏标签页时停掉，回来立刻补一次。 */
  var REFRESH_INTERVAL = 10000;

  /* 每张折线图的面积渐变必须有全局唯一的 id：同一文档里重复 id 时，url(#id) 只会解析到
     文档顺序中的第一个，导致项目页所有曲线（吞吐 / 延迟 / 失败）的面积都染成第一张图的颜色。 */
  var gradientSeq = 0;

  function cssVar(name, fallback) {
    var value = getComputedStyle(document.documentElement).getPropertyValue(name);
    return (value && value.replace(/^\s+|\s+$/g, '')) || fallback;
  }

  function group3(n) {
    return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }

  function formatNumber(value, digits) {
    if (value === null || value === undefined || isNaN(value)) return '—';
    if (digits === 0) return group3(Math.round(value));
    return group3(Math.round(value * 10) / 10);
  }

  var DURATION_UNITS = [
    { ms: 86400000, suffix: ' d' },
    { ms: 3600000, suffix: ' h' },
    { ms: 60000, suffix: ' m' },
    { ms: 1000, suffix: ' s' },
    { ms: 1, suffix: ' ms' }
  ];

  /**
   * 毫秒换算成最多两级时间单位：1135000 → "18 m 55 s"，1992 → "1 s 992 ms"。
   * 和变更日志的耗时（DurationFormat）同一种读法；单位字母与界面语言无关，中英文界面
   * 共用一份。不足一秒保留毫秒——写成 "0 s" 会把几百毫秒的延迟抹成「没有延迟」。
   */
  function formatDuration(value) {
    if (value === null || value === undefined || isNaN(value)) return '—';
    var ms = Math.max(0, Math.round(Number(value)));
    if (ms < 1000) return group3(ms) + ' ms';
    for (var i = 0; i < DURATION_UNITS.length; i++) {
      var unit = DURATION_UNITS[i];
      if (ms >= unit.ms) {
        var whole = Math.floor(ms / unit.ms);
        var lower = DURATION_UNITS[i + 1];
        var remainder = lower ? Math.floor((ms - whole * unit.ms) / lower.ms) : 0;
        return whole + unit.suffix + (remainder > 0 ? ' ' + remainder + lower.suffix : '');
      }
    }
    return ms + ' ms';
  }

  /** 曲线纵轴用的最大时间单位：全是 < 1 s 的探测延迟时，轴仍然是毫秒。 */
  function durationUnit(max) {
    for (var i = 0; i < DURATION_UNITS.length; i++) {
      if (max >= DURATION_UNITS[i].ms) return DURATION_UNITS[i];
    }
    return { ms: 1, suffix: ' ms' };
  }

  var BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];

  /**
   * 字节数换算成最多两级单位：16384 → "16 KB"，1 GB 又 2 MB → "1 GB 2 MB"。
   * 目录统计给的是原始字节（MySQL 为 DATA_LENGTH + INDEX_LENGTH），直接显示 16384
   * 既看不出单位，也读不出量级。
   */
  function formatBytes(value) {
    if (value === null || value === undefined || isNaN(value)) return '—';
    var n = Math.max(0, Math.round(Number(value)));
    var unit = 0;
    var scaled = n;
    while (scaled >= 1024 && unit < BYTE_UNITS.length - 1) {
      scaled = scaled / 1024;
      unit++;
    }
    if (unit === 0) return group3(n) + ' B';
    var whole = Math.floor(scaled);
    var text = group3(whole) + ' ' + BYTE_UNITS[unit];
    // 第二级只写真正有余数的部分；不足一级整数时省略，不写出 "1 GB 0 B"。
    var remainder = n - whole * Math.pow(1024, unit);
    var lower = Math.floor(remainder / Math.pow(1024, unit - 1));
    if (lower > 0) {
      text += ' ' + group3(lower) + ' ' + BYTE_UNITS[unit - 1];
    }
    return text;
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

  function clampY(value, low, high) {
    return Math.max(low, Math.min(high, value));
  }

  /** 刻度步长取整齐值：最大值除以四格后向上取到 1/2/5 × 10^n。 */
  function niceStep(value) {
    // 全零序列没有最大值可依；给 1 让轴画成 4/3/2/1/0，而不是四格都取整成同一个数。
    if (!(value > 0)) return 1;
    var raw = value / 4;
    var exp = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10));
    var steps = [1, 2, 5, 10];
    for (var i = 0; i < steps.length; i++) {
      if (steps[i] * exp >= raw) return steps[i] * exp;
    }
    return 10 * exp;
  }

  /**
   * 刻度文字。步长不足 1 时保留步长的小数位：直接 Math.round 会把 0.4 / 0.3 / 0.2 /
   * 0.1 / 0 抹成 0,0,0,0,0——与全零行数图曾出现的 1,1,1,0,0 是同一类错误。
   */
  function axisLabel(value, step) {
    if (step >= 1) return group3(Math.round(value));
    var digits = Math.min(3, Math.max(1, Math.ceil(-Math.log(step) / Math.LN10)));
    return String(Number(value.toFixed(digits)));
  }

  function clear(slot) {
    while (slot.firstChild) slot.removeChild(slot.firstChild);
  }

  /** ISO 时间点按浏览器时区显示成 HH:mm / MM-DD HH:mm。 */
  function timeLabel(iso, long) {
    var date = new Date(iso);
    if (isNaN(date.getTime())) return iso;
    function pad(n) { return n < 10 ? '0' + n : String(n); }
    var hm = pad(date.getHours()) + ':' + pad(date.getMinutes());
    return long ? pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' ' + hm : hm;
  }

  function showEmpty(slot, text) {
    clear(slot);
    var empty = document.createElement('div');
    empty.className = 'chart-empty';
    empty.textContent = text;
    slot.appendChild(empty);
  }

  /**
   * 单序列折线图。points = [{label, value}]，value 为 null 处断开。
   * opts: {color, unit, percent, bytes, duration, longLabels}
   */
  function line(slot, points, opts) {
    opts = opts || {};
    var values = [];
    var max = 0;
    var any = false;
    for (var i = 0; i < points.length; i++) {
      var v = points[i].value;
      if (v === null || v === undefined) {
        values.push(null);
      } else {
        values.push(Number(v));
        if (Number(v) > max) max = Number(v);
        any = true;
      }
    }
    if (!points.length || !any) {
      showEmpty(slot, opts.emptyText || '—');
      return;
    }

    // 字节序列先换算到最大量级再定刻度：16,384 B 的轴画成 "16 KB"，而不是 "16384"。
    var byteDiv = 1;
    var byteSuffix = '';
    if (opts.bytes) {
      var byteUnit = 0;
      var scaledMax = max;
      while (scaledMax >= 1024 && byteUnit < BYTE_UNITS.length - 1) {
        scaledMax = scaledMax / 1024;
        byteUnit++;
      }
      byteDiv = Math.pow(1024, byteUnit);
      byteSuffix = max > 0 ? ' ' + BYTE_UNITS[byteUnit] : '';
    }
    // 时间序列同理：1,135,000 ms 的轴画成 "20 m" 这一级，而不是七位数字。
    var durationDiv = 1;
    var durationSuffix = '';
    if (opts.duration) {
      var dUnit = durationUnit(max);
      durationDiv = dUnit.ms;
      durationSuffix = max > 0 ? dUnit.suffix : '';
    }
    var valueDiv = opts.bytes ? byteDiv : (opts.duration ? durationDiv : 1);
    var axisMax = opts.bytes ? max / byteDiv : (opts.duration ? max / durationDiv : max);

    var width = slot.clientWidth || 640;
    var height = slot.clientHeight || 200;
    var tickStep = opts.percent ? 25 : niceStep(axisMax);
    var top = opts.percent ? 100 : tickStep * 4;
    var axisSuffix = opts.percent ? '%' : (opts.bytes ? byteSuffix : durationSuffix);
    var topLabel = axisLabel(top, tickStep) + axisSuffix;
    var padL = Math.max(40, 14 + topLabel.length * 6.6);
    var padR = 12;
    var padT = 10;
    var padB = 24;
    var plotW = Math.max(1, width - padL - padR);
    var plotH = Math.max(1, height - padT - padB);
    var scale = plotH / top;
    var color = opts.color || cssVar('--brand', '#4f46e5');
    var line_color = cssVar('--line', '#e2e8f0');
    var mute = cssVar('--ink-mute', '#94a3b8');

    clear(slot);
    var svg = el('svg', { width: width, height: height, viewBox: '0 0 ' + width + ' ' + height });
    var defs = el('defs', {});
    gradientSeq += 1;
    var fillId = 'jync-metric-fill-' + gradientSeq;
    var gradient = el('linearGradient', { id: fillId, x1: '0', y1: '0', x2: '0', y2: '1' });
    gradient.appendChild(el('stop', { offset: '0%', 'stop-color': color, 'stop-opacity': '0.26' }));
    gradient.appendChild(el('stop', { offset: '100%', 'stop-color': color, 'stop-opacity': '0' }));
    defs.appendChild(gradient);
    svg.appendChild(defs);

    for (var g = 0; g <= 4; g++) {
      var gy = padT + (plotH / 4) * g;
      svg.appendChild(el('line', {
        x1: padL, y1: gy, x2: padL + plotW, y2: gy,
        stroke: line_color, 'stroke-width': 1,
        'stroke-dasharray': g === 4 ? '' : '3 5'
      }));
      var tick = el('text', {
        x: padL - 8, y: gy + 4, 'text-anchor': 'end', 'font-size': '11', fill: mute
      });
      tick.textContent = axisLabel(top - tickStep * g, tickStep) + axisSuffix;
      svg.appendChild(tick);
    }

    var step = points.length > 1 ? plotW / (points.length - 1) : plotW;
    var coords = [];
    for (var p = 0; p < points.length; p++) {
      coords.push(values[p] === null ? null : {
        x: padL + step * p,
        y: padT + plotH - (values[p] / valueDiv) * scale
      });
    }

    // 分段绘制：null 是缺口，不能跨过去连线
    var segment = [];
    function flushSegment() {
      if (segment.length < 2) { segment = []; return; }
      var d = 'M' + segment[0].x + ' ' + segment[0].y;
      for (var c = 0; c < segment.length - 1; c++) {
        var p1 = segment[c];
        var p2 = segment[c + 1];
        var p0 = segment[c - 1] || p1;
        var p3 = segment[c + 2] || p2;
        // Catmull-Rom 的控制点借邻段切线，可能越过本段两端；夹进区间后，三次贝塞尔
        //（始终落在控制点凸包内）就不会画到 0 以下，也不会冲过本段峰值。
        var low = Math.min(p1.y, p2.y);
        var high = Math.max(p1.y, p2.y);
        var c1x = p1.x + (p2.x - p0.x) / 6;
        var c1y = clampY(p1.y + (p2.y - p0.y) / 6, low, high);
        var c2x = p2.x - (p3.x - p1.x) / 6;
        var c2y = clampY(p2.y - (p3.y - p1.y) / 6, low, high);
        d += ' C' + c1x + ' ' + c1y + ' ' + c2x + ' ' + c2y + ' ' + p2.x + ' ' + p2.y;
      }
      var baseY = padT + plotH;
      svg.appendChild(el('path', {
        d: d + ' L' + segment[segment.length - 1].x + ' ' + baseY
          + ' L' + segment[0].x + ' ' + baseY + ' Z',
        fill: 'url(#' + fillId + ')', stroke: 'none'
      }));
      svg.appendChild(el('path', {
        d: d, fill: 'none', stroke: color, 'stroke-width': 2.2,
        'stroke-linecap': 'round', 'stroke-linejoin': 'round'
      }));
      segment = [];
    }
    for (var s = 0; s < coords.length; s++) {
      if (coords[s]) {
        segment.push(coords[s]);
      } else {
        flushSegment();
      }
    }
    flushSegment();

    // 只有一两个采样点、或点的两侧都是空桶时连不成线段。孤立点必须单独画标记，
    // 否则卡片上有数字、图里却是空白——一次手动同步的两个采样点就落在同一个小时桶里。
    for (var m = 0; m < coords.length; m++) {
      if (!coords[m]) continue;
      var prev = m > 0 ? coords[m - 1] : null;
      var next = m < coords.length - 1 ? coords[m + 1] : null;
      if (prev || next) continue;
      svg.appendChild(el('circle', {
        cx: coords[m].x, cy: coords[m].y, r: 3.2,
        fill: color, stroke: cssVar('--bg', '#fff'), 'stroke-width': 1.5
      }));
    }

    // 横轴：约每 1/5 一个刻度，标签是桶起点
    var stride = Math.max(1, Math.ceil(points.length / 5));
    for (var x = 0; x < points.length; x += stride) {
      tickLabel(timeLabel(points[x].label, opts.longLabels), padL + step * x,
        x === 0 ? 'start' : 'middle');
    }
    tickLabel(timeLabel(points[points.length - 1].label, opts.longLabels),
      padL + step * (points.length - 1), 'end');
    function tickLabel(text, cx, anchor) {
      var t = el('text', {
        x: cx, y: padT + plotH + 16, 'text-anchor': anchor, 'font-size': '11', fill: mute
      });
      t.textContent = text;
      svg.appendChild(t);
    }

    // 悬停：指示线 + 圆点 + 数值气泡
    var guide = el('line', {
      x1: 0, y1: padT, x2: 0, y2: padT + plotH,
      stroke: color, 'stroke-width': 1, 'stroke-dasharray': '3 4', opacity: 0
    });
    var dot = el('circle', {
      r: 4, fill: color, stroke: cssVar('--bg', '#fff'), 'stroke-width': 2, opacity: 0
    });
    svg.appendChild(guide);
    svg.appendChild(dot);

    var overlay = el('rect', {
      x: padL, y: padT, width: plotW, height: plotH,
      fill: 'transparent', style: 'cursor: crosshair'
    });
    svg.appendChild(overlay);
    slot.appendChild(svg);

    var tip = null;
    overlay.addEventListener('mousemove', function (event) {
      if (!tip) {
        tip = document.createElement('div');
        tip.className = 'chart-tip';
        slot.appendChild(tip);
      }
      var box = slot.getBoundingClientRect();
      var index = Math.round((event.clientX - box.left - padL) / step);
      if (index < 0) index = 0;
      if (index > points.length - 1) index = points.length - 1;
      var point = coords[index];
      if (!point) {
        guide.setAttribute('opacity', 0);
        dot.setAttribute('opacity', 0);
        tip.className = 'chart-tip';
        return;
      }
      guide.setAttribute('x1', point.x);
      guide.setAttribute('x2', point.x);
      guide.setAttribute('opacity', 1);
      dot.setAttribute('cx', point.x);
      dot.setAttribute('cy', point.y);
      dot.setAttribute('opacity', 1);
      tip.textContent = (opts.bytes ? formatBytes(values[index])
        : opts.duration ? formatDuration(values[index])
        : formatNumber(values[index], 1) + (opts.unit ? ' ' + opts.unit : ''))
        + ' · ' + timeLabel(points[index].label, true);
      tip.style.left = point.x + 'px';
      tip.style.top = (point.y - 12) + 'px';
      tip.className = 'chart-tip is-on';
    });
    overlay.addEventListener('mouseleave', function () {
      guide.setAttribute('opacity', 0);
      dot.setAttribute('opacity', 0);
      if (tip) tip.className = 'chart-tip';
    });
  }

  /** 环形占比图。points = [{label, value}]，中心写合计。 */
  function donut(slot, points, opts) {
    opts = opts || {};
    var live = [];
    var total = 0;
    for (var i = 0; i < points.length; i++) {
      var v = Number(points[i].value) || 0;
      if (v > 0) {
        live.push({ label: points[i].label, value: v });
        total += v;
      }
    }
    clear(slot);
    if (!total) {
      showEmpty(slot, opts.emptyText || '—');
      return;
    }

    var palette = [
      cssVar('--ok', '#16a34a'),
      cssVar('--info', '#0284c7'),
      cssVar('--danger', '#dc2626'),
      cssVar('--warn', '#d97706')
    ];
    var size = Math.min(slot.clientWidth || 220, 220);
    var radius = size / 2 - 6;
    var inner = radius * 0.62;
    var cx = size / 2;
    var cy = size / 2;

    var wrap = document.createElement('div');
    wrap.className = 'donut-wrap';
    slot.appendChild(wrap);

    var svg = el('svg', { width: size, height: size, viewBox: '0 0 ' + size + ' ' + size });
    var start = -Math.PI / 2;
    for (var s = 0; s < live.length; s++) {
      var sweep = (live[s].value / total) * Math.PI * 2;
      var end = start + sweep;
      // 整圈时终点与起点重合，arc 命令画不出来，单独画一个圆
      if (sweep >= Math.PI * 2 - 0.0001) {
        svg.appendChild(el('circle', {
          cx: cx, cy: cy, r: (radius + inner) / 2, fill: 'none',
          stroke: palette[s % palette.length], 'stroke-width': radius - inner
        }));
      } else {
        var x1 = cx + radius * Math.cos(start);
        var y1 = cy + radius * Math.sin(start);
        var x2 = cx + radius * Math.cos(end);
        var y2 = cy + radius * Math.sin(end);
        var ix2 = cx + inner * Math.cos(end);
        var iy2 = cy + inner * Math.sin(end);
        var ix1 = cx + inner * Math.cos(start);
        var iy1 = cy + inner * Math.sin(start);
        svg.appendChild(el('path', {
          d: 'M' + x1 + ' ' + y1
            + ' A' + radius + ' ' + radius + ' 0 ' + (sweep > Math.PI ? 1 : 0) + ' 1 ' + x2 + ' ' + y2
            + ' L' + ix2 + ' ' + iy2
            + ' A' + inner + ' ' + inner + ' 0 ' + (sweep > Math.PI ? 1 : 0) + ' 0 ' + ix1 + ' ' + iy1
            + ' Z',
          fill: palette[s % palette.length]
        }));
      }
      start = end;
    }
    var center = el('text', {
      x: cx, y: cy - 2, 'text-anchor': 'middle', 'font-size': '17', 'font-weight': '700',
      fill: cssVar('--ink', '#0f172a')
    });
    center.textContent = group3(Math.round(total));
    svg.appendChild(center);
    var caption = el('text', {
      x: cx, y: cy + 15, 'text-anchor': 'middle', 'font-size': '10.5',
      fill: cssVar('--ink-mute', '#94a3b8')
    });
    caption.textContent = opts.caption || '';
    svg.appendChild(caption);
    wrap.appendChild(svg);

    var legend = document.createElement('ul');
    legend.className = 'donut-legend';
    for (var l = 0; l < live.length; l++) {
      var item = document.createElement('li');
      var swatch = document.createElement('span');
      swatch.className = 'donut-swatch';
      swatch.style.background = palette[l % palette.length];
      item.appendChild(swatch);
      var label = document.createElement('span');
      label.textContent = live[l].label + ' · ' + group3(Math.round(live[l].value))
        + ' (' + Math.round((live[l].value / total) * 100) + '%)';
      item.appendChild(label);
      legend.appendChild(item);
    }
    wrap.appendChild(legend);
  }

  /* 变更日志表格用 changeType.* 的译文；占比图的服务端 label 是枚举名（INSERT …）。
     模板把译文挂在 data-label-* 上，这里换掉，中文界面不直接露代码。 */
  function localizedShare(slot, points) {
    var out = [];
    for (var i = 0; i < points.length; i++) {
      var translated = slot.getAttribute('data-label-'
        + String(points[i].label || '').toLowerCase());
      out.push({ label: translated || points[i].label, value: points[i].value });
    }
    return out;
  }

  /** 横向条形排行。points = [{label, value}]，已按服务端顺序（大的在前）。 */
  function bars(slot, points, opts) {
    opts = opts || {};
    clear(slot);
    var max = 0;
    var count = 0;
    for (var i = 0; i < points.length; i++) {
      var v = Number(points[i].value) || 0;
      if (v > max) max = v;
      if (v > 0) count++;
    }
    if (!count) {
      showEmpty(slot, opts.emptyText || '—');
      return;
    }
    var list = document.createElement('ol');
    list.className = 'bar-list';
    for (var p = 0; p < points.length; p++) {
      var value = Number(points[p].value) || 0;
      var row = document.createElement('li');
      var name = document.createElement('span');
      name.className = 'bar-name';
      name.textContent = points[p].label;
      name.title = points[p].label;
      var track = document.createElement('span');
      track.className = 'bar-track';
      var fill = document.createElement('span');
      fill.className = 'bar-fill';
      fill.style.width = Math.max(2, Math.round((value / max) * 100)) + '%';
      track.appendChild(fill);
      var num = document.createElement('span');
      num.className = 'bar-num';
      num.textContent = group3(Math.round(value));
      row.appendChild(name);
      row.appendChild(track);
      row.appendChild(num);
      list.appendChild(row);
    }
    slot.appendChild(list);
  }

  /* ─── 页面装配 ─────────────────────────────────────────── */

  function seriesOf(view, key) {
    var list = (view && view.series) || [];
    for (var i = 0; i < list.length; i++) {
      if (list[i].key === key) return list[i];
    }
    return null;
  }

  function paintCards(root, view) {
    var current = (view && view.current) || {};
    var cards = root.querySelectorAll('[data-current]');
    for (var i = 0; i < cards.length; i++) {
      var key = cards[i].getAttribute('data-current');
      var raw = Object.prototype.hasOwnProperty.call(current, key) ? current[key] : null;
      var digits = cards[i].getAttribute('data-digits');
      var suffix = cards[i].getAttribute('data-suffix') || '';
      var format = cards[i].getAttribute('data-format');
      var text = format === 'bytes' ? formatBytes(raw)
        : format === 'duration' ? formatDuration(raw)
        : formatNumber(raw, digits === null ? 1 : Number(digits)) + suffix;
      cards[i].textContent = raw === null || raw === undefined ? '—' : text;
    }
    var stamp = root.querySelector('[data-last-sample]');
    if (stamp) {
      var at = view && view.lastSample;
      stamp.textContent = at ? timeLabel(at, true) : (stamp.getAttribute('data-none') || '—');
    }
    var status = root.querySelector('[data-status]');
    if (status && view) {
      var code = view.status;
      // 模板把三种状态名放在 data-* 上，由 i18n 决定文字；直接写 code 会让中文界面
      // 露出 "RUNNING"。
      var label = code ? status.getAttribute('data-' + code.toLowerCase()) : null;
      status.textContent = label || code || '—';
      status.className = 'chip' + (code === 'RUNNING' ? ' chip-ok'
        : code === 'ERROR' ? ' chip-danger' : '');
    }
  }

  function paint(root, view) {
    paintCards(root, view);

    var slots = root.querySelectorAll('[data-chart]');
    for (var i = 0; i < slots.length; i++) {
      var key = slots[i].getAttribute('data-chart');
      var series = seriesOf(view, key);
      if (series) {
        line(slots[i], series.points, {
          color: slots[i].getAttribute('data-color') || undefined,
          unit: slots[i].getAttribute('data-unit') || '',
          percent: slots[i].getAttribute('data-percent') === 'true',
          bytes: slots[i].getAttribute('data-bytes') === 'true',
          duration: slots[i].getAttribute('data-duration') === 'true',
          longLabels: currentRange === '7d',
          emptyText: slots[i].getAttribute('data-empty') || ''
        });
      }
    }

    var shareSlot = root.querySelector('[data-chart-share]');
    if (shareSlot) {
      donut(shareSlot, localizedShare(shareSlot, (view && view.changeShare) || []), {
        caption: shareSlot.getAttribute('data-caption') || '',
        emptyText: shareSlot.getAttribute('data-empty') || ''
      });
    }
    var hotSlot = root.querySelector('[data-chart-hot]');
    if (hotSlot) {
      bars(hotSlot, (view && view.hotObjects) || [], {
        emptyText: hotSlot.getAttribute('data-empty') || ''
      });
    }
  }

  /* 数据库页多两件事要做：红条跟着最新一条采样开关，运行指标全读不到时露出占位说明。
     两者都要在轮询里跟着变，不能只靠首屏服务端渲染。 */
  var RUNTIME_KEYS = ['sessions', 'qps', 'cacheHit', 'locks'];

  /* Health messages are sometimes i18n keys (a timeout, an invalid connection), sometimes
     driver text. The banner carries the translations for the two keys, so a probe that fails
     after the page opened reads as a sentence instead of "error.db.health.timeout". */
  var HEALTH_MESSAGE_ATTRS = {
    'error.db.health.timeout': 'data-timeout',
    'error.db.health.invalid': 'data-invalid'
  };

  function healthMessage(banner, raw) {
    var attr = HEALTH_MESSAGE_ATTRS[raw];
    var localized = attr ? banner.getAttribute(attr) : null;
    return localized || raw;
  }

  function paintDatabase(root, view) {
    var banner = root.querySelector('#db-down-banner');
    if (banner) {
      banner.hidden = !!view.ok;
      if (!view.ok && view.message) {
        var body = banner.querySelector('[data-down-message]');
        if (body) body.textContent = healthMessage(banner, view.message);
      }
    }
    var unsupported = root.querySelector('#runtime-unsupported');
    if (unsupported) {
      var current = view.current || {};
      var any = false;
      for (var i = 0; i < RUNTIME_KEYS.length; i++) {
        if (current[RUNTIME_KEYS[i]] !== null && current[RUNTIME_KEYS[i]] !== undefined) {
          any = true;
        }
      }
      unsupported.hidden = any;
    }
    paint(root, view);
  }

  var currentRange = '24h';

  function bind(root) {
    if (!root || root.getAttribute('data-bound') === '1') return;
    root.setAttribute('data-bound', '1');
    var url = root.getAttribute('data-metrics-url');
    if (!url) return;
    var databaseMode = root.getAttribute('data-mode') === 'database';
    currentRange = root.getAttribute('data-range') || '24h';

    var segButtons = root.querySelectorAll('[data-range]');
    for (var b = 0; b < segButtons.length; b++) {
      (function (button) {
        button.classList.toggle('is-active', button.getAttribute('data-range') === currentRange);
        button.addEventListener('click', function () {
          var next = button.getAttribute('data-range');
          if (!next || next === currentRange) return;
          currentRange = next;
          for (var i = 0; i < segButtons.length; i++) {
            segButtons[i].classList.toggle('is-active', segButtons[i] === button);
          }
          load();
        });
      })(segButtons[b]);
    }

    var inFlight = false;
    var lastView = null;
    var loadedRange = null;
    function load() {
      if (inFlight) return;
      var range = currentRange;
      inFlight = true;
      loadedRange = range;
      fetch(url + '?range=' + encodeURIComponent(range), {
        cache: 'no-store', headers: { Accept: 'application/json' }
      })
        .then(function (response) {
          if (response.status === 401 || response.redirected) {
            window.location.reload();
            return null;
          }
          if (!response.ok) throw new Error('HTTP ' + response.status);
          return response.json();
        })
        .then(function (view) {
          if (!view) return;
          lastView = view;
          if (databaseMode) {
            paintDatabase(root, view);
          } else {
            paint(root, view);
          }
        })
        .catch(function () {
          // 一次网络抖动保留上一帧，不清空图表
        })
        .then(function () {
          inFlight = false;
          // 请求在途时点的范围切换会被上面那句挡掉；不在这里补一次，页面就会顶着新范围的
          // 选中态继续显示旧范围的数据，直到下一个 10 秒周期。
          if (currentRange !== loadedRange) load();
        });
    }

    var resizeTimer = null;
    window.addEventListener('resize', function () {
      if (resizeTimer) clearTimeout(resizeTimer);
      resizeTimer = setTimeout(function () {
        if (!lastView) return;
        if (databaseMode) {
          paintDatabase(root, lastView);
        } else {
          paint(root, lastView);
        }
      }, 150);
    });

    var timer = null;
    function apply() {
      if (timer) {
        clearInterval(timer);
        timer = null;
      }
      if (!document.hidden) {
        timer = setInterval(load, REFRESH_INTERVAL);
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

  /* 模板只用得上 bind —— 三种图由它按 data-chart* 属性自行选择，不是公开 API。 */
  window.JyncMetrics = { bind: bind };
})();
