package com.decision.loop;

import com.decision.config.AgentProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 成本护栏（spec Requirement: 成本护栏）：
 * - 模型调用计数：按 analysis_id 自查 llm_trace，超该分析额度（standard/light 默认 8 次，
 *   deep 走 deepMaxModelCalls 独立额度以容纳红队闭环）拦截抛 BudgetExceededException
 * - 时长护栏：分析累计活跃时长（挂起等用户期间不计时）超上限（默认 3 分钟）由循环侧触发超时完成
 * - 每次模型调用后写 llm_trace（call_seq/step/tokens/duration/template_hash），模板迭代不盲调
 */
@Service
public class BudgetGuard {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jt;
    private final AgentProperties props;

    public BudgetGuard(JdbcTemplate jt, AgentProperties props) {
        this.jt = jt;
        this.props = props;
    }

    /** 模型调用前检查：超该分析额度拦截 */
    public void beforeModelCall(String analysisId) {
        int limit = effectiveLimit(analysisId);
        int used = usedCalls(analysisId);
        if (used >= limit) {
            throw new BudgetExceededException("单次分析模型调用达上限 " + limit
                    + " 次（已用 " + used + "），被成本护栏拦截: " + analysisId);
        }
    }

    /**
     * 该分析的模型调用额度：deep 路径用 deepMaxModelCalls
     * （前置分析步 6 次 + 红队"质疑/修订/复审"闭环，8 次装不下完整闭环），其余路径用 maxModelCalls。
     * 查询失败按通用额度兜底，护栏不因异常失效。
     */
    public int effectiveLimit(String analysisId) {
        try {
            List<String> depths = jt.queryForList("SELECT depth FROM analysis WHERE id=?",
                    String.class, analysisId);
            if (!depths.isEmpty() && "deep".equals(FlowRouter.normalizeDepth(depths.get(0)))) {
                return props.getBudget().getDeepMaxModelCalls();
            }
        } catch (Exception ignored) {
            // 记录缺失/查询异常 → 通用额度
        }
        return props.getBudget().getMaxModelCalls();
    }

    /** 模型调用后写 llm_trace 并同步 analysis.model_calls */
    public void afterModelCall(String analysisId, String step, long durationMs,
                               int promptTokens, int completionTokens, String templateHash) {
        int seq = usedCalls(analysisId) + 1;
        jt.update("""
                        INSERT INTO llm_trace(analysis_id, call_seq, step, prompt_tokens, completion_tokens, duration_ms, template_hash, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                analysisId, seq, step, promptTokens, completionTokens, durationMs,
                templateHash, LocalDateTime.now().format(TS));
        jt.update("UPDATE analysis SET model_calls=? WHERE id=?", seq, analysisId);
    }

    /** 该分析已发生的模型调用次数（以 llm_trace 为准，含采集追问/意图识别） */
    public int usedCalls(String analysisId) {
        Integer count = jt.queryForObject("SELECT COUNT(*) FROM llm_trace WHERE analysis_id=?",
                Integer.class, analysisId);
        return count == null ? 0 : count;
    }

    /** 时长护栏：累计活跃时长超上限（挂起等待用户回答期间不计时） */
    public boolean isOverDuration(long activeMs) {
        return activeMs >= props.getBudget().getMaxDurationMinutes() * 60_000L;
    }
}
