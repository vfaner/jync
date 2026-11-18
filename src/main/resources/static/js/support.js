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
    openForm: root.getAttribute('data-label-open') || 'Open form',
    restore: root.getAttribute('data-label-restore') || 'Restore',
    greeting: root.getAttribute('data-label-greeting') || '',
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
  var unusable = false;

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
    if (max) {
      // 最大化由样式表的 inset 接管；内联 left/top/size 会压过它，先清掉（位置与
      // 尺寸留在会话状态里）。
      panel.style.left = '';
      panel.style.top = '';
      panel.style.right = '';
      panel.style.bottom = '';
      panel.style.width = '';
      panel.style.height = '';
    } else {
      var s = state();
      if (s.w && s.h) {
        panel.style.width = s.w + 'px';
        panel.style.height = s.h + 'px';
      }
      if (s.x != null && s.y != null) place(s.x, s.y);
    }
    setMaxLabel(max);
    save({ max: max });
  }

  function appendBubble(role, textToShow, markdown) {
    var el = document.createElement('div');
    el.className = 'support-msg support-' + role;
    if (markdown) {
      el.classList.add('support-md-host');
      el.innerHTML = mdToHtml(textToShow || '');
    } else {
      el.textContent = textToShow || '';
    }
    log.appendChild(el);
    log.scrollTop = log.scrollHeight;
    return el;
  }

  // —— 轻量 Markdown 渲染 ——
  // 安全边界：先把整段文本做 HTML 转义，再只生成白名单标签（p/strong/em/code/
  // pre/ul/ol/li/table/a），模型输出无法注入脚本；链接只放行 http(s) 与站内
  // 相对地址，并强制 noopener。内联代码先抽出占位，避免里面的 * 与 [ 被二次处理。
  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function inlineMd(escaped) {
    var codes = [];
    var out = escaped.replace(/`([^`\n]+)`/g, function (m, code) {
      codes.push('<code>' + code + '</code>');
      return '\u0001' + (codes.length - 1) + '\u0001';
    });
    out = out.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
    out = out.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>');
    out = out.replace(/\[([^\]\n]+)\]\(([^)\s]+)\)/g, function (m, label, url) {
      if (!/^(https?:\/\/|\/|#)/i.test(url)) return m;
      return '<a href="' + url + '" target="_blank" rel="noopener noreferrer">' + label + '</a>';
    });
    return out.replace(/\u0001(\d+)\u0001/g, function (m, n) { return codes[+n]; });
  }
  function mdTable(rows) {
    function cells(line) {
      var s = line.trim();
      if (s.charAt(0) === '|') s = s.substring(1);
      if (s.charAt(s.length - 1) === '|') s = s.substring(0, s.length - 1);
      return s.split('|');
    }
    var head = cells(rows[0]);
    var html = '<table class="support-md-table"><thead><tr>';
    head.forEach(function (cell) { html += '<th>' + inlineMd(esc(cell.trim())) + '</th>'; });
    html += '</tr></thead><tbody>';
    rows.slice(2).forEach(function (line) {
      var cols = cells(line);
      html += '<tr>';
      head.forEach(function (_, j) {
        html += '<td>' + inlineMd(esc((cols[j] || '').trim())) + '</td>';
      });
      html += '</tr>';
    });
    return html + '</tbody></table>';
  }
  function mdToHtml(src) {
    // 占位符用的控制字符先剔除，保证它只来自本函数的代码抽出。
    var lines = String(src == null ? '' : src).replace(/\u0001/g, '').split('\n');
    var html = [];
    var i = 0;
    var listRe = /^\s*(?:([-*•])|(\d+)\.)\s+(.*)$/;
    while (i < lines.length) {
      var line = lines[i];
      if (/^\s*```/.test(line)) {
        var code = [];
        i++;
        while (i < lines.length && !/^\s*```/.test(lines[i])) {
          code.push(lines[i]);
          i++;
        }
        i++;
        html.push('<pre class="support-md-pre"><code>' + esc(code.join('\n')) + '</code></pre>');
        continue;
      }
      if (/^\s*\|/.test(line) && i + 1 < lines.length && /\|/.test(lines[i + 1])
          && /^[\s|:-]+$/.test(lines[i + 1]) && lines[i + 1].indexOf('-') >= 0) {
        var rows = [];
        while (i < lines.length && /^\s*\|/.test(lines[i])) {
          rows.push(lines[i]);
          i++;
        }
        html.push(mdTable(rows));
        continue;
      }
      var item = line.match(listRe);
      if (item) {
        var ordered = !!item[2];
        var items = [];
        while (i < lines.length) {
          var next = lines[i].match(listRe);
          if (!next || !!next[2] !== ordered) break;
          items.push('<li>' + inlineMd(esc(next[3])) + '</li>');
          i++;
        }
        var tag = ordered ? 'ol' : 'ul';
        html.push('<' + tag + ' class="support-md-list">' + items.join('') + '</' + tag + '>');
        continue;
      }
      var heading = line.match(/^\s*#{1,6}\s+(.*)$/);
      if (heading) {
        html.push('<div class="support-md-h">' + inlineMd(esc(heading[1])) + '</div>');
        i++;
        continue;
      }
      if (/^\s*$/.test(line)) {
        i++;
        continue;
      }
      html.push('<p class="support-md-p">' + inlineMd(esc(line)) + '</p>');
      i++;
    }
    return html.join('');
  }

  // 首次打开（或点「新会话」后）的欢迎语；只作提示，不计入会话轮次。
  function showGreeting() {
    if (!labels.greeting || log.children.length) return;
    appendBubble('assistant', labels.greeting).classList.add('support-greeting');
  }

  // 拖动后的位置记录在 left/top，默认位置仍是右下角的 right/bottom。
  function place(x, y) {
    var w = panel.offsetWidth;
    var h = panel.offsetHeight;
    x = Math.max(0, Math.min(x, Math.max(0, window.innerWidth - w)));
    y = Math.max(0, Math.min(y, Math.max(0, window.innerHeight - h)));
    panel.style.left = x + 'px';
    panel.style.top = y + 'px';
    panel.style.right = 'auto';
    panel.style.bottom = 'auto';
  }

  function restore() {
    var s = state();
    if (s.open) open();
    if (s.w && s.h) {
      panel.style.width = s.w + 'px';
      panel.style.height = s.h + 'px';
    }
    if (s.x != null && s.y != null) place(s.x, s.y);
    // 最大化放在最后：它要清掉上面刚写入的内联位置与尺寸。
    if (s.max) toggleMax();
    (s.turns || []).forEach(function (turn) {
      appendBubble(turn.role, turn.text, turn.role === 'assistant');
    });
    if (!(s.turns || []).length) showGreeting();
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
      // 页面加载后管理员关掉了 AI：本次之内的输入框保持锁定，重载后整块不再渲染。
      unusable = true;
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
    // 服务端给的是 labelKey，界面语言由 layout 的 JyncMessages 决定。
    (data.fields || []).forEach(function (field) {
      var row = document.createElement('div');
      row.className = 'support-draft-field';
      var label = document.createElement('span');
      label.className = 'support-draft-label';
      label.textContent = window.JyncUI ? window.JyncUI.t(field.labelKey || '') : '';
      var value = document.createElement('span');
      value.textContent = field.value || '';
      row.appendChild(label);
      row.appendChild(value);
      card.appendChild(row);
    });
    if (!data.fields || !data.fields.length) {
      var summary = document.createElement('p');
      summary.textContent = data.summary || '';
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
    if (!text || busy || unusable) return;
    input.value = '';
    input.style.height = '';
    appendBubble('user', text);
    remember('user', text);
    var history = turns().slice(0, -1).map(function (turn) {
      return { role: turn.role, text: turn.text };
    });

    var bubble = appendBubble('assistant', '');
    bubble.classList.add('support-md-host', 'is-thinking');
    var thinkingEl = document.createElement('span');
    thinkingEl.textContent = labels.thinking;
    bubble.appendChild(thinkingEl);
    var answerEl = document.createElement('div');
    answerEl.className = 'support-md';
    var answer = '';
    var answered = false;
    var paintPending = false;
    function startAnswer() {
      if (answered) return;
      answered = true;
      bubble.classList.remove('is-thinking');
      if (thinkingEl.parentNode === bubble) bubble.removeChild(thinkingEl);
      bubble.appendChild(answerEl);
    }
    // 流式期间按帧合并重绘：逐 token 重排整段 Markdown 会明显掉帧。
    function paint() {
      if (paintPending) return;
      paintPending = true;
      var flush = function () {
        paintPending = false;
        answerEl.innerHTML = mdToHtml(answer);
        log.scrollTop = log.scrollHeight;
      };
      if (window.requestAnimationFrame) window.requestAnimationFrame(flush);
      else flush();
    }

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
        startAnswer();
        if (name === 'delta') {
          answer += data.text || '';
          paint();
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
      if (answer) {
        answerEl.innerHTML = mdToHtml(answer);
        remember('assistant', answer);
      }
      // 没正文也没工具进度（例如只回了错误事件）：不留空气泡。
      if (!answer && !bubble.querySelector('.support-tool') && bubble.parentNode) {
        bubble.parentNode.removeChild(bubble);
      }
      busy = false;
      sendBtn.disabled = unusable;
      input.disabled = unusable;
      if (!unusable) input.focus();
    });
  }

  // 输入框随内容长高，最多 6 行；未到上限时隐藏滚动条，避免 scrollHeight 取整
  // 比行高少 1px 就让一字输入也冒出滚动条。+2 是给亚像素行高留的冗余。
  input.addEventListener('input', function () {
    input.style.height = 'auto';
    var needed = input.scrollHeight;
    input.style.height = Math.min(needed + 2, 144) + 'px';
    input.style.overflowY = needed + 2 > 144 ? 'auto' : 'hidden';
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
  root.querySelector('[data-role="support-drag"]').addEventListener('dblclick', function (e) {
    // 双击标题空白处切换最大化；落在按钮上的双击交给按钮自己。
    if (e.target.closest && e.target.closest('button')) return;
    toggleMax();
  });
  root.querySelector('[data-role="support-new"]').addEventListener('click', function () {
    log.innerHTML = '';
    save({ turns: [], draftUsed: null });
    showGreeting();
    input.focus();
  });

  // Esc：最大化先还原，否则关闭。
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape' || panel.hidden) return;
    if (root.classList.contains('is-max')) toggleMax();
    else close();
  });

  // 标题栏拖动整个面板；落在按钮上不拖，最大化时由样式接管。
  var moving = false, moved = false;
  var moveStartX = 0, moveStartY = 0, moveOriginX = 0, moveOriginY = 0;
  var dragArea = root.querySelector('[data-role="support-drag"]');
  dragArea.addEventListener('pointerdown', function (e) {
    if (e.button !== 0 || (e.target.closest && e.target.closest('button'))) return;
    if (root.classList.contains('is-max')) return;
    var rect = panel.getBoundingClientRect();
    moving = true; moved = false;
    moveStartX = e.clientX; moveStartY = e.clientY;
    moveOriginX = rect.left; moveOriginY = rect.top;
    dragArea.setPointerCapture(e.pointerId);
  });
  dragArea.addEventListener('pointermove', function (e) {
    if (!moving) return;
    var dx = e.clientX - moveStartX;
    var dy = e.clientY - moveStartY;
    // 3px 以内视为点击，避免双击最大化时面板抖动。
    if (!moved && Math.abs(dx) + Math.abs(dy) < 3) return;
    moved = true;
    place(moveOriginX + dx, moveOriginY + dy);
  });
  dragArea.addEventListener('pointerup', function () {
    if (!moving) return;
    moving = false;
    if (moved) {
      var rect = panel.getBoundingClientRect();
      save({ x: Math.round(rect.left), y: Math.round(rect.top) });
    }
  });
  dragArea.addEventListener('pointercancel', function () { moving = false; });

  // 左上角拖拽调大小：默认右下角锚定时向左上拉伸；拖动过面板时同步移动 left/top。
  var dragging = false;
  var startX = 0, startY = 0, startW = 0, startH = 0, startLeft = 0, startTop = 0;
  resize.addEventListener('pointerdown', function (e) {
    if (e.button !== 0) return;
    dragging = true;
    startX = e.clientX; startY = e.clientY;
    var rect = panel.getBoundingClientRect();
    startW = rect.width; startH = rect.height;
    startLeft = rect.left; startTop = rect.top;
    resize.setPointerCapture(e.pointerId);
    e.preventDefault();
  });
  resize.addEventListener('pointermove', function (e) {
    if (!dragging) return;
    var w = Math.max(320, Math.min(window.innerWidth - 32, startW + (startX - e.clientX)));
    var h = Math.max(420, Math.min(window.innerHeight - 32, startH + (startY - e.clientY)));
    panel.style.width = w + 'px';
    panel.style.height = h + 'px';
    var patch = { w: w, h: h };
    if (panel.style.left) {
      place(startLeft + (startW - w), startTop + (startH - h));
      var rect = panel.getBoundingClientRect();
      patch.x = Math.round(rect.left);
      patch.y = Math.round(rect.top);
    }
    save(patch);
  });
  resize.addEventListener('pointerup', function () { dragging = false; });
  resize.addEventListener('pointercancel', function () { dragging = false; });

  restore();
})();
