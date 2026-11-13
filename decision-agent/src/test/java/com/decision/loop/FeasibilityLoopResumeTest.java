package com.decision.loop;

import com.decision.config.AgentProperties;
import com.decision.config.DbInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 13.3 核心链路单测②（挂起/恢复状态机回放，不真调模型）：
 * 覆盖 FeasibilityLoop 人机交织挂起/恢复的持久层语义（spec 强制）：
 * 1. 完成 assess 步骤 → steps_output + llm_trace(seq=1,2) 落库（status=running）
 * 2. 缺数据挂起 → status=waiting + pending_question 落库，_exit/_meta 保留（等用户回答期间无线程）
 * 3. 恢复重读 → 已完成步骤（assess）不重跑，断点步骤 = gap（FlowRouter 续跑判定）
 * 4. 续跑新调用 → llm_trace call_seq 从既有序列继续（3），不重复计数
 */
class FeasibilityLoopResumeTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate newDb(String dbFile) throws Exception {
        Files.deleteIfExists(Path.of(dbFile));
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        JdbcTemplate jt = new JdbcTemplate(ds);
        new DbInitializer(jt).afterPropertiesSet(); // 与生产一致的 9 张表
        return jt;
    }

    @Test
    void suspendThenResumeKeepsStepsAndContinuesSeq() throws Exception {
        String dbFile = tempDir.resolve("loop-test.db").toString();
        JdbcTemplate jt = newDb(dbFile);
        AgentProperties props = new AgentProperties();
        AnalysisStore analysisStore = new AnalysisStore(jt);
        AnalysisStateStore stateStore = new AnalysisStateStore(jt);
        BudgetGuard guard = new BudgetGuard(jt, props);
        String id = "t-loop";

        analysisStore.create(id, "我想转AI", "学习计划", "deep", "running");
        stateStore.create(id, null, "{}", "{}", "running", null);

        // ---- 第一次运行：完成 assess + path 步骤（结构化产出 + 两次模型调用 trace） ----
        LinkedHashMap<String, Object> steps = new LinkedHashMap<>();
        steps.put("assess", Map.of(
                "conclusion", "可行，按 B 现实档推进",
                "scenarios", List.of(Map.of("name", "A 乐观", "probability", 0.65))));
        steps.put("path", Map.of("stages", List.of(Map.of("name", "筹备", "weeks", 4))));
        guard.afterModelCall(id, "assess", 800, 120, 60, "hash-assess");   // seq=1
        guard.afterModelCall(id, "path", 500, 90, 40, "hash-path");        // seq=2
        steps.put("_meta", Map.of("activeMs", 1200L));                     // 活跃时长累计（挂起不计时）
        stateStore.updateProgress(id, "path", stateStore.writeSteps(steps), "running");
        assertEquals(2, guard.usedCalls(id));

        // ---- 挂起（FeasibilityLoop.suspendForFollowup 的落库路径：gap 步骤前缺数据，等用户回答，线程已释放） ----
        steps.put("_exit", Map.of("reason", "waiting:user",
                "detail", "分析中断等待用户补充信息（断点步骤: gap）"));
        stateStore.updateProgress(id, "gap", stateStore.writeSteps(steps),
                AnalysisStateStore.STATUS_WAITING);
        stateStore.updatePending(id, "{\"kind\":\"followup\",\"questions\":[{\"slot\":\"weeklyHours\","
                + "\"question\":\"每周能投入多少小时？\",\"skippable\":true}],\"askedAt\":\"2026-08-31 12:00:00\"}",
                AnalysisStateStore.STATUS_WAITING);

        AnalysisStateStore.AnalysisState waiting = stateStore.get(id);
        assertNotNull(waiting);
        assertEquals(AnalysisStateStore.STATUS_WAITING, waiting.status());
        assertTrue(waiting.pendingQuestion() != null && !waiting.pendingQuestion().isBlank(),
                "挂起须持久化 pending_question（用户回答触发恢复）");

        // ---- 恢复（用户回答 → resume → execute 重建：重读 steps_output） ----
        AnalysisStateStore.AnalysisState resumed = stateStore.get(id);
        LinkedHashMap<String, Object> restored = stateStore.parseSteps(resumed);
        assertTrue(restored.containsKey("assess"), "已完成步骤须保留（不重跑）");
        assertTrue(restored.containsKey("_exit"), "退出原因须保留");
        assertTrue(restored.containsKey("_meta"), "活跃时长累计须保留（挂起期间不计时）");

        // 断点续跑判定（FeasibilityLoop 主循环的跳过逻辑）：assess 已完成 → 下一步 gap
        List<String> loopSteps = new FlowRouter(analysisStore, stateStore).loopSteps("deep");
        String nextStep = null;
        for (String s : loopSteps) {
            if (!restored.containsKey(s)) { nextStep = s; break; }
        }
        assertEquals("gap", nextStep, "恢复后应从断点步骤 gap 续跑，已完成步骤不重跑");

        // ---- 续跑：gap 步骤新调用 → call_seq 从 3 继续（不重复计数，预算不重复扣） ----
        guard.afterModelCall(id, "gap", 600, 100, 80, "hash-gap");          // seq=3
        assertEquals(3, guard.usedCalls(id));
        List<Integer> seqs = jt.queryForList(
                "SELECT call_seq FROM llm_trace WHERE analysis_id=? ORDER BY call_seq", Integer.class, id);
        assertEquals(List.of(1, 2, 3), seqs, "挂起前后的调用序列应连续（1,2 → 3）");

        // 恢复后 status 置 running（FeasibilityLoop.execute 起始处）
        stateStore.updateStatus(id, AnalysisStateStore.STATUS_RUNNING);
        assertEquals(AnalysisStateStore.STATUS_RUNNING, stateStore.get(id).status());
    }

    @Test
    void completedStepsSkipOnResumeAcrossDepthChange() throws Exception {
        String dbFile = tempDir.resolve("loop-test2.db").toString();
        JdbcTemplate jt = newDb(dbFile);
        AgentProperties props = new AgentProperties();
        AnalysisStore analysisStore = new AnalysisStore(jt);
        AnalysisStateStore stateStore = new AnalysisStateStore(jt);
        String id = "t-loop2";

        analysisStore.create(id, "想开店", "商业经营", "standard", "running");
        // 手动升级深度：standard → deep（已完成步骤不重跑）
        new FlowRouter(analysisStore, stateStore).changeDepth(id, "deep");

        LinkedHashMap<String, Object> steps = new LinkedHashMap<>();
        steps.put("assess", Map.of("conclusion", "可行"));
        steps.put("path", Map.of("stages", List.of(Map.of("name", "筹备", "weeks", 4))));
        steps.put("_exit", Map.of("reason", "waiting:user", "detail", "暂停"));
        stateStore.updateProgress(id, "path", stateStore.writeSteps(steps), AnalysisStateStore.STATUS_WAITING);

        LinkedHashMap<String, Object> restored = stateStore.parseSteps(stateStore.get(id));
        List<String> loopSteps = new FlowRouter(analysisStore, stateStore).loopSteps("deep");
        String nextStep = null;
        for (String s : loopSteps) {
            if (!restored.containsKey(s)) { nextStep = s; break; }
        }
        // deep 路由 = [assess, path, gap, risk, redteam]；assess/path 已完成 → gap
        assertEquals("gap", nextStep, "升级深度后已完成步骤仍不重跑，从 gap 续跑");
    }
}
