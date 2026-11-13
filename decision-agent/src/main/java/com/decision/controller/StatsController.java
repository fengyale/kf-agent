package com.decision.controller;

import com.decision.model.LoopModels;
import com.decision.personal.CalibrationService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统计页聚合接口（Task 13.1）：
 * GET /api/stats —— ① 高频缺陷分布（红队质疑点关键词聚合 top N）
 *                    ② 行为完成率趋势（CalibrationService 分桶系数 + behavior_log 按月完成率）
 * 单次分析调用明细走 GET /api/analysis/{id}/trace（AnalysisController，llm_trace 表）。
 *
 * 高频缺陷数据源：event 表 redteam:issue 事件的 challenges[].point（质疑点）+ 报告
 * markdown 的「已知局限」区文本。聚合方式：提取 2 字以上中文连续段（长段补 4 字滑窗），
 * 过滤虚词停用词后按词频计数取 top N（简单计数，不做语义聚类）。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    /** 高频缺陷返回条数 */
    private static final int TOP_N = 15;
    /** 中文连续段（≥2 字）提取 */
    private static final Pattern CN_SEG = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");
    /** 4 字滑窗（长段补采，提升核心短语命中率） */
    private static final int WINDOW = 4;

    /** 虚词停用词（纯语法词，计数无信息量） */
    private static final Set<String> STOPWORDS = Set.of(
            "的", "了", "和", "与", "及", "或", "在", "是", "有", "为", "对", "中", "等", "也",
            "将", "从", "这", "那", "它", "其", "之", "以", "于", "由", "并", "而", "且", "但",
            "若", "则", "就", "都", "还", "只", "已", "未", "很", "较", "最", "更", "常", "会",
            "能", "可", "需", "要", "不", "被", "给", "向", "往", "按", "照", "据", "依", "用",
            "靠", "凭", "自", "即", "同", "跟", "随", "把", "让", "使", "该", "个", "些", "多",
            "各", "另", "再", "又", "仍", "却", "才", "刚", "正", "每", "次", "下", "上");

    private final JdbcTemplate jt;
    private final CalibrationService calibration;
    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public StatsController(JdbcTemplate jt, CalibrationService calibration) {
        this.jt = jt;
        this.calibration = calibration;
    }

    @GetMapping
    public Map<String, Object> stats() {
        Map<String, Object> resp = new LinkedHashMap<>();
        Map<String, Object> issue = issueTopN();
        resp.put("issueTop", issue.get("items"));
        resp.put("issueTotal", issue.get("total"));
        resp.put("buckets", bucketViews());
        resp.put("monthlyTrend", monthlyTrend());
        resp.put("traceAnalyses", traceAnalyses());
        resp.put("redteamHealth", redteamHealth());
        return resp;
    }

    // ==================== ① 高频缺陷分布（红队质疑点关键词聚合） ====================

    /** 红队质疑点文本：event 表 redteam:issue challenges[].point + 报告 markdown 已知局限区 */
    private List<String> issueTexts() {
        List<String> texts = new ArrayList<>();
        // 数据源 1：event 表 redteam:issue 事件的 challenges（point 字段）
        List<Map<String, Object>> rows = jt.queryForList(
                "SELECT payload FROM event WHERE type=? ORDER BY id",
                "redteam:issue");
        for (Map<String, Object> row : rows) {
            Object parsed = parseJson(String.valueOf(row.get("payload")));
            if (!(parsed instanceof Map<?, ?> m)) {
                continue;
            }
            Object challenges = m.get("challenges");
            if (!(challenges instanceof List<?> list)) {
                continue;
            }
            for (Object c : list) {
                if (!(c instanceof Map<?, ?> cm) || cm.get("point") == null) {
                    continue;
                }
                // 代码确定性校验的条目措辞固定，属系统自检而非用户方案缺陷 → 不计入"高频缺陷分布"
                if (LoopModels.SOURCE_CODE.equals(String.valueOf(cm.get("source")))) {
                    continue;
                }
                texts.add(String.valueOf(cm.get("point")));
            }
        }
        // 数据源 2：报告 markdown「已知局限」区（minor 质疑文本）
        List<Map<String, Object>> reports = jt.queryForList("SELECT markdown FROM report");
        for (Map<String, Object> r : reports) {
            String md = String.valueOf(r.get("markdown"));
            boolean inKnown = false;
            for (String line : md.split("\n")) {
                String t = line.strip();
                if (t.startsWith("## ")) {
                    inKnown = t.contains("已知局限");
                    continue;
                }
                if (inKnown && t.startsWith("- ") && t.length() > 2) {
                    texts.add(t.substring(2).strip());
                }
            }
        }
        return texts;
    }

    /** 关键词聚合：中文连续段 + 4 字滑窗补采 → 词频 top N；total 为去重词总数（含低频） */
    private Map<String, Object> issueTopN() {
        List<String> texts = issueTexts();
        Map<String, Integer> freq = new HashMap<>();
        for (String t : texts) {
            Matcher m = CN_SEG.matcher(t == null ? "" : t);
            while (m.find()) {
                String seg = m.group();
                addWord(freq, seg);
                // 长段（>4 字）补 4 字滑窗，让核心短语稳定聚集（如"时间冲突风险"→"时间冲突""冲突风险"）
                if (seg.length() > WINDOW) {
                    for (int i = 0; i + WINDOW <= seg.length(); i++) {
                        addWord(freq, seg.substring(i, i + WINDOW));
                    }
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        freq.entrySet().stream()
                .filter(e -> e.getValue() > 1) // 单次出现不构成"高频"
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_N)
                .forEach(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("keyword", e.getKey());
                    item.put("count", e.getValue());
                    out.add(item);
                });
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", out);
        resp.put("total", freq.size());
        return resp;
    }

    private void addWord(Map<String, Integer> freq, String word) {
        if (word.length() < 2 || STOPWORDS.contains(word)) {
            return;
        }
        freq.merge(word, 1, Integer::sum);
    }

    // ==================== ② 行为完成率趋势（分桶展示） ====================

    /** 分桶系数总览：CalibrationService.allBuckets()（系数/样本量/冷启动/分位/asOf） */
    private List<Map<String, Object>> bucketViews() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CalibrationService.Calibration cal : calibration.allBuckets()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("type", cal.type());
            view.put("coefficient", cal.coefficient());
            view.put("sampleSize", cal.sampleSize());
            view.put("coldStart", cal.coldStart());
            view.put("asOf", cal.asOf());
            view.put("percentiles", cal.percentiles());
            view.put("note", cal.note());
            out.add(view);
        }
        return out;
    }

    /** 按月完成率趋势：behavior_log 非自评记录按 occurred_at 月份聚合（completed 比例 + 记录数） */
    private List<Map<String, Object>> monthlyTrend() {
        List<Map<String, Object>> rows = jt.queryForList("""
                SELECT substr(COALESCE(occurred_at, created_at), 1, 7) AS ym,
                       COUNT(*) AS total,
                       SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) AS completed
                FROM behavior_log
                WHERE is_self_eval = 0
                GROUP BY ym ORDER BY ym
                """);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("month", row.get("ym"));
            long total = num(row.get("total"));
            long done = num(row.get("completed"));
            item.put("total", total);
            item.put("completed", done);
            item.put("rate", total == 0 ? 0 : Math.round(done * 100.0 / total) / 100.0);
            out.add(item);
        }
        return out;
    }

    /** 有调用 trace 的分析列表（分析 id + 问题 + 调用次数 + 总耗时，统计页 trace 明细选择器用） */
    private List<Map<String, Object>> traceAnalyses() {
        List<Map<String, Object>> rows = jt.queryForList("""
                SELECT a.id, a.question, a.created_at,
                       COUNT(t.id) AS calls,
                       COALESCE(SUM(t.duration_ms), 0) AS total_ms
                FROM analysis a
                JOIN llm_trace t ON t.analysis_id = a.id
                GROUP BY a.id ORDER BY a.created_at DESC LIMIT 50
                """);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("question", row.get("question"));
            item.put("createdAt", row.get("created_at"));
            item.put("calls", row.get("calls"));
            item.put("totalMs", row.get("total_ms"));
            out.add(item);
        }
        return out;
    }

    // ==================== ③ 红队体检（自审机制有效性） ====================

    /**
     * 红队自审机制的有效性指标：没有这组数字，就只能凭感觉判断红队在工作还是在敷衍。
     * 数据源：analysis_state.steps_output 的 redteam 元数据（deep 路径分析才产生）。
     * - avgRounds/convergedRate：平均质疑轮次与收敛占比
     * - budgetStoppedRate：因预算或轮次上限被截断、未跑满闭环的占比（机制是否"名义存在实际打折"）
     * - emptyRoundRate：出现"零质疑"轮次的分析占比（红队空转）
     * - noSubstanceRate：出现"修订无实质变化"的分析占比（假修订）
     * - majorCount/codeMajorCount/codeMajorShare：严重质疑由代码校验判定的占比（代码裁判的实际贡献）
     */
    private Map<String, Object> redteamHealth() {
        List<Map<String, Object>> rows = jt.queryForList(
                "SELECT steps_output FROM analysis_state WHERE steps_output LIKE '%redteam%'");
        int analyses = 0, converged = 0, budgetStopped = 0, emptyAnalyses = 0, noSubstanceAnalyses = 0;
        int roundsSum = 0, majors = 0, minors = 0, codeMajors = 0;
        for (Map<String, Object> row : rows) {
            Object parsed = parseJson(String.valueOf(row.get("steps_output")));
            if (!(parsed instanceof Map<?, ?> steps)) {
                continue;
            }
            if (!(steps.get("redteam") instanceof Map<?, ?> meta)) {
                continue;
            }
            analyses++;
            roundsSum += num(meta.get("rounds"));
            if (Boolean.TRUE.equals(meta.get("converged"))) {
                converged++;
            }
            if (Boolean.TRUE.equals(meta.get("budgetStopped"))) {
                budgetStopped++;
            }
            if (num(meta.get("emptyRounds")) > 0) {
                emptyAnalyses++;
            }
            if (num(meta.get("noSubstanceRounds")) > 0) {
                noSubstanceAnalyses++;
            }
            codeMajors += num(meta.get("codeMajorCount"));
            // 质疑严重度分布：优先跨轮累计（含已修订的 major），缺失时退回最后一轮
            Object challenges = meta.get("allChallenges");
            if (!(challenges instanceof List<?>)) {
                challenges = meta.get("challenges");
            }
            if (challenges instanceof List<?> list) {
                for (Object c : list) {
                    if (c instanceof Map<?, ?> cm && LoopModels.isMajor(String.valueOf(cm.get("severity")))) {
                        majors++;
                    } else if (c instanceof Map<?, ?>) {
                        minors++;
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analyses", analyses);
        out.put("avgRounds", analyses == 0 ? 0 : Math.round(roundsSum * 10.0 / analyses) / 10.0);
        out.put("convergedRate", rate(converged, analyses));
        out.put("budgetStoppedRate", rate(budgetStopped, analyses));
        out.put("emptyRoundRate", rate(emptyAnalyses, analyses));
        out.put("noSubstanceRate", rate(noSubstanceAnalyses, analyses));
        out.put("majorCount", majors);
        out.put("minorCount", minors);
        out.put("codeMajorCount", codeMajors);
        out.put("codeMajorShare", majors == 0 ? 0 : Math.round(codeMajors * 100.0 / majors) / 100.0);
        return out;
    }

    private static double rate(int part, int total) {
        return total == 0 ? 0 : Math.round(part * 100.0 / total) / 100.0;
    }

    // ==================== 内部 ====================

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return om.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
