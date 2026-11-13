/**
 * 历史页逻辑（Task 11.2）：
 * - GET /api/history 渲染分析列表（问题/类型/深度/日期/状态/进展/结论摘要/调用与搜索计数）
 * - 每条展开：三档预期（GET /api/analysis/{id}/expectations）→ 可标记进展（POST .../progress）
 *   + 填写实际结果（POST .../outcome，达成/未达成/部分达成 + 备注，预测后验留痕）
 * - 回看报告跳 /report/{id}（复用既有报告页）
 */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var listEl = $('h-list');
  var statsEl = $('h-stats');
  var emptyEl = $('h-empty');

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }
  function chip(text, cls) {
    return '<span class="chip ' + (cls || 'chip-faint') + '">' + esc(text) + '</span>';
  }
  function fmtDate(t) {
    if (!t) return '—';
    return String(t).substring(0, 16);
  }
  function fmtCost(ms) {
    if (!ms || ms <= 0) return '—';
    var sec = Math.round(ms / 1000);
    return sec >= 60 ? Math.floor(sec / 60) + '分' + (sec % 60) + '秒' : sec + '秒';
  }

  var STATUS_CLS = { done: 'chip-ok', error: 'chip-danger', cancelled: 'chip-faint', running: 'chip-data', waiting: 'chip-warn' };
  var STATUS_LABEL = { done: '已完成', error: '失败', cancelled: '已终止', running: '分析中', waiting: '等你回答追问' };
  var PROGRESS_OPTIONS = ['not_started', 'in_progress', 'done', 'abandoned'];
  var PROGRESS_LABEL = { not_started: '未开始', in_progress: '进行中', done: '已完成', abandoned: '已放弃' };
  var ACTUAL_OPTIONS = ['达成', '部分达成', '未达成'];

  function statusChip(s) { return chip(STATUS_LABEL[s] || s, STATUS_CLS[s] || 'chip-faint'); }
  function progressChip(p) { return chip(PROGRESS_LABEL[p] || p, p === 'done' ? 'chip-ok' : p === 'abandoned' ? 'chip-warn' : 'chip-faint'); }

  // 未完成的分析：主按钮是"继续会话"（回到 /session/{id} 回答追问/看进度），done 才是"回看报告"
  function actionButtons(r) {
    var id = encodeURIComponent(r.id);
    var main;
    if (r.status === 'done') {
      main = '<a class="btn-ghost btn-sm" href="/report/' + id + '">回看报告</a>';
    } else if (r.status === 'waiting') {
      main = '<a class="btn-primary btn-sm" href="/session/' + id + '">继续会话 · 去回答</a>';
    } else if (r.status === 'running') {
      main = '<a class="btn-primary btn-sm" href="/session/' + id + '">进入会话 · 看进度</a>';
    } else {
      main = '<a class="btn-ghost btn-sm" href="/session/' + id + '">查看会话</a>';
    }
    // 删除：running 后台仍在写数据，置灰不可点
    var del = r.status === 'running'
      ? '<button class="btn-ghost btn-sm danger" disabled title="分析进行中不能删除，等完成或终止后再删">删除</button>'
      : '<button class="btn-ghost btn-sm danger btn-del" data-id="' + esc(r.id) + '" data-q="' + esc(r.question || '') + '">删除</button>';
    return main + del;
  }

  // ---------- 渲染 ----------
  function renderStats(rows) {
    var total = rows.length;
    var done = rows.filter(function (r) { return r.status === 'done'; }).length;
    var doneProgress = rows.filter(function (r) { return r.progress === 'done'; }).length;
    statsEl.innerHTML =
      tItem('分析总数', total) + tItem('已出报告', done) + tItem('已标记完成', doneProgress)
      + tItem('零成本引用', rows.filter(function (r) { return r.searchCount === 0; }).length + ' 次');
    function tItem(k, v) {
      return '<div class="t-item"><span class="k" style="color:var(--faint);">' + esc(k) + '</span>'
        + '<span class="v">' + esc(v) + '</span></div>';
    }
  }

  function renderRows(rows) {
    listEl.innerHTML = '';
    rows.forEach(function (r) {
      var card = document.createElement('div');
      card.className = 'report-card';
      card.innerHTML = ''
        + '<div class="flex items-start justify-between gap-4 flex-wrap">'
        +   '<div class="flex-1 min-w-0">'
        +     '<div class="text-sm md:text-base font-semibold" style="color:var(--text);">' + esc(r.question || '（无问题）') + '</div>'
        +     (r.summary ? '<div class="text-xs mt-2 leading-relaxed" style="color:var(--muted);">' + esc(r.summary) + '</div>' : '')
        +     '<div class="flex flex-wrap gap-2 mt-3 items-center">'
        +       chip(r.type || '—') + chip(r.depth || '—') + statusChip(r.status) + progressChip(r.progress)
        +       chip('模型 ' + (r.modelCalls || 0) + ' 次')
        +       chip('搜索 ' + (r.searchCount || 0) + ' 次', r.searchCount === 0 ? 'chip-ok' : 'chip-warn')
        +       chip('耗时 ' + fmtCost(r.totalCostMs))
        +       chip(fmtDate(r.createdAt))
        +     '</div>'
        +   '</div>'
        +   '<div class="flex gap-2 shrink-0">'
        +     actionButtons(r)
        +     '<button class="btn-ghost btn-sm btn-toggle" data-id="' + esc(r.id) + '">回顾详情</button>'
        +   '</div>'
        + '</div>'
        + '<div class="detail hidden mt-4 pt-4" style="border-top:1px dashed var(--border-soft);" data-id="' + esc(r.id) + '"></div>';
      listEl.appendChild(card);
    });
    listEl.querySelectorAll('.btn-toggle').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var id = btn.dataset.id;
        var detail = listEl.querySelector('.detail[data-id="' + id + '"]');
        var opened = !detail.classList.contains('hidden');
        if (opened) {
          detail.classList.add('hidden');
          btn.textContent = '回顾详情';
          return;
        }
        btn.textContent = '收起';
        detail.classList.remove('hidden');
        loadDetail(id, detail);
      });
    });
    listEl.querySelectorAll('.btn-del').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var id = btn.dataset.id;
        var q = btn.dataset.q || '';
        var shortQ = q.length > 30 ? q.substring(0, 30) + '…' : q;
        if (!confirm('删除这条分析？\n\n「' + shortQ + '」\n\n会话记录、报告、三档预期、调用明细将一并删除，不可恢复。')) return;
        btn.disabled = true;
        btn.textContent = '删除中…';
        fetch('/api/analysis/' + encodeURIComponent(id), { method: 'DELETE' })
          .then(function (r) {
            if (!r.ok) {
              return r.json().then(function (j) { throw new Error(j.message || j.error || ('HTTP ' + r.status)); },
                function () { throw new Error('HTTP ' + r.status); });
            }
            return r.json();
          })
          .then(function () {
            var card = btn.closest('.report-card');
            if (card) card.remove();
            return fetch('/api/history').then(function (r) { return r.json(); });
          })
          .then(function (rows) {
            rows = rows || [];
            if (!rows.length) {
              listEl.innerHTML = '';
              statsEl.style.display = 'none';
              emptyEl.classList.remove('hidden');
              return;
            }
            renderStats(rows);
          })
          .catch(function (e) {
            btn.disabled = false;
            btn.textContent = '删除';
            alert('删除失败：' + e.message);
          });
      });
    });
  }

  // ---------- 详情：三档预期 + 进展 + 实际结果 ----------
  function loadDetail(id, el) {
    el.innerHTML = '<div class="text-xs" style="color:var(--faint);">加载回顾数据…</div>';
    fetch('/api/analysis/' + encodeURIComponent(id) + '/expectations')
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (expectations) {
        var expRows = (expectations || []).map(function (e) {
          return '<tr>'
            + '<td class="px-3 py-2">' + esc(e.scenario || '—') + '</td>'
            + '<td class="px-3 py-2">' + esc(e.expectation || '') + '</td>'
            + '<td class="px-3 py-2">' + (e.probability == null ? '—' : esc(Math.round(e.probability * 100) + '%')) + '</td>'
            + '<td class="px-3 py-2">' + esc(e.actual_result || '—') + '</td>'
            + '<td class="px-3 py-2 text-xs" style="color:var(--faint);">' + esc(e.actual_note || '') + '</td>'
            + '</tr>';
        }).join('');
        el.innerHTML = ''
          + '<div class="text-xs font-semibold mb-2" style="color:var(--muted);">三档量化预期（预测后验）</div>'
          + (expRows
              ? '<div class="overflow-x-auto mb-4"><table class="w-full text-xs" style="border-collapse:collapse;">'
                + '<thead><tr style="color:var(--faint);border-bottom:1px solid var(--border);">'
                + '<th class="px-3 py-2 text-left">档位</th><th class="px-3 py-2 text-left">当时预期</th>'
                + '<th class="px-3 py-2 text-left">概率</th><th class="px-3 py-2 text-left">实际结果</th>'
                + '<th class="px-3 py-2 text-left">备注</th></tr></thead><tbody>' + expRows + '</tbody></table></div>'
              : '<div class="text-xs mb-4" style="color:var(--faint);">该分析无三档量化预期（轻量路径或未完成）</div>')
          + '<div class="flex flex-wrap gap-3 items-end mb-4">'
          +   '<div><div class="text-xs mb-1" style="color:var(--faint);">标记进展（用户主动）</div>'
          +     '<select id="sel-progress" class="field" style="width:auto;padding:6px 10px;font-size:12px;">'
          +       PROGRESS_OPTIONS.map(function (p) { return '<option value="' + p + '">' + PROGRESS_LABEL[p] + '</option>'; }).join('')
          +     '</select></div>'
          +   '<button class="btn-ghost btn-sm" id="btn-progress">保存进展</button>'
          +   '<div class="flex-1"></div>'
          + '</div>'
          + '<div class="text-xs mb-1" style="color:var(--faint);">填写实际结果（达成 / 部分达成 / 未达成）</div>'
          + '<div class="flex flex-wrap gap-3 items-end">'
          +   '<select id="sel-outcome" class="field" style="width:auto;padding:6px 10px;font-size:12px;">'
          +     ACTUAL_OPTIONS.map(function (a) { return '<option value="' + a + '">' + a + '</option>'; }).join('')
          +   '</select>'
          +   '<input id="inp-note" class="field" style="flex:1;min-width:200px;padding:6px 10px;font-size:12px;" placeholder="备注（如：实际每周投入、延迟原因…）">'
          +   '<button class="btn-primary btn-sm" id="btn-outcome">提交实际结果</button>'
          + '</div>';
        bindActions(id, el);
      })
      .catch(function () {
        el.innerHTML = '<div class="text-xs" style="color:var(--danger);">回顾数据加载失败</div>';
      });
  }

  function bindActions(id, el) {
    var p = el.querySelector('#btn-progress');
    p.addEventListener('click', function () {
      var val = el.querySelector('#sel-progress').value;
      fetch('/api/analysis/' + encodeURIComponent(id) + '/progress', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ progress: val }),
      }).then(function (r) { return r.json(); }).then(function (j) {
        p.textContent = '已保存（' + (PROGRESS_LABEL[j.progress] || j.progress) + '）';
        setTimeout(function () { location.reload(); }, 600);
      }).catch(function () { alert('保存进展失败'); });
    });
    var o = el.querySelector('#btn-outcome');
    o.addEventListener('click', function () {
      var result = el.querySelector('#sel-outcome').value;
      var note = el.querySelector('#inp-note').value || '';
      fetch('/api/analysis/' + encodeURIComponent(id) + '/outcome', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ actualResult: result, note: note }),
      }).then(function (r) { return r.json(); }).then(function (j) {
        o.textContent = '已留痕（' + j.actualResult + '）';
        setTimeout(function () { location.reload(); }, 600);
      }).catch(function () { alert('提交实际结果失败'); });
    });
  }

  // ---------- 加载 ----------
  fetch('/api/history')
    .then(function (r) { return r.json(); })
    .then(function (rows) {
      rows = rows || [];
      if (!rows.length) {
        emptyEl.classList.remove('hidden');
        statsEl.style.display = 'none';
        return;
      }
      renderStats(rows);
      renderRows(rows);
    })
    .catch(function () {
      listEl.innerHTML = '<div class="report-card text-sm" style="color:var(--danger);">历史列表加载失败，请确认服务已启动。</div>';
    });
})();
