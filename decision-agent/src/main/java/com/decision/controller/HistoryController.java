package com.decision.controller;

import com.decision.loop.AnalysisStore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分析历史库接口（spec Requirement: 预测后验数据结构 / 回顾机制，Task 11.2）：
 * GET  /api/history                 —— 分析列表（id/question/type/depth/date/status/progress/结论摘要）
 * GET  /api/analysis/{id}/expectations —— 三档量化预期（回顾对照"当时预期是什么"，可填实际结果）
 * POST /api/analysis/{id}/progress  —— 用户主动标记进展（progress：not_started/in_progress/done/abandoned）
 * POST /api/analysis/{id}/outcome   —— 填写实际结果（actualResult 达成/未达成/部分达成 + note，
 *                                       写 analysis_expectation.actual_result/actual_note，预测后验留痕闭环）
 * 报告回看复用 GET /api/analysis/{id}/report（AnalysisController 已有）。
 */
@RestController
public class HistoryController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 进展状态枚举（用户主动标记，v1 不做 Agent 提醒） */
    public static final Set<String> PROGRESS_STATES = Set.of("not_started", "in_progress", "done", "abandoned");
    /** 实际结果枚举（预测后验） */
    public static final Set<String> ACTUAL_RESULTS = Set.of("达成", "未达成", "部分达成");

    private final JdbcTemplate jt;
    private final AnalysisStore analysisStore;

    public HistoryController(JdbcTemplate jt, AnalysisStore analysisStore) {
        this.jt = jt;
        this.analysisStore = analysisStore;
    }

    /** 历史列表：analysis 行 + 结论摘要（report.markdown 的可行性结论段，截 100 字） */
    @GetMapping("/api/history")
    public List<Map<String, Object>> history() {
        List<Map<String, Object>> rows = jt.queryForList("""
                SELECT a.id, a.question, a.type, a.depth, a.status, a.progress,
                       a.model_calls, a.search_count, a.total_cost_ms, a.created_at, a.finished_at,
                       r.markdown AS report_md, r.title AS report_title
                FROM analysis a
                LEFT JOIN report r ON r.id = a.report_id
                ORDER BY a.created_at DESC
                """);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("question", row.get("question"));
            item.put("type", row.get("type"));
            item.put("depth", row.get("depth"));
            item.put("status", row.get("status"));
            item.put("progress", row.get("progress"));
            item.put("modelCalls", row.get("model_calls"));
            item.put("searchCount", row.get("search_count"));
            item.put("totalCostMs", row.get("total_cost_ms"));
            item.put("createdAt", row.get("created_at"));
            item.put("finishedAt", row.get("finished_at"));
            item.put("summary", summaryOf(String.valueOf(row.get("report_md"))));
            item.put("reportTitle", row.get("report_title"));
            out.add(item);
        }
        return out;
    }

    /** 三档量化预期（一档一行：scenario/expectation/probability/actual_result/actual_note/reviewed_at） */
    @GetMapping("/api/analysis/{id}/expectations")
    public List<Map<String, Object>> expectations(@PathVariable String id) {
        requireAnalysis(id);
        return jt.queryForList("""
                SELECT id, scenario, expectation, probability, actual_result, actual_note, reviewed_at
                FROM analysis_expectation WHERE analysis_id=? ORDER BY id
                """, id);
    }

    /** 用户主动标记进展：body {progress: "in_progress"|"done"|"abandoned"|"not_started"} */
    @PostMapping("/api/analysis/{id}/progress")
    public Map<String, Object> markProgress(@PathVariable String id, @RequestBody Map<String, String> body) {
        requireAnalysis(id);
        String progress = body.get("progress");
        if (progress == null || !PROGRESS_STATES.contains(progress)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "progress 非法: " + progress + "（可选 " + PROGRESS_STATES + "）");
        }
        jt.update("UPDATE analysis SET progress=? WHERE id=?", progress, id);
        return Map.of("analysisId", id, "progress", progress, "ok", true);
    }

    /**
     * 填写实际结果（预测后验闭环）：body {actualResult: "达成"|"未达成"|"部分达成", note: "..."}
     * 写该分析全部三档预期行的 actual_result/actual_note/reviewed_at（一档一行同步留痕）。
     */
    @PostMapping("/api/analysis/{id}/outcome")
    public Map<String, Object> recordOutcome(@PathVariable String id, @RequestBody Map<String, String> body) {
        requireAnalysis(id);
        String actualResult = body.get("actualResult");
        if (actualResult == null || !ACTUAL_RESULTS.contains(actualResult)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "actualResult 非法: " + actualResult + "（可选 达成/未达成/部分达成）");
        }
        String note = body.get("note");
        String now = LocalDateTime.now().format(TS);
        int updated = jt.update("""
                        UPDATE analysis_expectation
                        SET actual_result=?, actual_note=?, reviewed_at=?
                        WHERE analysis_id=? AND actual_result IS NULL
                        """,
                actualResult, note, now, id);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("analysisId", id);
        resp.put("actualResult", actualResult);
        resp.put("note", note);
        resp.put("updatedRows", updated);
        resp.put("ok", true);
        return resp;
    }

    /**
     * 删除分析（含全部关联数据与报告文件）：report/slot/llm_trace/event/
     * analysis_expectation/analysis_state 级联清理；behavior_log 按 type 桶聚合，
     * 不随单次分析删除（校准资产）。running 状态后台线程仍在写表，拒绝删除。
     */
    @DeleteMapping("/api/analysis/{id}")
    public Map<String, Object> deleteAnalysis(@PathVariable String id) {
        Map<String, Object> analysis = requireAnalysis(id);
        if ("running".equals(analysis.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "分析进行中不能删除：后台仍在写数据，等完成或终止后再删");
        }
        // 报告文件删除失败不影响库数据清理
        List<String> files = jt.queryForList(
                "SELECT file_path FROM report WHERE analysis_id=? AND file_path IS NOT NULL",
                String.class, id);
        int filesDeleted = 0;
        for (String path : files) {
            try {
                if (java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(path))) {
                    filesDeleted++;
                }
            } catch (Exception ignored) {
                // 文件可能被占用或已不存在，跳过
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("analysisId", id);
        resp.put("filesDeleted", filesDeleted);
        resp.put("reportRows", jt.update("DELETE FROM report WHERE analysis_id=?", id));
        resp.put("slotRows", jt.update("DELETE FROM slot WHERE analysis_id=?", id));
        resp.put("llmTraceRows", jt.update("DELETE FROM llm_trace WHERE analysis_id=?", id));
        resp.put("eventRows", jt.update("DELETE FROM event WHERE analysis_id=?", id));
        resp.put("expectationRows", jt.update("DELETE FROM analysis_expectation WHERE analysis_id=?", id));
        resp.put("stateRows", jt.update("DELETE FROM analysis_state WHERE analysis_id=?", id));
        resp.put("analysisRows", jt.update("DELETE FROM analysis WHERE id=?", id));
        resp.put("ok", true);
        return resp;
    }

    // ==================== 内部 ====================

    private Map<String, Object> requireAnalysis(String id) {
        Map<String, Object> analysis = analysisStore.get(id);
        if (analysis == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析不存在: " + id);
        }
        return analysis;
    }

    /** 结论摘要：可行性结论段（## 可行性结论 / ## 结论）首段正文，截 100 字；无则报告标题 */
    private String summaryOf(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String[] lines = markdown.split("\n");
        boolean inConclusion = false;
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.strip();
            if (t.startsWith("## ") && (t.contains("可行性结论") || t.contains("结论"))) {
                inConclusion = true;
                continue;
            }
            if (inConclusion) {
                if (t.startsWith("## ") || t.startsWith("---")) {
                    break;
                }
                if (!t.isEmpty() && !t.startsWith("-") && !t.startsWith("**") && !t.startsWith(">")) {
                    sb.append(t);
                    if (sb.length() >= 100) {
                        break;
                    }
                }
            }
        }
        String summary = sb.toString().strip();
        return summary.length() > 100 ? summary.substring(0, 100) + "…" : summary;
    }
}
