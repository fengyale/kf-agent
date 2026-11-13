/* 对话式首页：会话管理 + SSE 意图路由（调研任务卡片 / 流式问答）+ 任务状态轮询 */
(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const el = {
    convList: $('conv-list'), msgs: $('msgs'), inner: $('msgs-inner'),
    input: $('input'), btnSend: $('btn-send'), btnNew: $('btn-new'),
    convTitle: $('conv-title'), convMeta: $('conv-meta'),
  };

  let convId = localStorage.getItem('chat.convId') || null;
  let conversations = [];
  let streaming = false;
  const watching = new Set();       // 需要轮询状态的任务卡片
  const taskCache = new Map();      // taskId -> task
  let pollTimer = null;

  const SUGS = [
    { icon: '🎯', k: '深度调研', v: '调研 2026 年 Java 工程师转型 AI 应用开发的岗位要求与技能趋势' },
    { icon: '📊', k: '市场分析', v: '分析国产大模型 API 的价格与性能对比' },
    { icon: '⚖️', k: '技术对比', v: '对比 LangChain4j 与 Spring AI 在企业 RAG 落地中的优劣' },
    { icon: '💬', k: '随便聊聊', v: '用一段话介绍你自己能做什么' },
  ];

  // ---------- 工具 ----------
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function inlineMd(s) {
    s = esc(s);
    s = s.replace(/`([^`]+)`/g, '<code>$1</code>');
    s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    return s;
  }

  function md(src) {
    if (!src) return '';
    const lines = src.split('\n');
    const html = []; let inCode = false, inList = null, para = [];
    const flushPara = () => { if (para.length) { html.push('<p>' + inlineMd(para.join('\n')) + '</p>'); para = []; } };
    const closeList = () => { if (inList) { html.push('</' + inList + '>'); inList = null; } };
    for (const raw of lines) {
      const line = raw.replace(/\s+$/, '');
      if (line.startsWith('```')) {
        flushPara(); closeList();
        if (!inCode) { html.push('<pre><code>'); inCode = true; }
        else { html.push('</code></pre>'); inCode = false; }
        continue;
      }
      if (inCode) { html.push(esc(raw)); continue; }
      const h = line.match(/^(#{1,4})\s+(.*)/);
      if (h) { flushPara(); closeList(); html.push('<h' + Math.min(h[1].length, 3) + '>' + inlineMd(h[2]) + '</h' + Math.min(h[1].length, 3) + '>'); continue; }
      const ul = line.match(/^\s*[-*]\s+(.*)/);
      const ol = line.match(/^\s*\d+[.、]\s*(.*)/);
      if (ul || ol) {
        flushPara();
        const type = ul ? 'ul' : 'ol';
        if (inList !== type) { closeList(); html.push('<' + type + '>'); inList = type; }
        html.push('<li>' + inlineMd((ul || ol)[1]) + '</li>');
        continue;
      }
      if (/^\s*>\s?/.test(line)) { flushPara(); closeList(); html.push('<blockquote>' + inlineMd(line.replace(/^\s*>\s?/, '')) + '</blockquote>'); continue; }
      if (!line.trim()) { flushPara(); closeList(); continue; }
      para.push(line);
    }
    flushPara(); closeList();
    if (inCode) html.push('</code></pre>');
    return html.join('\n');
  }

  function scrollBottom() { el.msgs.scrollTop = el.msgs.scrollHeight; }

  function fmtTime(s) {
    if (!s) return '';
    return String(s).replace(/^\d{4}-/, '').slice(0, 11);
  }

  function fmtCost(ms) {
    if (!ms) return '';
    const sec = Math.round(ms / 1000);
    return sec >= 60 ? Math.floor(sec / 60) + '分' + (sec % 60) + '秒' : sec + '秒';
  }

  // ---------- 欢迎空状态 ----------
  function renderWelcome() {
    el.inner.innerHTML =
      '<div class="welcome">'
      + '<div class="big-logo">🔍</div>'
      + '<h1>AI 调研助手</h1>'
      + '<div class="sub">和我聊天，或直接发送一个调研主题——<br>我会自动启动多轮深度搜索，生成带引用的调研报告，全程可监控。</div>'
      + '<div class="sugs">'
      + SUGS.map((s, i) =>
        '<div class="sug" data-i="' + i + '"><div class="k">' + s.icon + ' ' + s.k + '</div><div class="v">' + esc(s.v) + '</div></div>').join('')
      + '</div></div>';
    el.inner.querySelectorAll('.sug').forEach((n) => {
      n.addEventListener('click', () => {
        el.input.value = SUGS[+n.dataset.i].v;
        el.input.focus();
        autoGrow();
      });
    });
    el.convTitle.textContent = '新对话';
    el.convMeta.textContent = '';
  }

  // ---------- 会话列表 ----------
  async function loadConversations() {
    try {
      const list = await (await fetch('/api/chat/conversations')).json();
      if (Array.isArray(list)) conversations = list;
    } catch (e) { /* 网络失败保留旧列表 */ }
    renderConvList();
  }

  function renderConvList() {
    if (!conversations.length) {
      el.convList.innerHTML = '<div class="sec" style="text-align:center;padding:18px 0;color:var(--faint);font-size:11px">暂无历史对话</div>';
      return;
    }
    el.convList.innerHTML = conversations.map((c) =>
      '<div class="conv-item' + (c.id === convId ? ' active' : '') + '" data-id="' + esc(c.id) + '">'
      + '<div class="t">' + esc(c.title || '新对话') + '</div>'
      + '<div class="m"><span>' + (c.message_count || 0) + ' 条</span><span>' + esc(fmtTime(c.updated_at)) + '</span></div>'
      + '<button class="del" title="删除会话" data-del="' + esc(c.id) + '">✕</button>'
      + '</div>').join('');

    el.convList.querySelectorAll('.conv-item').forEach((n) => {
      n.addEventListener('click', (ev) => {
        if (ev.target.dataset.del) return;
        if (n.dataset.id !== convId && !streaming) openConversation(n.dataset.id);
      });
    });
    el.convList.querySelectorAll('.del').forEach((b) => {
      b.addEventListener('click', (ev) => { ev.stopPropagation(); deleteConversation(b.dataset.del); });
    });
  }

  async function newConversation() {
    if (streaming) return;
    try {
      const r = await (await fetch('/api/chat/conversations', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).json();
      convId = r.id;
      localStorage.setItem('chat.convId', convId);
      watching.clear(); stopPolling();
      conversations.unshift({ id: convId, title: '新对话', message_count: 0, updated_at: '' });
      renderConvList();
      renderWelcome();
      el.input.focus();
    } catch (e) { alert('创建会话失败：' + e.message); }
  }

  async function deleteConversation(id) {
    if (!confirm('删除该对话？消息记录不可恢复。')) return;
    try { await fetch('/api/chat/conversations/' + id, { method: 'DELETE' }); } catch (e) { /* 忽略 */ }
    conversations = conversations.filter((c) => c.id !== id);
    if (id === convId) {
      convId = null;
      localStorage.removeItem('chat.convId');
      watching.clear(); stopPolling();
      renderWelcome();
    }
    renderConvList();
  }

  // ---------- 历史消息 ----------
  async function openConversation(id) {
    convId = id;
    localStorage.setItem('chat.convId', id);
    watching.clear(); stopPolling();
    renderConvList();
    el.inner.innerHTML = '<div class="thinking-dots" style="margin:20px 0"><i></i><i></i><i></i></div>';
    try {
      // 消息与任务列表并行请求（此前串行是切换会话慢的主因）
      const [list] = await Promise.all([
        fetch('/api/chat/conversations/' + id + '/messages').then((r) => r.json()),
        refreshTaskCache().catch(() => {}),
      ]);
      el.inner.innerHTML = '';
      const conv = conversations.find((c) => c.id === id);
      el.convTitle.textContent = (conv && conv.title) || '对话';
      el.convMeta.textContent = list.length ? list.length + ' 条消息' : '';
      if (!list.length) { renderWelcome(); return; }

      for (const m of list) {
        if (m.role === 'user') appendUserMsg(m.content);
        else if (m.task_id) {
          const t = taskCache.get(m.task_id);
          const topic = (t && t.topic) || extractTopic(m.content) || '调研任务';
          const card = appendTaskCard(m.task_id, topic);
          // 缓存里已有任务状态则立即渲染终态/进度，不等轮询周期
          if (t) applyTaskState(card, t);
          if (!t || t.status === 'running') watchTask(m.task_id);
        } else if (m.content) {
          appendAssistantBubble().querySelector('.md').innerHTML = md(m.content);
        }
      }
      scrollBottom();
    } catch (e) {
      el.inner.innerHTML = '<div class="msg-error">加载失败：' + esc(e.message) + '</div>';
    }
  }

  function extractTopic(content) {
    const m = String(content || '').match(/[「『]([^」』]{2,60})[」』]/);
    return m ? m[1] : null;
  }

  // ---------- 消息渲染 ----------
  function appendUserMsg(text) {
    const div = document.createElement('div');
    div.className = 'msg user';
    div.innerHTML = '<div class="avatar">🧑</div><div class="body"></div>';
    div.querySelector('.body').textContent = text;
    el.inner.appendChild(div);
    scrollBottom();
    return div;
  }

  function appendAssistantBubble() {
    const div = document.createElement('div');
    div.className = 'msg assistant';
    div.innerHTML = '<div class="avatar">🔍</div><div class="body"><div class="md"></div></div>';
    el.inner.appendChild(div);
    scrollBottom();
    return div;
  }

  function appendIntentTag(type) {
    const div = document.createElement('div');
    div.className = 'msg assistant';
    div.innerHTML = '<div class="avatar">🔍</div><div class="body">'
      + (type === 'research'
        ? '<div class="intent-tag">🎯 已识别调研意图 · 正在启动深度搜索任务</div>'
        : '<div class="intent-tag">💬 普通问答</div>')
      + '</div>';
    el.inner.appendChild(div);
    scrollBottom();
    return div;
  }

  function appendError(text) {
    const div = document.createElement('div');
    div.className = 'msg assistant';
    div.innerHTML = '<div class="avatar">🔍</div><div class="body"><div class="msg-error"></div></div>';
    div.querySelector('.msg-error').textContent = '出错了：' + (text || '未知错误');
    el.inner.appendChild(div);
    scrollBottom();
    return div;
  }

  function appendTaskCard(taskId, topic) {
    const div = document.createElement('div');
    div.className = 'msg assistant';
    div.innerHTML = '<div class="avatar">🔍</div><div class="body">'
      + '<div class="task-card running" data-task="' + esc(taskId) + '">'
      + '<div class="tc-head"><div class="tc-topic">' + esc(topic) + '</div><span class="tc-status running">运行中</span></div>'
      + '<div class="tc-progress"><span>准备中…</span></div>'
      + '<div class="tc-bar"><i></i></div>'
      + '<div class="tc-actions">'
      + '<a class="tc-btn" href="/monitor/' + encodeURIComponent(taskId) + '" target="_blank" rel="noopener">查看工作流监控 ↗</a>'
      + '</div></div></div>';
    el.inner.appendChild(div);
    scrollBottom();
    return div.querySelector('.task-card');
  }

  // ---------- 任务状态轮询 ----------
  async function refreshTaskCache() {
    const list = await (await fetch('/api/research')).json();
    if (Array.isArray(list)) list.forEach((t) => taskCache.set(t.id, t));
  }

  function watchTask(taskId) {
    watching.add(taskId);
    if (!pollTimer) pollTimer = setInterval(pollTasks, 5000);
    pollTasks(); // 立即刷一次，不等首个 5 秒周期
  }

  function stopPolling() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  }

  async function pollTasks() {
    if (!watching.size) { stopPolling(); return; }
    try { await refreshTaskCache(); } catch (e) { return; }
    watching.forEach((id) => {
      const card = el.inner.querySelector('.task-card[data-task="' + id + '"]');
      if (!card) { watching.delete(id); return; }
      const t = taskCache.get(id);
      if (t) applyTaskState(card, t);
      if (t && t.status !== 'running') watching.delete(id);
    });
    if (!watching.size) stopPolling();
  }

  const STATUS_TEXT = { running: '运行中', done: '已完成', failed: '失败', cancelled: '已终止' };

  function applyTaskState(card, t) {
    const st = t.status || 'running';
    card.className = 'task-card ' + st;
    card.querySelector('.tc-status').className = 'tc-status ' + st;
    card.querySelector('.tc-status').textContent = STATUS_TEXT[st] || st;

    const meta = [];
    if (t.rounds) meta.push(t.rounds + ' 轮搜索');
    if (t.search_count) meta.push(t.search_count + ' 次检索');
    if (t.total_cost_ms) meta.push('用时 ' + fmtCost(t.total_cost_ms));
    if (st === 'running' && !meta.length) meta.push('搜索中…');
    card.querySelector('.tc-progress').innerHTML = '<span>' + meta.map(esc).join('</span><span>') + '</span>';

    const actions = card.querySelector('.tc-actions');
    actions.innerHTML =
      '<a class="tc-btn' + (st === 'done' ? '' : ' primary') + '" href="/monitor/' + encodeURIComponent(t.id) + '" target="_blank" rel="noopener">查看工作流监控 ↗</a>'
      + (st === 'done' ? '<a class="tc-btn primary" href="/api/research/' + encodeURIComponent(t.id) + '/report/download">⬇ 下载报告</a>' : '')
      + (st === 'failed' ? '<span class="tc-btn" style="border-color:rgba(239,68,68,.4);color:#fca5a5;cursor:default">任务失败，可在监控页查看原因</span>' : '');
  }

  // ---------- 发送（SSE 流解析） ----------
  function parseSseFrame(frame) {
    let event = 'message', data = '';
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data += line.slice(5).trim();
    }
    let parsed = {};
    try { if (data) parsed = JSON.parse(data); } catch (e) { parsed = { raw: data }; }
    return { event, data: parsed };
  }

  async function postSse(url, body, onEvent) {
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
      body: JSON.stringify(body),
    });
    if (!resp.ok || !resp.body) throw new Error('HTTP ' + resp.status);
    const reader = resp.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const frame = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        if (frame.trim()) onEvent(parseSseFrame(frame));
      }
    }
    if (buf.trim()) onEvent(parseSseFrame(buf));
  }

  async function send() {
    const content = el.input.value.trim();
    if (!content || streaming) return;

    if (!convId) {
      try {
        const r = await (await fetch('/api/chat/conversations', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).json();
        convId = r.id;
        localStorage.setItem('chat.convId', convId);
        conversations.unshift({ id: convId, title: '新对话', message_count: 0, updated_at: '' });
        renderConvList();
      } catch (e) { appendError('创建会话失败：' + e.message); return; }
    }

    // 清掉欢迎页
    const welcome = el.inner.querySelector('.welcome');
    if (welcome) welcome.remove();

    appendUserMsg(content);
    el.input.value = '';
    autoGrow();
    streaming = true;
    el.btnSend.disabled = true;

    let bubble = null, mdDiv = null, buf = '';
    try {
      await postSse('/api/chat/conversations/' + encodeURIComponent(convId) + '/messages', { content }, ({ event, data }) => {
        if (event === 'intent') {
          appendIntentTag(data.type);
        } else if (event === 'task') {
          const card = appendTaskCard(data.taskId, data.topic || content);
          watchTask(data.taskId);
        } else if (event === 'token') {
          if (!bubble) {
            bubble = appendAssistantBubble();
            mdDiv = bubble.querySelector('.md');
          }
          buf += (data.text || '');
          mdDiv.innerHTML = md(buf) + '<span class="cursor"></span>';
          scrollBottom();
        } else if (event === 'error') {
          appendError(data.message);
        }
        // done: 结束由 finally 收尾
      });
      if (bubble && mdDiv) mdDiv.innerHTML = md(buf); // 去掉光标
    } catch (e) {
      if (buf && mdDiv) mdDiv.innerHTML = md(buf);
      appendError(e.message);
    } finally {
      streaming = false;
      el.btnSend.disabled = false;
      loadConversations();
      el.convTitle.textContent = content.slice(0, 30);
    }
  }

  // ---------- 输入框 ----------
  function autoGrow() {
    el.input.style.height = 'auto';
    el.input.style.height = Math.min(el.input.scrollHeight, 160) + 'px';
  }

  el.input.addEventListener('input', autoGrow);
  el.input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
      e.preventDefault();
      send();
    }
  });
  el.btnSend.addEventListener('click', send);
  el.btnNew.addEventListener('click', newConversation);

  // ---------- 初始化 ----------
  async function init() {
    await loadConversations();
    if (convId && conversations.some((c) => c.id === convId)) {
      await openConversation(convId);
    } else if (convId) {
      // 本地记录的会话已被删除
      convId = null;
      localStorage.removeItem('chat.convId');
      renderWelcome();
    } else {
      renderWelcome();
    }
    // 恢复中断任务的卡片状态
    if (watching.size) pollTasks();
  }

  init();
})();
