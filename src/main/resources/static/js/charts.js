/* ==========================================================================
   图表公共工具：看板趋势图（app.js）与性能页折线图（metrics.js）共用。

   不引图表库（内网离线、老内核），配色取 CSS 变量，深浅主题自动跟随。
   这里只放纯计算与 SVG 节点创建，不认识任何页面的数据结构；取数、空状态、
   悬停气泡仍由各自的调用方负责。
   ========================================================================== */
(function () {
  'use strict';

  /** 读主题变量，读不到时用 fallback；切换深浅主题后重新调用即可跟随。 */
  function cssVar(name, fallback) {
    var value = getComputedStyle(document.documentElement).getPropertyValue(name);
    return (value && value.replace(/^\s+|\s+$/g, '')) || fallback;
  }

  /** 1234567 → "1,234,567"。 */
  function group3(n) {
    return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }

  /** 建一个 SVG 节点并设置属性。 */
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

  /** 四格坐标系的上界；全零/负数序列返回 0，调用方据此走空状态。 */
  function niceTop(value) {
    return value > 0 ? niceStep(value) * 4 : 0;
  }

  /**
   * 折线转平滑曲线：Catmull-Rom 转三次贝塞尔；点少时退化成直线。
   *
   * <p>切线会借用相邻点，所以曲线可以越过它连接的两个采样：一段全 0 会被前后的峰值
   * 拉下 0 轴，连续高点之间又会冲过最高值 —— 计数不可能为负，也不该比最大值还高。
   * 把每个控制点的 y 夹进本段两端的区间，贝塞尔就落在两端点之间（曲线在控制点的
   * 凸包内），平滑保留，多画出来的部分消失。
   */
  function smoothPath(coords) {
    var d = 'M' + coords[0].x + ' ' + coords[0].y;
    for (var c = 0; c < coords.length - 1; c++) {
      var p0 = coords[c - 1] || coords[c];
      var p1 = coords[c];
      var p2 = coords[c + 1];
      var p3 = coords[c + 2] || p2;
      var low = Math.min(p1.y, p2.y);
      var high = Math.max(p1.y, p2.y);
      var c1x = p1.x + (p2.x - p0.x) / 6;
      var c1y = clampY(p1.y + (p2.y - p0.y) / 6, low, high);
      var c2x = p2.x - (p3.x - p1.x) / 6;
      var c2y = clampY(p2.y - (p3.y - p1.y) / 6, low, high);
      d += ' C' + c1x + ' ' + c1y + ' ' + c2x + ' ' + c2y + ' ' + p2.x + ' ' + p2.y;
    }
    return d;
  }

  window.JyncCharts = {
    cssVar: cssVar,
    group3: group3,
    el: el,
    clampY: clampY,
    niceStep: niceStep,
    niceTop: niceTop,
    smoothPath: smoothPath
  };
})();
