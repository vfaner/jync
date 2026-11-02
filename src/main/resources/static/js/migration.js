/*
 * Dedicated-migration form helpers.
 *
 * Loaded only by migration-form.html. Everything here is progressive: with JavaScript off,
 * the textarea is still editable and every option is still submitted.
 */
(function () {
  'use strict';

  function text(node, value) {
    if (node) node.textContent = value == null ? '' : String(value);
  }

  function requestJson(url) {
    return fetch(url, { method: 'GET', headers: { 'Accept': 'application/json' } })
      .then(function (response) {
        if (response.status === 401) {
          window.location.reload();
          return { success: false, message: 'error.sessionExpired' };
        }
        return response.json().catch(function () {
          return { success: false, message: 'HTTP ' + response.status };
        });
      })
      .catch(function (err) {
        // fetch rejects when the server is unreachable; without this the caller's .then never
        // runs and the button stays disabled. Same network/other split as app.js.
        return {
          success: false,
          message: err instanceof TypeError ? 'error.network' : 'error.unexpected'
        };
      });
  }

  /* ─── Index discovery ──────────────────────────────────────────────────────────── */

  function bindDiscovery() {
    var button = document.querySelector('[data-role="discover-indices"]');
    if (!button) return;
    button.addEventListener('click', function () {
      var form = button.closest('form');
      if (!form) return;
      var select = form.querySelector('select[name="sourceConnectionId"]');
      var output = form.querySelector('textarea[name="opt.indicesText"]');
      var list = form.querySelector('[data-role="discovered-indices"]');
      if (!select || !select.value) {
        JyncUI.dialog.notify(button.dataset.chooseSource, 'warn');
        return;
      }
      var pattern = output ? output.value.trim() : '';
      var url = button.dataset.url + '?connectionId=' + encodeURIComponent(select.value)
        + '&pattern=' + encodeURIComponent(pattern);
      button.disabled = true;
      requestJson(url).then(function (payload) {
        button.disabled = false;
        if (!payload || !payload.success) {
          // JyncDialog renders the text verbatim; the API answers with an i18n key.
          JyncUI.dialog.notify(
            JyncUI.t((payload && payload.message) || 'error.unexpected'), 'danger');
          return;
        }
        var indices = payload.indices || [];
        if (!indices.length) {
          if (list) {
            list.hidden = false;
            text(list, button.dataset.empty);
          }
          return;
        }
        if (output) {
          output.value = indices.map(function (row) { return row.index; }).join(',\n');
        }
        if (list) {
          list.hidden = false;
          text(list, indices.map(function (row) {
            return row.index + ' · ' + row.docsCount + ' docs · ' + row.storeSize;
          }).join('\n'));
          list.style.whiteSpace = 'pre-line';
        }
      });
    });
  }

  /* ─── Target naming: show only the part that applies ───────────────────────────── */

  function bindTargetNaming() {
    var naming = document.getElementById('opt-targetNaming');
    if (!naming) return;
    var prefix = document.getElementById('opt-targetPrefix');
    var suffix = document.getElementById('opt-targetSuffix');
    function sync() {
      var mode = naming.value;
      var prefixField = prefix && prefix.closest('.field');
      var suffixField = suffix && suffix.closest('.field');
      if (prefixField) prefixField.hidden = mode !== 'PREFIX';
      if (suffixField) suffixField.hidden = mode !== 'SUFFIX';
    }
    naming.addEventListener('change', sync);
    sync();
  }

  /* ─── Scheduling: an enabled task needs a cron, so point at the field ──────────── */

  function bindScheduleHint() {
    var enabled = document.getElementById('enabled');
    var cron = document.getElementById('cronExpression');
    if (!enabled || !cron) return;
    enabled.addEventListener('change', function () {
      cron.required = enabled.checked;
      if (enabled.checked && !cron.value.trim()) {
        cron.focus();
      }
    });
    cron.required = enabled.checked;
  }

  function start() {
    bindDiscovery();
    bindTargetNaming();
    bindScheduleHint();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
