package com.decision.model;

import java.util.List;
import java.util.Locale;

/**
 * 分析线（线2）数据模型：六步主干各步骤的 JSON 输出
 * （模板3 完整版：三档情景/数据来源；模板4：红队质疑收敛循环）与挂起/恢复的追问载荷。
 */
public class LoopModels {

    // ==================== 模板3 完整版（Task 6 三档情景） ====================

    /** 三档情景里程碑：阶段名 + 可检查验收标准 + 降档条件 */
    public record ScenarioMilestone(String name, String criteria, String demoteCondition) {}

    /** 三档情景完整版：A 乐观(P75) / B 现实(校准系数修正) / C 保守(P25 最低可持续投入) */
    public record Scenario(String name, Double weeklyHours, Double duration, Double probability,
                           List<ScenarioMilestone> milestones, String demoteTrigger) {}

    /** 结论引用的数据来源（含 asOf 时效，数据保鲜度） */
    public record DataSource(String source, String asOf) {}

    /** assess 自声明缺失的关键槽位（缺什么 + 该怎么问，系统据此向用户追问） */
    public record MissingCritical(String slot, String question) {}

    /** assess 步骤输出：可行性结论 + 数据来源 + 完整三档情景 */
    public record AssessOutput(String conclusion, List<DataSource> dataSources, List<Scenario> scenarios,
                               List<MissingCritical> missingCritical, String notes) {}

    /** path 步骤输出：分阶段路径（简化版） */
    public record PathStage(String name, Double weeks, String goal, String validation) {}

    public record PathOutput(List<PathStage> stages, Double totalWeeks, String notes) {}

    /** gap 步骤输出：差距清单 + kill criteria */
    public record GapItem(String item, String current, String target, String severity, String killCriteria) {}

    public record GapOutput(List<GapItem> gaps) {}

    /** risk 步骤输出：按概率×影响排序的风险清单 */
    public record RiskItem(String risk, String probability, String impact, String mitigation) {}

    public record RiskOutput(List<RiskItem> risks) {}

    /** 轻量路径 assess-light 输出（1~2 次调用出简短结论） */
    public record AssessLightOutput(String conclusion, String suggestion, List<String> keyRisks) {}

    // ==================== 模板4 红队质疑（Task 7 收敛循环） ====================

    /** 质疑来源：model = 模型红队提出；code = 代码确定性校验判定（模型无权降级） */
    public static final String SOURCE_MODEL = "model";
    public static final String SOURCE_CODE = "code";

    /** 红队质疑项：severity = major（严重，触发修订轮）/ minor（次要，写入报告已知局限）；source 标记提出方 */
    public record RedTeamChallenge(String point, String severity, String mitigation, String source) {
        /** 模型质疑便捷构造（source 默认 model） */
        public RedTeamChallenge(String point, String severity, String mitigation) {
            this(point, severity, mitigation, SOURCE_MODEL);
        }
    }

    /** 红队质疑输出（模板4） */
    public record RedTeamOutput(List<RedTeamChallenge> challenges) {}

    /**
     * 红队收敛元数据（steps_output 键 redteam）：
     * challenges 为最后一轮质疑（报告红队区/已知局限区取材）；
     * allChallenges 为跨轮累计质疑（含已被修订解决的 major，红队体检与报告完整性用）；
     * 其余为红队自审机制的可观测指标（包装类型，兼容历史落库数据）。
     */
    public record RedTeamMeta(int rounds, boolean converged, List<RedTeamChallenge> challenges, String note,
                              List<RedTeamChallenge> allChallenges, Integer emptyRounds,
                              Integer codeMajorCount, Integer noSubstanceRounds, Boolean budgetStopped) {
        /** 兼容构造：无体检指标时按空值落库 */
        public RedTeamMeta(int rounds, boolean converged, List<RedTeamChallenge> challenges, String note) {
            this(rounds, converged, challenges, note,
                    challenges == null ? List.of() : challenges, 0, 0, 0, false);
        }
    }

    /** 修订轮输出：修订后的结论 + 三档 + 路径阶段（复用槽位/素材，不重新搜集） */
    public record RevisionOutput(String conclusion, List<Scenario> scenarios,
                                 List<PathStage> stages, String revisionNote) {}

    /** severity 归一判定：major/severe/严重 视为严重（触发修订轮） */
    public static boolean isMajor(String severity) {
        if (severity == null) {
            return false;
        }
        String s = severity.strip().toLowerCase(Locale.ROOT);
        return s.equals("major") || s.equals("severe") || s.contains("严重");
    }

    // ==================== 挂起/恢复载荷 ====================

    /** 追问问题项：collect:question 与 ask:followup 共用载荷结构 */
    public record QuestionItem(String slot, String question, boolean skippable) {}

    /** analysis_state.pending_question 的 JSON 形态：kind = collect | followup */
    public record PendingQuestions(String kind, List<QuestionItem> questions, String askedAt) {}
}
