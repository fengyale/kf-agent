package com.decision.collector;

import com.decision.loop.FlowRouter;
import com.decision.model.CollectorModels.IntentResult;
import com.decision.prompt.PromptBuilder;
import com.decision.service.TracedLlmClient;
import org.springframework.stereotype.Service;

/**
 * 意图识别（模板1）：从用户输入提取 {goal, pathHint, type, urgency, depth}。
 * type 归一到四枚举（日常事务/学习计划/远期规划/商业经营），depth 归一到 light/standard/deep；
 * type→depth 默认映射：日常事务→light，学习计划→standard，远期规划/商业经营→deep（用户可覆盖，
 * 覆盖发生在 ChatController 层——请求里的 depth 参数优先于 LLM 判断）。
 * 调用经 TracedLlmClient 计入该分析的模型调用预算（llm_trace）。
 */
@Service
public class IntentService {

    public static final String TYPE_DAILY = "日常事务";
    public static final String TYPE_LEARNING = "学习计划";
    public static final String TYPE_LONGTERM = "远期规划";
    public static final String TYPE_BUSINESS = "商业经营";

    private final TracedLlmClient llm;
    private final PromptBuilder prompts;

    public IntentService(TracedLlmClient llm, PromptBuilder prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    public IntentResult recognize(String analysisId, String question) {
        IntentResult raw = llm.generateJson(analysisId, "intent",
                prompts.intentSystem(), prompts.buildIntentPrompt(question),
                IntentResult.class, prompts.templateHash("intent"));
        return new IntentResult(
                raw.goal() == null || raw.goal().isBlank() ? question : raw.goal(),
                raw.pathHint() == null ? "" : raw.pathHint(),
                normalizeType(raw.type()),
                raw.urgency() == null || raw.urgency().isBlank() ? "中" : raw.urgency(),
                FlowRouter.normalizeDepth(raw.depth()));
    }

    /** type 归一：模糊匹配四枚举，识别不了按学习计划处理（标准流程，最稳妥的默认档） */
    public static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return TYPE_LEARNING;
        }
        String t = raw.strip();
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("日常") || lower.contains("daily")) {
            return TYPE_DAILY;
        }
        if (t.contains("学习") || lower.contains("learn")) {
            return TYPE_LEARNING;
        }
        if (t.contains("远期") || t.contains("长期") || lower.contains("long")) {
            return TYPE_LONGTERM;
        }
        if (t.contains("商业") || t.contains("经营") || t.contains("创业") || t.contains("副业")
                || lower.contains("business")) {
            return TYPE_BUSINESS;
        }
        return TYPE_LEARNING;
    }

    /** type→depth 默认映射：LLM 给了合法 depth 用之，否则按 type 映射（日常事务→light，学习计划→standard，远期规划/商业经营→deep） */
    public static String defaultDepth(String type, String llmDepth) {
        String d = FlowRouter.normalizeDepth(llmDepth);
        if (d != null) {
            return d;
        }
        return switch (type == null ? "" : type) {
            case TYPE_DAILY -> "light";
            case TYPE_LONGTERM, TYPE_BUSINESS -> "deep";
            default -> "standard";
        };
    }
}
