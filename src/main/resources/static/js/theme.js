/* ==========================================================================
   主题与语言 · 与参考项目 /Users/rgh/opt/html 保持一致的行为
   - 主题写在 <html data-theme>，存 localStorage
   - 未手动选择过时跟随系统 prefers-color-scheme，选过之后以用户选择为准
   - 语言默认按浏览器时区推断（中国大陆/港澳台 → 中文，其他 → 英文）
     时区比 navigator.language 更准：境外华人的浏览器语言常是英文
   ========================================================================== */
(function () {
  'use strict';

  var THEME_KEY = 'synctool-theme';
  var TZ_COOKIE = 'SYNCTOOL_TZ';
  var LANG_COOKIE = 'SYNCTOOL_LANG';

  /* ─── 主题 ──────────────────────────────────────────────── */

  function applyTheme(mode) {
    document.documentElement.setAttribute('data-theme', mode);
    document.querySelectorAll('[data-theme-btn]').forEach(function (btn) {
      // 图标是 SVG 精灵表引用，改的是 <use> 的 href 而不是 class
      var use = btn.querySelector('svg use');
      if (use) {
        var id = mode === 'dark' ? '#sun-fill' : '#moon-stars-fill';
        use.setAttribute('href', id);
      }
      // 提示文字说明「点了会变成什么」，而不是当前状态
      var next = mode === 'dark' ? btn.dataset.toLight : btn.dataset.toDark;
      if (next) {
        btn.title = next;
        btn.setAttribute('aria-label', next);
      }
    });
  }

  function storedTheme() {
    try {
      return localStorage.getItem(THEME_KEY);
    } catch (e) {
      return null; // 隐私模式下不可读，退回跟随系统
    }
  }

  function toggleTheme() {
    var next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    applyTheme(next);
    try {
      localStorage.setItem(THEME_KEY, next);
    } catch (e) { /* 写不进去也不影响本次会话 */ }
  }

  // 系统主题变化时跟随，但只在用户从没手动选过的情况下
  if (window.matchMedia) {
    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    var onChange = function (e) {
      if (!storedTheme()) {
        applyTheme(e.matches ? 'dark' : 'light');
      }
    };
    if (mq.addEventListener) {
      mq.addEventListener('change', onChange);
    } else if (mq.addListener) {
      mq.addListener(onChange); // 旧版 Safari
    }
  }

  /* ─── 语言 ──────────────────────────────────────────────── */

  /* 中文时区表，与参考项目一致。港澳台目前也归中文。 */
  var ZH_TIMEZONES = [
    'Asia/Shanghai', 'Asia/Chongqing', 'Asia/Chungking', 'Asia/Harbin',
    'Asia/Urumqi', 'Asia/Kashgar', 'Asia/Hong_Kong', 'Asia/Macau',
    'Asia/Macao', 'Asia/Taipei', 'Asia/Beijing', 'PRC', 'ROC', 'Hongkong'
  ];

  function readCookie(name) {
    var parts = document.cookie ? document.cookie.split(';') : [];
    for (var i = 0; i < parts.length; i++) {
      var kv = parts[i].trim();
      if (kv.indexOf(name + '=') === 0) {
        return decodeURIComponent(kv.substring(name.length + 1));
      }
    }
    return null;
  }

  function writeCookie(name, value, days) {
    var expires = new Date(Date.now() + days * 864e5).toUTCString();
    document.cookie = name + '=' + encodeURIComponent(value)
      + ';expires=' + expires + ';path=/;SameSite=Lax';
  }

  /**
   * 把浏览器时区告诉服务端。
   *
   * 页面是服务端渲染的，语言必须在渲染前定好，所以这里只负责「上报时区」：
   * 首次访问时写入 cookie 并重新加载一次，让服务端按时区选语言。
   * 用户显式选过语言（LANG cookie 存在）之后就不再重载 —— 用户的选择优先。
   */
  function reportTimezone() {
    var known = readCookie(TZ_COOKIE);
    var tz = null;
    try {
      tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    } catch (e) {
      return; // 不支持 Intl，服务端会退回 Accept-Language
    }
    if (!tz || known === tz) {
      return;
    }

    writeCookie(TZ_COOKIE, tz, 365);

    // 只有在用户还没手动选过语言、且服务端这次渲染的语言与时区推断不一致时才重载，
    // 避免无条件刷新造成闪烁或循环。
    if (readCookie(LANG_COOKIE)) {
      return;
    }
    var isZhTz = ZH_TIMEZONES.indexOf(tz) !== -1;
    var rendered = (document.documentElement.getAttribute('lang') || '').toLowerCase();
    var renderedIsZh = rendered.indexOf('zh') === 0;
    if (isZhTz !== renderedIsZh) {
      window.location.reload();
    }
  }

  /* ─── 初始化 ────────────────────────────────────────────── */

  function init() {
    // 主题在 <head> 的内联脚本里已经应用过（避免首屏闪烁），这里只同步按钮状态
    applyTheme(document.documentElement.getAttribute('data-theme') || 'light');

    document.querySelectorAll('[data-theme-btn]').forEach(function (btn) {
      btn.addEventListener('click', function (e) {
        e.preventDefault();
        toggleTheme();
      });
    });

    reportTimezone();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

  window.SyncToolTheme = {
    toggle: toggleTheme,
    apply: applyTheme,
    get current() { return document.documentElement.getAttribute('data-theme'); }
  };
})();
