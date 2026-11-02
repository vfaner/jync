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
      var output = form.querySelector(
        'textarea[name="opt.indicesText"], textarea[name="opt.collectionsText"]');
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
          output.value = indices.map(function (row) { return row.name; }).join(',\n');
        }
        if (list) {
          list.hidden = false;
          text(list, indices.map(function (row) {
            return row.name + ' · ' + row.docsCount + ' docs · ' + row.storeSize;
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

  /* ─── Connection kind: show the fields that product actually uses ─────────────── */

  function bindConnectionKind() {
    var kind = document.getElementById('kind');
    if (!kind) return;
    var baseUrl = document.getElementById('baseUrl');
    var auth = document.querySelector('[data-role="migration-auth-type"]');
    var database = document.querySelector('[data-role="database-name-field"]');
    var schema = document.querySelector('[data-role="kingbase-only"]');
    var extra = document.querySelector('[data-role="extra-params-field"]');
    var tls = document.querySelector('[data-role="tls-field"]');
    // Which authentication choices each product accepts. A disabled option is not submitted,
    // so the selection falls back to the first allowed one.
    var allowed = {
      ELASTICSEARCH: ['NONE', 'BASIC', 'API_KEY'],
      OPENSEARCH: ['NONE', 'BASIC', 'API_KEY'],
      EASYSEARCH: ['NONE', 'BASIC', 'API_KEY'],
      MONGODB: ['NONE', 'BASIC'],
      KINGBASE: ['BASIC']
    };

    function show(node, visible) {
      if (node) node.hidden = !visible;
    }

    function sync() {
      var value = kind.value;
      var mongo = value === 'MONGODB';
      var kingbase = value === 'KINGBASE';
      show(database, mongo || kingbase);
      show(schema, kingbase);
      show(extra, mongo || kingbase);
      // TLS verification is an Elasticsearch-client setting; Mongo and JDBC configure it
      // through the URI/driver parameters instead.
      show(tls, !mongo && !kingbase);

      if (baseUrl) {
        var placeholder = mongo ? baseUrl.dataset.placeholderMongo
          : kingbase ? baseUrl.dataset.placeholderKingbase
            : baseUrl.dataset.placeholderEs;
        if (placeholder) baseUrl.placeholder = placeholder;
      }

      if (auth) {
        var choices = allowed[value] || allowed.ELASTICSEARCH;
        for (var i = 0; i < auth.options.length; i++) {
          auth.options[i].disabled = choices.indexOf(auth.options[i].value) < 0;
        }
        var selected = auth.options[auth.selectedIndex];
        if (selected && selected.disabled) auth.value = choices[0];
      }
    }

    kind.addEventListener('change', sync);
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
    bindConnectionKind();
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
