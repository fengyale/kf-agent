package com.research.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DbInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DbInitializer.class);

    private final JdbcTemplate jt;

    public DbInitializer(JdbcTemplate jt) {
        this.jt = jt;
    }

    @Override
    public void afterPropertiesSet() {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS task (
                    id TEXT PRIMARY KEY,
                    topic TEXT NOT NULL,
                    status TEXT NOT NULL,
                    report_path TEXT,
                    rounds INTEGER DEFAULT 0,
                    search_count INTEGER DEFAULT 0,
                    total_cost_ms INTEGER,
                    created_at TEXT,
                    finished_at TEXT
                )""");
        jt.execute("""
                CREATE TABLE IF NOT EXISTS event (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    payload TEXT,
                    created_at TEXT
                )""");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_event_task ON event(task_id, seq)");
        jt.execute("""
                CREATE TABLE IF NOT EXISTS report (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id TEXT NOT NULL,
                    title TEXT,
                    markdown TEXT,
                    created_at TEXT
                )""");
        jt.execute("""
                CREATE TABLE IF NOT EXISTS review_issues (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id TEXT NOT NULL,
                    type TEXT,
                    description TEXT,
                    section TEXT,
                    created_at TEXT
                )""");
        jt.execute("""
                CREATE TABLE IF NOT EXISTS conversation (
                    id TEXT PRIMARY KEY,
                    title TEXT,
                    created_at TEXT,
                    updated_at TEXT
                )""");
        jt.execute("""
                CREATE TABLE IF NOT EXISTS message (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    task_id TEXT,
                    created_at TEXT
                )""");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_message_conv ON message(conversation_id, id)");
        log.info("SQLite 初始化完成: data/research.db");
    }
}
