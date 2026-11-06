/*
 * 系统配置页的元数据库测试器。
 *
 * 表单本身是普通的 POST；脚本接管提交，把四个字段以 JSON 发给 /system-config/db-test，
 * 再把「产品/版本 + 可直接粘贴的环境变量块」渲染到 #metadata-db-test-result。
 * 没有脚本时表单仍会提交，只是看到的是 JSON —— 不阻塞任何操作。
 *
 * 密码只出现在请求体与结果面板里，不写 console、不落 localStorage。
 */
(function () {
  'use strict';

  var form = document.getElementById('metadata-db-test-form');
  var panel = document.getElementById('metadata-db-test-result');
  if (!form || !panel) return;

  function t(key) {
    if (!key) return '';
    return window.JyncUI && JyncUI.t ? JyncUI.t(key) : key;
  }

  /** The API answers with an i18n key for its own refusals, and with driver text otherwise. */
  function message(payload) {
    var raw = (payload && payload.message) || 'error.dbTest.failed';
    // A key the map does not know comes back unchanged, which is exactly what driver text needs.
    return t(raw);
  }

  function post(payload) {
    var headers = { 'Accept': 'application/json', 'Content-Type': 'application/json' };
    var token = document.querySelector('meta[name="_csrf"]');
    var header = document.querySelector('meta[name="_csrf_header"]');
    if (token && header && token.content && header.content) {
      headers[header.content] = token.content;
    }
    return fetch(form.getAttribute('action') || '/system-config/db-test', {
      method: 'POST',
      headers: headers,
      body: JSON.stringify(payload)
    }).then(function (response) {
      if (response.status === 401) {
        window.location.reload();
        return null;
      }
      return response.json().catch(function () {
        return { success: false, message: 'HTTP ' + response.status };
      });
    }).catch(function (err) {
      return {
        success: false,
        message: err instanceof TypeError ? 'error.network' : 'error.dbTest.failed'
      };
    });
  }

  function label(name, fallback) {
    var value = panel.getAttribute('data-' + name);
    return value == null || value === '' ? fallback : value;
  }

  function appendText(parent, tag, text, className) {
    var node = document.createElement(tag);
    node.textContent = text;
    if (className) node.className = className;
    parent.appendChild(node);
    return node;
  }

  function renderResult(payload) {
    panel.textContent = '';
    panel.hidden = false;
    panel.className = 'small ' + (payload && payload.success ? 'alert alert-ok' : 'alert alert-warn');

    appendText(panel, 'p', message(payload));

    if (!payload || !payload.success) return;

    var meta = label('product', 'Product') + ': ' + (payload.product || '-')
      + ' · ' + label('version', 'Version') + ': ' + (payload.version || '-');
    appendText(panel, 'p', meta, 'soft');

    if (payload.envBlock) {
      appendText(panel, 'p', label('env', 'Startup environment'), 'soft');
      appendText(panel, 'pre', payload.envBlock, 'code-block');
    }
  }

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    var button = form.querySelector('button[type="submit"]');
    if (button) button.disabled = true;

    // 结果面板先清空：上一次的密码与环境块不应停留在屏幕上等待新结果。
    panel.hidden = false;
    panel.textContent = '…';

    post({
      url: (form.querySelector('[name="url"]') || {}).value || '',
      driverClass: (form.querySelector('[name="driverClass"]') || {}).value || '',
      username: (form.querySelector('[name="username"]') || {}).value || '',
      password: (form.querySelector('[name="password"]') || {}).value || ''
    }).then(function (payload) {
      if (button) button.disabled = false;
      if (payload) renderResult(payload);
    });
  });
})();
