/**
 * 报告页逻辑（v2 研究报告版式）：
 * 页面结构（report.html 三区）：
 *   首屏 #r-primary  —— 结论条（conclusion-hero）→ 三档对比矩阵 → 第四档（若有）
 *   折叠 #r-secondary —— ① 实施路径与差距（expando）② 风险与红队质疑（expando）
 *                        ③ 附录：假设与数据说明（expando）④ 思考流/调用明细（自带折叠面板）
 * 数据源两级：
 * 1. 结构化优先 —— GET /api/analysis/{id}/steps 返回 steps_output JSON（assess 三档 / path /
 *    gap 差距矩阵 / risk 风险清单 / redteam 红队），数据驱动渲染；结构化存在的区块，
 *    markdown 兜底自动跳过（hasStruct 预置 renderedTypes，避免同类型双渲染）
 * 2. markdown 兜底 —— 结构化缺失的区块继续按语义化 heading 分区渲染。
 * 导出全家桶 / 重新分析 / trace 摘要 沿用既有实现。
 */
(function () {
  'use strict';

  var analysisId = document.body.dataset.analysisId || '';
  var $ = function (id) { return document.getElementById(id); };
  var primaryEl = $('r-primary');
  var secondaryEl = $('r-secondary');

  // ---------- 工具 ----------
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }
  function inlineMd(s) {
    s = esc(s);
    s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/`([^`]+)`/g, '<code style="background:var(--panel-2);border:1px solid var(--border-soft);padding:1px 5px;border-radius:3px;font-size:12px;font-family:var(--mono);">$1</code>');
    return s;
  }
  function fmtCost(ms) {
    if (!ms || ms <= 0) return null;
    var sec = Math.round(ms / 1000);
    return sec >= 60 ? Math.floor(sec / 60) + ' 分 ' + (sec % 60) + ' 秒' : sec + ' 秒';
  }
  function card(title) {
    var c = document.createElement('div');
    c.className = 'report-card';
    c.innerHTML = '<h3>' + esc(title) + '</h3><div class="card-body"></div>';
    return c;
  }
  // 折叠组（明细区）
  function expando(title, sub) {
    var d = document.createElement('details');
    d.className = 'expando';
    var s = document.createElement('summary');
    s.innerHTML = esc(title) + (sub ? '<span class="sub">' + esc(sub) + '</span>' : '');
    var b = document.createElement('div');
    b.className = 'expando-body';
    d.appendChild(s);
    d.appendChild(b);
    return { root: d, body: b, has: function () { return b.childElementCount > 0; } };
  }
  function severityChip(text) {
    var t = String(text || '');
    var cls = /高|严重/.test(t) ? 'chip-danger' : /中/.test(t) ? 'chip-warn' : 'chip-ok';
    return '<span class="chip ' + cls + '">' + esc(t || '—') + '</span>';
  }
  // 概率/影响文本 → 数值（"高/中/低"、"65%"、"0.65"、"80" 均归一 [0,1]）
  function levelToNum(s) {
    var t = String(s == null ? '' : s).trim();
    var m = t.match(/(\d+(?:\.\d+)?)/);
    if (m) {
      var n = parseFloat(m[1]);
      if (n > 1) n = n / 100;
      return Math.max(0, Math.min(1, n));
    }
    if (/高/.test(t) || /high/i.test(t)) return 0.85;
    if (/中/.test(t) || /mid/i.test(t)) return 0.5;
    if (/低/.test(t) || /low/i.test(t)) return 0.15;
    return 0.5;
  }
  function str(o) { return o == null ? '' : String(o); }

  // ---------- Markdown 解析（兜底渲染保留） ----------
  function parseReport(md) {
    var lines = md.split('\n');
    var title = '', metaLine = '', footer = '';
    var sections = [];
    var cur = null;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (/^#\s+/.test(line) && !title) { title = line.replace(/^#\s+/, ''); continue; }
      if (/^>\s/.test(line) && !cur && !sections.length) { metaLine += (metaLine ? ' ' : '') + line.replace(/^>\s?/, ''); continue; }
      if (/^##\s+/.test(line)) {
        cur = { heading: line.replace(/^##\s+/, ''), body: [] };
        sections.push(cur);
        continue;
      }
      if (/^---+\s*$/.test(line)) {
        var rest = lines.slice(i + 1).join('\n').replace(/^\*|\*$/g, '').trim();
        if (rest) footer = rest;
        break;
      }
      if (cur) cur.body.push(line);
    }
    return { title: title, metaLine: metaLine, sections: sections, footer: footer };
  }

  // ================================================================
  // 结构化渲染（数据驱动，优先于 markdown）
  // ================================================================

  // ---------- 结论条（首屏第一区） ----------
  function renderConclusionHero(assess) {
    var conclusion = assess && assess.conclusion;
    if (!conclusion) return null;
    var c = card('可行性结论');
    var body = c.querySelector('.card-body');
    body.innerHTML = '<div class="conclusion-hero">' + inlineMd(conclusion) + '</div>';
    // 数据来源 asOf（数据保鲜度可见）
    var ds = assess.dataSources;
    if (Array.isArray(ds) && ds.length) {
      var chips = ds.map(function (d) {
        var label = str(d && d.source);
        if (d && d.asOf) label += '（asOf ' + esc(d.asOf) + '）';
        return '<span class="chip chip-faint">' + esc(label) + '</span>';
      }).join('');
      body.innerHTML += '<div class="conclusion-hero-sub"><strong>数据来源</strong><div class="flex flex-wrap gap-2 mt-2">' + chips + '</div></div>';
    }
    if (assess && assess.notes) {
      body.innerHTML += '<blockquote style="margin-top:10px;padding:6px 12px;border-left:2px solid var(--accent);background:var(--bg-soft);border-radius:0 4px 4px 0;font-size:12.5px;color:var(--muted);">'
        + inlineMd(assess.notes) + '</blockquote>';
    }
    return c;
  }

  // ---------- 三档并排对比卡（参数差异标注） ----------
  function renderScenariosStructured(scenarios) {
    if (!Array.isArray(scenarios) || !scenarios.length) return null;
    var list = scenarios.slice(0, 3);
    var grid = document.createElement('div');
    grid.className = 'scenario-grid';
    var fallback = { A: '乐观', B: '现实', C: '保守' };

    // A/C 参数差异分析：probability 对比色；weeklyHours/duration 差异大时高亮
    var a = list[0] || {}, c = list[2] || {};
    var diff = {};
    ['probability', 'weeklyHours', 'duration'].forEach(function (k) {
      if (a[k] == null || c[k] == null) return;
      var av = parseFloat(a[k]), cv = parseFloat(c[k]);
      if (isNaN(av) || isNaN(cv)) return;
      var base = Math.max(Math.abs(av), Math.abs(cv), 1e-6);
      diff[k] = { pct: Math.abs(av - cv) / base, a: av, c: cv };
    });

    list.forEach(function (s, i) {
      if (!s) return;
      var name = str(s.name);
      var grade = name.charAt(0).toUpperCase();
      if (!/^[A-C]$/.test(grade)) grade = ['A', 'B', 'C'][i];
      var label = name.length > 1 ? name.slice(1).trim() : (fallback[grade] || '');
      var gCls = grade === 'A' ? 'g-a' : grade === 'B' ? 'g-b' : 'g-c';
      var rows = '';

      // 概率：A/C 差异大时对比色（A 高 → 绿 hi，C 低 → 赭 lo）
      if (s.probability != null) {
        var pv = String(s.probability);
        var pCls = '';
        if (i === 0 && diff.probability && diff.probability.pct >= 0.2 && diff.probability.a > diff.probability.c) pCls = ' sc-value hi';
        if (i === 2 && diff.probability && diff.probability.pct >= 0.2 && diff.probability.a > diff.probability.c) pCls = ' sc-value lo';
        rows += '<div class="sc-row"><span class="k">达成概率</span><span class="v' + pCls + '">' + esc(pv) + '</span></div>';
      }
      // 周投入 / 周期：差异大时 A/C 高亮
      if (s.weeklyHours != null && s.duration != null) {
        var wCls = '', dCls = '';
        if (i === 0 && diff.weeklyHours && diff.weeklyHours.pct >= 0.25) wCls = ' sc-value';
        if (i === 2 && diff.weeklyHours && diff.weeklyHours.pct >= 0.25) wCls = ' sc-value';
        if (i === 0 && diff.duration && diff.duration.pct >= 0.25) dCls = ' sc-value';
        if (i === 2 && diff.duration && diff.duration.pct >= 0.25) dCls = ' sc-value';
        rows += '<div class="sc-row"><span class="k">投入</span><span class="v' + wCls + '">' + esc(s.weeklyHours) + ' 小时/周</span>'
          + '<span class="k" style="margin-left:4px;">周期</span><span class="v' + dCls + '">' + esc(s.duration) + ' 周</span></div>';
      } else if (s.weeklyHours != null || s.duration != null) {
        rows += '<div class="sc-row"><span class="k">投入</span><span class="v">'
          + esc(s.weeklyHours == null ? '?' : s.weeklyHours) + ' 小时/周 × '
          + esc(s.duration == null ? '?' : s.duration) + ' 周</span></div>';
      }
      // 里程碑列表：阶段 + 验收标准 + 触发降档条件
      var msHtml = '';
      if (Array.isArray(s.milestones) && s.milestones.length) {
        msHtml = '<ul class="sc-milestones">' + s.milestones.map(function (m) {
          return '<li><span class="m-name">' + esc(str(m && m.name)) + '</span>'
            + (m && m.criteria ? '<span class="m-criteria">' + esc(m.criteria) + '</span>' : '')
            + (m && m.demoteCondition ? '<span class="m-demote">' + esc(m.demoteCondition) + '</span>' : '')
            + '</li>';
        }).join('') + '</ul>';
      }
      // 触发降档条件（卡级，warn 高亮）
      if (s.demoteTrigger) {
        rows += '<div class="sc-row"><span class="k">触发降档</span>'
          + '<span class="chip chip-warn" style="text-align:left;white-space:normal;">' + esc(s.demoteTrigger) + '</span></div>';
      }

      var el = document.createElement('div');
      el.className = 'scenario-card ' + gCls;
      el.innerHTML = '<div class="flex items-center gap-2.5"><div class="grade">' + esc(grade) + '</div>'
        + '<div class="sc-label">' + esc(label) + '</div></div>' + rows + msHtml;
      grid.appendChild(el);
    });

    // A/C 差异说明（数据驱动）
    if (Object.keys(diff).length) {
      var notes = [];
      if (diff.probability && diff.probability.pct >= 0.2) {
        notes.push('达成概率 A 档与 C 档相差约 <b>' + Math.round(diff.probability.pct * 100) + '%</b>');
      }
      if (diff.weeklyHours && diff.weeklyHours.pct >= 0.25) {
        notes.push('周投入相差 <b>' + Math.abs(diff.weeklyHours.a - diff.weeklyHours.c).toFixed(1) + '</b> 小时/周');
      }
      if (diff.duration && diff.duration.pct >= 0.25) {
        notes.push('周期相差 <b>' + Math.abs(diff.duration.a - diff.duration.c).toFixed(0) + '</b> 周');
      }
      if (notes.length) {
        var dn = document.createElement('div');
        dn.className = 'sc-diff-note';
        dn.innerHTML = '参数差异标注：' + notes.join('，') + '（A 档与 C 档对比）';
        grid.appendChild(dn);
      }
    }
    var c = card('三档情景对比');
    c.querySelector('.card-body').appendChild(grid);
    return c;
  }

  // ---------- 风险矩阵四象限（概率×影响，纯 CSS 网格+定位） ----------
  function renderRiskMatrixStructured(risks) {
    if (!Array.isArray(risks) || !risks.length) return null;
    var c = card('风险矩阵');
    var body = c.querySelector('.card-body');
    var wrap = document.createElement('div');
    wrap.className = 'risk-matrix-wrap';

    var matrix = document.createElement('div');
    matrix.className = 'risk-matrix';
    matrix.innerHTML =
      '<div class="rm-axis y">影 响 →</div>'
      + '<div class="rm-axis x">概率 →</div>'
      + '<div class="rm-q q1"><b>需关注</b>高影响 · 低概率</div>'
      + '<div class="rm-q q2"><b>高危区</b>高影响 · 高概率</div>'
      + '<div class="rm-q q3"><b>低风险</b>低影响 · 低概率</div>'
      + '<div class="rm-q q4"><b>易发生</b>低影响 · 高概率</div>';

    var legend = '<div class="rm-legend mt-2">'
      + '<span class="lg"><i style="background:var(--danger);"></i>高风险</span>'
      + '<span class="lg"><i style="background:var(--warn);"></i>中风险</span>'
      + '<span class="lg"><i style="background:var(--ok);"></i>低风险</span>'
      + '</div>';

    risks.forEach(function (r, idx) {
      if (!r) return;
      var p = levelToNum(r.probability);
      var imp = levelToNum(r.impact);
      var score = p * imp;
      var sev = score >= 0.6 ? 2 : score >= 0.22 ? 1 : 0;
      var dot = document.createElement('div');
      dot.className = 'rm-dot sev-' + sev;
      dot.style.left = (8 + p * 84) + '%';
      dot.style.top = (6 + (1 - imp) * 88) + '%';
      dot.textContent = idx + 1;
      dot.title = str(r.risk) + '（概率 ' + str(r.probability) + ' × 影响 ' + str(r.impact) + '）';
      matrix.appendChild(dot);
    });
    wrap.appendChild(matrix);

    // 右侧：风险列表（序号对应散点）
    var side = document.createElement('div');
    side.className = 'rm-side';
    side.innerHTML = legend + '<div class="space-y-2"></div>';
    var listWrap = side.querySelector('.space-y-2');
    risks.forEach(function (r, idx) {
      if (!r) return;
      var sev = levelToNum(r.probability) * levelToNum(r.impact) >= 0.6 ? 2 : levelToNum(r.probability) * levelToNum(r.impact) >= 0.22 ? 1 : 0;
      var el = document.createElement('div');
      el.className = 'risk-item';
      el.innerHTML = '<div class="risk-text"><span class="chip ' + (sev === 2 ? 'chip-danger' : sev === 1 ? 'chip-warn' : 'chip-ok')
        + '" style="margin-right:8px;">' + (idx + 1) + '</span>' + inlineMd(r.risk) + '</div>'
        + '<div class="risk-meta"><span class="chip chip-warn">概率 ' + esc(r.probability) + '</span>'
        + '<span class="chip chip-danger">影响 ' + esc(r.impact) + '</span></div>'
        + (r.mitigation ? '<div class="mitigation"><span class="k">缓解</span>' + inlineMd(r.mitigation) + '</div>' : '');
      listWrap.appendChild(el);
    });
    wrap.appendChild(side);
    body.appendChild(wrap);
    return c;
  }

  // ---------- 差距矩阵表格化（现状 vs 目标 + kill criteria 警示） ----------
  function renderGapTableStructured(gaps) {
    if (!Array.isArray(gaps) || !gaps.length) return null;
    var c = card('差距矩阵');
    var body = c.querySelector('.card-body');
    var html = '<div class="overflow-x-auto"><table class="gap-table"><thead><tr>'
      + '<th>能力项</th><th>现状</th><th>目标</th><th>差距判定</th></tr></thead><tbody>';
    gaps.forEach(function (g) {
      if (!g) return;
      var kill = str(g.killCriteria);
      html += '<tr' + (kill ? ' class="row-kill"' : '') + '>'
        + '<td>' + severityChip(g.severity) + ' <span style="color:var(--text);font-weight:600;">' + esc(g.item) + '</span></td>'
        + '<td class="gap-cur">' + esc(g.current) + '</td>'
        + '<td class="gap-tgt">' + esc(g.target) + '</td>'
        + '<td>' + (kill
            ? '<span class="kill-tag">警示</span><span class="gap-kill">' + esc(kill) + '</span>'
            : '<span class="chip chip-ok">可达成</span>') + '</td>'
        + '</tr>';
    });
    html += '</tbody></table></div>';
    if (gaps.some(function (g) { return g && g.killCriteria; })) {
      html += '<div class="text-xs mt-3" style="color:var(--faint);">'
        + '<span style="color:var(--danger);">警示行</span> 为 kill criteria：触发即停止推进（决策权在你，Agent 只给依据）</div>';
    }
    body.innerHTML = html;
    return c;
  }

  // ---------- 路径阶段（结构化） ----------
  function renderPathStructured(stages, totalWeeks) {
    if (!Array.isArray(stages) || !stages.length) return null;
    var c = card('路径设计');
    var html = '<div>';
    stages.forEach(function (st, i) {
      html += '<div class="path-stage"><div class="no">' + (i + 1) + '</div><div class="flex-1 min-w-0">'
        + '<div style="font-size:13.5px;color:var(--text);"><strong>' + esc(str(st.name)) + '</strong>'
        + '<span class="chip chip-data" style="margin-left:8px;">' + esc(st.weeks == null ? '?' : st.weeks) + ' 周</span></div>'
        + '<div class="text-xs mt-1" style="color:var(--muted);line-height:1.7;">' + inlineMd(str(st.goal)) + '</div>'
        + (st.validation ? '<div class="text-xs mt-1" style="color:var(--ok);">验收 · ' + inlineMd(st.validation) + '</div>' : '')
        + '</div></div>';
    });
    html += '</div>';
    if (totalWeeks != null) html += '<p class="text-xs mt-3" style="color:var(--faint);">合计约 ' + esc(totalWeeks) + ' 周</p>';
    c.querySelector('.card-body').innerHTML = html;
    return c;
  }

  // ---------- 红队质疑（结构化：执行情况 + 跨轮质疑清单 + 代码校验标识） ----------
  function renderRedteamStructured(meta) {
    if (!meta) return null;
    var c = card('红队质疑');
    var body = c.querySelector('.card-body');
    // 优先跨轮累计（含已被修订解决的 major），避免单轮视角丢失历史质疑（与报告 Markdown 同口径）
    var challenges = Array.isArray(meta.allChallenges) && meta.allChallenges.length
      ? meta.allChallenges
      : (Array.isArray(meta.challenges) ? meta.challenges : []);
    var quote = '红队共 ' + (meta.rounds || 0) + ' 轮质疑：'
      + (meta.converged ? '未发现严重质疑' : '仍有未解决的严重质疑') + '。'
      + str(meta.note);
    var html = '<div class="text-xs mb-3" style="color:var(--faint);">' + esc(quote) + '</div>';
    // 执行情况：机制是否被预算/轮次截断、是否空转与假修订，如实展示
    var exec = [];
    if (meta.emptyRounds > 0) exec.push(meta.emptyRounds + ' 轮未提出实质质疑（未经实质审查）');
    if (meta.codeMajorCount > 0) exec.push('代码确定性校验判定 ' + meta.codeMajorCount + ' 条严重问题');
    if (meta.noSubstanceRounds > 0) exec.push(meta.noSubstanceRounds + ' 轮修订未见实质变化');
    if (meta.budgetStopped === true) exec.push('受预算/轮次上限截断，未跑满完整收敛闭环');
    if (exec.length) {
      html += '<div class="text-xs mb-3" style="color:var(--faint);">执行情况：' + esc(exec.join('；')) + '。</div>';
    }
    html += '<div class="space-y-3">';
    if (!challenges.length) {
      html += '<div class="text-xs" style="color:var(--faint);">红队未提出实质质疑（未经实质审查，不作为通过依据）。</div>';
    }
    challenges.forEach(function (it) {
      var major = /major|severe|严重/i.test(str(it.severity));
      var byCode = str(it.source) === 'code';
      html += '<div class="risk-item">'
        + '<div class="risk-text">' + inlineMd(str(it.point)) + '</div>'
        + '<div class="risk-meta"><span class="chip ' + (major ? 'chip-danger' : 'chip-warn') + '">'
        + esc(str(it.severity)) + '</span>'
        + (byCode ? '<span class="chip chip-info">代码校验</span>' : '')
        + (major && !byCode ? '<span class="chip chip-data">已触发修订轮</span>' : '') + '</div>'
        + (it.mitigation ? '<div class="mitigation"><span class="k">' + (major ? '修订' : '应对') + '</span>'
          + inlineMd(it.mitigation) + '</div>' : '')
        + '</div>';
    });
    body.innerHTML = html + '</div>';
    return c;
  }

  // ---------- 思考流面板（折叠 + 滚动 + 自动滚底） ----------
  function renderThinking(fragments) {
    if (!Array.isArray(fragments) || !fragments.length) return null;
    var panel = document.createElement('div');
    panel.className = 'think-panel report-think';
    var html = '<div class="think-head"><span class="caret">▶</span><span>思考流</span>'
      + '<span class="ml-auto" style="color:var(--faint);">模型推理过程 · 与正式结论区分展示</span></div>'
      + '<div class="think-body"></div>';
    panel.innerHTML = html;
    var head = panel.querySelector('.think-head');
    head.addEventListener('click', function () { panel.classList.toggle('open'); });
    var body = panel.querySelector('.think-body');
    fragments.forEach(function (f, i) {
      var frag = document.createElement('div');
      frag.className = 'think-frag';
      frag.innerHTML = '<div class="tf-meta"><span class="dot"></span>思考片段 ' + (i + 1)
        + '<span style="color:var(--faint);">· 非结论</span></div>'
        + '<div class="tf-text"></div>';
      frag.querySelector('.tf-text').textContent = f;
      body.appendChild(frag);
    });
    // 最后一条自动滚到底
    requestAnimationFrame(function () { body.scrollTop = body.scrollHeight; });
    return panel;
  }

  // ---------- 调用 trace 明细（步骤/token/耗时/模板hash） ----------
  function renderTraceDetail(trace) {
    if (!Array.isArray(trace) || !trace.length) return null;
    var panel = document.createElement('div');
    panel.className = 'think-panel report-think';
    var html = '<div class="think-head"><span class="caret">▶</span><span>调用明细</span>'
      + '<span class="ml-auto" style="color:var(--faint);">步骤 · token · 耗时 · 模板 hash（钱花在哪）</span></div>'
      + '<div class="think-body"><table class="trace-table"><thead><tr>'
      + '<th>#</th><th>步骤</th><th>入 token</th><th>出 token</th><th>耗时</th><th>模板 hash</th></tr></thead><tbody>';
    trace.forEach(function (t) {
      html += '<tr><td>' + esc(t.call_seq) + '</td>'
        + '<td class="step">' + esc(t.step || '—') + '</td>'
        + '<td class="tok">' + esc(t.prompt_tokens == null ? '—' : t.prompt_tokens) + '</td>'
        + '<td class="tok">' + esc(t.completion_tokens == null ? '—' : t.completion_tokens) + '</td>'
        + '<td>' + esc(fmtCost(t.duration_ms) || '—') + '</td>'
        + '<td class="hash">' + esc(t.template_hash || '—') + '</td></tr>';
    });
    html += '</tbody></table></div>';
    panel.innerHTML = html;
    panel.querySelector('.think-head').addEventListener('click', function () { panel.classList.toggle('open'); });
    return panel;
  }

  // ================================================================
  // Markdown 兜底渲染（结构化缺失的区块）
  // ================================================================
  function renderSection(sec, renderedTypes) {
    var h = sec.heading || '';
    var body = sec.body.join('\n');
    if (h.indexOf('三档情景') >= 0) return renderedTypes.scenarios ? null : renderScenarios(sec);
    if (h.indexOf('第四档') >= 0) return renderNoGo(sec);
    if (h.indexOf('红队') >= 0) return renderedTypes.redteam ? null : renderRedteamSection(sec);
    if (h.indexOf('结论') >= 0) return renderedTypes.conclusion ? null : renderConclusion(sec);
    if (h.indexOf('路径设计') >= 0) return renderedTypes.path ? null : renderPath(sec);
    if (h.indexOf('差距') >= 0) return renderedTypes.gap ? null : renderGap(sec);
    if (h.indexOf('风险') >= 0) return renderedTypes.risk ? null : renderRisk(sec);
    if (h.indexOf('已采集信息') >= 0) return renderCollected(sec);
    if (h.indexOf('假设') >= 0) return renderAssumptions(sec);
    if (h.indexOf('数据说明') >= 0) return renderCalcNote(sec);
    if (h.indexOf('退出原因') >= 0) return renderExit(sec);
    return renderGeneric(sec); // 建议 / 主要风险（轻量路径）/ 已知局限 / 校准与数据保鲜度 等
  }

  function renderConclusion(sec) {
    var c = card(sec.heading);
    var body = c.querySelector('.card-body');
    var incomplete = sec.heading.indexOf('未完成') >= 0;
    if (incomplete) body.innerHTML = '<span class="chip chip-warn mb-2">分析未完成</span>';
    var md = '<div class="report-md">' + mdBlock(sec.body.join('\n')) + '</div>';
    body.innerHTML += md;
    return c;
  }

  // 三档情景 markdown 兜底：kv 行通用渲染
  function renderScenarios(sec) {
    var c = card('三档情景对比');
    var body = c.querySelector('.card-body');
    var blocks = sec.body.join('\n').split(/\n(?=###\s)/).filter(function (b) { return b.trim(); });
    var grid = document.createElement('div');
    grid.className = 'scenario-grid';
    var note = '';
    blocks.forEach(function (b) {
      var ls = b.split('\n');
      var head = ls.shift().replace(/^###\s*/, '');
      var m = head.match(/^([A-Za-z])\s*档\s*[·:：]?\s*(.*)$/);
      var grade = m ? m[1].toUpperCase() : '·';
      var label = m ? m[2] : head;
      var rows = [];
      ls.forEach(function (l) {
        var mm = l.match(/^-\s*([^：:]+)[：:]\s*(.*)$/);
        if (mm) rows.push({ k: mm[1].trim(), v: mm[2].trim() });
      });
      var el = document.createElement('div');
      el.className = 'scenario-card g-' + (grade === 'A' ? 'a' : grade === 'B' ? 'b' : 'c');
      var rowsHtml = rows.map(function (r) {
        if (r.k === '投入') {
          var verified = r.v.indexOf('经计算校验') >= 0;
          var inv = r.v.replace('（经计算校验）', '').trim();
          return '<div class="sc-row"><span class="k">' + esc(r.k) + '</span><span class="v">' + esc(inv) + '</span>'
            + (verified ? '<span class="chip chip-ok">经计算校验</span>' : '') + '</div>';
        }
        if (r.k === '降档条件') {
          return '<div class="sc-row"><span class="k">' + esc(r.k) + '</span>'
            + '<span class="chip chip-warn" style="text-align:left;white-space:normal;">' + esc(r.v) + '</span></div>';
        }
        if (r.k === '前提假设') {
          return '<div class="sc-row"><span class="k">' + esc(r.k) + '</span>'
            + '<span class="badge-assume">基于假设</span>'
            + '<span class="v">' + esc(r.v) + '</span></div>';
        }
        return '<div class="sc-row"><span class="k">' + esc(r.k) + '</span><span class="v">' + esc(r.v) + '</span></div>';
      }).join('');
      el.innerHTML = '<div class="flex items-center gap-2.5"><div class="grade">' + esc(grade) + '</div>'
        + '<div class="sc-label">' + esc(label) + '</div></div>' + rowsHtml;
      grid.appendChild(el);
      var bq = ls.filter(function (l) { return /^>\s?/.test(l); }).map(function (l) { return l.replace(/^>\s?/, ''); }).join(' ');
      if (bq) note = bq;
    });
    body.appendChild(grid);
    if (note) {
      var n = document.createElement('p');
      n.className = 'text-xs mt-3';
      n.style.color = 'var(--faint)';
      n.textContent = '备注：' + note;
      body.appendChild(n);
    }
    return c;
  }

  function renderNoGo(sec) {
    var c = card('第四档 · 不建议以当前形式推进');
    var body = c.querySelector('.card-body');
    body.innerHTML = '<div class="mb-3"><span class="chip chip-danger">不建议以当前形式推进</span>'
      + '<span class="text-xs ml-2" style="color:var(--faint);">红队严重质疑 + 历史完成率双重确认</span></div>'
      + '<div class="report-md">' + mdBlock(sec.body.join('\n')) + '</div>';
    return c;
  }

  function renderRedteamSection(sec) {
    var c = card('红队质疑');
    var body = c.querySelector('.card-body');
    var quote = '';
    var items = [];
    sec.body.forEach(function (l) {
      var q = l.match(/^>\s?(.*)$/);
      if (q) { quote = quote || q[1]; return; }
      var m = l.match(/^-\s*\*\*\[(.+?)\]\*\*\s*(.+)$/);
      if (m) {
        var parts = m[2].split(/\s*→\s*/);
        var point = parts.shift();
        items.push({ sev: m[1], point: point, mit: parts.join(' → ') });
      }
    });
    var html = quote
      ? '<div class="text-xs mb-3" style="color:var(--faint);">' + esc(quote) + '</div>' : '';
    html += '<div class="space-y-3">';
    items.forEach(function (it) {
      var major = /major|severe|严重/i.test(it.sev);
      html += '<div class="risk-item">'
        + '<div class="risk-text">' + inlineMd(it.point) + '</div>'
        + '<div class="risk-meta"><span class="chip ' + (major ? 'chip-danger' : 'chip-warn') + '">'
        + esc(it.sev) + '</span>'
        + (major ? '<span class="chip chip-data">已触发修订轮</span>' : '') + '</div>'
        + (it.mit ? '<div class="mitigation"><span class="k">' + (major ? '修订' : '应对') + '</span>'
          + inlineMd(it.mit) + '</div>' : '')
        + '</div>';
    });
    html += '</div>';
    body.innerHTML = html;
    return c;
  }

  function renderPath(sec) {
    var c = card('路径设计');
    var items = [];
    var totalWeeks = null;
    sec.body.forEach(function (l) {
      var m = l.match(/^\d+\.\s+\*\*(.+?)\*\*（(.+?)）：(.*)$/);
      if (m) { items.push({ name: m[1], weeks: m[2], goal: m[3], validation: '' }); return; }
      var v = l.match(/^\s+-\s*验收[：:]\s*(.*)$/);
      if (v && items.length) { items[items.length - 1].validation = v[1]; return; }
      var t = l.match(/^合计约\s*(.+?)\s*周。?$/);
      if (t) totalWeeks = t[1];
    });
    var html = '<div>';
    items.forEach(function (st, i) {
      html += '<div class="path-stage"><div class="no">' + (i + 1) + '</div><div class="flex-1 min-w-0">'
        + '<div style="font-size:13.5px;color:var(--text);"><strong>' + esc(st.name) + '</strong>'
        + '<span class="chip chip-data" style="margin-left:8px;">' + esc(st.weeks) + ' 周</span></div>'
        + '<div class="text-xs mt-1" style="color:var(--muted);line-height:1.7;">' + inlineMd(st.goal) + '</div>'
        + (st.validation ? '<div class="text-xs mt-1" style="color:var(--ok);">验收 · ' + inlineMd(st.validation) + '</div>' : '')
        + '</div></div>';
    });
    html += '</div>';
    if (totalWeeks) html += '<p class="text-xs mt-3" style="color:var(--faint);">合计约 ' + esc(totalWeeks) + ' 周</p>';
    c.querySelector('.card-body').innerHTML = html;
    return c;
  }

  function renderGap(sec) {
    var c = card('差距清单');
    var body = c.querySelector('.card-body');
    var html = '<div class="space-y-3">';
    var items = [];
    sec.body.forEach(function (l) {
      var m = l.match(/^-\s*\*\*(.+?)\*\*（(.+?)）：(.+?)\s*→\s*(.+)$/);
      if (m) { items.push({ item: m[1], severity: m[2], current: m[3], target: m[4], kill: '' }); return; }
      var k = l.match(/^\s+-\s*kill criteria[：:]\s*(.*)$/);
      if (k && items.length) items[items.length - 1].kill = k[1];
    });
    items.forEach(function (g) {
      html += '<div class="gap-item">'
        + '<div class="gap-main flex items-center gap-2 flex-wrap">' + severityChip(g.severity)
        + '<span>' + esc(g.item) + '</span></div>'
        + '<div class="gap-flow"><span>' + esc(g.current) + '</span><span class="to">→</span><span style="color:var(--text);">' + esc(g.target) + '</span></div>'
        + (g.kill ? '<div class="kill">kill criteria：' + esc(g.kill) + '</div>' : '')
        + '</div>';
    });
    body.innerHTML = html + '</div>';
    return c;
  }

  function renderRisk(sec) {
    var c = card(sec.heading.indexOf('主要风险') >= 0 ? '主要风险' : '风险与对策');
    var body = c.querySelector('.card-body');
    var items = [];
    sec.body.forEach(function (l) {
      var m = l.match(/^-\s*(.+?)（概率\s*(.+?)\s*×\s*影响\s*(.+?)）\s*→\s*(.*)$/);
      if (m) { items.push({ risk: m[1], p: m[2], i: m[3], mit: m[4] }); return; }
      var plain = l.match(/^-\s+(.+)$/);
      if (plain && plain[1].trim()) items.push({ risk: plain[1].trim(), p: '', i: '', mit: '' });
    });
    var html = '<div class="space-y-3">';
    items.forEach(function (r) {
      html += '<div class="risk-item">'
        + '<div class="risk-text">' + inlineMd(r.risk) + '</div>'
        + (r.p ? '<div class="risk-meta"><span class="chip chip-warn">概率 ' + esc(r.p) + '</span>'
          + '<span class="chip chip-danger">影响 ' + esc(r.i) + '</span></div>' : '')
        + (r.mit ? '<div class="mitigation"><span class="k">缓解</span>' + inlineMd(r.mit) + '</div>' : '')
        + '</div>';
    });
    body.innerHTML = html + '</div>';
    return c;
  }

  function renderCollected(sec) {
    var c = card('已采集信息');
    var body = c.querySelector('.card-body');
    var html = '<div class="flex flex-wrap gap-2">';
    sec.body.forEach(function (l) {
      var m = l.match(/^-\s*([^：:]+)[：:]\s*(.*)$/);
      if (!m) return;
      html += '<div class="filled-chip"><span class="k">' + esc(m[1].trim()) + '</span><span>=</span>'
        + '<span class="v">' + esc(m[2].trim()) + '</span></div>';
    });
    body.innerHTML = html + '</div>';
    return c;
  }

  function renderAssumptions(sec) {
    var c = card('假设与默认值');
    var body = c.querySelector('.card-body');
    var html = '<div class="space-y-2">';
    sec.body.forEach(function (l) {
      var m = l.match(/^-\s*基于假设[：:]\s*(.*)$/);
      if (!m) return;
      var t = m[1].replace('（未提供，按默认值处理）', '').trim();
      html += '<div class="assumption-item"><span class="badge-assume">基于假设</span>'
        + '<span>' + esc(t) + '</span>'
        + '<span class="text-xs" style="color:var(--faint);">未提供，按默认值处理</span></div>';
    });
    body.innerHTML = html + '</div>';
    return c;
  }

  function renderCalcNote(sec) {
    var c = card('数据说明');
    var body = c.querySelector('.card-body');
    var text = sec.body.join(' ').replace(/^-\s*/, '').trim();
    var m = text.match(/(（经计算校验）)?[：:]\s*(.*)$/);
    var pairs = m ? m[2] : '';
    var html = '<div class="flex items-center gap-2 flex-wrap">'
      + '<span class="chip chip-ok">经计算校验</span>'
      + '<span class="text-xs" style="color:var(--muted);">报告中的总量数字由确定性计算工具求值，非模型心算</span></div>';
    if (pairs) {
      html += '<div class="flex flex-wrap gap-2 mt-3">';
      pairs.trim().split(/\s+/).forEach(function (kv) {
        if (!kv) return;
        html += '<span class="chip chip-data">' + esc(kv) + '</span>';
      });
      html += '</div>';
    }
    body.innerHTML = html;
    return c;
  }

  function renderExit(sec) {
    var c = card('退出原因');
    var body = c.querySelector('.card-body');
    var m = sec.body.join('\n').match(/^-\s*([^：:]+)[：:]\s*(.*)$/m);
    if (!m) return c;
    body.innerHTML = '<div class="flex items-center gap-2 flex-wrap">'
      + '<span class="chip ' + (/model-decision/.test(m[1]) ? 'chip-ok' : 'chip-warn') + '">' + esc(m[1]) + '</span>'
      + '<span class="text-xs" style="color:var(--muted);">' + esc(m[2]) + '</span></div>';
    return c;
  }

  function renderGeneric(sec) {
    var c = card(sec.heading);
    c.querySelector('.card-body').innerHTML = '<div class="report-md">' + mdBlock(sec.body.join('\n')) + '</div>';
    return c;
  }

  function mdBlock(src) {
    var html = [];
    var para = [];
    function flush() {
      if (para.length) { html.push('<p>' + inlineMd(para.join(' ')) + '</p>'); para = []; }
    }
    src.split('\n').forEach(function (raw) {
      var line = raw.replace(/\s+$/, '');
      if (!line.trim()) { flush(); return; }
      var li = line.match(/^-\s+(.*)$/);
      if (li) { flush(); html.push('<div style="padding:2px 0 2px 16px;position:relative;">'
        + '<span style="position:absolute;left:2px;top:11px;width:5px;height:5px;border-radius:50%;background:var(--accent);"></span>'
        + inlineMd(li[1]) + '</div>'); return; }
      if (/^>\s?/.test(line)) { flush(); html.push('<blockquote>' + inlineMd(line.replace(/^>\s?/, '')) + '</blockquote>'); return; }
      para.push(line);
    });
    flush();
    return html.join('');
  }

  // ---------- trace 摘要 ----------
  function renderTrace(progress) {
    var a = (progress && progress.analysis) || {};
    var st = (progress && progress.state) || {};
    var exit = st.exit || {};
    var cost = fmtCost(a.total_cost_ms);
    var items = '';
    if (a.type) items += tItem('类型', a.type);
    if (a.depth) items += tItem('深度', a.depth);
    if (progress && progress.modelCalls != null) items += tItem('模型调用', progress.modelCalls + ' 次');
    if (cost) items += tItem('活跃耗时', cost);
    if (exit.reason) items += tItem('退出原因', exit.reason);
    $('r-trace').innerHTML = items
      ? '<div class="t-item"><span class="k" style="color:var(--faint);">调用 trace 摘要</span></div>' + items
      : '<div class="t-item"><span class="k">调用 trace 摘要</span></div>';
    function tItem(k, v) { return '<div class="t-item"><span class="k">' + esc(k) + '</span><span class="v">' + esc(v) + '</span></div>'; }
  }

  // ---------- 导出全家桶 ----------
  function bindExport(id) {
    var bar = $('r-export');
    if (!bar) return;
    bar.classList.remove('hidden');
    bar.querySelectorAll('a[data-format]').forEach(function (a) {
      var f = a.dataset.format;
      a.href = '/api/export/' + encodeURIComponent(id)
        + (f === 'bundle' ? '/bundle' : '?format=' + f);
    });
  }

  // ---------- 加载 ----------
  function load() {
    var pProgress = fetch('/api/analysis/' + encodeURIComponent(analysisId))
      .then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });
    var pReport = fetch('/api/analysis/' + encodeURIComponent(analysisId) + '/report')
      .then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });
    var pSteps = fetch('/api/analysis/' + encodeURIComponent(analysisId) + '/steps')
      .then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });
    var pThinking = fetch('/api/analysis/' + encodeURIComponent(analysisId) + '/thinking')
      .then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });
    var pTrace = fetch('/api/analysis/' + encodeURIComponent(analysisId) + '/trace')
      .then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });

    Promise.all([pProgress, pReport, pSteps, pThinking, pTrace]).then(function (res) {
      var progress = res[0], report = res[1], stepsResp = res[2], thinkingResp = res[3], traceResp = res[4];
      if (progress) {
        renderTrace(progress);
        bindRerun(progress);
        var a = progress.analysis || {};
        if (a.question) $('r-title').textContent = a.question;
      }
      if (!report) {
        renderNotReady(progress);
        return;
      }
      bindExport(analysisId);
      var parsed = parseReport(report.markdown || '');
      if (parsed.title) $('r-title').textContent = parsed.title.replace(/^可行性分析：/, '');
      if (parsed.footer) $('r-footer-note').textContent = parsed.footer;
      renderMetaChips(parsed.metaLine, progress);

      // steps_output 结构化数据（assess 挂起恢复后为 Map，直接当对象用）
      var steps = (stepsResp && stepsResp.steps) || {};
      var assess = steps.assess || null;

      // 结构化数据可用性预判：存在的区块，markdown 兜底自动跳过（防同类型双渲染）
      var scenarios = assess && Array.isArray(assess.scenarios) ? assess.scenarios : null;
      var renderedTypes = {
        conclusion: !!(assess && assess.conclusion),
        scenarios: !!(scenarios && scenarios.length),
        path: !!steps.path,
        gap: !!(steps.gap && Array.isArray(steps.gap.gaps) && steps.gap.gaps.length),
        risk: !!(steps.risk && Array.isArray(steps.risk.risks) && steps.risk.risks.length),
        redteam: !!steps.redteam,
      };

      // 折叠组（明细区）：按内容分组，空组不显示
      var gPath = expando('实施路径与差距', '分阶段推进 · 能力差距与 kill criteria');
      var gRisk = expando('风险与红队质疑', '概率 × 影响矩阵 · 红队收敛记录');
      var gAppx = expando('附录：假设与数据说明', '已采集信息 · 假设默认值 · 校准与局限');

      // ---------- 首屏区：结论 → 三档 → 第四档 ----------
      var hero = renderConclusionHero(assess);
      if (hero) primaryEl.appendChild(hero);

      // ---------- markdown 兜底区块分流 ----------
      var scFallback = null; // 三档兜底（结构化缺失时）
      var noGoCard = null;  // 第四档（首屏末位）
      parsed.sections.forEach(function (sec) {
        var node = renderSection(sec, renderedTypes);
        if (!node) return;
        var h = sec.heading || '';
        if (h.indexOf('第四档') >= 0) { noGoCard = node; return; }
        if (h.indexOf('三档情景') >= 0) { scFallback = node; return; }
        if (h.indexOf('路径设计') >= 0 || h.indexOf('差距') >= 0) { gPath.body.appendChild(node); return; }
        if (h.indexOf('风险') >= 0 || h.indexOf('红队') >= 0) { gRisk.body.appendChild(node); return; }
        if (h.indexOf('结论') >= 0) { primaryEl.appendChild(node); return; }
        gAppx.body.appendChild(node);
      });

      // ---------- 结构化卡片（固定顺序，进入对应分组） ----------
      if (scenarios && scenarios.length) {
        var sc = renderScenariosStructured(scenarios);
        if (sc) primaryEl.appendChild(sc);
      } else if (scFallback) {
        primaryEl.appendChild(scFallback);
      }
      if (noGoCard) primaryEl.appendChild(noGoCard);

      if (steps.path) {
        var pn = renderPathStructured(steps.path.stages, steps.path.totalWeeks);
        if (pn) gPath.body.insertBefore(pn, gPath.body.firstChild);
      }
      if (steps.gap && Array.isArray(steps.gap.gaps)) {
        var gn = renderGapTableStructured(steps.gap.gaps);
        if (gn) gPath.body.appendChild(gn);
      }
      if (steps.risk && Array.isArray(steps.risk.risks)) {
        var rn = renderRiskMatrixStructured(steps.risk.risks);
        if (rn) gRisk.body.insertBefore(rn, gRisk.body.firstChild);
      }
      if (steps.redteam) {
        var rt = renderRedteamStructured(steps.redteam);
        if (rt) gRisk.body.appendChild(rt);
      }

      // ---------- 明细区组装（空组不渲染） ----------
      if (gPath.has()) secondaryEl.appendChild(gPath.root);
      if (gRisk.has()) secondaryEl.appendChild(gRisk.root);
      if (gAppx.has()) secondaryEl.appendChild(gAppx.root);

      // ---------- 过程明细（自带折叠面板） ----------
      var think = renderThinking(thinkingResp && thinkingResp.fragments);
      if (think) secondaryEl.appendChild(think);
      var td = renderTraceDetail(traceResp);
      if (td) secondaryEl.appendChild(td);
    });
  }

  function renderMetaChips(metaLine, progress) {
    var chips = [];
    String(metaLine || '').split('｜').forEach(function (part) {
      var t = part.trim();
      if (t) chips.push('<span class="chip chip-faint">' + esc(t) + '</span>');
    });
    var exit = progress && progress.state && progress.state.exit;
    if (exit && exit.reason && metaLine.indexOf('退出') < 0) {
      chips.push('<span class="chip ' + (/model-decision/.test(exit.reason) ? 'chip-ok' : 'chip-warn') + '">退出 · '
        + esc(exit.reason) + '</span>');
    }
    $('r-chips').innerHTML = chips.join('');
  }

  function renderNotReady(progress) {
    var st = (progress && progress.state) || {};
    var running = st.status === 'running' || st.status === 'waiting';
    var c = card(running ? '报告尚未生成' : '报告不可用');
    c.querySelector('.card-body').innerHTML =
      '<p class="text-sm" style="color:var(--muted);">'
      + (running ? '分析仍在进行，报告会在完成后生成。' : '该分析未生成报告（可能已中断或失败）。') + '</p>'
      + '<div class="mt-4 flex gap-3">'
      + (running ? '<a class="btn-ghost btn-sm" href="/session/' + encodeURIComponent(analysisId) + '">回到会话页</a>' : '')
      + '<a class="btn-ghost btn-sm" href="/">返回首页</a></div>';
    primaryEl.appendChild(c);
  }

  // ---------- 重新分析 ----------
  function bindRerun(progress) {
    var btn = $('btn-rerun');
    btn.addEventListener('click', function () {
      var a = (progress && progress.analysis) || {};
      if (!a.question) { location.href = '/'; return; }
      btn.disabled = true;
      btn.textContent = '重新分析中…';
      var body = { question: a.question };
      if (a.depth) body.depth = a.depth;
      fetch('/api/chat/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }).then(function (r) { return r.json(); }).then(function (j) {
        if (j && j.analysisId) {
          location.href = '/session/' + encodeURIComponent(j.analysisId);
        } else {
          btn.disabled = false;
          btn.textContent = '重新分析';
          alert('重新发起失败：' + ((j && (j.error || j.message)) || '未知错误'));
        }
      }).catch(function (e) {
        btn.disabled = false;
        btn.textContent = '重新分析';
        alert('网络错误：' + e.message);
      });
    });
  }

  if (!analysisId) {
    primaryEl.appendChild((function () {
      var c = card('页面错误');
      c.querySelector('.card-body').innerHTML = '<p class="text-sm" style="color:var(--danger);">缺少分析 id</p><a href="/">返回首页</a>';
      return c;
    })());
    return;
  }
  load();
})();
