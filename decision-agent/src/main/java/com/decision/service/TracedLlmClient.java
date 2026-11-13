package com.decision.service;

import com.decision.loop.BudgetGuard;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * LlmClient 的 trace 包装层（调用 trace 落库的侵入最小方案：不改 LlmClient，
 * 在其外包一层）。模型调用前挂 BudgetGuard 计数/拦截，调用后写 llm_trace
 * （call_seq/step/tokens/duration/template_hash）。
 *
 * token 数为字符数近似估算（LlmClient 未暴露 usage；中文 ≈ 1 字符 1 token 量级），
 * 用于"钱花在哪"的量级观测而非精确计费。
 */
@Service
public class TracedLlmClient {

    private final LlmClient llm;
    private final BudgetGuard guard;

    public TracedLlmClient(LlmClient llm, BudgetGuard guard) {
        this.llm = llm;
        this.guard = guard;
    }

    /**
     * 计入 analysis 预算的结构化调用：超该分析额度（standard/light 默认 8 次，deep 见 BudgetGuard）抛
     * BudgetExceededException；成功与失败都写 llm_trace（失败调用可能已产生费用，同样计次）。
     * 解析失败重试同样走预算与 trace（否则"重试"会把真实调用数放大到额度之外）。
     */
    public <T> T generateJson(String analysisId, String step, String system, String userPrompt,
                              Class<T> type, String templateHash) {
        guard.beforeModelCall(analysisId);
        long start = System.currentTimeMillis();
        int promptTokens = estimateTokens(system) + estimateTokens(userPrompt);
        try {
            T result = llm.generateJson(system, userPrompt, type, retryCharge(analysisId, step, promptTokens, templateHash));
            guard.afterModelCall(analysisId, step, System.currentTimeMillis() - start,
                    promptTokens, estimateTokens(result == null ? "" : String.valueOf(result)), templateHash);
            return result;
        } catch (RuntimeException e) {
            guard.afterModelCall(analysisId, step, System.currentTimeMillis() - start,
                    promptTokens, 0, templateHash);
            throw e;
        }
    }

    /**
     * 流式结构化调用（体验升级：分析步骤实时推送思考流，页面不再干等）：
     * 护栏/计费/trace 与 generateJson 完全一致，额外把流式思考片段实时回调给调用方。
     */
    public <T> T generateJsonStreaming(String analysisId, String step, String system, String userPrompt,
                                       Class<T> type, String templateHash, Consumer<String> onThinking) {
        guard.beforeModelCall(analysisId);
        long start = System.currentTimeMillis();
        int promptTokens = estimateTokens(system) + estimateTokens(userPrompt);
        try {
            T result = llm.generateJsonStreaming(system, userPrompt, type, onThinking,
                    retryCharge(analysisId, step, promptTokens, templateHash));
            guard.afterModelCall(analysisId, step, System.currentTimeMillis() - start,
                    promptTokens, estimateTokens(result == null ? "" : String.valueOf(result)), templateHash);
            return result;
        } catch (RuntimeException e) {
            guard.afterModelCall(analysisId, step, System.currentTimeMillis() - start,
                    promptTokens, 0, templateHash);
            throw e;
        }
    }

    /** 重试前的预算检查 + 计次：额度耗尽时抛 BudgetExceededException，使重试不被放任执行 */
    private Runnable retryCharge(String analysisId, String step, int promptTokens, String templateHash) {
        return () -> {
            guard.beforeModelCall(analysisId);
            guard.afterModelCall(analysisId, step, 0, promptTokens, 0, templateHash);
        };
    }

    private static int estimateTokens(String text) {
        return text == null ? 0 : text.length();
    }
}
