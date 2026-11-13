/**
 * 会话页逻辑（Task 5.2）：
 * - 跨天/断线恢复：先 GET /api/analysis/{id} 恢复状态（阶段/待答问题/终态），
 *   再连 SSE（/api/events/{analysisId}）；EventPublisher 订阅时先回放历史事件再挂监听，
 *   回放与实时双通道靠事件指纹幂等去重，重连不产生重复渲染
 * - 事件渲染：collect:question/ask:followup 追问卡片（跳过/直接给结论）、collect:filled
 *   槽位确认、analysis:stage 阶段指示器推进、analysis:thinking 思考流（可折叠）、
 *   scenario / redteam:issue 预留渲染、done/error 终态
 * - 断线重连：sse.js 指数退避（上限 30s），重连后历史回放自动补齐错过的事件
 */
(function () {
  'use strict';

  var analysisId = document.body.dataset.analysisId || '';

  var $ = function (id) { return document.getElementById(id); };
  var flowEl = $('flow');
  var scrollEl = $('flow-scroll');

  // ---------- 常量 ----------
  var STAGES = [
    { key: 'intent', label: '意图' },
    { key: 'collect', label: '采集' },
    { key: 'assess', label: '评估' },
    { key: 'path', label: '路径' },
    { key: 'gap', label: '差距' },
    { key: 'risk', label: '风险' },
  ];
  var STEP_LABEL = {
    intent: '意图识别', collect: '交互采集', assess: '可行性评估', 'assess-light': '简短评估',
    path: '路径设计', gap: '差距分析', risk: '风险与对策', redteam: '红队质疑',
  };
  var STATUS_META = {
    waiting: { text: '等你补充信息', cls: 'chip chip-warn' },
    running: { text: '分析进行中', cls: 'chip chip-info chip-pulse' },
    done: { text: '已完成', cls: 'chip chip-ok' },
    error: { text: '失败', cls: 'chip chip-danger' },
    cancelled: { text: '已终止', cls: 'chip chip-faint' },
  };

  // ---------- 状态 ----------
  var sse = null;
  var terminal = false;       // done/error/cancelled 后停止采集交互
  var collectLive = false;   // 处于采集问答阶段（collect 类卡片可作答）
  var replayDone = false;     // hello 到达 = 历史回放结束，此后 collect:question 视为实时
  var lastQuestionKey = null; // 最新一批追问（SSE 回放 / HTTP 响应双通道去重）
  var filledSeen = {};        // collect:filled 指纹 slot|value
  var thinkingSeen = {};      // 思考片段指纹
  var jsonSeen = {};          // 通用事件指纹（scenario/redteam/done/error）
  var questionCards = {};     // slot -> 追问卡片元素
  var maxStageIdx = -1;       // 阶段指示器推进到的最大下标
  var answering = false;      // 防重复提交
  var stick = true;          // 自动滚动（用户上翻则暂停）

  // ---------- 工具 ----------
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }
  function stageIndex(name) {
    if (name === 'assess-light') return 2; // 轻量路径映射到「评估」
    if (name === 'redteam') return STAGES.length; // deep 附加步骤：阶段条全绿（红色独立提示）
    for (var i = 0; i < STAGES.length; i++) if (STAGES[i].key === name) return i;
    return -1;
  }
  function batchKey(questions) {
    return (questions || []).map(function (q) { return q.slot + '=' + (q.question || ''); }).join('|');
  }
  function onceJson(key, obj) {
    var fp = key + '|' + JSON.stringify(obj);
    if (jsonSeen[fp]) return false;
    jsonSeen[fp] = 1;
    return true;
  }

  scrollEl.addEventListener('scroll', function () {
    stick = scrollEl.scrollHeight - scrollEl.scrollTop - scrollEl.clientHeight < 160;
  });
  function appendFlow(node) {
    flowEl.appendChild(node);
    if (stick) scrollEl.scrollTop = scrollEl.scrollHeight;
  }

  // ---------- 顶栏状态 ----------
  function setStatus(st) {
    var m = STATUS_META[st] || { text: st, cls: 'chip chip-faint' };
    var chip = $('status-chip');
    chip.className = m.cls;
    chip.textContent = m.text;
  }
  function setConn(state, text) {
    var dot = $('conn-dot');
    dot.className = 'conn-dot ' + (state || '');
    $('conn-text').textContent = text || '';
  }
  function goReport() {
    location.href = '/report/' + encodeURIComponent(analysisId);
  }

  // ---------- 阶段指示器 ----------
  function buildStageBar() {
    var bar = $('stage-bar');
    bar.innerHTML = STAGES.map(function (s) {
      return '<div class="stage-step" data-stage="' + s.key + '">'
        + '<div class="dot">' + (STAGES.indexOf(s) + 1) + '</div><div class="lbl">' + s.label + '</div></div>';
    }).join('');
  }
  function stageEls() { return document.querySelectorAll('#stage-bar .stage-step'); }
  function setStage(idx) {
    if (idx > maxStageIdx) maxStageIdx = idx;
    renderStageBar();
  }
  function renderStageBar() {
    stageEls().forEach(function (e, i) {
      e.classList.toggle('done', i < maxStageIdx);
      e.classList.toggle('active', i === maxStageIdx);
    });
  }
  function setStageAllDone() {
    stageEls().forEach(function (e) { e.classList.add('done'); e.classList.remove('active'); });
  }

  // ---------- 思考流 ----------
  function toggleThink() { $('think-panel').classList.toggle('open'); }
  $('think-head').addEventListener('click', toggleThink);
  function appendThinking(text) {
    $('think-wrap').classList.remove('hidden');
    var body = $('think-body');
    var line = document.createElement('div');
    line.className = 'think-line';
    line.textContent = text;
    body.appendChild(line);
    body.scrollTop = body.scrollHeight;
  }

  // ---------- 对话流元素 ----------
  function renderQuestionBubble(text) {
    var wrap = document.createElement('div');
    wrap.className = 'flex justify-end';
    var b = document.createElement('div');
    b.className = 'bubble-user';
    b.textContent = text;
    wrap.appendChild(b);
    appendFlow(wrap);
  }
  function renderMetaChips(analysis) {
    var wrap = document.createElement('div');
    wrap.className = 'flex flex-wrap gap-2';
    var html = '';
    if (analysis.type) html += '<span class="chip chip-info">类型 · ' + esc(analysis.type) + '</span>';
    if (analysis.depth) html += '<span class="chip chip-data">深度 · ' + esc(analysis.depth) + '</span>';
    wrap.innerHTML = html;
    if (html) appendFlow(wrap);
  }
  function renderNotice(text) {
    if (!text) return;
    var d = document.createElement('div');
    d.className = 'notice-line';
    d.textContent = text;
    appendFlow(d);
  }
  function renderSysLine(text) {
    var d = document.createElement('div');
    d.className = 'sys-line';
    d.textContent = text;
    appendFlow(d);
  }
  function renderFilled(d) {
    var div = document.createElement('div');
    div.className = 'filled-chip';
    div.innerHTML = '<span style="color: var(--ok);">✓</span>'
      + '<span class="k"></span><span>=</span><span class="v"></span>'
      + (d.assumed ? '<span class="badge-assume">基于假设</span>' : '');
    div.querySelector('.k').textContent = d.slot;
    div.querySelector('.v').textContent = d.value == null ? '' : d.value;
    appendFlow(div);
    markAnswered(d.slot, d.value, d.assumed);
  }

  // ---------- waiting 引导：说明"任务挂起不耗额度" + 自动定位到待答追问 ----------
  var waitingHintShown = false;
  function showWaitingHint() {
    if (waitingHintShown) return;
    waitingHintShown = true;
    renderNotice('分析已暂停等你补充：回答下方追问后自动继续，等待期间不消耗模型调用');
  }
  function focusFirstPending() {
    var ae = document.activeElement;
    if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA')) return; // 用户正在输入时不抢焦点
    var pending = Object.keys(questionCards).map(function (k) { return questionCards[k]; })
      .filter(function (c) { return !c.classList.contains('answered'); });
    if (!pending.length) return;
    var last = pending[pending.length - 1];
    last.scrollIntoView({ block: 'center' });
    var input = last.querySelector('.q-input');
    if (input) setTimeout(function () { input.focus({ preventScroll: true }); }, 300);
  }

  // ---------- 追问卡片 ----------
  function renderQuestionBatch(kind, questions, context) {
    if (terminal) return;
    // 实时性判定：followup 追问始终可答；collect 批次在回放期须处于采集阶段才算实时
    var liveBatch = (kind === 'followup') || collectLive || replayDone;
    if (context) renderNotice(context);
    (questions || []).forEach(function (q) {
      if (!q || !q.slot) return;
      var card = questionCards[q.slot];
      if (card) {
        updateQuestionCard(card, kind, q);
      } else {
        card = buildQuestionCard(kind, q);
        appendFlow(card);
        questionCards[q.slot] = card;
      }
      setCardActive(card, liveBatch);
    });
    if (liveBatch) {
      setStatus('waiting');
      showWaitingHint();
      focusFirstPending();
    }
  }

  function buildQuestionCard(kind, q) {
    var card = document.createElement('div');
    card.className = 'q-card' + (kind === 'followup' ? ' followup' : '');
    card.dataset.slot = q.slot;
    card.innerHTML =
      '<div class="flex items-center gap-2 flex-wrap">'
      + '<span class="chip ' + (kind === 'followup' ? 'chip-data' : 'chip-info') + '">'
      + (kind === 'followup' ? '分析中追问' : '追问') + '</span>'
      + '<span class="q-slot"></span>'
      + (q.skippable === false ? '<span class="chip chip-warn">必答</span>' : '')
      + '</div>'
      + '<div class="q-text"></div>'
      + '<div class="q-answer flex gap-2">'
      + '<input class="field q-input flex-1" placeholder="输入你的回答…">'
      + '<button class="btn-primary btn-sm q-submit flex-shrink-0">提交</button>'
      + '</div>'
      + '<div class="q-actions flex gap-2 mt-2">'
      + '<button class="btn-ghost btn-sm q-skip">跳过此问</button>'
      + '<button class="btn-ghost btn-sm q-conclude">直接给结论</button>'
      + '</div>'
      + '<div class="q-done" hidden></div>';
    card.querySelector('.q-text').textContent = q.question || ('请补充：' + q.slot);
    card.querySelector('.q-slot').textContent = q.slot;
    bindCard(card, q.slot);
    return card;
  }

  function updateQuestionCard(card, kind, q) {
    if (q.question) card.querySelector('.q-text').textContent = q.question;
  }

  function setCardActive(card, active) {
    if (card.classList.contains('answered')) return;
    card.classList.toggle('q-live', active);
    card.querySelector('.q-answer').style.display = active ? '' : 'none';
    card.querySelector('.q-actions').style.display = active ? '' : 'none';
  }
  function setCardsActive(active) {
    Object.keys(questionCards).forEach(function (slot) { setCardActive(questionCards[slot], active); });
  }

  function markAnswered(slot, value, assumed) {
    var card = questionCards[slot];
    if (!card || card.classList.contains('answered')) return;
    card.classList.add('answered');
    setCardActive(card, false);
    var done = card.querySelector('.q-done');
    done.hidden = false;
    done.textContent = assumed ? '✓ 按默认假设填充：' + (value == null ? '' : value) : '✓ 已回答：' + (value == null ? '' : value);
  }

  function bindCard(card, slot) {
    var input = card.querySelector('.q-input');
    function submit() {
      var v = input.value.trim();
      if (!v) { input.focus(); return; }
      answer({ action: 'answer', slotName: slot, value: v });
    }
    card.querySelector('.q-submit').addEventListener('click', submit);
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.isComposing && !e.shiftKey) { e.preventDefault(); submit(); }
    });
    card.querySelector('.q-skip').addEventListener('click', function () {
      answer({ action: 'skip' }, '已跳过此问');
    });
    card.querySelector('.q-conclude').addEventListener('click', function () {
      answer({ action: 'conclude' }, '已选择直接给结论，按现有信息分析');
    });
  }

  // ---------- 回答 / 跳过 / 直接给结论（POST /api/chat/answer） ----------
  function answer(body, localNote) {
    if (answering || terminal) return;
    answering = true;
    body.analysisId = analysisId;
    fetch('/api/chat/answer', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).then(function (r) {
      return r.json().then(function (j) { return { ok: r.ok, json: j || {} }; });
    }).then(function (res) {
      answering = false;
      if (!res.ok) {
        renderNotice('提交失败：' + (res.json.message || res.json.error || '请求异常'));
        return;
      }
      if (localNote) renderNotice(localNote);
      handleAnswerResult(res.json, body);
    }).catch(function (e) {
      answering = false;
      renderNotice('网络错误：' + e.message + '（回答未提交，可重试）');
    });
  }

  function handleAnswerResult(j, body) {
    // 已结束的分析
    if (j.status === 'done' || j.status === 'error' || j.status === 'cancelled') {
      if (j.status === 'done') { goReport(); return; }
      finishTerminal(j.status, { message: j.message, exitDetail: j.message });
      return;
    }
    // 本地回显回答（HTTP 先于 SSE 到达时；指纹登记防重复渲染）
    if (body.action === 'answer' && body.slotName) {
      var fp = body.slotName + '|' + body.value;
      if (!filledSeen[fp]) {
        filledSeen[fp] = 1;
        renderFilled({ slot: body.slotName, value: body.value });
      }
    }
    if (j.triggered) {
      // 槽位满/降级 → 触发/恢复分析（挂起的从断点续跑），等阶段事件推进
      collectLive = false;
      setCardsActive(false);
      setStatus('running');
      renderSysLine('信息已就绪，分析继续…');
      return;
    }
    if (Array.isArray(j.questions) && j.questions.length) {
      renderQuestionBatch('collect', j.questions, null);
      return;
    }
    setStatus('running');
  }

  // ---------- 终态 ----------
  function homeLink(text) {
    return '<a href="/" class="text-xs" style="color: var(--link);">' + (text || '返回首页') + '</a>';
  }
  function finishTerminal(kind, d) {
    if (terminal) return;
    terminal = true;
    collectLive = false;
    if (sse) sse.close();
    setConn('', '已结束');
    setCardsActive(false);
    d = d || {};
    var card = document.createElement('div');
    card.className = 'agent-card';
    if (kind === 'done') {
      setStageAllDone();
      setStatus('done');
      card.innerHTML = '<div class="flex items-center gap-2 mb-2"><span class="chip chip-ok">分析完成</span>'
        + (d.exitReason ? '<span class="text-xs" style="color: var(--faint);">' + esc(d.exitReason) + '</span>' : '')
        + '</div><div class="text-sm" style="color: var(--text);">正在跳转报告页…</div>';
      appendFlow(card);
      setTimeout(goReport, 1200);
      return;
    }
    if (kind === 'cancelled') {
      setStatus('cancelled');
      card.innerHTML = '<div class="flex items-center gap-2 mb-2"><span class="chip chip-faint">已终止</span></div>'
        + '<div class="text-sm" style="color: var(--muted);">' + esc(d.exitDetail || d.message || '分析已终止') + '</div>'
        + '<div class="mt-3">' + homeLink() + '</div>';
    } else {
      setStatus('error');
      card.innerHTML = '<div class="flex items-center gap-2 mb-2"><span class="chip chip-danger">分析失败</span>'
        + (d.exitReason ? '<span class="text-xs" style="color: var(--faint);">' + esc(d.exitReason) + '</span>' : '')
        + '</div><div class="text-sm" style="color: var(--danger);">' + esc(d.message || d.exitDetail || '未知错误') + '</div>'
        + '<div class="mt-3">' + homeLink('返回首页重新发起') + '</div>';
    }
    appendFlow(card);
  }

  function renderNotFound() {
    $('q-title').textContent = '分析不存在';
    setStatus('error');
    var card = document.createElement('div');
    card.className = 'agent-card';
    card.innerHTML = '<div class="text-sm" style="color: var(--danger);">分析不存在或已被删除</div>'
      + '<div class="mt-3">' + homeLink() + '</div>';
    flowEl.appendChild(card);
  }

  // ---------- SSE 事件处理 ----------
  var handlers = {
    hello: function () {
      replayDone = true; // 历史回放完成（subscribe 先回放后 hello）
      setConn('online', '在线');
      focusFirstPending(); // 回放补齐对话现场后，定位到当前待答追问
    },
    heartbeat: function () { /* 心跳仅保活；退避重置由 sse.js 统一处理 */ },

    'collect:question': function (d) {
      if (terminal) return;
      var key = batchKey(d.questions);
      if (key === lastQuestionKey) return; // 回放 / HTTP 响应双通道去重
      lastQuestionKey = key;
      renderQuestionBatch('collect', d.questions || [], null);
    },

    'collect:filled': function (d) {
      if (!d || !d.slot) return;
      var fp = d.slot + '|' + (d.value == null ? '' : d.value);
      if (filledSeen[fp]) return;
      filledSeen[fp] = 1;
      renderFilled(d);
    },

    'analysis:stage': function (d) {
      if (!d || !d.stage) return;
      if (d.stage === 'pausing' || d.stage === 'cancelling') {
        renderNotice(d.message || d.stage);
        return;
      }
      var idx = stageIndex(d.stage);
      if (idx < 0) return;
      setStage(idx);
      renderSysLine('进入阶段：' + (STEP_LABEL[d.stage] || d.stage));
      if (d.stage !== 'intent' && d.stage !== 'collect') {
        collectLive = false;
        setCardsActive(false);
        setStatus('running');
      }
    },

    'analysis:thinking': function (d) {
      var text = d && (d.text || d.content || d.thinking || d.fragment);
      if (!text || thinkingSeen[text]) return;
      thinkingSeen[text] = 1;
      appendThinking(text);
    },

    'ask:followup': function (d) {
      if (terminal || !d) return;
      var key = 'fu:' + batchKey(d.questions);
      if (key === lastQuestionKey) return;
      lastQuestionKey = key;
      renderQuestionBatch('followup', d.questions || [],
        d.context || '评估中发现关键信息缺失：回答后分析从中断处自动继续');
    },

    scenario: function (d) {
      if (!d || !onceJson('scenario', d)) return;
      renderScenarioPreview(d);
    },

    'redteam:issue': function (d) {
      if (!d || !onceJson('redteam', d)) return;
      renderRedteam(d);
    },

    done: function (d) {
      if (!onceJson('done', d || {})) return;
      if (d && d.cancelled) { finishTerminal('cancelled', d); return; }
      finishTerminal('done', d);
    },

    error: function (d) {
      if (!onceJson('error', d || {})) return;
      finishTerminal('error', d);
    },
  };

  // scenario 事件（P2 完整版载荷 {scenarios:[{name, weeklyHours, duration, probability, milestones, demoteTrigger}]}）
  function renderScenarioPreview(d) {
    var list = Array.isArray(d.scenarios) ? d.scenarios : [];
    if (!list.length) return;
    var wrap = document.createElement('div');
    wrap.innerHTML = '<div class="sys-line mb-1">三档情景生成</div>'
      + '<div class="scenario-grid"></div>';
    var grid = wrap.querySelector('.scenario-grid');
    var fallback = { A: '乐观', B: '现实', C: '保守' };
    list.forEach(function (s, i) {
      var name = String(s.name || '');
      var grade = name.charAt(0).toUpperCase();
      if (!/^[A-C]$/.test(grade)) grade = ['A', 'B', 'C'][Math.min(i, 2)];
      var label = name.slice(1).trim() || fallback[grade] || '';
      var rows = '';
      if (s.probability != null) {
        rows += '<div class="sc-row"><span class="k">概率</span><span class="v">'
          + esc(s.probability) + '</span></div>';
      }
      if (s.weeklyHours != null && s.duration != null) {
        rows += '<div class="sc-row"><span class="k">投入</span><span class="v">'
          + esc(s.weeklyHours) + ' 小时/周 × ' + esc(s.duration) + ' 周</span></div>';
      }
      if (Array.isArray(s.milestones) && s.milestones.length) {
        var names = s.milestones.map(function (m) { return m && m.name; }).filter(Boolean);
        rows += '<div class="sc-row"><span class="k">里程碑</span><span class="v">'
          + esc(names.join(' / ') || (s.milestones.length + ' 个')) + '</span></div>';
      }
      if (s.demoteTrigger) {
        rows += '<div class="sc-row"><span class="k">降档</span><span class="v">'
          + esc(s.demoteTrigger) + '</span></div>';
      }
      var card = document.createElement('div');
      card.className = 'scenario-card g-' + (grade === 'A' ? 'a' : grade === 'B' ? 'b' : 'c');
      card.innerHTML = '<div class="flex items-center gap-2"><div class="grade">' + esc(grade) + '</div>'
        + '<div class="sc-label">' + esc(label) + '</div></div>' + rows;
      grid.appendChild(card);
    });
    appendFlow(wrap);
  }

  // redteam:issue 事件（红队收敛循环：{round, challenges:[{point, severity, mitigation, source}]}）
  function renderRedteam(d) {
    var list = Array.isArray(d.challenges) ? d.challenges : [];
    var card = document.createElement('div');
    card.className = 'agent-card';
    var items = list.map(function (c) {
      var sev = /major|severe|严重/i.test(c.severity || '') ? 'chip-danger' : 'chip-warn';
      // 代码确定性校验的条目标注来源（与报告页口径一致：该类判定模型无权降级为 minor）
      var code = String(c.source || '') === 'code'
        ? '<span class="chip chip-info" style="flex-shrink:0;">代码校验</span>' : '';
      return '<li style="display:flex; gap:8px; align-items:flex-start; padding:4px 0;">'
        + '<span class="chip ' + sev + '" style="flex-shrink:0;">' + esc(c.severity || '质疑') + '</span>'
        + code
        + '<span style="font-size:13px; color:var(--text);">' + esc(c.point || '') + '</span></li>';
    }).join('');
    var head = d.round ? (' · 第 ' + esc(d.round) + ' 轮') : '';
    card.innerHTML = '<div class="flex items-center gap-2 mb-2"><span class="chip chip-danger">红队质疑</span>'
      + '<span class="text-xs" style="color:var(--faint);">' + head + '</span></div>'
      + '<ul style="list-style:none; padding:0; margin:0;">' + (items || '<li class="text-xs" style="color:var(--faint);">暂无</li>') + '</ul>';
    appendFlow(card);
  }

  // ---------- 状态恢复（GET /api/analysis/{id}）----------
  function restore() {
    fetch('/api/analysis/' + encodeURIComponent(analysisId)).then(function (r) {
      return r.json().then(function (j) { return { ok: r.ok, json: j || {} }; });
    }).then(function (res) {
      if (!res.ok) { renderNotFound(); return; }
      var j = res.json;
      var analysis = j.analysis || {};
      var st = j.state || {};
      $('q-title').textContent = analysis.question || analysisId;
      renderQuestionBubble(analysis.question || '');
      renderMetaChips(analysis);
      restoreStage(st, j.route || {});

      if (st.status === 'done') { goReport(); return; }
      if (st.status === 'error') {
        finishTerminal('error', { message: (st.exit && st.exit.detail) || '分析失败', exitReason: st.exit && st.exit.reason });
      } else if (st.status === 'cancelled') {
        finishTerminal('cancelled', { exitDetail: (st.exit && st.exit.detail) || '分析已终止' });
      } else if (st.status === 'waiting') {
        var pq = st.pendingQuestion;
        if (pq && Array.isArray(pq.questions) && pq.questions.length) {
          collectLive = pq.kind !== 'followup'; // 先置采集阶段标记，卡片渲染为可作答
          lastQuestionKey = batchKey(pq.questions);
          renderQuestionBatch(pq.kind === 'followup' ? 'followup' : 'collect', pq.questions, null);
        } else {
          setStatus('waiting');
          renderPausedResume();
        }
      } else {
        setStatus('running');
      }
      connectSse(); // 终态也连：回放补全对话现场
    }).catch(function (e) {
      setStatus('error');
      renderNotice('状态恢复失败：' + e.message);
      connectSse();
    });
  }

  function restoreStage(st, route) {
    var completed = st.completedSteps || [];
    var doneIdxs = completed.map(stageIndex).filter(function (i) { return i >= 0; });
    var activeIdx = -1;
    if (st.status === 'waiting') {
      var cur = stageIndex(st.currentStep);
      activeIdx = cur >= 0 ? cur : 1;
    } else {
      var doneSet = {};
      completed.forEach(function (s) { doneSet[s] = 1; });
      var routeSteps = (route && route.steps) || [];
      var next = null;
      for (var i = 0; i < routeSteps.length; i++) {
        if (!doneSet[routeSteps[i]]) { next = routeSteps[i]; break; }
      }
      if (next) activeIdx = stageIndex(next);
      else if (doneIdxs.length) activeIdx = Math.max.apply(null, doneIdxs);
    }
    if (activeIdx >= 0) setStage(activeIdx);
  }

  // 挂起但无待答问题（如外部调用过 pause）：提供继续按钮（POST /api/analysis/{id}/resume）
  function renderPausedResume() {
    var card = document.createElement('div');
    card.className = 'agent-card';
    card.innerHTML = '<div class="text-sm" style="color: var(--muted);">分析已挂起</div>'
      + '<button class="btn-ghost btn-sm mt-3" id="btn-resume">继续分析</button>';
    appendFlow(card);
    card.querySelector('#btn-resume').addEventListener('click', function () {
      fetch('/api/analysis/' + encodeURIComponent(analysisId) + '/resume', { method: 'POST' })
        .then(function () { setStatus('running'); card.remove(); })
        .catch(function (e) { renderNotice('恢复失败：' + e.message); });
    });
  }

  // ---------- SSE 连接 ----------
  function connectSse() {
    sse = createReconnectingEventSource('/api/events/' + encodeURIComponent(analysisId), handlers, {
      onState: function (state, info) {
        if (state === 'connecting') setConn('connecting', '连接中');
        else if (state === 'backoff') {
          setConn('backoff', Math.round((info.delay || 0) / 1000) + 's 后重连');
        }
      },
    });
  }

  // ---------- 启动 ----------
  if (!analysisId) { renderNotFound(); return; }
  buildStageBar();
  restore();
})();
