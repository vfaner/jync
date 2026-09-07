/* ==========================================================================
   通用交互：Toast、API 调用、对象选择、连接测试、移动端导航
   纯原生 JS，不依赖任何框架，全部本地资源。
   ========================================================================== */
(function () {
  'use strict';

  /* 服务端返回的是 i18n key，布局里把解析好的文案挂在这里 */
  var messages = window.SyncToolMessages || {};

  function t(key) {
    if (!key) return '';
    return messages[key] || key;
  }

  /* ─── Toast ─────────────────────────────────────────────── */

  var ICONS = {
    ok: 'check-circle-fill',
    danger: 'x-circle-fill',
    warn: 'exclamation-triangle-fill',
    info: 'info-circle-fill'
  };

  /**
   * 构造一个引用内联 SVG 精灵表的图标元素。
   * 精灵表已随页面内联，因此 <use href="#id"> 不产生额外请求。
   */
  function icon(name, cls) {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'ico' + (cls ? ' ' + cls : ''));
    var use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    // href 需要用 setAttribute：SVG 元素没有可写的 href 属性
    use.setAttribute('href', '#' + name);
    svg.appendChild(use);
    return svg;
  }

  /** 图标的 HTML 字符串形式，供需要拼 innerHTML 的地方使用 */
  function iconHtml(name, cls) {
    return '<svg class="ico' + (cls ? ' ' + cls : '') + '"><use href="#' + name + '"/></svg>';
  }

  function toast(message, kind) {
    var box = document.getElementById('toasts');
    if (!box) return;

    kind = kind || 'info';
    var el = document.createElement('div');
    el.className = 'toast toast-' + kind;

    var iconEl = icon(ICONS[kind] || ICONS.info);
    var body = document.createElement('div');
    body.className = 'toast-msg';
    body.textContent = message;           // textContent：避免把服务端消息当 HTML 执行
    var close = document.createElement('button');
    close.className = 'alert-close';
    close.type = 'button';
    close.innerHTML = iconHtml('x-lg');

    el.appendChild(iconEl);
    el.appendChild(body);
    el.appendChild(close);
    box.appendChild(el);

    var timer = setTimeout(dismiss, kind === 'danger' ? 8000 : 4000);
    close.addEventListener('click', function () {
      clearTimeout(timer);
      dismiss();
    });

    function dismiss() {
      el.classList.add('out');
      // 等退场动画结束再移除，否则会瞬间消失
      setTimeout(function () { el.remove(); }, 220);
    }
  }

  /* ─── API 调用 ──────────────────────────────────────────── */

  /**
   * POST，返回解析好的 JSON。
   *
   * json=true 时补上 Content-Type，否则 Spring 的 @RequestBody 会以 415 拒收 ——
   * 表单式调用不带 body，不能无条件加这个头。
   */
  function postJson(url, body, json) {
    var headers = { 'Accept': 'application/json' };
    if (json) headers['Content-Type'] = 'application/json';
    // CSRF：表单由 Thymeleaf 自动注入隐藏域，fetch 得自己带头。
    // 令牌在 layout.html 的 <meta> 里，所以整个 JS 层只有这一处要管。
    var token = document.querySelector('meta[name="_csrf"]');
    var header = document.querySelector('meta[name="_csrf_header"]');
    if (token && header && token.content && header.content) {
      headers[header.content] = token.content;
    }
    return fetch(url, {
      method: 'POST',
      headers: headers,
      body: body
    }).then(function (response) {
      // 会话过期会被安全层拦成 401 JSON。此时页面上的一切操作都已无效，
      // 停在原地只会让用户对着「未授权」反复重试。这里重载而不是跳转到
      // 拼出来的 /login：重载走的是当前 URL，由安全层自己重定向，
      // 于是 context-path 和多层路径（如 /projects/1/conversions/...）都不会拼错。
      if (response.status === 401) {
        window.location.reload();
        return { success: false, message: 'error.sessionExpired' };
      }
      return response.json().catch(function () {
        // 非 JSON 响应说明是意外的服务端错误，把状态码带出来
        return { success: false, message: 'HTTP ' + response.status };
      });
    });
  }

  /** 带加载态的动作按钮，完成后可选刷新页面 */
  function bindActionButton(btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      if (btn.dataset.confirm && !window.confirm(btn.dataset.confirm)) return;

      var original = btn.innerHTML;
      btn.disabled = true;
      btn.innerHTML = '<span class="spin"></span>' +
        (btn.dataset.busyText ? ' ' + btn.dataset.busyText : '');

      postJson(btn.dataset.action).then(function (payload) {
        var text = payload.summary
          ? t(payload.message) + ' — ' + payload.summary
          : t(payload.message);
        toast(text, payload.success ? 'ok' : 'danger');

        // 逐条列出错误，比只给一句「失败」有用
        if (payload.errors && payload.errors.length) {
          payload.errors.slice(0, 3).forEach(function (err) { toast(err, 'danger'); });
        }

        if (payload.success && btn.dataset.reload === 'true') {
          setTimeout(function () { window.location.reload(); }, 850);
          return;
        }
        btn.disabled = false;
        btn.innerHTML = original;
      }).catch(function (err) {
        toast(String(err && err.message || err), 'danger');
        btn.disabled = false;
        btn.innerHTML = original;
      });
    });
  }

  /* ─── 连接测试 ──────────────────────────────────────────── */

  /** 直接用表单当前值测试，不需要先保存 —— 自定义驱动最容易填错，先验证再存 */
  function bindConnectionTest(btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      var form = document.getElementById(btn.dataset.form);
      if (!form) return;

      var out = document.getElementById('test-result');
      var original = btn.innerHTML;
      btn.disabled = true;
      btn.innerHTML = '<span class="spin"></span> ' + (btn.dataset.busyText || '');

      postJson(btn.dataset.action, new FormData(form)).then(function (p) {
        if (!out) {
          toast(t(p.success ? (btn.dataset.okKey || 'db.testSuccess')
                            : (btn.dataset.failKey || 'db.testFailed'))
            + (p.message ? ': ' + p.message : ''), p.success ? 'ok' : 'danger');
          return;
        }
        out.className = 'alert ' + (p.success ? 'alert-ok' : 'alert-danger');
        out.innerHTML = '';

        var iconEl = icon(p.success ? 'check-circle-fill' : 'x-circle-fill');
        var body = document.createElement('div');
        body.className = 'alert-body';

        var head = document.createElement('strong');
        head.textContent = t(btn.dataset.okKey || 'db.testSuccess');
        if (!p.success) head.textContent = t(btn.dataset.failKey || 'db.testFailed');
        body.appendChild(head);

        /* 数据库测试给 productInfo/jdbcUrl，AI 测试给 model/endpoint —— 同一套渲染，
           各自用自己的字段名，不必为了复用而假装是另一种东西 */
        [p.productInfo || p.model, p.driverInfo, p.success ? null : p.message,
          p.jdbcUrl || p.endpoint]
          .forEach(function (line, idx) {
            if (!line) return;
            var div = document.createElement('div');
            div.className = idx === 0 ? 'small' : 'small muted';
            if (idx >= 2) div.classList.add('mono');
            div.textContent = line;
            body.appendChild(div);
          });

        var ms = document.createElement('div');
        ms.className = 'small muted';
        ms.textContent = p.elapsedMs + ' ms';
        body.appendChild(ms);

        out.appendChild(iconEl);
        out.appendChild(body);
        out.hidden = false;
      }).catch(function (err) {
        toast(String(err && err.message || err), 'danger');
      }).then(function () {
        btn.disabled = false;
        btn.innerHTML = original;
      });
    });
  }

  /* ─── 对象选择列表 ──────────────────────────────────────── */

  function bindPicker(panel) {
    var boxes = panel.querySelectorAll('input[type="checkbox"]');
    var counter = panel.querySelector('[data-count]');

    function refresh() {
      if (!counter) return;
      var n = 0;
      boxes.forEach(function (cb) { if (cb.checked) n++; });
      counter.textContent = (counter.dataset.template || '{0}/{1}')
        .replace('{0}', n).replace('{1}', boxes.length);
    }

    panel.querySelectorAll('[data-all]').forEach(function (b) {
      b.addEventListener('click', function (e) {
        e.preventDefault();
        // 只勾选当前可见的项，这样「搜索 + 全选」可以组合使用
        boxes.forEach(function (cb) {
          if (cb.closest('.pick').style.display !== 'none') cb.checked = true;
        });
        refresh();
      });
    });
    panel.querySelectorAll('[data-none]').forEach(function (b) {
      b.addEventListener('click', function (e) {
        e.preventDefault();
        boxes.forEach(function (cb) {
          if (cb.closest('.pick').style.display !== 'none') cb.checked = false;
        });
        refresh();
      });
    });
    panel.querySelectorAll('[data-filter]').forEach(function (input) {
      input.addEventListener('input', function () {
        var term = input.value.trim().toLowerCase();
        panel.querySelectorAll('.pick').forEach(function (row) {
          var label = row.querySelector('span');
          var text = label ? label.textContent.toLowerCase() : '';
          row.style.display = (!term || text.indexOf(term) !== -1) ? '' : 'none';
        });
      });
    });

    boxes.forEach(function (cb) { cb.addEventListener('change', refresh); });
    refresh();
  }

  /* ─── 驱动类检测 ────────────────────────────────────────── */

  function bindDriverDiscovery(btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      var jar = document.getElementById('customJarPath');
      if (!jar || !jar.value.trim()) {
        toast(t('db.customJarPathHelp'), 'warn');
        return;
      }
      var original = btn.innerHTML;
      btn.disabled = true;
      btn.innerHTML = '<span class="spin"></span>';

      fetch(btn.dataset.action + '?jarPath=' + encodeURIComponent(jar.value.trim()))
        .then(function (r) { return r.json(); })
        .then(function (p) {
          var list = document.getElementById('driver-options');
          if (list) {
            list.innerHTML = '';
            (p.drivers || []).forEach(function (d) {
              var o = document.createElement('option');
              o.value = d;
              list.appendChild(o);
            });
          }
          if (p.drivers && p.drivers.length) {
            var input = document.getElementById('customDriver');
            if (input && !input.value.trim()) input.value = p.drivers[0];
            toast(p.drivers.join(', '), 'ok');
          } else {
            toast(t(p.message || 'msg.no.drivers.declared'), 'warn');
          }
        })
        .catch(function (err) { toast(String(err && err.message || err), 'danger'); })
        .then(function () {
          btn.disabled = false;
          btn.innerHTML = original;
        });
    });
  }

  /* ─── 数据库类型切换：预填端口、显隐自定义区 ────────────── */

  function bindTypeSelect(select) {
    function apply() {
      fetch(select.dataset.action + '?type=' + encodeURIComponent(select.value))
        .then(function (r) { return r.json(); })
        .then(function (p) {
          var port = document.getElementById('port');
          // 只在端口为空或还是上一个类型的默认值时才覆盖，避免抹掉用户手填的值
          if (port && p.defaultPort) {
            if (!port.value.trim() || port.dataset.autofilled === 'true') {
              port.value = p.defaultPort;
              port.dataset.autofilled = 'true';
            }
          }
          var custom = document.getElementById('custom-section');
          if (custom) custom.hidden = !p.custom;
          var hint = document.getElementById('url-hint');
          if (hint) hint.textContent = p.urlTemplate || '';
          var driverHint = document.getElementById('driver-hint');
          if (driverHint) driverHint.textContent = p.driverClass || '';
        })
        .catch(function () { /* 预填失败只是少了便利，表单仍可手填 */ });
    }
    select.addEventListener('change', apply);

    var port = document.getElementById('port');
    if (port) {
      port.addEventListener('input', function () { port.dataset.autofilled = 'false'; });
    }
  }

  /* ─── AI 协议切换：预填 base URL ─────────────────────────── */

  function bindProtocolSelect(select) {
    var url = document.getElementById('baseUrl');
    if (url) {
      // 手动改过就不再覆盖 —— 内网端点几乎一定和默认值不同，抹掉它最恼人
      url.addEventListener('input', function () { url.dataset.autofilled = 'false'; });
    }

    select.addEventListener('change', function () {
      fetch(select.dataset.action + '?protocol=' + encodeURIComponent(select.value))
        .then(function (r) { return r.json(); })
        .then(function (p) {
          if (url && p.defaultBaseUrl
              && (!url.value.trim() || url.dataset.autofilled === 'true')) {
            url.value = p.defaultBaseUrl;
            url.dataset.autofilled = 'true';
          }
          var hint = document.getElementById('endpoint-hint');
          if (hint) hint.textContent = p.chatPath || '';
        })
        .catch(function () { /* 预填失败只是少了便利，表单仍可手填 */ });
    });
  }

  /* ─── 移动端导航抽屉 ────────────────────────────────────── */

  function bindNavToggle(btn) {
    var links = document.getElementById('nav-links');
    if (!links) return;

    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      var open = links.classList.toggle('open');
      btn.innerHTML = open ? iconHtml('x-lg') : iconHtml('list');
    });
    // 点抽屉外部收起，否则会一直挡住内容
    document.addEventListener('click', function (e) {
      if (!links.classList.contains('open')) return;
      if (!links.contains(e.target) && !btn.contains(e.target)) {
        links.classList.remove('open');
        btn.innerHTML = iconHtml('list');
      }
    });
  }

  /* ─── 游标列候选提示 ──────────────────────────────────────
     项目详情页的游标列输入框：聚焦时拉取该表可用的游标列（时间 + 整数类型），
     填到 <datalist> 里给出原生下拉建议。加载过一次就缓存，避免反复请求。 */
  function bindCursorInput(input) {
    var loaded = false;
    function load() {
      if (loaded) return;
      loaded = true;
      var url = input.dataset.action + '?table=' + encodeURIComponent(input.dataset.table);
      fetch(url, { headers: { 'Accept': 'application/json' } })
        .then(function (r) { return r.json(); })
        .then(function (data) {
          if (!data || !data.success || !Array.isArray(data.columns)) return;
          var listId = input.getAttribute('list');
          if (!listId) return;
          var dl = document.getElementById(listId);
          if (!dl) return;
          data.columns.forEach(function (col) {
            var opt = document.createElement('option');
            opt.value = col;
            dl.appendChild(opt);
          });
        })
        .catch(function () { /* 加载失败不影响使用，输入框仍可手填 */ });
    }
    input.addEventListener('focus', load);
    // 点击时也触发一次（focus 有时在移动端不触发）
    input.addEventListener('click', load);
  }

  /* ─── 赞赏弹窗 ──────────────────────────────────────────── */

  /**
   * 打开/关闭弹窗，并在支付方式之间切换收款码。
   * 图片文件名与 data-pay 同名（wechat/alipay/qq），提示文案由服务端渲染在
   * data-hint 上，因此这里不需要第二份 i18n 字典。
   */
  function bindDonate() {
    var modal = document.getElementById('donate-modal');
    if (!modal) return;

    var assets = modal.dataset.assets || '/assets/';

    function open() {
      modal.classList.add('open');
      modal.setAttribute('aria-hidden', 'false');
      // 弹窗自己可滚动，锁住背景避免两层滚动条打架
      document.body.style.overflow = 'hidden';
    }
    function close() {
      modal.classList.remove('open');
      modal.setAttribute('aria-hidden', 'true');
      document.body.style.overflow = '';
    }

    document.querySelectorAll('[data-role="open-donate"]').forEach(function (btn) {
      btn.addEventListener('click', open);
    });
    document.querySelectorAll('[data-role="close-donate"]').forEach(function (el) {
      el.addEventListener('click', close);
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && modal.classList.contains('open')) close();
    });

    var tabs = modal.querySelector('[data-role="pay-tabs"]');
    if (!tabs) return;
    var qr = document.getElementById('donate-qr');
    var tip = document.getElementById('donate-tip');

    tabs.addEventListener('click', function (e) {
      var btn = e.target.closest('.pay-tab');
      if (!btn) return;
      if (qr) {
        qr.src = assets + btn.dataset.pay + '.webp';
        qr.alt = btn.textContent.trim();
      }
      if (tip && btn.dataset.hint) tip.textContent = btn.dataset.hint;
      tabs.querySelectorAll('.pay-tab').forEach(function (b) {
        b.classList.toggle('active', b === btn);
      });
    });
  }

  /* ─── 转换复审：AI 起草 + 目标库语法检查 ──────────────────── */

  /**
   * 渲染「不保证」清单。
   *
   * 这一份清单比 SQL 本身更值得看：模型对着一段 PL/SQL 一定能吐出语法正确的
   * MySQL，能不能吐出行为一致的 MySQL 是另一回事。所以清单放在编辑器上方，
   * 空清单也照样把框显示出来 —— 「模型说它没有疑虑」本身就是一条需要警惕的信息。
   */
  function renderUncertainties(entries) {
    var box = document.getElementById('uncertainty-box');
    var list = document.getElementById('uncertainty-list');
    if (!box || !list) return;

    list.innerHTML = '';
    (entries || []).forEach(function (entry) {
      var li = document.createElement('li');
      // 服务端可能给 i18n key（error.ai.*），也可能是模型的原文，t() 两种都能处理
      li.textContent = t(entry);
      list.appendChild(li);
    });
    if (!entries || !entries.length) {
      var li = document.createElement('li');
      li.textContent = t('conversion.noUncertainties');
      list.appendChild(li);
    }
    box.style.display = '';
  }

  /** AI 起草：把候选写进编辑器，同时把模型的疑虑摊开 */
  function bindAiDraft(btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      var editor = document.getElementById('override-sql');
      if (!editor) return;

      var original = btn.innerHTML;
      btn.disabled = true;
      btn.innerHTML = '<span class="spin"></span> ' + (btn.dataset.busyText || '');

      postJson(btn.dataset.action).then(function (payload) {
        if (!payload.success) {
          // 模型主动拒绝转换时，理由就在 uncertainties 里，那是这次调用唯一有价值的
          // 产出。只弹一个 toast 就丢掉，用户会以为是网络错误而反复重试。
          if (payload.uncertainties && payload.uncertainties.length) {
            renderUncertainties(payload.uncertainties);
          }
          toast(t(payload.message) || t('conversion.draftFailed'), 'danger');
          return;
        }
        editor.value = payload.sql || '';
        renderUncertainties(payload.uncertainties);
        toast(t('conversion.draftDone') + ' — ' + (payload.model || '') +
          ' (' + (payload.elapsedMs || 0) + 'ms)', 'ok');
      }).catch(function (err) {
        toast(String((err && err.message) || err), 'danger');
      }).then(function () {
        btn.disabled = false;
        btn.innerHTML = original;
      });
    });
  }

  /**
   * 语法检查：把编辑器里的当前内容发到目标库上真建一次再删掉。
   *
   * 发的是编辑器内容而不是服务端重新推导的 SQL —— 用户手改过的地方才是最需要
   * 验的地方。会写目标库，所以必须先 confirm。
   */
  function bindValidateSql(btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      var editor = document.getElementById('override-sql');
      var out = document.getElementById('validate-result');
      if (!editor) return;
      if (!editor.value.trim()) {
        toast(t('error.override.empty'), 'danger');
        return;
      }
      if (btn.dataset.confirm && !window.confirm(btn.dataset.confirm)) return;

      var original = btn.innerHTML;
      btn.disabled = true;
      btn.innerHTML = '<span class="spin"></span> ' + (btn.dataset.busyText || '');
      if (out) out.innerHTML = '';

      postJson(btn.dataset.action, JSON.stringify({
        kind: btn.dataset.kind,
        name: btn.dataset.name,
        sql: editor.value
      }), true).then(function (payload) {
        var kind = payload.success ? 'ok' : 'danger';
        toast(t(payload.success ? 'conversion.validateOk' : 'conversion.validateFailed'), kind);

        if (!out) return;
        var alert = document.createElement('div');
        alert.className = 'alert alert-' + kind;
        alert.appendChild(icon(payload.success ? 'check-circle-fill' : 'x-circle-fill'));

        var body = document.createElement('div');
        body.className = 'alert-body';
        var head = document.createElement('strong');
        head.textContent = t(payload.success
          ? 'conversion.validateOk' : 'conversion.validateFailed');
        body.appendChild(head);

        // 失败时目标库自己的报错最有用，原样带出来
        if (!payload.success && payload.message) {
          var detail = document.createElement('div');
          detail.className = 'small mono';
          detail.textContent = payload.message;
          body.appendChild(detail);
        }
        // 即使通过也要显示 caveats：「目标库接受了」和「行为一致」是两件事
        (payload.caveats || []).forEach(function (c) {
          var line = document.createElement('div');
          line.className = 'small muted';
          line.textContent = t(c);
          body.appendChild(line);
        });
        alert.appendChild(body);
        out.appendChild(alert);
      }).catch(function (err) {
        toast(String((err && err.message) || err), 'danger');
      }).then(function () {
        btn.disabled = false;
        btn.innerHTML = original;
      });
    });
  }

  /* ─── 默认口令横幅 ──────────────────────────────────────── */

  /**
   * 关掉「仍在用默认口令」的横幅。
   *
   * 记在 sessionStorage 而不是 localStorage：关掉只对本次会话有效，浏览器一关
   * 状态就没了，下次打开继续提示。口令真的改了之后服务端不再渲染这条横幅，
   * 所以这里不需要、也不应该提供永久关闭。
   */
  function bindDefaultPwDismiss(btn) {
    btn.addEventListener('click', function () {
      var banner = btn.closest('.default-pw-banner');
      if (banner) banner.remove();
      try {
        sessionStorage.setItem('synctool-pw-banner-dismissed', '1');
      } catch (e) { /* 隐私模式：写不进去，本次点击仍然生效 */ }
    });
  }

  /* ─── 初始化 ────────────────────────────────────────────── */

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-role="api-btn"]').forEach(bindActionButton);
    document.querySelectorAll('[data-role="test-conn"]').forEach(bindConnectionTest);
    document.querySelectorAll('[data-role="picker"]').forEach(bindPicker);
    document.querySelectorAll('[data-role="find-drivers"]').forEach(bindDriverDiscovery);
    document.querySelectorAll('[data-role="type-select"]').forEach(bindTypeSelect);
    document.querySelectorAll('[data-role="protocol-select"]').forEach(bindProtocolSelect);
    document.querySelectorAll('[data-role="ai-draft"]').forEach(bindAiDraft);
    document.querySelectorAll('[data-role="validate-sql"]').forEach(bindValidateSql);
    document.querySelectorAll('[data-role="nav-toggle"]').forEach(bindNavToggle);
    document.querySelectorAll('[data-role="toggle-password"]').forEach(bindPasswordToggle);
    document.querySelectorAll('[data-role="cursor-form"] input[name=cursorColumn]')
      .forEach(bindCursorInput);
    document.querySelectorAll('[data-role="dismiss-default-pw"]').forEach(bindDefaultPwDismiss);
    bindDonate();

    // 关闭提示条
    document.querySelectorAll('[data-dismiss]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var alert = btn.closest('.alert');
        if (alert) alert.remove();
      });
    });
    // 成功提示自动消失；错误提示留着让用户看清
    document.querySelectorAll('.alert-ok[data-auto-dismiss]').forEach(function (alert) {
      setTimeout(function () { alert.remove(); }, 5000);
    });

    // 提交前确认（删除等不可逆操作）
    document.querySelectorAll('form[data-confirm]').forEach(function (form) {
      form.addEventListener('submit', function (e) {
        if (!window.confirm(form.dataset.confirm)) e.preventDefault();
      });
    });
  });

  /**
   * 密码框的显示/隐藏。
   *
   * 只切 type 属性，不把明文写进任何变量或 DOM 文本节点，
   * 于是不会有一份密码留在别处等着被别的脚本读到。
   */
  function bindPasswordToggle(btn) {
    var input = document.getElementById(btn.dataset.target);
    if (!input) return;
    btn.addEventListener('click', function () {
      var shown = input.type === 'text';
      input.type = shown ? 'password' : 'text';
      btn.classList.toggle('active', !shown);
      input.focus();
    });
  }

  window.SyncToolUI = { toast: toast, t: t, icon: icon, iconHtml: iconHtml };
})();
