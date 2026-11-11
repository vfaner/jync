/* ==========================================================================
   变更日志页的 DDL 待办弹窗。

   与其它页面脚本（system-config.js、metrics.js…）一样是页面私有的：通用能力
   ——弹窗体、带 CSRF 的 POST、确认与提示框——都取自 app.js 暴露的 window.JyncUI，
   这里只做这一页的事：点状态 tag 打开共用弹窗、按行刷新两个按钮的可用状态、
   把语句提交到目标库，或重放缺口。app.js 不认识 DDL，本页也不重复实现通用能力。

   服务端只回 JSON（success + 已本地化的文案），不跳转页面；管理员看完提示关掉
   弹窗后原地刷新列表，状态 tag 与按钮随之更新。
   ========================================================================== */
(function () {
  'use strict';

  var dialog = (window.JyncUI || {}).dialog || {};
  var toast = (window.JyncUI || {}).toast;

  /**
   * 带 CSRF 的 POST，返回服务端回话 {success, message}。
   *
   * <p>本页自己发请求，不借用公共脚本的内部实现：app.js 不认识 DDL，页面脚本也不该
   * 把它的内部函数挂到全局出口上去。
   */
  function postJson(url, body) {
    var headers = { 'Accept': 'application/json' };
    var token = document.querySelector('meta[name="_csrf"]');
    var header = document.querySelector('meta[name="_csrf_header"]');
    if (token && header && token.content && header.content) {
      headers[header.content] = token.content;
    }
    return fetch(url, { method: 'POST', headers: headers, body: body })
      .then(function (response) {
        if (response.status === 401) {
          // 会话过期：重载当前页，由安全层决定跳登录
          window.location.reload();
          return { success: false, message: 'error.sessionExpired' };
        }
        return response.json().catch(function () {
          return { success: false, message: 'HTTP ' + response.status };
        });
      });
  }

  /** 显示/隐藏本页的弹窗：只管这一个，不去动公共的弹窗体。 */
  function toggleModal(id, open) {
    var modal = document.getElementById(id);
    if (!modal) { return; }
    modal.classList.toggle('open', open);
    modal.setAttribute('aria-hidden', open ? 'false' : 'true');
    document.body.style.overflow = open ? 'hidden' : '';
  }

  /** 打开弹窗前的焦点所在元素；关闭时归还，焦点不能滞留在已隐藏的子树里。 */
  var modalOpener = null;

  /**
   * 显示 DDL 弹窗，并管住焦点。
   *
   * <p>焦点必须先进弹窗、再在关闭前移出来。留在触发按钮上时，关闭那一刻焦点仍在
   * 即将隐藏的子树里，浏览器会拒绝 aria-hidden=true（"descendant retained focus"），
   * 关闭行为随之失灵。
   */
  function showDdlModal(open) {
    var id = 'ddl-execute-modal';
    var modal = document.getElementById(id);
    if (!modal) { return; }
    if (open) {
      modalOpener = document.activeElement;
      toggleModal(id, true);
      var focusable = modal.querySelector(
        'textarea, input:not([type=hidden]), select, button:not(.modal-close), [tabindex]');
      if (focusable) { focusable.focus(); }
      return;
    }
    if (modal.contains(document.activeElement)) {
      if (modalOpener && document.contains(modalOpener)) {
        modalOpener.focus();
      } else if (document.activeElement && document.activeElement.blur) {
        document.activeElement.blur();
      }
    }
    modalOpener = null;
    toggleModal(id, false);
  }

  /**
   * 源 DDL 的库名限定符写的是源库名，而语句只能跑在目标库上，所以展示前先换成
   * 目标库名。服务端在真正执行前还会强制改写一次，管理员改不动它。
   */
  function retargetDdlSql(btn) {
    var sql = btn.getAttribute('data-ddl-sql') || '';
    var src = btn.getAttribute('data-ddl-src-db') || '';
    var tgt = btn.getAttribute('data-ddl-target-db') || '';
    if (src && tgt && sql.indexOf('`' + src + '`.') >= 0) {
      sql = sql.split('`' + src + '`.').join('`' + tgt + '`.');
    }
    return sql;
  }

  /** 打开共用弹窗：填入该行的语句，按行刷新按钮状态与提示文字。 */
  function openDdlDialog(btn) {
    var box = document.getElementById('ddl-execute-sql');
    var exec = document.querySelector('[data-role="ddl-execute"]');
    var retry = document.querySelector('[data-role="ddl-retry"]');
    if (!box || !exec) {
      if (window.console) { console.warn('[jync] the DDL dialog is missing on this page'); }
      return;
    }
    var modal = document.getElementById('ddl-execute-modal');
    // 服务端渲染的前缀（context-path 感知）；弹窗缺失时退回默认部署的根路径。
    var base = (modal && modal.getAttribute('data-action-base')) || '/change-logs';
    var id = btn.getAttribute('data-ddl-id') || '';
    var sql = retargetDdlSql(btn);
    if (!sql) {
      var src = document.getElementById('ddl-sql-' + id);
      if (src) { sql = src.textContent || ''; }
    }
    box.value = sql;
    // 赋值不会触发 input：手动派发一次，SQL 高亮的垫层才会同步（否则预览是空的）。
    try { box.dispatchEvent(new Event('input', { bubbles: true })); } catch (e) { /* 旧浏览器忽略 */ }
    // 已执行过目标 DDL 就不必再执行；目标还没对齐时无从重放；已补齐的数据也不必再重放。
    var executed = btn.getAttribute('data-ddl-executed') === 'true';
    var replayed = btn.getAttribute('data-ddl-replayed') === 'true';
    // 已关闭（已执行或已处理）的任务服务端一律拒绝执行：按钮不能看起来可用。
    exec.disabled = executed || replayed;
    exec.setAttribute('data-action', base + '/' + id + '/execute');
    if (retry) {
      // 目标库还没执行这条 DDL，重放无从谈起（服务端也会拒绝）：执行之后才可用。
      retry.disabled = !executed || replayed;
      retry.setAttribute('data-action', base + '/' + id + '/retry');
      retry.setAttribute('data-confirm-text', btn.getAttribute('data-ddl-confirm') || '');
      retry.setAttribute('title', executed ? '' : (btn.getAttribute('data-ddl-needs-ddl') || ''));
    }
    var state = document.getElementById('ddl-execute-state');
    if (state) {
      state.textContent = replayed ? (btn.getAttribute('data-ddl-text-processed') || '')
          : (executed ? (btn.getAttribute('data-ddl-text-executed') || '')
          : (btn.getAttribute('data-ddl-text-pending') || ''));
    }
    var dismiss = document.querySelector('[data-role="ddl-dismiss"]');
    if (dismiss) {
      // A processed task has nothing left to close; an executed-but-not-replayed one does.
      dismiss.disabled = replayed;
      dismiss.setAttribute('data-action', base + '/' + id + '/dismiss');
      dismiss.setAttribute('data-confirm-text',
        btn.getAttribute('data-ddl-dismiss-confirm') || '');
    }
    var err = document.getElementById('ddl-execute-error');
    if (err) { err.textContent = btn.getAttribute('data-ddl-error') || ''; }
    var gap = document.getElementById('ddl-execute-gap');
    if (gap) { gap.textContent = btn.getAttribute('data-ddl-gap') || ''; }
    showDdlModal(true);
  }

  /** 统一处理服务端回话：成功则原地刷新列表，失败把原因留在弹窗里。 */
  function afterResult(btn, payload) {
    btn.disabled = false;
    var ok = !!(payload && payload.success);
    var text = (payload && payload.message) || 'Request failed';
    if (ok) {
      if (dialog.alert) {
        dialog.alert(text, 'info', function () { window.location.reload(); });
      } else {
        window.location.reload();
      }
      return;
    }
    var box = document.getElementById('ddl-execute-error');
    if (box) { box.textContent = text; }
  }

  /** 把失败原因留在弹窗里，不打断当前操作。 */
  function showMessage(text) {
    var box = document.getElementById('ddl-execute-error');
    if (box) { box.textContent = text; }
  }

  /**
   * 执行 DDL 的回话。
   *
   * <p>还需要补齐时**不关弹窗、不刷新**：把按钮就地切换成「执行已禁用 / 重试可用」，
   * 管理员可以接着点【重试补齐】，不必回到列表重新打开。没有缺口（纯 DROP）才提示
   * 并刷新，让行直接变成「已处理」。
   */
  function afterExecution(btn, payload) {
    btn.disabled = false;
    var text = (payload && payload.message) || 'Request failed';
    if (!payload || !payload.success) {
      showMessage(text);
      return;
    }
    if (!payload.needsReplay) {
      if (dialog.alert) {
        dialog.alert(text, 'info', function () { window.location.reload(); });
      } else {
        window.location.reload();
      }
      return;
    }
    var exec = document.querySelector('[data-role="ddl-execute"]');
    var retry = document.querySelector('[data-role="ddl-retry"]');
    var openerTag = modalOpener;
    if (exec) { exec.disabled = true; }
    if (retry) {
      retry.disabled = false;
      retry.setAttribute('title', '');
    }
    var state = document.getElementById('ddl-execute-state');
    if (state && openerTag) {
      state.textContent = openerTag.getAttribute('data-ddl-text-executed') || '';
    }
    var gap = document.getElementById('ddl-execute-gap');
    if (gap) { gap.textContent = payload.affectedTables || ''; }
    if (toast) { toast(text, 'ok'); }
  }

  function bindExecution(btn) {
    btn.addEventListener('click', function () {
      var box = document.getElementById('ddl-execute-sql');
      if (!box || !btn.dataset.action) { return; }
      var form = new FormData();
      form.append('sql', box.value);
      btn.disabled = true;
      var err = document.getElementById('ddl-execute-error');
      if (err) { err.textContent = ''; }
      postJson(btn.dataset.action, form).then(function (payload) {
        afterExecution(btn, payload);
      }).catch(function () {
        afterExecution(btn, { success: false, message: 'Request failed' });
      });
    });
  }

  /**
   * 重放 DDL 缺口：把受影响的表从源库重读并 upsert 覆盖到目标库。表可能很大，
   * 所以先二次确认；目标库不会被清空。
   */
  function bindRetry(btn) {
    btn.addEventListener('click', function () {
      if (!btn.dataset.action) { return; }
      var run = function () {
        btn.disabled = true;
        postJson(btn.dataset.action, new FormData()).then(function (payload) {
          afterResult(btn, payload);
        }).catch(function () {
          afterResult(btn, { success: false, message: 'Request failed' });
        });
      };
      var confirmText = btn.getAttribute('data-confirm-text');
      if (confirmText && dialog.confirm) {
        dialog.confirm(confirmText, run);
      } else {
        run();
      }
    });
  }

  /**
   * 直接关闭待办：不执行 DDL，也不重放数据。目标端可能由管理员手工对齐，或这些行
   * 已不再重要；没有这个出口，待办永远开着，列投影也就永远关不掉。
   */
  function bindDismiss(btn) {
    btn.addEventListener('click', function () {
      if (!btn.dataset.action) { return; }
      var run = function () {
        btn.disabled = true;
        postJson(btn.dataset.action, new FormData()).then(function (payload) {
          afterResult(btn, payload);
        }).catch(function () {
          afterResult(btn, { success: false, message: 'Request failed' });
        });
      };
      var confirmText = btn.getAttribute('data-confirm-text');
      if (confirmText && dialog.confirm) {
        dialog.confirm(confirmText, run);
      } else {
        run();
      }
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-role="ddl-execute"]').forEach(bindExecution);
    document.querySelectorAll('[data-role="ddl-retry"]').forEach(bindRetry);
    document.querySelectorAll('[data-role="ddl-dismiss"]').forEach(bindDismiss);
    // 事件委托：行是服务端渲染的，列表局部刷新后新加的 tag 同样能打开弹窗。
    document.addEventListener('click', function (e) {
      var btn = e.target && e.target.closest ? e.target.closest('[data-role="ddl-open"]') : null;
      if (btn) { openDdlDialog(btn); }
    });
    // 关闭不用通用的 data-close-modal：通用逻辑会先设 aria-hidden，那时焦点还在弹窗里，
    // 浏览器会拒绝它。这一页自己接管关闭，先把焦点移出来。
    document.addEventListener('click', function (e) {
      var el = e.target && e.target.closest ? e.target.closest('[data-role="ddl-close"]') : null;
      if (el) { showDdlModal(false); }
    });
    // Esc 在捕获阶段处理：先于 app.js 的通用 Esc（冒泡阶段）把焦点移出弹窗。
    document.addEventListener('keydown', function (e) {
      if (e.key !== 'Escape') { return; }
      var modal = document.getElementById('ddl-execute-modal');
      if (modal && modal.classList.contains('open')) { showDdlModal(false); }
    }, true);
  });
})();
