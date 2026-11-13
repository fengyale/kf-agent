/**
 * 统计页逻辑（Task 13.1）：
 * - GET /api/stats 一次拉三块聚合：
 *   ① issueTop —— 高频缺陷分布（红队质疑点关键词聚合 top N，横向 bar）
 *   ② buckets —— 分桶校准系数（CalibrationService 四桶：系数/样本/冷启动/asOf）
 *   ③ monthlyTrend —— 行为完成率按月趋势（非自评数据，柱状）
 *   ④ traceAnalyses —— 有调用 trace 的分析列表（选择器）
 * - 选择分析后 GET /api/analysis/{id}/trace 渲染调用明细表（步骤/token/耗时/模板hash）
 */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }
  function chip(text, cls) {
    return '<span class="chip ' + (cls || 'chip-faint') + '">' + esc(text) + '</span>';
  }
  function fmtCost(ms) {
    if (!ms || ms <= 0) return '—';
    var sec = Math.round(ms / 1000);
    return sec >= 60 ? Math.floor(sec / 60) + '分' + (sec % 60) + '秒' : sec + '秒';
  }
  function fmtDate(t) {
    if (!t) return '—';
    return String(t).substring(0, 10);
  }

  // ---------- ① 高频缺陷分布（横向 bar） ----------
  function renderIssues(items, totalText) {
    var el = $('issue-bars');
    if (!items || !items.length) {
      el.innerHTML = '<div class="issue-empty">暂无红队质疑记录（deep 路径分析产生质疑后此处可见高频缺陷主题）。</div>';
      $('issue-sub').textContent = totalText || '红队质疑点关键词聚合（计数）';
      return;
    }
    $('issue-sub').textContent = totalText || ('红队质疑点关键词聚合 · Top ' + items.length);
    var max = items[0].count;
    el.innerHTML = items.map(function (it) {
      var w = Math.max(6, Math.round(it.count / max * 100));
      return '<div class="issue-bar-row">'
        + '<span class="kw" title="' + esc(it.keyword) + '">' + esc(it.keyword) + '</span>'
        + '<div class="issue-bar-track"><div class="issue-bar-fill" style="width:' + w + '%"></div></div>'
        + '<span class="cnt">' + esc(it.count) + '</span></div>';
    }).join('');
  }

  // ---------- ② 分桶系数 + 月度趋势 ----------
  function renderBuckets(buckets) {
    var el = $('bucket-grid');
    if (!buckets || !buckets.length) {
      el.innerHTML = '<div class="issue-empty">暂无分桶数据</div>';
      return;
    }
    el.innerHTML = buckets.map(function (b) {
      return '<div class="bucket-item">'
        + '<div class="bt-type">' + esc(b.type) + '桶'
        + (b.coldStart ? ' <span class="chip chip-warn" style="margin-left:6px;font-size:10px;">建议校准</span>' : '')
        + '</div>'
        + '<div class="bt-coef">' + esc(b.coefficient) + '</div>'
        + '<div class="bt-note">' + esc(b.note || '') + '</div>'
        + '</div>';
    }).join('');
  }

  function renderTrend(months) {
    var el = $('trend-chart');
    if (!months || !months.length) {
      el.innerHTML = '<div class="trend-empty">暂无行为记录（行为数据页录入打卡/完成情况后此处可见趋势）。</div>';
      return;
    }
    el.innerHTML = months.map(function (m) {
      var h = Math.max(4, Math.round(m.rate * 120));
      return '<div class="trend-col">'
        + '<span class="rate">' + Math.round(m.rate * 100) + '%</span>'
        + '<div class="bar' + (m.rate < 0.5 ? ' low' : '') + '" style="height:' + h + 'px;"></div>'
        + '<span class="month">' + esc(m.month) + '</span>'
        + '<span class="n">' + esc(m.completed) + '/' + esc(m.total) + ' 案</span>'
        + '</div>';
    }).join('');
  }

  // ---------- ③ 红队体检（自审机制有效性：没有这组数字就只能凭感觉判断红队在不在干活） ----------
  function renderRedteamHealth(h) {
    var el = $('redteam-health');
    if (!el) return;
    if (!h || !h.analyses) {
      el.innerHTML = '<div class="issue-empty">暂无红队数据（deep 路径分析才会产生红队质疑环节）。</div>';
      return;
    }
    function pct(v) { return Math.round((v || 0) * 100) + '%'; }
    var items = [
      { k: '红队分析数', v: h.analyses, note: 'deep 路径（含红队环节）的分析总数' },
      { k: '平均质疑轮次', v: h.avgRounds, note: '轮次上限 3 轮' },
      { k: '收敛占比', v: pct(h.convergedRate), note: '红队未发现严重质疑的占比' },
      { k: '被预算/轮次截断', v: pct(h.budgetStoppedRate), note: '未跑满"质疑-修订-复审"闭环' },
      { k: '出现空转（零质疑）', v: pct(h.emptyRoundRate), note: '不判通过：仅表示未经实质审查' },
      { k: '出现假修订', v: pct(h.noSubstanceRate), note: '修订未见实质数值变化' },
      { k: '严重质疑总数', v: h.majorCount, note: '含已被修订解决的' },
      { k: '其中代码校验判定', v: h.codeMajorCount + '（' + pct(h.codeMajorShare) + '）', note: '确定性校验，模型无权降级' }
    ];
    el.innerHTML = items.map(function (it) {
      return '<div class="bucket-item">'
        + '<div class="bt-type">' + esc(it.k) + '</div>'
        + '<div class="bt-coef">' + esc(it.v) + '</div>'
        + '<div class="bt-note">' + esc(it.note) + '</div>'
        + '</div>';
    }).join('');
  }

  // ---------- ④ 调用明细（分析选择器 + trace 表） ----------
  function renderTracePicker(analyses) {
    var el = $('trace-picker');
    if (!analyses || !analyses.length) {
      $('trace-panel').innerHTML = '<div class="issue-empty">暂无调用 trace 记录（完成一次分析后此处可见调用明细）。</div>';
      return;
    }
    analyses.forEach(function (a) {
      var btn = document.createElement('button');
      btn.className = 'btn-ghost btn-sm';
      btn.textContent = (a.question || a.id) + ' · ' + fmtDate(a.createdAt) + ' · ' + a.calls + ' 次';
      btn.title = '调用 ' + a.calls + ' 次 · 总耗时 ' + fmtCost(a.totalMs);
      btn.addEventListener('click', function () {
        el.querySelectorAll('button').forEach(function (b) { b.classList.remove('active'); });
        btn.classList.add('active');
        loadTrace(a.id, btn);
      });
      el.appendChild(btn);
    });
  }

  function loadTrace(id, btn) {
    var panel = $('trace-panel');
    panel.innerHTML = '<div class="text-xs" style="color:var(--faint);">加载调用明细…</div>';
    fetch('/api/analysis/' + encodeURIComponent(id) + '/trace')
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (trace) {
        if (!trace || !trace.length) {
          panel.innerHTML = '<div class="issue-empty">该分析无调用 trace 记录</div>';
          return;
        }
        var totalTok = 0, totalMs = 0;
        trace.forEach(function (t) {
          totalTok += (t.prompt_tokens || 0) + (t.completion_tokens || 0);
          totalMs += t.duration_ms || 0;
        });
        var rows = trace.map(function (t) {
          return '<tr>'
            + '<td>' + esc(t.call_seq) + '</td>'
            + '<td class="step">' + esc(t.step || '—') + '</td>'
            + '<td class="tok">' + esc(t.prompt_tokens == null ? '—' : t.prompt_tokens) + '</td>'
            + '<td class="tok">' + esc(t.completion_tokens == null ? '—' : t.completion_tokens) + '</td>'
            + '<td>' + esc(fmtCost(t.duration_ms)) + '</td>'
            + '<td class="hash">' + esc(t.template_hash || '—') + '</td>'
            + '<td style="color:var(--faint);">' + esc(fmtDate(t.created_at)) + '</td>'
            + '</tr>';
        }).join('');
        panel.innerHTML = '<div class="flex flex-wrap gap-3 mb-3">'
          + chip('合计 ' + trace.length + ' 次调用', 'chip-info')
          + chip('总 token（近似）' + totalTok)
          + chip('总耗时 ' + fmtCost(totalMs))
          + '</div>'
          + '<div class="overflow-x-auto"><table class="trace-table"><thead><tr>'
          + '<th>#</th><th>步骤</th><th>入 token</th><th>出 token</th><th>耗时</th><th>模板 hash</th><th>时间</th>'
          + '</tr></thead><tbody>' + rows + '</tbody></table></div>';
      })
      .catch(function () {
        panel.innerHTML = '<div class="issue-empty" style="color:var(--danger);">调用明细加载失败</div>';
      });
  }

  // ---------- 加载 ----------
  fetch('/api/stats')
    .then(function (r) { return r.json(); })
    .then(function (data) {
      data = data || {};
      var issues = data.issueTop || [];
      renderIssues(issues, '红队质疑点关键词聚合 · 共 ' + (data.issueTotal || issues.length) + ' 个高频主题');
      renderBuckets(data.buckets);
      renderTrend(data.monthlyTrend);
      renderRedteamHealth(data.redteamHealth);
      renderTracePicker(data.traceAnalyses);
    })
    .catch(function () {
      $('issue-bars').innerHTML = '<div class="issue-empty" style="color:var(--danger);">统计页加载失败，请确认服务已启动。</div>';
    });
})();
