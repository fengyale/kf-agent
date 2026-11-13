package com.decision.personal;

import com.decision.collector.IntentService;
import com.decision.prompt.PromptBuilder;
import com.decision.service.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 行为日志服务（spec Requirement: 行为数据双入口）：
 * - ① 手填表单录入 record(...)（打卡/完成情况）
 * - ② 粘贴文本导入：extractFromText(text) 用 LLM 从文本提取行为记录列表，
 *   仅返回给前端预览确认，confirmImport(...) 才入库
 * - 自评记录 is_self_eval=1 仅展示不参与校准计算（防三档锚定后喂出自欺数据）
 *
 * LLM 提取直接走 LlmClient（不属于任何 analysis，不计入 BudgetGuard 分析预算）。
 */
@Service
public class BehaviorLogService {

    private static final Logger log = LoggerFactory.getLogger(BehaviorLogService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** behavior_log 行记录 */
    public record BehaviorRecord(long id, String type, String title, Double plannedValue, Double actualValue,
                                 boolean completed, boolean selfEval, String source,
                                 String occurredAt, String createdAt) {}

    /** LLM 提取结果（预览用；completed 归一为 "true"/"false" 字符串便于前端回传） */
    public record ExtractedBehavior(Double plannedValue, Double actualValue, String completed,
                                    String occurredAt, String note) {}

    /** LLM 提取输出包裹（generateJson 需要对象类型而非数组） */
    private record ExtractedBehaviors(List<ExtractedBehavior> records) {}

    private final JdbcTemplate jt;
    private final LlmClient llm;
    private final PromptBuilder prompts;

    public BehaviorLogService(JdbcTemplate jt, LlmClient llm, PromptBuilder prompts) {
        this.jt = jt;
        this.llm = llm;
        this.prompts = prompts;
    }

    // ==================== ① 手填表单录入 ====================

    /** 表单录入：occurredAt 缺省今天；source=form */
    public BehaviorRecord record(String type, String title, Double planned, Double actual,
                                 boolean completed, String occurredAt, boolean selfEval) {
        String bucket = IntentService.normalizeType(type);
        String when = occurredAt == null || occurredAt.isBlank()
                ? LocalDate.now().toString() : occurredAt.strip();
        return insert(bucket, title, planned, actual, completed, selfEval, "form", when);
    }

    // ==================== ② 粘贴文本 LLM 提取导入 ====================

    /** LLM 从粘贴文本提取行为记录（仅预览，不入库）；completed 归一为 "true"/"false" */
    public List<ExtractedBehavior> extractFromText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 不能为空");
        }
        ExtractedBehaviors out = llm.generateJson(prompts.behaviorExtractSystem(),
                prompts.buildBehaviorExtractPrompt(text, LocalDate.now().toString()), ExtractedBehaviors.class);
        List<ExtractedBehavior> records = out == null || out.records() == null ? List.of() : out.records();
        return records.stream()
                .filter(r -> r != null)
                .map(r -> new ExtractedBehavior(
                        r.plannedValue(), r.actualValue(),
                        normalizeCompleted(r.completed(), r.plannedValue(), r.actualValue()),
                        r.occurredAt(), r.note()))
                .toList();
    }

    /** 确认导入（用户在预览上确认后调用）：source=import，occurredAt 缺省今天 */
    public int confirmImport(String type, List<ExtractedBehavior> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        String bucket = IntentService.normalizeType(type);
        int n = 0;
        for (ExtractedBehavior r : records) {
            if (r == null) {
                continue;
            }
            boolean completed = "true".equals(normalizeCompleted(r.completed(), r.plannedValue(), r.actualValue()));
            String when = r.occurredAt() == null || r.occurredAt().isBlank()
                    ? LocalDate.now().toString() : r.occurredAt().strip();
            insert(bucket, r.note(), r.plannedValue(), r.actualValue(), completed, false, "import", when);
            n++;
        }
        log.info("行为记录导入 {} 条（{}桶）", n, bucket);
        return n;
    }

    // ==================== 查询 / 删除 ====================

    /** 列表（可按桶过滤，最近在前） */
    public List<BehaviorRecord> list(String type, int limit) {
        String bucket = type == null || type.isBlank() ? null : IntentService.normalizeType(type);
        if (bucket != null) {
            return jt.query(SELECT + " WHERE type=? ORDER BY id DESC LIMIT ?", MAPPER, bucket, limit);
        }
        return jt.query(SELECT + " ORDER BY id DESC LIMIT ?", MAPPER, limit);
    }

    /** 校准数据源：桶内非自评记录（is_self_eval=0，按发生时间升序） */
    public List<BehaviorRecord> listForCalibration(String type) {
        return jt.query(SELECT + " WHERE type=? AND (is_self_eval IS NULL OR is_self_eval=0)"
                        + " ORDER BY COALESCE(occurred_at, created_at)",
                MAPPER, type);
    }

    public boolean delete(long id) {
        return jt.update("DELETE FROM behavior_log WHERE id=?", id) > 0;
    }

    // ==================== 内部 ====================

    private static final String SELECT = """
            SELECT id, type, title, planned_value, actual_value, completed, is_self_eval, source, occurred_at, created_at
            FROM behavior_log""";

    private static final org.springframework.jdbc.core.RowMapper<BehaviorRecord> MAPPER = (rs, i) -> new BehaviorRecord(
            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4) == null ? null : rs.getDouble(4),
            rs.getObject(5) == null ? null : rs.getDouble(5), rs.getInt(6) == 1, rs.getInt(7) == 1,
            rs.getString(8), rs.getString(9), rs.getString(10));

    private BehaviorRecord insert(String type, String title, Double planned, Double actual,
                                  boolean completed, boolean selfEval, String source, String occurredAt) {
        String now = LocalDateTime.now().format(TS);
        jt.update("""
                        INSERT INTO behavior_log(type, title, planned_value, actual_value, completed, is_self_eval, source, occurred_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                type, title, planned, actual, completed ? 1 : 0, selfEval ? 1 : 0, source, occurredAt, now);
        Long id = jt.queryForObject("SELECT last_insert_rowid()", Long.class);
        return new BehaviorRecord(id == null ? 0 : id, type, title, planned, actual, completed, selfEval,
                source, occurredAt, now);
    }

    /** completed 归一：LLM 可能输出 true/false/是/未完成 等变体；无值时按 实际≥计划 推断 */
    private static String normalizeCompleted(String raw, Double planned, Double actual) {
        if (raw != null && !raw.isBlank()) {
            String s = raw.strip().toLowerCase(Locale.ROOT);
            if (s.equals("true") || s.equals("1") || s.equals("yes") || s.contains("完成") || s.contains("达成") || s.contains("是")) {
                return "true";
            }
            if (s.equals("false") || s.equals("0") || s.equals("no") || s.contains("未") || s.contains("否")) {
                return "false";
            }
        }
        if (planned != null && actual != null) {
            return actual >= planned ? "true" : "false";
        }
        return "false";
    }
}
