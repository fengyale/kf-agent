package com.decision.loop;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 流程路由（spec Requirement: 流程路由）：按 depth 映射步骤序列，三档分流。
 * - light（日常事务）：[intent, assess-light]，1~2 次调用出简短结论
 * - standard（学习计划）：[intent, collect, assess, path, gap, risk] 六步全流程
 * - deep（远期规划/商业经营）：六步 + redteam 红队收敛循环（质疑→修订→复审，
 *   代码确定性校验与模型质疑合并判定 major，直到无严重质疑或预算/轮次上限；
 *   deep 走独立额度 deepMaxModelCalls 以容纳完整闭环）
 *
 * 支持手动升降级：changeDepth 在 waiting 状态下更新 analysis.depth，
 * 下次 run/resume 按新路由执行（steps_output 已完成的步骤仍不重跑）。
 */
@Service
public class FlowRouter {

    public record FlowRoute(String depth, List<String> steps) {}

    private static final List<String> LIGHT_ROUTE = List.of("intent", "assess-light");
    private static final List<String> STANDARD_ROUTE = List.of("intent", "collect", "assess", "path", "gap", "risk");
    private static final List<String> DEEP_ROUTE = List.of("intent", "collect", "assess", "path", "gap", "risk", "redteam");

    private final AnalysisStore analysisStore;
    private final AnalysisStateStore stateStore;

    public FlowRouter(AnalysisStore analysisStore, AnalysisStateStore stateStore) {
        this.analysisStore = analysisStore;
        this.stateStore = stateStore;
    }

    /** depth → 完整步骤序列（intent/collect 由会话线完成，其余由 FeasibilityLoop 执行） */
    public FlowRoute route(String depth) {
        return switch (normalizeDepth(depth)) {
            case "light" -> new FlowRoute("light", LIGHT_ROUTE);
            case "deep" -> new FlowRoute("deep", DEEP_ROUTE);
            default -> new FlowRoute("standard", STANDARD_ROUTE);
        };
    }

    /** FeasibilityLoop 实际执行的步骤（剔除已在会话线完成的 intent/collect；断点恢复按 steps_output 跳过） */
    public List<String> loopSteps(String depth) {
        return route(depth).steps().stream()
                .filter(s -> !"intent".equals(s) && !"collect".equals(s))
                .toList();
    }

    /** depth 归一：light/standard/deep（接受中英文与常见变体），非法返回 null */
    public static String normalizeDepth(String raw) {
        if (raw == null) {
            return null;
        }
        String d = raw.strip().toLowerCase(Locale.ROOT);
        if (d.isEmpty()) {
            return null;
        }
        if (d.contains("light") || d.contains("轻量") || d.contains("浅")) {
            return "light";
        }
        if (d.contains("deep") || d.contains("深度")) {
            return "deep";
        }
        if (d.contains("standard") || d.contains("标准")) {
            return "standard";
        }
        return null;
    }

    /** 手动升降级：已结束（done/error/cancelled）的分析不允许调整 */
    public FlowRoute changeDepth(String analysisId, String newDepth) {
        String depth = normalizeDepth(newDepth);
        if (depth == null) {
            throw new IllegalArgumentException("非法深度: " + newDepth + "（可选 light/standard/deep）");
        }
        AnalysisStateStore.AnalysisState state = stateStore.get(analysisId);
        if (state != null) {
            String status = state.status();
            if (AnalysisStateStore.STATUS_DONE.equals(status)
                    || AnalysisStateStore.STATUS_ERROR.equals(status)
                    || AnalysisStateStore.STATUS_CANCELLED.equals(status)) {
                throw new IllegalStateException("分析已结束（" + status + "），无法调整深度");
            }
        }
        analysisStore.updateDepth(analysisId, depth);
        return route(depth);
    }

    /** 步骤中文名（事件与日志用） */
    public static String stepLabel(String step) {
        return switch (step == null ? "" : step) {
            case "intent" -> "意图识别";
            case "collect" -> "交互采集";
            case "assess" -> "可行性评估";
            case "assess-light" -> "简短评估";
            case "path" -> "路径设计";
            case "gap" -> "差距分析";
            case "risk" -> "风险与对策";
            case "redteam" -> "红队质疑";
            default -> step == null ? "" : step;
        };
    }

    /** 供进度接口展示路由 */
    public Map<String, Object> routeView(String depth) {
        FlowRoute r = route(depth);
        return Map.of("depth", r.depth(), "steps", r.steps());
    }
}
