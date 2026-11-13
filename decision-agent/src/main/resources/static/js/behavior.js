/**
 * 行为数据页逻辑（Task 8 前端）：
 * - GET    /api/behavior          四桶校准总览 + 记录列表（可按桶过滤）
 * - POST   /api/behavior          手填表单录入（可标记自评：仅展示不参与校准）
 * - POST   /api/behavior/extract  粘贴文本 → LLM 提取预览（不入库，可逐行修改）
 * - POST   /api/behavior/import   预览确认后入库
 * - DELETE /api/behavior/{id}     删除记录
 * 任一写入操作后刷新校准总览与列表（B 档修正系数实时可见变化）
 */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var TYPES = ['日常事务', '学习计划', '远期规划', '商业经营'];
  var filterType = '';

  // ---------- 工具 ----------
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }
  function numOrEmpty(v) { return v == null || v === '' ? '—' : v; }
  function today() {
    var d = new Date();
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  }
  function errText(j, status) {
    return (j && (j.message || j.detail || j.error)) || ('请求失败（HTTP ' + status + '）');
  }
  function parseJson(res) {
    return res.json().then(function (j) {
      if (!res.ok) { var e = new Error(errText(j, res.status)); e.handled = true; throw e; }
      return j || {};
    });
  }
  function coefColor(c) {
    return c < 0.4 ? 'var(--danger)' : c < 0.7 ? 'var(--warn)' : 'var(--ok)';
  }

  // ---------- 四桶校准总览 ----------
  function renderCalibrations(cals) {
    var grid = $('cal-grid');
    grid.innerHTML = (cals || []).map(function (c) {
      var p = c.percentiles || null;
      return '<div class="scenario-card">'
        + '<div class="flex items-center justify-between">'
        + '<div class="sc-label">' + esc(c.type) + '</div>'
        + '<span class="chip ' + (c.coldStart ? 'chip-faint' : 'chip-data') + '">'
        + (c.coldStart ? '冷启动' : esc(c.sampleSize) + ' 案') + '</span>'
        + '</div>'
        + '<div class="flex items-baseline gap-2">'
        + '<span class="text-2xl font-bold" style="color:' + coefColor(c.coefficient) + ';">' + esc(c.coefficient) + '</span>'
        + '<span class="text-xs" style="color:var(--faint);">完成率系数' + (c.coldStart ? '（默认）' : '') + '</span>'
        + '</div>'
        + (p ? '<div class="flex flex-wrap gap-1.5">'
          + '<span class="chip chip-ok">P75 ' + esc(p.p75) + '</span>'
          + '<span class="chip chip-info">P50 ' + esc(p.p50) + '</span>'
          + '<span class="chip chip-warn">P25 ' + esc(p.p25) + '</span>'
          + '</div>' : '<div class="text-xs" style="color:var(--faint);">分位待积累（需 ≥4 条含计划/实际值的记录）</div>')
        + (c.stale ? '<span class="chip chip-warn" style="align-self:flex-start;">已 ' + esc(c.staleMonths) + ' 个月未校准</span>' : '')
        + '<div class="text-xs" style="color:var(--faint);line-height:1.6;">' + esc(c.note || '') + '</div>'
        + (c.asOf ? '<div class="text-xs" style="color:var(--faint);">asOf：' + esc(c.asOf) + '</div>' : '')
        + '</div>';
    }).join('');
  }

  // ---------- 记录列表 ----------
  function renderPills() {
    var pills = [''].concat(TYPES).map(function (t) {
      var active = filterType === t;
      return '<button class="btn-ghost btn-sm bucket-pill" data-bucket="' + esc(t) + '"'
        + (active ? ' style="border-color: var(--accent); color: var(--text);"' : '') + '>'
        + (t || '全部') + '</button>';
    }).join('');
    $('bucket-pills').innerHTML = pills;
  }

  function renderRecords(records) {
    var body = $('records-body');
    if (!records || !records.length) {
      body.innerHTML = '<p class="text-xs" style="color:var(--faint);">'
        + '暂无记录。先用上方任一入口录入几条真实行为，'
        + (filterType ? esc(filterType) + '桶' : '') + '校准将从「默认 0.7」切换为你的真实完成率。</p>';
      return;
    }
    body.innerHTML = records.map(function (r) {
      var done = r.completed ? '<span class="chip chip-ok">已完成</span>' : '<span class="chip chip-warn">未完成</span>';
      var self = r.selfEval ? '<span class="badge-assume">自评 · 不参与校准</span>' : '';
      return '<div class="gap-item flex items-start justify-between gap-3">'
        + '<div class="min-w-0 flex-1">'
        + '<div class="gap-main flex items-center gap-2 flex-wrap">'
        + '<span class="chip chip-info">' + esc(r.type) + '</span>' + done + self
        + '<span>' + esc(r.title || '（无标题）') + '</span>'
        + '</div>'
        + '<div class="gap-flow">计划 ' + esc(numOrEmpty(r.plannedValue)) + ' <span class="to">→</span> 实际 '
        + esc(numOrEmpty(r.actualValue))
        + ' · 发生于 ' + esc(r.occurredAt || '—')
        + ' · 来源 ' + esc(r.source || '—')
        + '</div>'
        + '</div>'
        + '<button class="btn-ghost btn-sm danger flex-shrink-0" data-del="' + esc(r.id) + '">删除</button>'
        + '</div>';
    }).join('');
  }

  function load() {
    var url = '/api/behavior' + (filterType ? ('?type=' + encodeURIComponent(filterType)) : '');
    fetch(url).then(parseJson).then(function (j) {
      renderCalibrations(j.calibrations);
      renderRecords(j.records);
    }).catch(function (e) {
      $('records-body').innerHTML = '<p class="text-xs" style="color:var(--danger);">加载失败：' + esc(e.message) + '</p>';
    });
  }

  // ---------- ① 表单录入 ----------
  function saveForm() {
    var btn = $('btn-form-save');
    var msg = $('form-msg');
    var body = {
      type: $('form-type').value,
      title: $('form-title').value.trim(),
      plannedValue: $('form-planned').value === '' ? null : Number($('form-planned').value),
      actualValue: $('form-actual').value === '' ? null : Number($('form-actual').value),
      completed: $('form-completed').checked,
      occurredAt: $('form-when').value,
      isSelfEval: $('form-self').checked,
    };
    if (!body.type) { msg.style.color = 'var(--danger)'; msg.textContent = '请选择问题类型'; return; }
    btn.disabled = true;
    msg.style.color = 'var(--faint)';
    msg.textContent = '保存中…';
    fetch('/api/behavior', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).then(parseJson).then(function (j) {
      btn.disabled = false;
      var cal = j.calibration || {};
      msg.style.color = 'var(--ok)';
      msg.textContent = '已保存。' + esc(cal.type || body.type) + '桶当前系数 '
        + (cal.coefficient != null ? cal.coefficient : '—')
        + (cal.coldStart ? '（冷启动，数据还不足）' : '');
      $('form-title').value = '';
      $('form-planned').value = '';
      $('form-actual').value = '';
      $('form-completed').checked = false;
      $('form-self').checked = false;
      load();
    }).catch(function (e) {
      btn.disabled = false;
      msg.style.color = 'var(--danger)';
      msg.textContent = e.message;
    });
  }

  // ---------- ② 粘贴导入：提取 → 预览 → 确认 ----------
  function extract() {
    var btn = $('btn-extract');
    var msg = $('import-msg');
    var text = $('import-text').value.trim();
    if (!text) { msg.style.color = 'var(--danger)'; msg.textContent = '请先粘贴要提取的文本'; return; }
    btn.disabled = true;
    msg.style.color = 'var(--faint)';
    msg.textContent = 'LLM 提取中…（数秒）';
    fetch('/api/behavior/extract', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: text }),
    }).then(parseJson).then(function (j) {
      btn.disabled = false;
      var records = j.records || [];
      if (!records.length) {
        msg.style.color = 'var(--warn)';
        msg.textContent = '未从文本中提取到行为记录，换个写法试试';
        return;
      }
      msg.style.color = 'var(--faint)';
      msg.textContent = '提取到 ' + records.length + ' 条，确认前可修改';
      renderExtractPreview(records);
    }).catch(function (e) {
      btn.disabled = false;
      msg.style.color = 'var(--danger)';
      msg.textContent = e.message;
    });
  }

  function renderExtractPreview(records) {
    $('extract-preview').classList.remove('hidden');
    $('extract-rows').innerHTML = records.map(function (r, i) {
      var done = String(r.completed) === 'true';
      return '<div class="gap-item ex-row" data-idx="' + i + '">'
        + '<div class="grid grid-cols-2 md:grid-cols-5 gap-2 items-center">'
        + '<input class="field ex-note" style="grid-column:span 2;" value="' + esc(r.note || '') + '" placeholder="简述">'
        + '<input class="field ex-planned" type="number" step="any" min="0" value="' + esc(r.plannedValue == null ? '' : r.plannedValue) + '" placeholder="计划">'
        + '<input class="field ex-actual" type="number" step="any" min="0" value="' + esc(r.actualValue == null ? '' : r.actualValue) + '" placeholder="实际">'
        + '<input class="field ex-when" type="date" value="' + esc((r.occurredAt || '').slice(0, 10)) + '">'
        + '</div>'
        + '<label class="flex items-center gap-2 text-xs mt-2 cursor-pointer" style="color:var(--muted);">'
        + '<input type="checkbox" class="ex-done accent-violet-500"' + (done ? ' checked' : '') + '> 整体已完成'
        + '</label>'
        + '</div>';
    }).join('');
  }

  function confirmImport() {
    var btn = $('btn-import');
    var msg = $('import-msg');
    var type = $('import-type').value;
    if (!type) { msg.style.color = 'var(--danger)'; msg.textContent = '请选择导入到哪个桶'; return; }
    var records = Array.prototype.map.call(document.querySelectorAll('#extract-rows .ex-row'), function (row) {
      return {
        note: row.querySelector('.ex-note').value.trim(),
        plannedValue: row.querySelector('.ex-planned').value === '' ? null : Number(row.querySelector('.ex-planned').value),
        actualValue: row.querySelector('.ex-actual').value === '' ? null : Number(row.querySelector('.ex-actual').value),
        completed: row.querySelector('.ex-done').checked ? 'true' : 'false',
        occurredAt: row.querySelector('.ex-when').value,
      };
    }).filter(function (r) { return r.note || r.plannedValue != null || r.actualValue != null; });
    if (!records.length) { msg.style.color = 'var(--danger)'; msg.textContent = '没有可导入的记录'; return; }

    btn.disabled = true;
    msg.style.color = 'var(--faint)';
    msg.textContent = '导入中…';
    fetch('/api/behavior/import', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type: type, records: records }),
    }).then(parseJson).then(function (j) {
      btn.disabled = false;
      var cal = j.calibration || {};
      msg.style.color = 'var(--ok)';
      msg.textContent = '已导入 ' + (j.imported || 0) + ' 条。' + esc(type) + '桶当前系数 '
        + (cal.coefficient != null ? cal.coefficient : '—')
        + (cal.coldStart ? '（冷启动，数据还不足）' : '');
      cancelExtract();
      $('import-text').value = '';
      load();
    }).catch(function (e) {
      btn.disabled = false;
      msg.style.color = 'var(--danger)';
      msg.textContent = e.message;
    });
  }

  function cancelExtract() {
    $('extract-preview').classList.add('hidden');
    $('extract-rows').innerHTML = '';
  }

  // ---------- 删除 ----------
  function removeRecord(id) {
    if (!window.confirm('确定删除这条记录？删除后校准系数会重新计算。')) return;
    fetch('/api/behavior/' + encodeURIComponent(id), { method: 'DELETE' })
      .then(parseJson)
      .then(load)
      .catch(function (e) { alert('删除失败：' + e.message); });
  }

  // ---------- 初始化 ----------
  function initSelects() {
    var opts = TYPES.map(function (t) { return '<option value="' + esc(t) + '">' + esc(t) + '</option>'; }).join('');
    $('form-type').innerHTML = opts;
    $('import-type').innerHTML = opts;
    $('form-when').value = today();
  }

  $('btn-form-save').addEventListener('click', saveForm);
  $('btn-extract').addEventListener('click', extract);
  $('btn-import').addEventListener('click', confirmImport);
  $('btn-extract-cancel').addEventListener('click', cancelExtract);

  $('bucket-pills').addEventListener('click', function (e) {
    var b = e.target.closest('.bucket-pill');
    if (!b) return;
    filterType = b.dataset.bucket || '';
    renderPills();
    load();
  });
  $('records-body').addEventListener('click', function (e) {
    var b = e.target.closest('[data-del]');
    if (b) removeRecord(b.dataset.del);
  });

  initSelects();
  renderPills();
  load();
})();
