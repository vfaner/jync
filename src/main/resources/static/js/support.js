/* AI 运维助手悬浮窗：SSE 流式回答、工具进度、草稿卡与四种窗口状态。
 *
 * 复用边界：app.js 的 JyncUI.readSse 只认识 delta/done 两种事件，而这里还要消费
 * tool/draft/error，所以解析器自带一份；文案、工具名与提交地址全部来自片段里的
 * data-*，本文件不出现界面文字。
 */
(function () {
  'use strict';

  var root = document.querySelector('.support-widget');
  if (!root) return;

  var STORE_KEY = 'jync.support';
  var MAX_TURNS = 20;
  var labels = {
    thinking: root.getAttribute('data-label-thinking') || '…',
    timeout: root.getAttribute('data-label-timeout') || 'Timed out',
    disabled: root.getAttribute('data-label-disabled') || 'AI is off',
    busy: root.getAttribute('data-label-busy') || 'Still answering',
    used: root.getAttribute('data-label-used') || 'Opened',
    expired: root.getAttribute('data-label-expired') || 'Draft expired',
    openForm: root.getAttribute('data-label-open') || 'Open form',
    send: root.getAttribute('data-label-send') || 'Send',
    restore: root.getAttribute('data-label-restore') || 'Restore',
    network: root.getAttribute('data-label-network') || 'Network error'
  };

  var fab = root.querySelector('[data-role="support-toggle"]');
  var panel = root.querySelector('[data-role="support-panel"]');
  var log = root.querySelector('[data-role="support-log"]');
  var form = root.querySelector('[data-role="support-form"]');
  var input = root.querySelector('[data-role="support-input"]');
  var maxBtn = root.querySelector('[data-role="support-max"]');
  var resize = root.querySelector('[data-role="support-resize"]');
  var sendBtn = root.querySelector('[data-role="support-send"]');
  var busy = false;

  // 工具名是 snake_case，property 名会把 data-tool- 后的连字符大写，所以按属性名查。
  function toolLabel(name) {
    return root.getAttribute('data-tool-' + name) || name;
  }

  function state() {
    try { return JSON.parse(sessionStorage.getItem(STORE_KEY)) || {}; }
    catch (e) { return {}; }
  }
  function save(patch) {
    var next = Object.assign(state(), patch);
    try { sessionStorage.setItem(STORE_KEY, JSON.stringify(next)); } catch (e) { /* 隐私模式 */ }
  }

  function open() {
    panel.hidden = false;
    root.classList.add('is-open');
    input.focus();
    save({ open: true });
  }
  function close() {
    panel.hidden = true;
    root.classList.remove('is-open', 'is-max');
    setMaxLabel(false);
    save({ open: false, max: false });
  }
  function setMaxLabel(max) {
    var use = maxBtn.querySelector('use');
    if (use) use.setAttribute('href', max ? '#arrows-angle-contract' : '#arrows-angle-expand');
    var label = max ? labels.restore : maxBtn.getAttribute('data-label-max');
    if (label) {
      maxBtn.setAttribute('title', label);
      maxBtn.setAttribute('aria-label', label);
    }
  }
  function toggleMax() {
    var max = !root.classList.contains('is-max');
    root.classList.toggle('is-max', max);
    setMaxLabel(max);
    save({ max: max });
  }

  function appendBubble(role, textToShow) {
    var el = document.createElement('div');
    el.className = 'support-msg support-' + role;
    el.textContent = textToShow || '';
    log.appendChild(el);
    log.scrollTop = log.scrollHeight;
    return el;
  }

  function restore() {
    var s = state();
    if (s.open) open();
    if (s.max) toggleMax();
    if (s.w && s.h) {
      panel.style.width = s.w + 'px';
      panel.style.height = s.h + 'px';
    }
    (s.turns || []).forEach(function (turn) {
      appendBubble(turn.role, turn.text);
    });
    if (s.draftUsed) markDraftUsed(s.draftUsed);
  }

  function turns() {
    return (state().turns || []).slice(-MAX_TURNS);
  }

  function remember(role, text) {
    var all = turns();
    all.push({ role: role, text: text });
    save({ turns: all.slice(-MAX_TURNS) });
  }

  function showError(key) {
    var el = document.createElement('div');
    el.className = 'support-msg support-error';
    var text = key === 'error.support.busy' ? labels.busy
      : key === 'error.support.timeout' ? labels.timeout
      : key === 'error.ai.disabled' ? labels.disabled
      : key === 'error.network' ? labels.network
      : (window.JyncUI ? window.JyncUI.t(key) : key);
    el.textContent = text;
    log.appendChild(el);
    log.scrollTop = log.scrollHeight;
    if (key === 'error.ai.disabled') {
      input.disabled = true;
      sendBtn.disabled = true;
    }
  }

  function showDraft(data) {
    var card = document.createElement('div');
    card.className = 'support-draft';
    var title = document.createElement('strong');
    title.textContent = data.title || '';
    card.appendChild(title);
    if (data.summary) {
      var summary = document.createElement('p');
      summary.textContent = data.summary;
      card.appendChild(summary);
    }
    var link = document.createElement('a');
    link.className = 'btn btn-primary btn-sm';
    link.href = (root.getAttribute('data-draft-url') || '/projects/new')
      + '?draft=' + encodeURIComponent(data.token || '');
    link.textContent = labels.openForm;
    link.addEventListener('click', function () {
      save({ draftUsed: data.token });
      link.textContent = labels.used;
      link.removeAttribute('href');
    });
    card.appendChild(link);
    log.appendChild(card);
    log.scrollTop = log.scrollHeight;
  }

  function markDraftUsed(token) {
    var links = log.querySelectorAll('.support-draft a');
    for (var i = 0; i < links.length; i++) {
      var href = links[i].getAttribute('href') || '';
      if (href.indexOf(token) >= 0) {
        links[i].textContent = labels.used;
        links[i].removeAttribute('href');
      }
    }
  }

  // SSE 解析：按空行分帧，event: 与 data: 各一行；坏数据静默跳过。
  function consume(response, onEvent) {
    var reader = response.body.getReader();
    var decoder = new TextDecoder('utf-8');
    var buffer = '';
    var event = null;
    function line(raw) {
      if (raw === '') { event = null; return; }
      if (raw.indexOf('event:') === 0) { event = raw.substring(6).trim(); return; }
      if (raw.indexOf('data:') !== 0) return;
      var data = raw.substring(5).trim();
      if (!data) return;
      try { onEvent(event, JSON.parse(data)); } catch (e) { /* 半行/坏数据 */ }
    }
    function pump() {
      return reader.read().then(function (result) {
        if (result.done) {
          if (buffer) line(buffer);
          return;
        }
        buffer += decoder.decode(result.value, { stream: true });
        var idx;
        while ((idx = buffer.indexOf('\n')) >= 0) {
          line(buffer.substring(0, idx).replace(/\r$/, ''));
          buffer = buffer.substring(idx + 1);
        }
        return pump();
      });
    }
    return pump();
  }

  function submit() {
    var text = (input.value || '').trim();
    if (!text || busy) return;
    input.value = '';
    input.style.height = '';
    appendBubble('user', text);
    remember('user', text);
    var history = turns().slice(0, -1).map(function (turn) {
      return { role: turn.role, text: turn.text };
    });

    var bubble = appendBubble('assistant', '');
    bubble.classList.add('is-thinking');
    bubble.textContent = labels.thinking;
    var answer = '';

    busy = true;
    sendBtn.disabled = true;
    input.disabled = true;

    var csrf = document.querySelector('meta[name="_csrf"]');
    var csrfHeader = document.querySelector('meta[name="_csrf_header"]');
    var headers = { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' };
    if (csrf && csrfHeader) headers[csrfHeader.content] = csrf.content;

    fetch(root.getAttribute('data-action'), {
      method: 'POST', headers: headers, credentials: 'same-origin',
      body: JSON.stringify({ message: text, history: history })
    }).then(function (response) {
      if (response.status === 409) {
        showError('error.support.busy');
        return null;
      }
      if (!response.ok) {
        showError('error.network');
        return null;
      }
      if (!response.body) {
        return response.json().then(function (data) { showError(data.messageKey); });
      }
      return consume(response, function (name, data) {
        if (bubble.classList.contains('is-thinking')) {
          bubble.classList.remove('is-thinking');
          bubble.textContent = '';
        }
        if (name === 'delta') {
          answer += data.text || '';
          bubble.textContent = answer;
          log.scrollTop = log.scrollHeight;
        } else if (name === 'tool') {
          var toolLine = document.createElement('div');
          toolLine.className = 'support-tool';
          toolLine.textContent = '⏳ ' + toolLabel(data.name || '');
          bubble.appendChild(toolLine);
          log.scrollTop = log.scrollHeight;
        } else if (name === 'draft') {
          showDraft(data);
        } else if (name === 'error') {
          showError(data.messageKey || 'error.unexpected');
        }
      });
    }).catch(function () {
      showError('error.network');
    }).finally(function () {
      bubble.classList.remove('is-thinking');
      if (!answer && bubble.textContent === labels.thinking) {
        bubble.textContent = '';
      }
      if (answer) remember('assistant', answer);
      busy = false;
      sendBtn.disabled = false;
      input.disabled = false;
      input.focus();
    });
  }

  // 输入框随内容长高，最多 6 行。
  input.addEventListener('input', function () {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 144) + 'px';
  });
  input.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  });
  form.addEventListener('submit', function (e) { e.preventDefault(); submit(); });
  fab.addEventListener('click', open);
  root.querySelector('[data-role="support-close"]').addEventListener('click', close);
  maxBtn.setAttribute('data-label-max', maxBtn.getAttribute('aria-label') || '');
  maxBtn.addEventListener('click', toggleMax);
  root.querySelector('[data-role="support-drag"]').addEventListener('dblclick', toggleMax);
  root.querySelector('[data-role="support-new"]').addEventListener('click', function () {
    log.innerHTML = '';
    save({ turns: [], draftUsed: null });
    input.focus();
  });

  // Esc：最大化先还原，否则关闭。
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape' || panel.hidden) return;
    if (root.classList.contains('is-max')) toggleMax();
    else close();
  });

  // 左上角拖拽调大小（面板贴右下，向左上拉伸）。
  var dragging = false;
  var startX = 0, startY = 0, startW = 0, startH = 0;
  resize.addEventListener('pointerdown', function (e) {
    dragging = true;
    startX = e.clientX; startY = e.clientY;
    startW = panel.offsetWidth; startH = panel.offsetHeight;
    resize.setPointerCapture(e.pointerId);
    e.preventDefault();
  });
  resize.addEventListener('pointermove', function (e) {
    if (!dragging) return;
    var w = Math.max(320, Math.min(window.innerWidth - 32, startW + (startX - e.clientX)));
    var h = Math.max(420, Math.min(window.innerHeight - 32, startH + (startY - e.clientY)));
    panel.style.width = w + 'px';
    panel.style.height = h + 'px';
    save({ w: w, h: h });
  });
  resize.addEventListener('pointerup', function () { dragging = false; });
  resize.addEventListener('pointercancel', function () { dragging = false; });

  restore();
})();
