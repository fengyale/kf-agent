package com.decision.controller;

import com.decision.loop.AnalysisStateStore;
import com.decision.loop.AnalysisStore;
import com.decision.loop.BudgetGuard;
import com.decision.loop.FeasibilityLoop;
import com.decision.loop.FlowRouter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析控制接口（Task 4.8）：
 * POST /api/analysis/{id}/resume   —— 断点恢复（挂起/暂停后从 current_step 续跑）
 * GET  /api/analysis/{id}          —— 进度 + 状态（路由/已完成步骤/待答问题/退出原因/调用数）
 * GET  /api/analysis/{id}/report   —— 简化报告（MD）
 * POST /api/analysis/{id}/depth    —— 手动升降级深度（light/standard/deep）
 * POST /api/analysis/{id}/pause    —— 暂停（当前步骤后挂起）
 * POST /api/analysis/{id}/cancel   —— 取消
 * GET  /api/analysis/{id}/steps    —— steps_output 结构化 JSON（三档/路径/差距/风险/红队，报告页数据驱动渲染）
 * GET  /api/analysis/{id}/trace    —— llm_trace 调用明细（步骤/token/耗时/模板hash，统计页与报告页共用）
 * GET  /api/analysis/{id}/thinking —— analysis:thinking 事件回放（思考流折叠面板）
 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final AnalysisStore analysisStore;
    private final AnalysisStateStore stateStore;
    private final FlowRouter flowRouter;
    private final FeasibilityLoop loop;
    private final BudgetGuard budgetGuard;
    private final JdbcTemplate jt;
    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public AnalysisController(AnalysisStore analysisStore, AnalysisStateStore stateStore,
                              FlowRouter flowRouter, FeasibilityLoop loop, BudgetGuard budgetGuard,
                              JdbcTemplate jt) {
        this.analysisStore = analysisStore;
        this.stateStore = stateStore;
        this.flowRouter = flowRouter;
        this.loop = loop;
        this.budgetGuard = budgetGuard;
        this.jt = jt;
    }

    /** 断点恢复：重读 analysis_state 从 current_step 续推（已完成步骤不重跑） */
    @PostMapping("/{id}/resume")
    public Map<String, Object> resume(@PathVariable String id) {
        Map<String, Object> analysis = analysisStore.get(id);
        if (analysis == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析不存在: " + id);
        }
        loop.resume(id);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("analysisId", id);
        resp.put("resumed", true);
        resp.put("note", "已触发恢复（已结束的分析会被忽略）");
        return resp;
    }

    /** 进度查询：analysis 行 + 状态/当前步骤/已完成步骤/待答问题/退出原因 */
    @GetMapping("/{id}")
    public Map<String, Object> progress(@PathVariable String id) {
        Map<String, Object> analysis = analysisStore.get(id);
        if (analysis == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析不存在: " + id);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("analysis", analysis);
        resp.put("route", flowRouter.routeView(str(analysis.get("depth"))));
        resp.put("modelCalls", budgetGuard.usedCalls(id));

        Map<String, Object> st = new LinkedHashMap<>();
        AnalysisStateStore.AnalysisState state = stateStore.get(id);
        if (state != null) {
            st.put("status", state.status());
            st.put("currentStep", state.currentStep());
            LinkedHashMap<String, Object> steps = stateStore.parseSteps(state);
            List<String> completed = steps.keySet().stream()
                    .filter(k -> !k.startsWith("_"))
                    .toList();
            st.put("completedSteps", completed);
            st.put("exit", steps.get("_exit"));
            st.put("pendingQuestion", parseJson(state.pendingQuestion()));
            st.put("updatedAt", state.updatedAt());
        }
        resp.put("state", st);
        return resp;
    }

    @GetMapping("/{id}/report")
    public Map<String, Object> report(@PathVariable String id) {
        Map<String, Object> report = analysisStore.getReport(id);
        if (report == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告尚未生成（分析可能仍在进行或已中断）");
        }
        return report;
    }

    /**
     * steps_output 结构化 JSON（Task 12 报告页数据驱动渲染的数据源）：
     * 返回 analysis_state.steps_output 解析后的完整对象（assess 三档/ path 路径/ gap 差距矩阵/
     * risk 风险清单/ redteam 红队收敛 + _exit/_meta），供前端优先渲染三档对比卡、风险矩阵、差距表格。
     * 结构化数据不足时报告页回退 markdown 分区渲染。
     */
    @GetMapping("/{id}/steps")
    public Map<String, Object> steps(@PathVariable String id) {
        requireAnalysis(id);
        AnalysisStateStore.AnalysisState state = stateStore.get(id);
        LinkedHashMap<String, Object> steps = stateStore.parseSteps(state);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("analysisId", id);
        resp.put("steps", steps);
        // 路由信息（light/standard/deep 与步骤序列）供前端判断结构化键可用性
        Map<String, Object> analysis = analysisStore.get(id);
        resp.put("route", flowRouter.routeView(str(analysis == null ? null : analysis.get("depth"))));
        return resp;
    }

    /** llm_trace 调用明细（Task 13.1 统计页/报告页共用）：call_seq/step/tokens/duration/template_hash */
    @GetMapping("/{id}/trace")
    public List<Map<String, Object>> trace(@PathVariable String id) {
        requireAnalysis(id);
        return jt.queryForList("""
                SELECT call_seq, step, prompt_tokens, completion_tokens, duration_ms, template_hash, created_at
                FROM llm_trace WHERE analysis_id=? ORDER BY call_seq
                """, id);
    }

    /** analysis:thinking 事件回放（报告页思考流折叠面板）：按时间序返回思考片段 */
    @GetMapping("/{id}/thinking")
    public Map<String, Object> thinking(@PathVariable String id) {
        requireAnalysis(id);
        List<Map<String, Object>> rows = jt.queryForList(
                "SELECT payload, created_at FROM event WHERE analysis_id=? AND type=? ORDER BY seq",
                id, "analysis:thinking");
        List<String> fragments = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String payload = String.valueOf(row.get("payload"));
            Object parsed = parseJson(payload);
            if (parsed instanceof Map<?, ?> m) {
                Object text = m.get("text");
                if (text == null) text = m.get("content");
                if (text == null) text = m.get("thinking");
                if (text == null) text = m.get("fragment");
                if (text != null) fragments.add(String.valueOf(text));
            } else if (parsed instanceof String s && !s.isBlank()) {
                fragments.add(s);
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("analysisId", id);
        resp.put("fragments", fragments);
        resp.put("count", fragments.size());
        return resp;
    }

    /** 手动升降级深度（仅 waiting/running 可调，已结束的分析拒绝） */
    @PostMapping("/{id}/depth")
    public Map<String, Object> changeDepth(@PathVariable String id, @RequestBody Map<String, String> body) {
        Map<String, Object> analysis = analysisStore.get(id);
        if (analysis == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析不存在: " + id);
        }
        try {
            FlowRouter.FlowRoute route = flowRouter.changeDepth(id, body.get("depth"));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("analysisId", id);
            resp.put("depth", route.depth());
            resp.put("steps", route.steps());
            resp.put("note", "下次 run/resume 按新路由执行，已完成步骤不重跑");
            return resp;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{id}/pause")
    public Map<String, Object> pause(@PathVariable String id) {
        loop.pause(id);
        return Map.of("analysisId", id, "paused", true, "note", "当前步骤完成后挂起（非阻塞）");
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        loop.cancel(id);
        return Map.of("analysisId", id, "cancelled", true);
    }

    // ==================== 内部 ====================

    private void requireAnalysis(String id) {
        if (analysisStore.get(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析不存在: " + id);
        }
    }

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

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
