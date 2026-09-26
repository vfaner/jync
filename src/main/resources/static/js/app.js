/* ==========================================================================
   通用交互：Toast、API 调用、对象选择、连接测试、移动端导航
   纯原生 JS，不依赖任何框架，全部本地资源。
   ========================================================================== */
(function () {
  'use strict';

  /* 服务端返回的是 i18n key，布局里把解析好的文案挂在这里 */
  var messages = window.JyncMessages || {};

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

  /**
   * GET，返回解析好的 JSON，约定与 postJson 完全一致：
   * 401 视为会话过期，重载当前 URL（为什么重载而不是拼 /login，见 postJson 里的注释）；
   * 非 JSON 响应兜底成 { success:false, message:'HTTP 状态码' }，调用方永远不会拿到 rejection。
   * GET 不改状态，CSRF 防护不拦它，所以不像 postJson 那样带令牌。
   */
  function getJson(url) {
    return fetch(url, {
      method: 'GET',
      headers: { 'Accept': 'application/json' }
    }).then(function (response) {
      if (response.status === 401) {
        window.location.reload();
        return { success: false, message: 'error.sessionExpired' };
      }
      return response.json().catch(function () {
        return { success: false, message: 'HTTP ' + response.status };
      });
    });
  }

  /**
   * POST 并以 SSE 逐段读取，返回 Promise<payload|null>。
   *
   * 与 postJson 的约定一致：401 重载页面并返回会话过期对象；CSRF 令牌同样取自
   * layout.html 的 <meta>。额外的约定是「null 表示服务端没给流」——旧版后端、
   * 或被网关缓冲成普通响应的情况，调用方应退回 postJson，功能不降级只是不实时。
   */
  function postSse(url, onDelta) {
    var headers = { 'Accept': 'text/event-stream' };
    var token = document.querySelector('meta[name="_csrf"]');
    var header = document.querySelector('meta[name="_csrf_header"]');
    if (token && header && token.content && header.content) {
      headers[header.content] = token.content;
    }
    return fetch(url, { method: 'POST', headers: headers }).then(function (response) {
      if (response.status === 401) {
        window.location.reload();
        return { success: false, message: 'error.sessionExpired' };
      }
      var ctype = response.headers.get('Content-Type') || '';
      if (ctype.indexOf('text/event-stream') < 0 ||
          !response.body || !response.body.getReader) {
        return null;
      }
      return readSse(response, onDelta);
    });
  }

  /**
   * 把 SSE 字节流解析成事件：event: 行定种类，data: 行带 JSON。
   * delta → onDelta(text)；done → resolve(payload) 并取消读取。
   * 流结束都没等到 done 视为中断，reject 交给调用方善后。
   */
  function readSse(response, onDelta) {
    return new Promise(function (resolve, reject) {
      var reader = response.body.getReader();
      var decoder = new TextDecoder('utf-8');
      var buffer = '';
      var pendingEvent = null;
      var settled = false;

      function processLine(line) {
        if (line === '') { pendingEvent = null; return; }
        if (line.indexOf('event:') === 0) {
          pendingEvent = line.substring(6).trim();
          return;
        }
        if (line.indexOf('data:') !== 0) return;
        var data = line.substring(5).trim();
        if (!data) return;
        var parsed;
        try { parsed = JSON.parse(data); } catch (e) { return; }
        if (pendingEvent === 'delta') {
          if (parsed && parsed.text) onDelta(parsed.text);
        } else if (pendingEvent === 'done') {
          settled = true;
          resolve(parsed);
          reader.cancel().catch(function () { /* 服务端可能已经关了，无所谓 */ });
        }
      }

      function pump() {
        reader.read().then(function (result) {
          if (settled) return;
          if (result.done) {
            if (buffer) processLine(buffer);
            if (!settled) reject(new Error('stream ended before done'));
            return;
          }
          buffer += decoder.decode(result.value, { stream: true });
          var idx;
          while ((idx = buffer.indexOf('\n')) >= 0) {
            var line = buffer.substring(0, idx);
            if (line.charAt(line.length - 1) === '\r') {
              line = line.substring(0, line.length - 1);
            }
            buffer = buffer.substring(idx + 1);
            processLine(line);
            if (settled) return;
          }
          pump();
        }, function (err) {
          if (!settled) reject(err);
        });
      }
      pump();
    });
  }

  /**
   * 按钮加载态：记下原内容 → 禁用 → 换成转圈 → 任务落定后恢复原样。
   *
   * 恢复用 then 的双参形式而不是 finally：效果相同，但不依赖较新的 Promise 特性，
   * 与本文件的兼容基线一致。业务错误应由 task 自己 catch（并 toast），
   * 这里的兜底只负责一件事——按钮无论如何都会恢复，不会永远卡在加载态。
   */
  function withBusy(btn, busyHtml, task) {
    var original = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = busyHtml;
    function restore() {
      btn.disabled = false;
      btn.innerHTML = original;
    }
    return task().then(restore, restore);
  }

  /** 带加载态的动作按钮，完成后可选刷新页面 */
  function bindActionButton(btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      if (btn.dataset.confirm && !window.confirm(btn.dataset.confirm)) return;

      // 这里不用 withBusy：下方 reload 分支刻意「不恢复」按钮 ——
      // 保持加载态直到页面刷新，避免重载前按钮文案闪回原样
      var original = btn.innerHTML;
      btn.disabled = true;
      btn.innerHTML = '<span class="spin"></span>' +
        (btn.dataset.busyText ? ' ' + btn.dataset.busyText : '');

      postJson(btn.dataset.action).then(function (payload) {
        var text = payload.summary
          ? t(payload.message) + ' — ' + payload.summary
          : t(payload.message);
        // 服务端可用 toastKind 指定级别（如「已排队补跑」用 warn），缺省按成败二色
        toast(text, payload.toastKind || (payload.success ? 'ok' : 'danger'));

        // 逐条列出错误，比只给一句「失败」有用
        if (payload.errors && payload.errors.length) {
          payload.errors.slice(0, 3).forEach(function (err) { toast(err, 'danger'); });
        }

        // noReload：请求被接受但没有真正执行完（如补跑排队），页面不该刷新
        if (payload.success && payload.noReload !== true && btn.dataset.reload === 'true') {
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

      withBusy(btn, '<span class="spin"></span> ' + (btn.dataset.busyText || ''), function () {
        return postJson(btn.dataset.action, new FormData(form)).then(function (p) {
          if (!out) {
            toast(t(p.success ? (btn.dataset.okKey || 'db.testSuccess')
                              : (btn.dataset.failKey || 'db.testFailed'))
              // p.message 可能是 i18n key，t() 找不到时原样返回（驱动的英文报错不受影响）
              + (p.message ? ': ' + t(p.message) : ''), p.success ? 'ok' : 'danger');
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
          [p.productInfo || p.model, p.driverInfo, p.success ? null : t(p.message),
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
        });
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
      withBusy(btn, '<span class="spin"></span>', function () {
        return getJson(btn.dataset.action + '?jarPath=' + encodeURIComponent(jar.value.trim()))
          .then(function (p) {
            // 预设类型（GBase / 神通）的驱动类名由枚举固定，驱动类输入框藏在 CUSTOM 卡片里。
            // 这时只把检测结果 toast 出来供核对，绝不能写隐藏域 —— 否则服务端会优先用它覆盖预设类名。
            var customSection = document.getElementById('custom-section');
            var customEditable = !customSection || !customSection.hidden;
            if (customEditable) {
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
              }
            }
            if (p.drivers && p.drivers.length) {
              toast(p.drivers.join(', '), 'ok');
            } else {
              // getJson 的兜底载荷（HTTP xxx / error.sessionExpired）没有 drivers 字段，
              // 也落到这一支：p.message 经 t() 解析后原样 toast 出具体原因，不会静默失败
              toast(t(p.message || 'msg.no.drivers.declared'), 'warn');
            }
          })
          .catch(function (err) { toast(String(err && err.message || err), 'danger'); });
      });
    });
  }

  /* ─── 数据库类型切换：预填端口、显隐自定义区 ────────────── */

  function bindTypeSelect(select) {
    function apply() {
      getJson(select.dataset.action + '?type=' + encodeURIComponent(select.value))
        .then(function (p) {
          // getJson 的兜底载荷（success:false）不含任何预填字段：直接返回，
          // 和旧版「静默失败」一致，不能拿 undefined 去改端口和卡片显隐
          if (p.success === false) return;
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
          // 未内置的预设（如 GBase / 神通）也要填 jar；bundled 由服务端查 classpath 得出
          var jarCard = document.getElementById('jar-card');
          if (jarCard) {
            var showJar = p.custom || p.bundled === false;
            jarCard.hidden = !showJar;
            // 只 hidden 还会照常提交，必须连控件一起禁用，否则残留的旧 jar 路径会劫持内置驱动
            jarCard.querySelectorAll('input,button').forEach(function (el) { el.disabled = !showJar; });
          }
          var hint = document.getElementById('url-hint');
          if (hint) hint.textContent = p.urlTemplate || '';
          // 服务名说明只对 Oracle 有意义，其余类型隐藏
          var oracleHelp = document.getElementById('oracle-name-help');
          if (oracleHelp) oracleHelp.hidden = select.value !== 'ORACLE';
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
      getJson(select.dataset.action + '?protocol=' + encodeURIComponent(select.value))
        .then(function (p) {
          // 兜底载荷（success:false）没有预填字段：跳过，别用空值抹掉 endpoint 提示
          if (p.success === false) return;
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
      // getJson 的兜底载荷（success:false）正好被下面第一行守卫拦住，无需另加判断
      getJson(url)
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
   * 赞赏弹窗：开关与 Esc 复用 setModalOpen / bindGenericModals，不再自己维护
   * 一份重复实现；这里只保留支付方式之间切换收款码的逻辑。
   * 图片文件名与 data-pay 同名（wechat/alipay/qq），提示文案由服务端渲染在
   * data-hint 上，因此这里不需要第二份 i18n 字典。
   */
  function bindDonate() {
    var modal = document.getElementById('donate-modal');
    if (!modal) return;

    var assets = modal.dataset.assets || '/assets/';

    // 触发属性是 data-role="open-donate/close-donate" 而不是通用的
    // data-open-modal/data-close-modal，所以事件绑定留在这里；
    // Esc 关闭已由 bindGenericModals 统一处理（#donate-modal 带 .modal 类）
    document.querySelectorAll('[data-role="open-donate"]').forEach(function (btn) {
      btn.addEventListener('click', function () { setModalOpen(modal.id, true); });
    });
    document.querySelectorAll('[data-role="close-donate"]').forEach(function (el) {
      el.addEventListener('click', function () { setModalOpen(modal.id, false); });
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
   * 起草完成后亮出「人工自测」提醒。
   *
   * AI 对着一段 PL/SQL 一定能吐出语法正确的 MySQL，能不能吐出行为一致的 MySQL
   * 是另一回事。模型自语的疑虑清单读起来像噪音，真正管用的提醒就这一句：
   * 保存为覆盖之前，先在目标库跑一遍。
   */
  function showDraftNotice() {
    var box = document.getElementById('draft-notice');
    if (box) box.style.display = '';
  }

  /**
   * 从流式原文里实时整理出 SQL，供起草过程中「边生成边看」。
   *
   * 系统提示词要求模型只回 SQL，因此原文通常即成品，原样增长即可。留一层容忍：
   * 模型偶尔仍会套 markdown fence 或 JSON 包装，这里剥掉 fence、或对 JSON 做增量
   * 提取（找到 "sql" 的值后逐字符反转义，值没写完就返回已到达的前缀，转义序列被
   * 截断时停在原地等下一段），不让包装壳闪现在编辑器里。
   */
  function extractSqlPrefix(raw) {
    var s = raw;
    var fence = s.indexOf('```');
    if (fence >= 0) {
      var nl = s.indexOf('\n', fence);
      s = nl >= 0 ? s.substring(nl + 1) : s.substring(fence + 3);
    }
    var marker = s.indexOf('"sql"');
    if (marker < 0) return s;
    var colon = s.indexOf(':', marker + 5);
    if (colon < 0) return '';
    var i = colon + 1;
    while (i < s.length && /\s/.test(s.charAt(i))) i++;
    if (s.charAt(i) !== '"') return '';
    i++;
    var out = '';
    while (i < s.length) {
      var c = s.charAt(i);
      if (c === '"') break;
      if (c === '\\') {
        var n = s.charAt(i + 1);
        if (n === '') break; // 转义序列被切断，等下一段 delta
        if (n === 'n') { out += '\n'; i += 2; continue; }
        if (n === 't') { out += '\t'; i += 2; continue; }
        if (n === 'r') { i += 2; continue; }
        if (n === 'u') {
          if (i + 6 > s.length) break; // \uXXXX 还没到齐
          out += String.fromCharCode(parseInt(s.substring(i + 2, i + 6), 16));
          i += 6; continue;
        }
        out += n; i += 2; continue;
      }
      out += c; i++;
    }
    return out;
  }

  /**
   * AI 起草：把候选写进编辑器，同时把模型的疑虑摊开。
   *
   * 走 SSE 流式（data-action + '/stream'）：模型每写一段，编辑器就长一段 SQL，
   * 首字延迟从「整个存储过程生成完」降到「第一个网络分片」。done 事件带来的
   * payload 与一次性接口完全同形，以它解析出的 SQL 为准整体替换编辑器内容。
   * 服务端没给流（postSse 返回 null）时退回原 /draft 接口，功能不降级。
   */
  function bindAiDraft(btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      var editor = document.getElementById('override-sql');
      if (!editor) return;

      withBusy(btn, '<span class="spin"></span> ' + (btn.dataset.busyText || ''), function () {
        var originalValue = editor.value;
        var streamRaw = '';
        var syncTimer = null;

        // 流式期间编辑器内容随时在变，高亮底衬靠 input 事件跟着刷；
        // 每个 delta 都全量重高亮太贵，80ms 合并一次。
        function flushSync() {
          if (syncTimer) { clearTimeout(syncTimer); syncTimer = null; }
          editor.dispatchEvent(new Event('input'));
        }
        function scheduleSync() {
          if (syncTimer) return;
          syncTimer = setTimeout(function () {
            syncTimer = null;
            editor.value = extractSqlPrefix(streamRaw);
            editor.scrollTop = editor.scrollHeight;
            editor.dispatchEvent(new Event('input'));
          }, 80);
        }
        function restoreEditor() {
          editor.value = originalValue;
          flushSync();
        }
        function applyPayload(payload) {
          if (syncTimer) { clearTimeout(syncTimer); syncTimer = null; }
          if (!payload || !payload.success) {
            // 失败（含模型拒绝、流中断）：编辑器退回起草前的内容，半成品不留下。
            // 服务端的 message 是 i18n key 或网关原文，toast 里说清楚，
            // 用户才不会把「模型拒绝」当成网络错误反复重试。
            restoreEditor();
            toast((payload && t(payload.message)) || t('conversion.draftFailed'), 'danger');
            return;
          }
          editor.value = payload.sql || '';
          flushSync();
          showDraftNotice();
          // 缓存命中会「秒回」，不标注的话用户会以为根本没调模型
          var extra = payload.cached ? ' ' + t('conversion.draftCached') : '';
          toast(t('conversion.draftDone') + ' — ' + (payload.model || '') +
            ' (' + (payload.elapsedMs || 0) + 'ms)' + extra, 'ok');
        }

        return postSse(btn.dataset.action + '/stream', function (text) {
          streamRaw += text;
          scheduleSync();
        }).then(function (payload) {
          if (payload === null) {
            return postJson(btn.dataset.action);
          }
          return payload;
        }).then(applyPayload).catch(function (err) {
          restoreEditor();
          toast(String((err && err.message) || err), 'danger');
        });
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

      if (out) out.innerHTML = '';

      withBusy(btn, '<span class="spin"></span> ' + (btn.dataset.busyText || ''), function () {
        return postJson(btn.dataset.action, JSON.stringify({
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

          // 失败时目标库自己的报错最有用；message 也可能是 i18n key，t() 找不到时原样返回。
          // 服务端会拼 "key: 驱动原始报错"，先在第一个 ": " 处拆开：key 部分走翻译，
          // 驱动细节原样附上（t() 未命中时返回原文，拆开再拼回是无损的）。
          if (!payload.success && payload.message) {
            var detail = document.createElement('div');
            detail.className = 'small mono';
            var msg = String(payload.message);
            var cut = msg.indexOf(': ');
            detail.textContent = cut > -1
              ? t(msg.slice(0, cut)) + msg.slice(cut)
              : t(msg);
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
        });
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
        sessionStorage.setItem('jync-pw-banner-dismissed', '1');
      } catch (e) { /* 隐私模式：写不进去，本次点击仍然生效 */ }
    });
  }

  /* ─── 变更日志的刷新 ────────────────────────────────────── */

  var LOG_AUTO_KEY = 'jync-log-auto-refresh';
  var LOG_AUTO_INTERVAL = 5000;
  try {
    // 一次性迁移：SyncTool 时代的自动刷新偏好，搬完即删
    var legacyAuto = localStorage.getItem('synctool-log-auto-refresh');
    if (legacyAuto !== null) {
      if (localStorage.getItem(LOG_AUTO_KEY) === null) {
        localStorage.setItem(LOG_AUTO_KEY, legacyAuto);
      }
      localStorage.removeItem('synctool-log-auto-refresh');
    }
  } catch (e) { /* 隐私模式：偏好回到默认值（开） */ }

  /**
   * 变更日志页的手动刷新 + 自动刷新。
   *
   * 这一页是服务端渲染的一次快照：同步在后台一直往日志里写，页面却停在你打开
   * 它的那一刻。所以要么自己刷，要么给它一个会刷的理由。
   *
   * 不用 location.reload()：整页重载会闪、会把滚动位置弹回顶部、正在读的错误
   * 详情会被打断。这里重新取一次「当前 URL」（projectId、page 都在里面），
   * 只把卡片和计数换掉。表格怎么画仍然只有 Thymeleaf 一份，这里一行渲染逻辑
   * 都不复制。
   */
  function bindLogRefresh(btn) {
    var card = document.getElementById('log-card');
    if (!card) return;

    var countEl = document.getElementById('log-count');
    var stampEl = document.getElementById('log-updated');
    var autoBtn = document.querySelector('[data-role="log-auto"]');
    var icon = btn.querySelector('.ico');
    var timer = null;
    var inFlight = false;

    function stamp() {
      if (!stampEl) return;
      var tpl = stampEl.dataset.tpl || '{0}';
      stampEl.textContent = tpl.replace('{0}', new Date().toLocaleTimeString());
      stampEl.hidden = false;
    }

    function refresh() {
      // 上一轮还没回来就跳过这一轮：网络慢的时候不该把请求越堆越多
      if (inFlight) return;
      inFlight = true;
      if (icon) icon.classList.add('spinning');

      // 这里取的是整页 HTML（喂给 DOMParser）而不是 JSON，所以不能套用 getJson
      fetch(window.location.href, { cache: 'no-store' })
        .then(function (response) {
          // 服务端明确报错（5xx 等）时不解析、更不重载：自动刷新每 5 秒一轮，
          // 重载过去多半还是错误页，等于把整页变成重载循环。抛给下方 catch，
          // 与网络抖动同样处理——保留用户正在看的表格，下一轮再试。
          // （会话过期不走这里：重定向被 fetch 跟随，回来的是 200 的登录页，
          // 由下面「找不到 log-card 就重载」的分支处理。）
          if (!response.ok) throw new Error('HTTP ' + response.status);
          return response.text();
        })
        .then(function (html) {
          var fresh = new DOMParser().parseFromString(html, 'text/html');
          var freshCard = fresh.getElementById('log-card');
          // 取回来的不是日志页，说明会话过期、安全层把它换成了登录页。
          // 和 postJson 里一样重载当前 URL，让安全层自己决定跳到哪儿。
          if (!freshCard) {
            window.location.reload();
            return;
          }
          card.innerHTML = freshCard.innerHTML;
          var freshCount = fresh.getElementById('log-count');
          if (countEl && freshCount) countEl.textContent = freshCount.textContent;
          stamp();
        })
        .catch(function () {
          // 网络抖一下不值得清空用户正在看的表格，保留原样，下一轮再试
        })
        .then(function () {
          inFlight = false;
          if (icon) icon.classList.remove('spinning');
        });
    }

    function stop() {
      if (timer) {
        clearInterval(timer);
        timer = null;
      }
    }

    /** 默认开启：没存过就是开。存过 'off' 才关。 */
    function autoOn() {
      if (!autoBtn || autoBtn.disabled) return false;
      try {
        return localStorage.getItem(LOG_AUTO_KEY) !== 'off';
      } catch (e) {
        return true; // 隐私模式：记不住选择，那就按默认来
      }
    }

    function applyAuto() {
      var on = autoOn();
      if (autoBtn) autoBtn.classList.toggle('is-on', on);
      stop();
      // 页面在后台时刷新纯属浪费流量，切回来再补
      if (on && !document.hidden) timer = setInterval(refresh, LOG_AUTO_INTERVAL);
    }

    btn.addEventListener('click', function () { refresh(); });

    if (autoBtn) {
      autoBtn.addEventListener('click', function () {
        var next = autoOn() ? 'off' : 'on';
        try {
          localStorage.setItem(LOG_AUTO_KEY, next);
        } catch (e) { /* 隐私模式：本次点击仍然生效，只是记不住 */ }
        applyAuto();
        if (next === 'on') refresh();
      });
    }

    document.addEventListener('visibilitychange', function () {
      var wasOn = timer !== null;
      applyAuto();
      // 回到前台时先补一次，不让用户对着一屏旧数据等满一个间隔
      if (!document.hidden && !wasOn && autoOn()) refresh();
    });

    applyAuto();
  }

  /**
   * 通用弹窗：[data-open-modal=id] 打开、[data-close-modal=id] 关闭、Esc 关当前开着的。
   * 赞赏弹窗的触发属性名不同、还带收款码切换，事件绑定留在 bindDonate 里，
   * 但开/关效果与 Esc 同样复用这一套。
   */
  function setModalOpen(id, open) {
    var modal = document.getElementById(id);
    if (!modal) return;
    modal.classList.toggle('open', open);
    modal.setAttribute('aria-hidden', open ? 'false' : 'true');
    // 弹窗自己可滚动，锁住背景避免两层滚动条打架
    document.body.style.overflow = open ? 'hidden' : '';
  }

  function bindGenericModals() {
    document.querySelectorAll('[data-open-modal]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        setModalOpen(btn.getAttribute('data-open-modal'), true);
      });
    });
    document.querySelectorAll('[data-close-modal]').forEach(function (el) {
      el.addEventListener('click', function () {
        setModalOpen(el.getAttribute('data-close-modal'), false);
      });
    });
    document.addEventListener('keydown', function (e) {
      if (e.key !== 'Escape') return;
      document.querySelectorAll('.modal.open').forEach(function (m) {
        setModalOpen(m.id, false);
      });
    });
  }

  /**
   * 用户名下拉：点击展开/收起，点菜单外或 Esc 收起；
   * 菜单里的退出项提交隐藏表单（CSRF 隐藏域由服务端渲染）。
   */
  function bindUserMenu() {
    var groups = document.querySelectorAll('.nav-group');

    function closeAll(except) {
      groups.forEach(function (g) {
        if (g === except) return;
        g.classList.remove('open');
        var t = g.querySelector('.nav-trigger');
        if (t) t.setAttribute('aria-expanded', 'false');
      });
    }

    groups.forEach(function (group) {
      var trigger = group.querySelector('.nav-trigger');
      if (!trigger) return;
      trigger.addEventListener('click', function (e) {
        e.stopPropagation();
        var willOpen = !group.classList.contains('open');
        closeAll(group);
        group.classList.toggle('open', willOpen);
        trigger.setAttribute('aria-expanded', String(willOpen));
      });
      // 桌面鼠标移出后清掉点击留下的展开态，避免再次悬停状态错乱
      group.addEventListener('mouseleave', function () {
        group.classList.remove('open');
        trigger.setAttribute('aria-expanded', 'false');
      });
      // 选中菜单项（打开弹窗 / 提交退出）后收起下拉
      group.querySelectorAll('.menu-item').forEach(function (item) {
        item.addEventListener('click', function () { closeAll(); });
      });
    });
    document.addEventListener('click', function (e) {
      if (!e.target.closest || !e.target.closest('.nav-group')) closeAll();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') closeAll();
    });

    var logoutBtn = document.getElementById('logout-btn');
    var logoutForm = document.getElementById('logout-form');
    if (logoutBtn && logoutForm) {
      logoutBtn.addEventListener('click', function () { logoutForm.submit(); });
    }
  }

  /* ─── 分页条 ──────────────────────────────────────────────
     页码和省略号都是服务端渲染好的链接，这里只接两个拼 URL 的控件：
     每页条数下拉（改条数回到第 1 页）和跳页输入（回车或失焦跳转，越界夹住）。 */

  function bindPager(bar) {
    var base = bar.dataset.base;
    var sep = bar.dataset.sep;
    var sizeSel = bar.querySelector('.pager-size');
    var jump = bar.querySelector('.pager-jump-input');

    function go(page, size) {
      window.location.href = base + sep + 'page=' + page + '&size=' + size;
    }
    function commitJump() {
      var max = parseInt(jump.max, 10);
      var n = parseInt(jump.value, 10);
      if (isNaN(n)) { jump.value = ''; return; }
      if (!isNaN(max)) { n = Math.min(n, max); }
      go(Math.max(1, n), sizeSel ? sizeSel.value : 20);
    }

    if (sizeSel) {
      sizeSel.addEventListener('change', function () { go(1, sizeSel.value); });
    }
    if (jump) {
      jump.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.keyCode === 13) { commitJump(); }
      });
      jump.addEventListener('change', commitJump);
    }
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
    document.querySelectorAll('[data-role="log-refresh"]').forEach(bindLogRefresh);
    document.querySelectorAll('[data-role="pager"]').forEach(bindPager);
    bindDonate();
    bindGenericModals();
    bindUserMenu();

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

  // 有意暴露的全局入口（承自改名前的 SyncToolUI）：供浏览器控制台调试与后续扩展使用。
  // 仓库内搜不到调用方属预期情况，不是死代码，评审时请勿删除。
  // readSse / extractSqlPrefix 一并暴露：流式解析不依赖页面 DOM，控制台里用
  // 合成的 Response 就能单测，排查「起草不实时」时也用得上。
  window.JyncUI = {
    toast: toast, t: t, icon: icon, iconHtml: iconHtml,
    readSse: readSse, extractSqlPrefix: extractSqlPrefix
  };
})();
