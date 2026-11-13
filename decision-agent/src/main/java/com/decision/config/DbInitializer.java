package com.decision.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 建表模式照搬 research-agent：CREATE TABLE IF NOT EXISTS + JdbcTemplate 直连。
 * 共 9 张表：profile、behavior_log、analysis、analysis_state、analysis_expectation、
 * event、report、slot、llm_trace。
 */
@Component
public class DbInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DbInitializer.class);

    private final JdbcTemplate jt;

    public DbInitializer(JdbcTemplate jt) {
        this.jt = jt;
    }

    @Override
    public void afterPropertiesSet() {
        // 1. 画像（k/v 结构，冷启动画像引导填充，避免每次分析从零问起）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS profile (
                    k TEXT PRIMARY KEY,
                    v TEXT,
                    updated_at TEXT
                )""");

        // 2. 行为日志（分桶校准数据源：type=问题类型桶；is_self_eval=1 的自评数据不参与校准计算）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS behavior_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type TEXT NOT NULL,
                    title TEXT,
                    planned_value REAL,
                    actual_value REAL,
                    completed INTEGER DEFAULT 0,
                    is_self_eval INTEGER DEFAULT 0,
                    source TEXT,
                    occurred_at TEXT,
                    created_at TEXT
                )""");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_behavior_type ON behavior_log(type, occurred_at)");

        // 3. 分析历史（progress：用户主动标记的进展状态，not_started/in_progress/done/abandoned，Task 11.2）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS analysis (
                    id TEXT PRIMARY KEY,
                    question TEXT NOT NULL,
                    type TEXT,
                    depth TEXT,
                    status TEXT NOT NULL,
                    progress TEXT,
                    report_id INTEGER,
                    model_calls INTEGER DEFAULT 0,
                    search_count INTEGER DEFAULT 0,
                    total_cost_ms INTEGER,
                    created_at TEXT,
                    finished_at TEXT
                )""");
        // 兼容旧库：已有 analysis 表缺 progress 列时补列（SQLite ALTER TABLE ADD COLUMN）
        ensureColumn(jt, "analysis", "progress");

        // 4. 挂起/恢复状态（等用户回答时流程退出释放线程，状态全量落库，回答触发断点恢复）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS analysis_state (
                    analysis_id TEXT PRIMARY KEY,
                    current_step TEXT,
                    steps_output TEXT,
                    slot_snapshot TEXT,
                    status TEXT,
                    pending_question TEXT,
                    updated_at TEXT
                )""");

        // 5. 预测后验（分析时落三档量化预期，回顾时填实际结果，为 v2 预测校准曲线留种子）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS analysis_expectation (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    analysis_id TEXT NOT NULL,
                    scenario TEXT,
                    expectation TEXT,
                    probability REAL,
                    actual_result TEXT,
                    actual_note TEXT,
                    created_at TEXT,
                    reviewed_at TEXT
                )""");
        // 兼容旧库：缺 actual_note 列时补列（实际结果备注，Task 11.2）
        ensureColumn(jt, "analysis_expectation", "actual_note");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_expectation_analysis ON analysis_expectation(analysis_id)");

        // 6. SSE 事件（EventPublisher 双写：先落库再推在线 emitter，断线重放）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS event (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    analysis_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    payload TEXT,
                    created_at TEXT
                )""");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_event_analysis ON event(analysis_id, seq)");

        // 7. 报告
        jt.execute("""
                CREATE TABLE IF NOT EXISTS report (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    analysis_id TEXT NOT NULL,
                    title TEXT,
                    markdown TEXT,
                    file_path TEXT,
                    created_at TEXT
                )""");

        // 8. 采集槽位（is_standard=1 为标准词汇表字段，参与确定性计算；扩展字段仅展示）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS slot (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    analysis_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    importance TEXT,
                    value TEXT,
                    filled INTEGER DEFAULT 0,
                    is_standard INTEGER DEFAULT 0,
                    created_at TEXT,
                    updated_at TEXT
                )""");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_slot_analysis ON slot(analysis_id)");

        // 9. LLM 调用 trace（步骤/token/耗时/模板 hash，模板迭代不盲调）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS llm_trace (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    analysis_id TEXT NOT NULL,
                    call_seq INTEGER NOT NULL,
                    step TEXT,
                    prompt_tokens INTEGER,
                    completion_tokens INTEGER,
                    duration_ms INTEGER,
                    template_hash TEXT,
                    created_at TEXT
                )""");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_trace_analysis ON llm_trace(analysis_id, call_seq)");

        log.info("SQLite 初始化完成: 9 张表");
    }

    /** 兼容旧库：列不存在时 ALTER TABLE ADD COLUMN（SQLite 支持新增 TEXT 列） */
    private void ensureColumn(JdbcTemplate jt, String table, String column) {
        try {
            java.util.List<java.util.Map<String, Object>> cols =
                    jt.queryForList("PRAGMA table_info(" + table + ")");
            boolean exists = cols.stream()
                    .anyMatch(c -> column.equalsIgnoreCase(String.valueOf(c.get("name"))));
            if (!exists) {
                jt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " TEXT");
                log.info("兼容补列: {}.{}", table, column);
            }
        } catch (Exception e) {
            log.warn("兼容补列失败（{}）: {}", table + "." + column, e.getMessage());
        }
    }
}
