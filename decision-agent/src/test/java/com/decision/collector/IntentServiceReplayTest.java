package com.decision.collector;

import com.decision.config.AgentProperties;
import com.decision.config.DbInitializer;
import com.decision.loop.BudgetGuard;
import com.decision.model.CollectorModels.IntentResult;
import com.decision.prompt.PromptBuilder;
import com.decision.service.LlmClient;
import com.decision.service.TracedLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 13.3 核心链路单测①（录制模式回放，不真调模型）：
 * replay-mode 下 IntentService 提取意图（模板1）：
 * - LlmClient 从录制文件按序号回放响应（不发起真实模型调用）
 * - 意图归一：goal 兜底、type 归一到四枚举、depth 归一到 light/standard/deep
 * - TracedLlmClient + BudgetGuard 把本次调用写入 llm_trace（call_seq=1，step=intent）
 */
class IntentServiceReplayTest {

    @TempDir
    Path tempDir;

    /** 构造 replay 模式的最小依赖链：LlmClient(props) + BudgetGuard(临时库) + TracedLlmClient + PromptBuilder */
    private AgentProperties replayProps() {
        AgentProperties props = new AgentProperties();
        props.getLlm().setReplayMode(true);
        props.getLlm().setRecordingsDir(tempDir.toString());
        return props;
    }

    private JdbcTemplate newDb(String dbFile) throws Exception {
        Files.deleteIfExists(Path.of(dbFile));
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        JdbcTemplate jt = new JdbcTemplate(ds);
        new DbInitializer(jt).afterPropertiesSet(); // 与生产一致的 9 张表
        return jt;
    }

    /** 写一条录制文件（seq=1）：response 字段为 JSON 字符串（LlmClient 回放 path("response").asText() 读文本节点） */
    private void writeRecording(String responseJson) throws Exception {
        String quoted = responseJson.replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{\"seq\":1,\"model\":\"replay\",\"system\":\"s\",\"userPrompt\":\"u\","
                + "\"response\":\"" + quoted + "\"}";
        Files.writeString(tempDir.resolve("0001.json"), json);
    }

    @Test
    void replayIntentExtractionWritesTrace() throws Exception {
        String dbFile = tempDir.resolve("intent-test.db").toString();
        JdbcTemplate jt = newDb(dbFile);
        // 模板1 录制响应：学习计划 + deep
        writeRecording("{\"goal\":\"转行 AI 应用开发\",\"pathHint\":\"应用开发方向\","
                + "\"type\":\"学习计划\",\"urgency\":\"高\",\"depth\":\"deep\"}");

        AgentProperties props = replayProps();
        BudgetGuard guard = new BudgetGuard(jt, props);
        LlmClient llm = new LlmClient(null, null, props);
        IntentService svc = new IntentService(new TracedLlmClient(llm, guard), new PromptBuilder());

        IntentResult result = svc.recognize("t-intent", "我想转行做 AI 应用开发");

        assertNotNull(result);
        assertEquals("转行 AI 应用开发", result.goal());
        assertEquals("应用开发方向", result.pathHint());
        assertEquals(IntentService.TYPE_LEARNING, result.type());   // type 归一
        assertEquals("deep", result.depth());                       // depth 归一
        assertEquals("高", result.urgency());

        // 该调用计入分析预算与 llm_trace（step=intent，call_seq=1）
        assertEquals(1, guard.usedCalls("t-intent"));
        Integer seq = jt.queryForObject(
                "SELECT call_seq FROM llm_trace WHERE analysis_id=? AND step='intent'", Integer.class, "t-intent");
        assertEquals(1, seq);
    }

    @Test
    void replayIntentNormalizesTypeAndDepth() throws Exception {
        String dbFile = tempDir.resolve("intent-test2.db").toString();
        JdbcTemplate jt = newDb(dbFile);
        // 日常事务 + 非法 depth（LLM 命名漂移）→ 归一到 light
        writeRecording("{\"goal\":\"买新键盘\",\"type\":\"日常事务\",\"depth\":\"轻量\"}");

        AgentProperties props = replayProps();
        BudgetGuard guard = new BudgetGuard(jt, props);
        LlmClient llm = new LlmClient(null, null, props);
        IntentService svc = new IntentService(new TracedLlmClient(llm, guard), new PromptBuilder());

        IntentResult result = svc.recognize("t-intent2", "这两周要不要买个键盘");

        assertNotNull(result);
        assertEquals("买新键盘", result.goal());
        assertEquals(IntentService.TYPE_DAILY, result.type());      // 日常事务归一
        assertEquals("light", result.depth());                      // 轻量归一
        assertEquals("中", result.urgency());                       // 缺省中
        assertTrue(jt.queryForObject("SELECT COUNT(*) FROM llm_trace WHERE analysis_id=?",
                Integer.class, "t-intent2") >= 1);
    }
}
