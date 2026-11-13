package com.decision.loop;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * analysis 表 CRUD + report 表读写（分析历史与报告）。
 */
@Service
public class AnalysisStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jt;
    private final SimpleJdbcInsert reportInsert;

    public AnalysisStore(JdbcTemplate jt) {
        this.jt = jt;
        this.reportInsert = new SimpleJdbcInsert(jt.getDataSource())
                .withTableName("report")
                .usingGeneratedKeyColumns("id");
    }

    public void create(String id, String question, String type, String depth, String status) {
        jt.update("""
                        INSERT INTO analysis(id, question, type, depth, status, model_calls, search_count, created_at)
                        VALUES (?, ?, ?, ?, ?, 0, 0, ?)
                        """,
                id, question, type, depth, status, LocalDateTime.now().format(TS));
    }

    /** 意图识别后回填类型与深度 */
    public void updateIntent(String id, String type, String depth) {
        jt.update("UPDATE analysis SET type=?, depth=? WHERE id=?", type, depth, id);
    }

    /** 手动升降级深度（FlowRouter） */
    public void updateDepth(String id, String depth) {
        jt.update("UPDATE analysis SET depth=? WHERE id=?", depth, id);
    }

    public void updateStatus(String id, String status) {
        jt.update("UPDATE analysis SET status=? WHERE id=?", status, id);
    }

    /** 终态收尾：status / report_id / total_cost_ms / finished_at */
    public void finish(String id, String status, Long reportId, long totalCostMs) {
        jt.update("UPDATE analysis SET status=?, report_id=?, total_cost_ms=?, finished_at=? WHERE id=?",
                status, reportId, totalCostMs, LocalDateTime.now().format(TS), id);
    }

    /** 联网搜索计数（引用调研报告为零成本，不调用本方法；每次联网搜索 +1，验证"引用即零成本"） */
    public void addSearchCount(String id, int delta) {
        jt.update("UPDATE analysis SET search_count = COALESCE(search_count, 0) + ? WHERE id=?", delta, id);
    }

    public Map<String, Object> get(String id) {
        List<Map<String, Object>> rows = jt.queryForList("SELECT * FROM analysis WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 报告落库（简化报告 MD 字符串），返回报告 id */
    public long saveReport(String analysisId, String title, String markdown) {
        Number key = reportInsert.executeAndReturnKey(Map.of(
                "analysis_id", analysisId,
                "title", title,
                "markdown", markdown,
                "created_at", LocalDateTime.now().format(TS)));
        return key.longValue();
    }

    public Map<String, Object> getReport(String analysisId) {
        List<Map<String, Object>> rows = jt.queryForList(
                "SELECT id, analysis_id, title, markdown, file_path, created_at FROM report WHERE analysis_id=? ORDER BY id DESC LIMIT 1",
                analysisId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 报告落盘后回写 file_path（ReportWriter 双级落盘的实际路径，含降级路径） */
    public void updateReportFilePath(long reportId, String filePath) {
        jt.update("UPDATE report SET file_path=? WHERE id=?", filePath, reportId);
    }
}
