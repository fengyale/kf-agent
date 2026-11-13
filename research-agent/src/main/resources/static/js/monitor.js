/* 工作流监控页（深色三栏）：SSE → SVG DAG 画布 + 节点详情 + 策略视图 + 任务列表 + 报告流式渲染 */
(function () {
  'use strict';

  // ---------- 基础引用 ----------
  const $ = (id) => document.getElementById(id);
  const taskId = (window.__TASK_ID__ || '') || new URLSearchParams(location.search).get('taskId') || '';
  const svg = $('canvas');
  const NS = 'http://www.w3.org/2000/svg';

  const el = {
    topic: $('topic'), taskId: $('taskId'), status: $('status'), budget: $('budget'), exit: $('exit'),
    detailPlaceholder: $('detail-placeholder'), detail: $('detail'),
    think: $('think'), thinkLabel: $('think-label'), thinkBody: $('think-body'),
    issues: $('issues'), report: $('report'), reportStatus: $('report-status'), dlBtn: $('dl-btn'),
    canvasEmpty: $('canvas-empty'), taskList: $('task-list'),
    btnPause: $('btn-pause'), btnResume: $('btn-resume'), btnCancel: $('btn-cancel'),
    flowView: $('flow-view'), strategyView: $('strategy-view'),
  };

  if (!taskId) {
    el.status.textContent = '暂无任务';
    el.status.className = 'chip failed';
    el.canvasEmpty.textContent = '还没有任何调研任务——回首页发起一次调研，这里就能看到全程工作流';
    el.topic.textContent = '暂无调研任务';
    return;
  }
  el.taskId.textContent = '任务 ' + taskId;

  // 任务终态（控制按钮显隐）
  let taskFinal = false;
  let paused = false;

  // ---------- 画布模型 ----------
  // 节点: { id, name, sub, status, round, kind, seq, detail, search, error }
  const nodes = new Map();
  const edges = new Set();
  let nodeSeq = 0;

  function node(id) {
    if (!nodes.has(id)) nodes.set(id, {
      id, name: id, sub: '', status: 'running', round: 0, kind: '',
      detail: null, search: null, error: null, seq: 0,
    });
    return nodes.get(id);
  }

  function register(id, kind) {
    const n = node(id);
    if (!n.seq) n.seq = ++nodeSeq;
    if (kind && !n.kind) n.kind = kind;
    return n;
  }

  function link(src, dst) {
    if (src && dst && src !== dst) edges.add(src + '>' + dst);
  }

  function parseId(id) {
    if (id === 'node-topic') return { kind: 'topic' };
    if (id === 'node-report') return { kind: 'report' };
    if (id === 'node-review') return { kind: 'review' };
    let m = id.match(/^node-r(\d+)-(strategy|search|eval)$/);
    if (m) return { kind: m[2], round: +m[1] };
    m = id.match(/^node-sup-?(\d+)$/);
    if (m) return { kind: 'sup', seq: +m[1] };
    return { kind: 'other' };
  }

  function wireEdges(id) {
    const p = parseId(id);
    if (p.kind === 'strategy') {
      link(p.round === 1 ? 'node-topic' : 'node-r' + (p.round - 1) + '-eval', id);
    } else if (p.kind === 'search') {
      link('node-r' + p.round + '-strategy', id);
    } else if (p.kind === 'eval') {
      for (let i = 1; i <= 9; i++) {
        const s = 'node-r' + p.round + '-search' + i;
        if (nodes.has(s)) link(s, id);
      }
    } else if (p.kind === 'report') {
      link(lastEval(), id);
    } else if (p.kind === 'review') {
      link('node-report', id);
    } else if (p.kind === 'sup') {
      link('node-review', id);
    }
  }

  function lastEval() {
    let last = null;
    nodes.forEach((n) => {
      const p = parseId(n.id);
      if (p.kind === 'eval' && (!last || p.round > parseId(last).round)) last = n.id;
    });
    return last;
  }

  // ---------- SVG 渲染 ----------
  const W = 196, H = 60, HGAP = 64, VGAP = 18, PAD = 26, TOP = 18;

  function svgEl(tag, attrs) {
    const e = document.createElementNS(NS, tag);
    for (const k in attrs) e.setAttribute(k, attrs[k]);
    return e;
  }

  function layout() {
    const laneRounds = [];
    nodes.forEach((n) => {
      const p = parseId(n.id);
      if (p.kind === 'strategy' || p.kind === 'search' || p.kind === 'eval') {
        if (!laneRounds.includes(p.round)) laneRounds.push(p.round);
      }
    });
    laneRounds.sort((a, b) => a - b);

    const laneRows = {};
    laneRounds.forEach((r) => {
      let rows = 1;
      for (let i = 1; i <= 9; i++) if (nodes.has('node-r' + r + '-search' + i)) rows = i;
      laneRows[r] = rows;
    });

    const pos = {};
    let laneTop = TOP + H + VGAP;

    let laneTotal = 0;
    laneRounds.forEach((r) => { laneTotal += laneRows[r] * (H + VGAP); });

    const colOf = { topic: 0, strategy: 1, search: 2, eval: 3, report: 4, review: 5, sup: 6 };
    const cx = (col) => PAD + col * (W + HGAP);

    laneRounds.forEach((r) => {
      const rows = laneRows[r];
      const strategyId = 'node-r' + r + '-strategy';
      const evalId = 'node-r' + r + '-eval';
      if (nodes.has(strategyId)) pos[strategyId] = { x: cx(colOf.strategy), y: laneTop };
      for (let i = 1; i <= rows; i++) {
        const sid = 'node-r' + r + '-search' + i;
        if (nodes.has(sid)) pos[sid] = { x: cx(colOf.search), y: laneTop + (i - 1) * (H + VGAP) };
      }
      if (nodes.has(evalId)) {
        const mid = Math.max(0, Math.floor((rows - 1) / 2));
        pos[evalId] = { x: cx(colOf.eval), y: laneTop + mid * (H + VGAP) };
      }
      laneTop += rows * (H + VGAP);
    });

    let midY = TOP + H;
    if (laneRounds.length) {
      const center = TOP + H + VGAP + laneTotal / 2 - H / 2;
      midY = Math.max(TOP, center);
    }
    if (nodes.has('node-topic')) pos['node-topic'] = { x: cx(colOf.topic), y: midY };
    if (nodes.has('node-report')) pos['node-report'] = { x: cx(colOf.report), y: midY };
    if (nodes.has('node-review')) pos['node-review'] = { x: cx(colOf.review), y: midY };

    let supCount = 0;
    nodes.forEach((n) => { if (parseId(n.id).kind === 'sup') supCount++; });
    for (let i = 1; i <= supCount; i++) {
      const sid = 'node-sup-' + i;
      if (nodes.has(sid)) pos[sid] = { x: cx(colOf.sup), y: midY + i * (H + VGAP) };
    }

    let maxCol = 0;
    nodes.forEach((n) => { const p = parseId(n.id); const c = colOf[p.kind] ?? 2; if (c > maxCol) maxCol = c; });
    const height = Math.max(laneTop + TOP, midY + H + TOP + (supCount + 1) * (H + VGAP));
    const width = PAD * 2 + (maxCol + 1) * (W + HGAP);
    return { pos, width, height };
  }

  let selectedId = null;

  function render() {
    if (currentView !== 'flow') return;
    const { pos, width, height } = layout();
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    svg.setAttribute('width', width);
    svg.setAttribute('height', Math.max(height, 240));
    el.canvasEmpty.style.display = nodes.size ? 'none' : 'flex';

    while (svg.firstChild) svg.removeChild(svg.firstChild);

    // 边
    edges.forEach((key) => {
      const [src, dst] = key.split('>');
      if (!nodes.has(src) || !nodes.has(dst)) return;
      const a = pos[src], b = pos[dst];
      if (!a || !b) return;
      const sn = nodes.get(src), dn = nodes.get(dst);
      const path = svgEl('path', {
        d: `M ${a.x + W} ${a.y + H / 2} C ${a.x + W + HGAP / 2} ${a.y + H / 2}, ${b.x - HGAP / 2} ${b.y + H / 2}, ${b.x} ${b.y + H / 2}`,
        class: 'edge' + (sn.status !== 'error' && dn.status === 'running' ? ' flow' : ''),
      });
      svg.appendChild(path);
    });

    // 节点
    nodes.forEach((n) => {
      const p = pos[n.id];
      if (!p) return;
      const g = svgEl('g', { class: 'node ' + n.status + (n.id === selectedId ? ' selected' : ''), transform: `translate(${p.x},${p.y})`, 'data-id': n.id });
      g.appendChild(svgEl('rect', { class: 'box', width: W, height: H, rx: 10 }));
      // 左侧状态色条
      g.appendChild(svgEl('rect', { class: 'st-bar', x: 0, y: 10, width: 3, height: H - 20 }));
      // 序号徽标（#01）
      const num = svgEl('g', { class: 'num-badge', transform: `translate(10,10)` });
      num.appendChild(svgEl('rect', { width: 22, height: 16, rx: 8 }));
      const nt = svgEl('text', { x: 11, y: 11.5, 'text-anchor': 'middle' });
      nt.textContent = '#' + String(n.seq).padStart(2, '0');
      num.appendChild(nt);
      g.appendChild(num);
      // 名称
      const label = svgEl('text', { x: 40, y: 22 });
      label.textContent = truncate(n.name, 11);
      g.appendChild(label);
      // 描述
      const sub = svgEl('text', { x: 40, y: 40, class: 'sub' });
      sub.textContent = truncate(n.sub, 15);
      g.appendChild(sub);
      // kind 标签（右上）
      if (n.kind) {
        const tag = svgEl('g', { class: 'kind-tag', transform: `translate(${W - 10 - kindWidth(n.kind)},${H - 20})` });
        const tw = kindWidth(n.kind);
        tag.appendChild(svgEl('rect', { width: tw, height: 14, rx: 7 }));
        const tt = svgEl('text', { x: tw / 2, y: 10, 'text-anchor': 'middle' });
        tt.textContent = n.kind;
        tag.appendChild(tt);
        g.appendChild(tag);
      }
      // 留存徽标（搜索节点）
      if (n.search && n.search.keptCount != null) {
        const badge = svgEl('g', { class: 'badge ' + badgeClass(n), transform: `translate(${W - 40},${-8})` });
        badge.appendChild(svgEl('rect', { width: 34, height: 16, rx: 8 }));
        const bt = svgEl('text', { x: 17, y: 11.5, 'text-anchor': 'middle' });
        bt.textContent = '留' + n.search.keptCount;
        badge.appendChild(bt);
        g.appendChild(badge);
      }
      svg.appendChild(g);
    });
  }

  // 节点选中：SVG 事件委托 + pointerdown（SSE 事件频繁重建节点会吞掉 click，委托+按下即选可稳定触发）
  svg.addEventListener('pointerdown', (e) => {
    const g = e.target.closest ? e.target.closest('.node') : null;
    if (!g) return;
    const n = nodes.get(g.dataset.id);
    if (n) { selectedId = n.id; render(); showDetail(n); }
  });

  function kindWidth(k) { return Math.max(46, String(k).length * 9 + 12); }

  function badgeClass(n) {
    if (n.status === 'error') return 'error';
    const kept = n.search.keptCount;
    if (kept === 0) return 'warn';
    return 'done';
  }

  function truncate(s, n) {
    s = String(s || '');
    return s.length > n ? s.slice(0, n - 1) + '…' : s;
  }

  // ---------- 详情面板（结构化） ----------
  function showDetail(n) {
    el.detailPlaceholder.style.display = 'none';
    el.detail.style.display = 'block';

    const type = n.kind || parseId(n.id).kind || '-';
    let h = `<div class="info-card">
      <div class="row"><span class="k">类型</span><span class="v">${esc(n.name)}</span></div>
      <div class="row"><span class="k">节点</span><span class="v">${esc(n.id)}</span></div>
      <div class="row"><span class="k">状态</span><span class="v">${statusText(n.status)}</span></div>
      ${n.round ? `<div class="row"><span class="k">轮次</span><span class="v">第 ${n.round} 轮</span></div>` : ''}
      ${n.detail && n.detail.costMs != null ? `<div class="row"><span class="k">耗时</span><span class="v">${(n.detail.costMs / 1000).toFixed(1)} 秒</span></div>` : ''}
      ${n.detail && n.detail.model ? `<div class="row"><span class="k">模型</span><span class="v">${esc(n.detail.model)}</span></div>` : ''}
      ${n.search ? `<div class="row"><span class="k">平台</span><span class="v">${esc(n.search.platform || 'web')}</span></div>
      ${n.search.keptCount != null ? `<div class="row"><span class="k">筛选</span><span class="v">返回 ${n.search.resultCount} / 保留 ${n.search.keptCount}</span></div>` : ''}` : ''}
    </div>`;

    // INPUT
    const input = n.search ? n.search.query : (n.detail ? n.detail.input : '');
    if (input) {
      h += `<div class="sec-label">INPUT · 输入</div>`;
      if (n.search) {
        h += `<div class="codeblock">${esc(n.search.query)}</div>`;
        if (n.search.reason) h += `<div class="codeblock" style="margin-top:6px;color:#94a3b8">意图：${esc(n.search.reason)}</div>`;
      } else {
        h += `<div class="codeblock">${esc(input)}</div>`;
      }
    }

    // SYSTEM PROMPT / USER PROMPT（策略节点）
    if (n.detail && (n.detail.systemPrompt || n.detail.userPrompt)) {
      if (n.detail.systemPrompt) {
        h += `<div class="sec-label fold" data-fold="sys"><span class="arr">▾</span>SYSTEM PROMPT · 系统提示词</div>
        <div class="fold-body"><div class="codeblock">${esc(n.detail.systemPrompt)}</div></div>`;
      }
      if (n.detail.userPrompt) {
        h += `<div class="sec-label fold" data-fold="usr"><span class="arr">▾</span>USER PROMPT · 用户提示词</div>
        <div class="fold-body"><div class="codeblock">${esc(n.detail.userPrompt)}</div></div>`;
      }
    }

    // 搜索结果标题
    if (n.search && n.search.topTitles && n.search.topTitles.length) {
      h += `<div class="sec-label">OUTPUT · 保留的头部结果</div>
      <div class="codeblock">${n.search.topTitles.map((t) => '· ' + esc(t)).join('\n')}</div>`;
    }

    // OUTPUT
    if (n.detail && n.detail.output) {
      h += `<div class="sec-label">OUTPUT · 输出</div>
      <div class="codeblock">${esc(n.detail.output)}</div>`;
    }

    if (n.error) h += `<div class="detail-err">${esc(n.error)}</div>`;

    el.detail.innerHTML = h;
    el.detail.querySelectorAll('.fold').forEach((f) => {
      f.addEventListener('click', () => f.classList.toggle('open'));
    });
  }

  // JSON 语法高亮（简化版）
  function highlightJson(s) {
    return esc(s)
      .replace(/"([^"\\]+)":/g, '"<span class="jk">$1</span>:')
      .replace(/: "([^"\\]*)"/g, ': "<span class="js">$1</span>"')
      .replace(/: (-?\d+\.?\d*)/g, ': <span class="jn">$1</span>');
  }

  function statusText(s) { return { running: '运行中', done: '已完成', error: '出错' }[s] || s; }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  // ---------- 策略与筛选视图 ----------
  let currentView = 'flow';

  $('view-switch').addEventListener('click', (e) => {
    const btn = e.target.closest('button');
    if (!btn) return;
    currentView = btn.dataset.view;
    document.querySelectorAll('#view-switch button').forEach((b) => b.classList.toggle('active', b === btn));
    el.flowView.style.display = currentView === 'flow' ? '' : 'none';
    el.strategyView.style.display = currentView === 'strategy' ? '' : 'none';
    if (currentView === 'flow') render();
    else renderStrategyView();
  });

  function renderStrategyView() {
    const rounds = [];
    nodes.forEach((n) => {
      const p = parseId(n.id);
      if (p.kind === 'search' && !rounds.includes(p.round)) rounds.push(p.round);
    });
    rounds.sort((a, b) => a - b);
    let h = '';
    if (!rounds.length) h = '<div class="placeholder">暂无搜索数据</div>';
    rounds.forEach((r) => {
      h += `<div class="sv-round"><h4>第 ${r} 轮</h4><table class="sv">
        <tr><th style="width:24%">搜索词</th><th style="width:10%">平台</th><th style="width:12%">返回/保留</th><th>意图与结果</th></tr>`;
      for (let i = 1; i <= 9; i++) {
        const n = nodes.get('node-r' + r + '-search' + i);
        if (!n) continue;
        const s = n.search || {};
        const kept = s.keptCount;
        const pill = kept == null ? '' : kept === 0 ? '<span class="pill zero">全被过滤</span>'
            : kept < 3 ? '<span class="pill warn">留存低</span>' : '<span class="pill">健康</span>';
        h += `<tr>
          <td>${esc(s.query || n.id)}</td>
          <td class="muted">${esc(s.platform || 'web')}</td>
          <td class="muted">${s.resultCount ?? '-'}/${s.keptCount ?? '-'} ${pill}</td>
          <td class="muted">${esc(s.reason || '')}${s.topTitles && s.topTitles.length ? '<br>· ' + s.topTitles.map(esc).join('<br>· ') : ''}</td>
        </tr>`;
      }
      h += '</table></div>';
    });
    // 补搜轮
    const sups = [];
    nodes.forEach((n) => { if (parseId(n.id).kind === 'sup') sups.push(n); });
    if (sups.length) {
      h += `<div class="sv-round"><h4>审稿补搜（B类缺口）</h4><table class="sv">
        <tr><th style="width:24%">搜索词</th><th style="width:10%">平台</th><th style="width:12%">返回/保留</th><th>意图</th></tr>`;
      sups.forEach((n) => {
        const s = n.search || {};
        h += `<tr><td>${esc(s.query || n.id)}</td><td class="muted">${esc(s.platform || 'web')}</td>
        <td class="muted">${s.resultCount ?? '-'}/${s.keptCount ?? '-'}</td><td class="muted">${esc(s.reason || '')}</td></tr>`;
      });
      h += '</table></div>';
    }
    el.strategyView.innerHTML = h;
  }

  // ---------- 任务列表 ----------
  let taskFilter = 'all';
  $('filter-tabs').addEventListener('click', (e) => {
    const btn = e.target.closest('button');
    if (!btn) return;
    taskFilter = btn.dataset.f;
    document.querySelectorAll('#filter-tabs button').forEach((b) => b.classList.toggle('active', b === btn));
    loadTasks();
  });

  function loadTasks() {
    fetch('/api/research').then((r) => r.json()).then((list) => {
      if (!Array.isArray(list)) return;
      const filtered = list.filter((t) => {
        if (taskFilter === 'all') return true;
        if (taskFilter === 'running') return t.status === 'running';
        if (taskFilter === 'done') return t.status === 'done' || t.status === 'cancelled';
        return true;
      });
      if (!filtered.length) { el.taskList.innerHTML = '<div class="placeholder">无任务</div>'; return; }
      el.taskList.innerHTML = filtered.map((t) => {
        const cls = t.status === 'running' ? 'running' : t.status === 'failed' ? 'failed' : t.status === 'cancelled' ? 'cancelled' : 'done';
        const time = (t.finished_at || t.created_at || '').slice(5, 16);
        return `<div class="task-item ${t.id === taskId ? 'current' : ''}" data-id="${t.id}">
          <div class="t">${esc(t.topic)}</div>
          <div class="s"><span class="dot ${cls}"></span>${t.status === 'running' ? '调研中' : t.status === 'failed' ? '失败' : t.status === 'cancelled' ? '已取消' : '已完成'} · ${time}</div>
        </div>`;
      }).join('');
      el.taskList.querySelectorAll('.task-item').forEach((item) => {
        item.addEventListener('click', () => {
          if (item.dataset.id !== taskId) location.href = '/monitor/' + encodeURIComponent(item.dataset.id);
        });
      });
    }).catch(() => {
      el.taskList.innerHTML = '<div class="placeholder">加载失败</div>';
    });
  }
  loadTasks();
  setInterval(loadTasks, 15000);

  // ---------- 控制按钮 ----------
  function api(path) {
    return fetch('/api/research/' + encodeURIComponent(taskId) + '/' + path, { method: 'POST' })
      .then((r) => r.json()).catch(() => ({}));
  }
  el.btnPause.addEventListener('click', () => api('pause'));
  el.btnResume.addEventListener('click', () => api('resume'));
  el.btnCancel.addEventListener('click', () => { if (confirm('确定终止该调研任务？')) api('cancel'); });
  $('btn-refresh').addEventListener('click', () => location.reload());

  function refreshControls() {
    const running = !taskFinal && !paused;
    el.btnPause.style.display = running ? '' : 'none';
    el.btnResume.style.display = (!taskFinal && paused) ? '' : 'none';
    el.btnCancel.style.display = running || paused ? '' : 'none';
  }
  refreshControls();

  // ---------- 思考区 & 报告流式渲染 ----------
  let phase = 'idle';
  let reportBuf = '';
  let dirty = false;
  el.think.querySelector('.tlabel').addEventListener('click', () => el.think.classList.toggle('open'));

  function appendThink(text) {
    el.think.style.display = 'block';
    el.thinkBody.textContent += text;
    if (el.think.classList.contains('open')) el.thinkBody.scrollTop = el.thinkBody.scrollHeight;
  }

  function appendToken(text) {
    reportBuf += text;
    dirty = true;
  }

  setInterval(() => {
    if (!dirty) return;
    dirty = false;
    renderReport();
  }, 150);

  function renderReport() {
    el.report.innerHTML = md(reportBuf);
    if (phase !== 'idle') {
      el.report.insertAdjacentHTML('beforeend', '<span class="cursor"></span>');
    }
  }

  // 轻量 Markdown 渲染
  function md(src) {
    if (!src) return '';
    const lines = src.split('\n');
    let html = [], inCode = false, inList = null, para = [];
    const flushPara = () => {
      if (para.length) { html.push('<p>' + inline(para.join('\n')) + '</p>'); para = []; }
    };
    const closeList = () => {
      if (inList) { html.push(inList === 'ul' ? '</ul>' : '</ol>'); inList = null; }
    };
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
      if (h) {
        flushPara(); closeList();
        const lv = Math.min(h[1].length + 1, 4);
        html.push(`<h${lv}>` + inline(h[2]) + `</h${lv}>`);
        continue;
      }
      const ul = line.match(/^\s*[-*]\s+(.*)/);
      const ol = line.match(/^\s*\d+[.、]\s*(.*)/);
      if (ul || ol) {
        flushPara();
        const type = ul ? 'ul' : 'ol';
        if (inList !== type) { closeList(); html.push('<' + type + '>'); inList = type; }
        html.push('<li>' + inline((ul || ol)[1]) + '</li>');
        continue;
      }
      if (/^\s*>\s?/.test(line)) {
        flushPara(); closeList();
        html.push('<blockquote>' + inline(line.replace(/^\s*>\s?/, '')) + '</blockquote>');
        continue;
      }
      if (/^\s*\|.*\|\s*$/.test(line)) {
        flushPara(); closeList();
        const cells = line.split('|').map((c) => c.trim()).filter((c, i, a) => !(i === 0 && c === '') && !(i === a.length - 1 && c === ''));
        if (cells.every((c) => /^:?-{2,}:?$/.test(c))) continue;
        html.push('<p class="tbl-row">' + cells.map((c) => inline(c)).join(' | ') + '</p>');
        continue;
      }
      if (!line.trim()) { flushPara(); closeList(); continue; }
      para.push(line);
    }
    flushPara(); closeList();
    if (inCode) html.push('</code></pre>');
    return html.join('\n');
  }

  function inline(s) {
    s = esc(s);
    s = s.replace(/`([^`]+)`/g, '<code>$1</code>');
    s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a class="cite" href="$2" target="_blank" rel="noopener">$1</a>');
    s = s.replace(/\[(\d{1,2})\]/g, '<sup class="cite">[$1]</sup>');
    return s;
  }

  // ---------- 事件处理 ----------
  const handlers = {
    'task:created': (d) => {
      el.topic.textContent = d.topic || '调研监控';
      document.title = (d.topic || '调研') + ' · 工作流监控';
      const b = d.budget || {};
      el.budget.style.display = '';
      el.budget.textContent = `兜底上限 ${b.maxRounds || '-'} 轮 / ${b.maxTotalSearches || '-'} 次搜索`;
    },
    'round:start': (d) => {
      paused = false;
      refreshControls();
      el.status.className = 'chip running';
      el.status.textContent = `第 ${d.round} 轮搜索中…`;
    },
    'task:paused': (d) => {
      paused = true;
      refreshControls();
      el.status.className = 'chip warn';
      el.status.textContent = '⏸ 已暂停';
    },
    'task:resumed': (d) => {
      paused = false;
      refreshControls();
      el.status.className = 'chip running';
      el.status.textContent = '已恢复';
    },
    'task:cancel-requested': (d) => {
      el.status.className = 'chip warn';
      el.status.textContent = '终止请求已发出…';
    },
    'node:running': (d) => {
      const n = register(d.nodeId, d.kind);
      n.name = d.name || n.name;
      n.round = d.round || n.round;
      n.status = 'running';
      wireEdges(d.nodeId);
      render();
    },
    'node:detail': (d) => {
      const n = register(d.nodeId, d.kind);
      const wasSelected = selectedId === d.nodeId;
      n.detail = d;
      n.status = 'done';
      wireEdges(d.nodeId);
      render();
      if (wasSelected) showDetail(n);
    },
    'node:error': (d) => {
      const n = register(d.nodeId);
      n.error = d.message || '未知错误';
      n.status = 'error';
      render();
      if (selectedId === d.nodeId) showDetail(n);
      if (d.nodeId === 'node-topic') {
        taskFinal = true; refreshControls();
        el.status.className = 'chip failed';
        el.status.textContent = '任务失败';
        el.reportStatus.textContent = '失败';
      }
    },
    'search:start': (d) => {
      const n = register(d.nodeId, '搜索');
      n.name = d.query ? String(d.query) : n.id;
      n.sub = d.platform && d.platform !== 'web' ? '@' + d.platform : '';
      n.search = { query: d.query, platform: d.platform, reason: d.reason };
      n.status = 'running';
      n.round = parseId(d.nodeId).round || 0;
      wireEdges(d.nodeId);
      render();
    },
    'search:done': (d) => {
      const n = register(d.nodeId, '搜索');
      n.status = n.error ? 'error' : 'done';
      n.sub = `${d.keptCount}/${d.resultCount} 条`;
      Object.assign(n.search || {}, { resultCount: d.resultCount, keptCount: d.keptCount, topTitles: d.topTitles || [] });
      render();
      if (selectedId === d.nodeId) showDetail(n);
      if (currentView === 'strategy') renderStrategyView();
    },
    'round:end': (d) => {
      const n = register('node-r' + d.round + '-eval', '筛选评估');
      n.sub = `净增${d.newFindings} · 累计${d.totalSearches}次`;
      render();
    },
    'loop:exit': (d) => {
      const reason = d.reason || 'unknown';
      const label = reason.startsWith('model-decision') ? '模型自主停止'
          : reason.startsWith('guard:') ? '护栏止损'
          : reason === 'user:cancelled' ? '用户终止'
          : '异常退出';
      el.exit.className = 'chip ' + (reason.startsWith('model-decision') ? 'done' : reason.startsWith('guard:') ? 'warn' : 'failed');
      el.exit.style.display = '';
      el.exit.textContent = `${label}：${d.detail || reason}（${d.rounds || '-'}轮 / ${d.totalSearches || '-'}次）`;
      el.exit.title = reason;
    },
    'task:failed': (d) => {
      phase = 'idle';
      dirty = true;
      taskFinal = true; refreshControls();
      el.status.className = 'chip failed';
      el.status.textContent = '任务失败';
      el.reportStatus.textContent = d.message || '失败';
      if (d.exitReason) {
        el.exit.className = 'chip failed';
        el.exit.style.display = '';
        el.exit.textContent = '失败：' + (d.exitReason || '');
        el.exit.title = d.exitReason || '';
      }
    },
    'thinking': (d) => appendThink(d.text || ''),
    'token': (d) => {
      if (phase === 'idle') phase = 'draft';
      appendToken(d.text || '');
    },
    'review:issue': (d) => {
      const issues = d.issues || [];
      if (!issues.length) return;
      let h = '<h4>⚠ 审稿发现 ' + issues.length + ' 个问题</h4>';
      issues.forEach((it) => {
        const type = (it.type || 'A').toString().toUpperCase();
        h += `<div class="issue"><span class="tag ${type === 'A' ? 'a' : 'b'}">${type}类</span>${esc(it.description || '')}${it.section ? `<span class="sec">§ ${esc(it.section)}</span>` : ''}</div>`;
      });
      el.issues.innerHTML = h;
    },
    'revise:start': () => {
      phase = 'revise';
      reportBuf = '';
      dirty = true;
      el.thinkLabel.textContent = '修订思考中…';
      el.reportStatus.textContent = '根据审稿意见修订中…';
    },
    'revise:done': () => {
      dirty = true;
    },
    'task:done': (d) => {
      phase = 'idle';
      dirty = true;
      taskFinal = true; paused = false; refreshControls();
      el.status.className = 'chip done';
      el.status.textContent = '已完成';
      el.reportStatus.textContent = `完成 · ${d.rounds || '-'} 轮 / ${d.searchCount || '-'} 次搜索 / ${Math.round((d.totalCostMs || 0) / 1000)}s`;
      if (d.exitReason && el.exit.style.display === 'none') {
        const isModel = String(d.exitReason).startsWith('model-decision');
        el.exit.className = 'chip ' + (isModel ? 'done' : 'warn');
        el.exit.style.display = '';
        el.exit.textContent = (isModel ? '模型自主停止' : d.exitReason === 'user:cancelled' ? '用户终止' : '护栏止损') + '：' + (d.exitDetail || d.exitReason);
      }
      el.dlBtn.style.display = '';
      el.dlBtn.href = '/api/research/' + encodeURIComponent(taskId) + '/report/download';
      fetchReport(true);
      loadTasks();
      es.close();
    },
  };

  // ---------- SSE ----------
  let finished = false;
  const es = new EventSource('/api/monitor/' + encodeURIComponent(taskId));
  Object.keys(handlers).forEach((t) => es.addEventListener(t, (ev) => {
    let data = {};
    try { data = JSON.parse(ev.data); } catch (ignored) { }
    handlers[t](data);
  }));

  es.addEventListener('hello', () => {
    if (!finished && !taskFinal) {
      el.status.className = 'chip running';
      el.status.textContent = el.status.textContent === '连接中…' ? '监控中' : el.status.textContent;
    }
  });

  es.onerror = () => {
    fetch('/api/research/' + encodeURIComponent(taskId) + '/report')
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => {
        if (!d) return;
        if (d.status === 'done' || d.status === 'failed' || d.status === 'cancelled') {
          finished = true;
          taskFinal = true; refreshControls();
          es.close();
          if (d.status === 'done') {
            phase = 'idle';
            if (d.markdown) { reportBuf = d.markdown; dirty = true; }
            el.status.className = 'chip done';
            el.status.textContent = '已完成（回放）';
            el.dlBtn.style.display = '';
            el.dlBtn.href = '/api/research/' + encodeURIComponent(taskId) + '/report/download';
          } else if (d.status === 'cancelled') {
            el.status.className = 'chip warn';
            el.status.textContent = '已取消';
          } else {
            el.status.className = 'chip failed';
            el.status.textContent = '任务失败';
          }
        } else if (!taskFinal) {
          el.status.className = 'chip running';
          el.status.textContent = '重连中…';
        }
      })
      .catch(() => { });
  };

  function fetchReport(replace) {
    fetch('/api/research/' + encodeURIComponent(taskId) + '/report')
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => {
        if (d && d.markdown) {
          reportBuf = d.markdown;
          phase = 'idle';
          dirty = true;
          el.dlBtn.style.display = '';
          el.dlBtn.href = '/api/research/' + encodeURIComponent(taskId) + '/report/download';
        }
      })
      .catch(() => { });
  }

  // 初始渲染
  render();
  fetchReport(false);
})();
