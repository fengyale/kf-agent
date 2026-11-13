/**
 * 首页逻辑（Task 5.1）：
 * 1. 深度选择（自动/轻量/标准/深度，默认自动——自动 = 不传 depth，由意图识别判断）
 * 2. 首访画像引导：GET /api/profile，profile 为空且未引导过 → 渲染 3~5 题引导卡（可跳过），
 *    POST /api/profile 提交（{answers:{k:v}} 或 {skipped:true}）
 * 3. 提交问题：POST /api/chat/start {question, depth?} → 跳转 /session/{analysisId}
 */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var elQuestion = $('question');
  var elStart = $('btn-start');
  var elError = $('start-error');
  var selectedDepth = ''; // 空 = 自动

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  // ---------- 深度选择 ----------
  function bindDepthPills() {
    var pills = document.querySelectorAll('.depth-pill');
    pills.forEach(function (p) {
      p.addEventListener('click', function () {
        pills.forEach(function (x) { x.classList.remove('active'); });
        p.classList.add('active');
        selectedDepth = p.dataset.depth || '';
      });
    });
  }

  // ---------- 示例问题 ----------
  function bindSuggestions() {
    document.querySelectorAll('.sug').forEach(function (b) {
      b.addEventListener('click', function () {
        elQuestion.value = b.dataset.q || '';
        elQuestion.focus();
      });
    });
  }

  // ---------- 首访画像引导 ----------
  function loadProfile() {
    fetch('/api/profile').then(function (r) { return r.json(); }).then(function (p) {
      if (p && p.empty && !p.guidanceDone && Array.isArray(p.guidance) && p.guidance.length) {
        renderGuidance(p.guidance);
      }
    }).catch(function () { /* 画像加载失败不阻塞提问 */ });
  }

  function renderGuidance(questions) {
    var box = $('guidance-questions');
    box.innerHTML = questions.map(function (q, i) {
      return '<div>'
        + '<label class="block text-xs mb-1.5" style="color: var(--muted);">'
        + (i + 1) + '. ' + esc(q.question) + '</label>'
        + '<input class="field guidance-input" data-key="' + esc(q.key) + '" placeholder="选填">'
        + '</div>';
    }).join('');
    $('guidance-wrap').classList.remove('hidden');
  }

  function saveGuidance(skipped) {
    var answers = {};
    if (!skipped) {
      document.querySelectorAll('.guidance-input').forEach(function (input) {
        var v = input.value.trim();
        if (v && input.dataset.key) answers[input.dataset.key] = v;
      });
      if (!Object.keys(answers).length) {
        $('guidance-msg').textContent = '都空着？可以直接跳过';
        return;
      }
    }
    $('guidance-msg').textContent = '保存中…';
    fetch('/api/profile', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(skipped ? { skipped: true } : { answers: answers }),
    }).then(function (r) { return r.json(); }).then(function () {
      $('guidance-wrap').classList.add('hidden');
    }).catch(function () {
      $('guidance-msg').textContent = '保存失败（不影响本次分析）';
    });
  }

  // ---------- 提交分析 ----------
  function start() {
    var question = elQuestion.value.trim();
    if (!question) {
      elError.textContent = '先写下你想做的决定';
      elQuestion.focus();
      return;
    }
    elError.textContent = '';
    elStart.disabled = true;
    elStart.textContent = '正在创建分析…';

    var body = { question: question };
    if (selectedDepth) body.depth = selectedDepth; // 自动 = 不传，走意图识别

    fetch('/api/chat/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).then(function (r) {
      return r.json().then(function (j) { return { ok: r.ok, json: j }; });
    }).then(function (res) {
      var j = res.json || {};
      if (j.analysisId) {
        location.href = '/session/' + encodeURIComponent(j.analysisId);
        return;
      }
      // start 失败：后端落 error 事件 + 返回 {status:'error', error}；或 4xx 参数错误
      elError.textContent = j.error || j.message || '创建失败，请稍后重试';
      elStart.disabled = false;
      elStart.textContent = '开始分析 →';
    }).catch(function (e) {
      elError.textContent = '网络错误：' + e.message;
      elStart.disabled = false;
      elStart.textContent = '开始分析 →';
    });
  }

  // ---------- 绑定 ----------
  elStart.addEventListener('click', start);
  elQuestion.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) start();
  });
  $('btn-save-guidance').addEventListener('click', function () { saveGuidance(false); });
  $('btn-skip-guidance').addEventListener('click', function () { saveGuidance(true); });

  bindDepthPills();
  bindSuggestions();
  loadProfile();
})();
