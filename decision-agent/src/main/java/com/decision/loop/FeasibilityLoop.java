package com.decision.loop;

import com.decision.collector.CollectorStateMachine;
import com.decision.collector.SlotVocabulary;
import com.decision.config.AgentProperties;
import com.decision.model.CollectorModels.SlotRecord;
import com.decision.model.LoopModels;
import com.decision.model.LoopModels.AssessLightOutput;
import com.decision.model.LoopModels.AssessOutput;
import com.decision.model.LoopModels.DataSource;
import com.decision.model.LoopModels.GapItem;
import com.decision.model.LoopModels.GapOutput;
import com.decision.model.LoopModels.PathOutput;
import com.decision.model.LoopModels.PathStage;
import com.decision.model.LoopModels.PendingQuestions;
import com.decision.model.LoopModels.QuestionItem;
import com.decision.model.LoopModels.RedTeamChallenge;
import com.decision.model.LoopModels.RedTeamMeta;
import com.decision.model.LoopModels.RedTeamOutput;
import com.decision.model.LoopModels.RevisionOutput;
import com.decision.model.LoopModels.RiskItem;
import com.decision.model.LoopModels.RiskOutput;
import com.decision.model.LoopModels.Scenario;
import com.decision.external.ExternalDataService;
import com.decision.external.ExternalDataService.ExternalRef;
import com.decision.export.ReportWriter;
import com.decision.personal.CalibrationService;
import com.decision.personal.ProfileService;
import com.decision.prompt.PromptBuilder;
import com.decision.service.CalcTool;
import com.decision.service.EventPublisher;
import com.decision.service.EventTypes;
import com.decision.service.TracedLlmClient;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 六步分析主干（线2：FeasibilityLoop），借 research-agent ResearchLoop 骨架：
 * 弹性步骤推进 + checkpoint() 暂停/取消 + 退出原因四分类
 * （model-decision:* / guard:* / error:* / waiting:user），把"搜索轮次"换成"分析步骤推进"。
 *
 * P2 升级（Task 6/7/9）：
 * - 模板3 完整版：assess 产出三档情景（里程碑+验收标准+降档条件）+ 数据来源 asOf；
 *   校准系数（CalibrationService 分桶完成率 + P25/P50/P75 分位 + 保鲜度）注入提示词，
 *   B 现实档按系数修正，冷启动标注"建议校准"；三档完整数据经 scenario 事件外发
 * - 红队收敛循环（deep 路径 redteam 步骤）：质疑(major/minor) → major 触发修订轮
 *   （复用槽位/素材不重新搜集）→ 再质疑，直到无 major 或预算耗尽（8 次上限是缰绳）；
 *   红队输入隔离：只喂结论 JSON + 槽位 + 校准系数，绝不含推理过程/thinking/steps_output 中间文本
 * - 第四档"不建议推进"：红队严重质疑未收敛 + 校准系数 <0.4 双重确认才触发
 * - 数据保鲜度：校准 asOf 与画像 updated_at 超 6 个月注入"此数据已 X 个月未校准"
 * - 预测后验：分析完成时三档量化预期落 analysis_expectation（一档一行）
 *
 * 挂起/恢复语义（spec 强制）：等用户回答时 execute() 直接 return 退出释放线程，
 * 完整状态（当前步骤/steps_output/槽位快照/pending_question）落 analysis_state；
 * 用户回答的 HTTP 请求触发 run()/resume() 从断点继续（steps_output 已含的步骤不重跑）。
 * 禁止任何阻塞式等待——暂停同样落库退出，而非睡眠轮询。
 *
 * 工作记忆压缩：每步提示词 = 系统模板 + 槽位 JSON + 上一步 JSON 输出，绝不拼原始对话文本。
 * 算术（总投入=weeklyHours×周数 等）经 CalcTool 代码求值后注入提示词与报告（经计算校验）。
 */
@Service
public class FeasibilityLoop {

    private static final Logger log = LoggerFactory.getLogger(FeasibilityLoop.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** steps_output 保留键：退出原因 / 循环元数据 */
    private static final String EXIT_KEY = "_exit";
    private static final String META_KEY = "_meta";
    /** 历轮修订说明（供红队复审聚焦"改了什么"，防假修订盲审） */
    private static final String REVISION_KEY = "_revisionNote";
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    /** 红队收敛循环轮次上限（第二道缰绳；主缰绳是 BudgetGuard 的模型调用上限） */
    private static final int MAX_REDTEAM_ROUNDS = 3;
    /** 第四档"不建议推进"触发阈值：未解决严重质疑条数 / 现实档概率下限 / 校准系数软阈值 */
    private static final int NO_GO_UNRESOLVED_MAJOR_MIN = 3;
    private static final double NO_GO_PROBABILITY_MIN = 0.25;
    private static final double NO_GO_COEF_SOFT = 0.6;

    private final AgentProperties props;
    private final AnalysisStore analysisStore;
    private final AnalysisStateStore stateStore;
    private final BudgetGuard budgetGuard;
    private final FlowRouter flowRouter;
    private final CollectorStateMachine collector;
    private final TracedLlmClient llm;
    private final PromptBuilder prompts;
    private final EventPublisher events;
    private final CalcTool calcTool;
    private final CalibrationService calibration;
    private final ProfileService profile;
    private final ExpectationStore expectations;
    private final ExternalDataService externalData;
    private final ReportWriter reportWriter;
    private final RedTeamAssertions assertions;
    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "feasibility-loop");
        t.setDaemon(true);
        return t;
    });

    /** 运行中标记：防重复提交 */
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    /** 暂停/取消控制（key 缺失 = 正常运行） */
    private final ConcurrentHashMap<String, ControlState> controls = new ConcurrentHashMap<>();

    enum ControlState { RUNNING, PAUSED, CANCELLED }

    public FeasibilityLoop(AgentProperties props, AnalysisStore analysisStore,
                           AnalysisStateStore stateStore, BudgetGuard budgetGuard,
                           FlowRouter flowRouter, CollectorStateMachine collector,
                           TracedLlmClient llm, PromptBuilder prompts,
                           EventPublisher events, CalcTool calcTool,
                           CalibrationService calibration, ProfileService profile,
                           ExpectationStore expectations, ExternalDataService externalData,
                           ReportWriter reportWriter, RedTeamAssertions assertions) {
        this.props = props;
        this.analysisStore = analysisStore;
        this.stateStore = stateStore;
        this.budgetGuard = budgetGuard;
        this.flowRouter = flowRouter;
        this.collector = collector;
        this.llm = llm;
        this.prompts = prompts;
        this.events = events;
        this.calcTool = calcTool;
        this.calibration = calibration;
        this.profile = profile;
        this.expectations = expectations;
        this.externalData = externalData;
        this.reportWriter = reportWriter;
        this.assertions = assertions;
    }

    // ==================== 对外入口 ====================

    /** 提交/恢复分析（异步执行）：已结束或已在运行则忽略 */
    public void run(String analysisId) {
        AnalysisStateStore.AnalysisState state = stateStore.get(analysisId);
        if (state != null) {
            String s = state.status();
            if (AnalysisStateStore.STATUS_DONE.equals(s)
                    || AnalysisStateStore.STATUS_ERROR.equals(s)
                    || AnalysisStateStore.STATUS_CANCELLED.equals(s)) {
                log.info("[{}] 分析已结束（{}），忽略触发", analysisId, s);
                return;
            }
        }
        if (!active.add(analysisId)) {
            log.info("[{}] 分析正在运行，忽略重复触发", analysisId);
            return;
        }
        controls.put(analysisId, ControlState.RUNNING);
        executor.submit(() -> execute(analysisId));
    }

    /** 断点恢复：用户回答触发，从 analysis_state 重读 current_step/steps_output/slot_snapshot 续跑 */
    public void resume(String analysisId) {
        run(analysisId);
    }

    /** 暂停：非阻塞——当前步骤完成后落库退出（waiting:user），线程即刻释放 */
    public void pause(String analysisId) {
        if (!active.contains(analysisId)) {
            log.info("[{}] 未在运行（挂起中），暂停无需处理", analysisId);
            return;
        }
        controls.put(analysisId, ControlState.PAUSED);
        events.publish(analysisId, EventTypes.ANALYSIS_STAGE,
                Map.of("stage", "pausing", "message", "用户请求暂停，当前步骤完成后挂起"));
    }

    /** 取消：运行中则当前步骤后终止；挂起中直接落终态 */
    public void cancel(String analysisId) {
        if (active.contains(analysisId)) {
            controls.put(analysisId, ControlState.CANCELLED);
            events.publish(analysisId, EventTypes.ANALYSIS_STAGE,
                    Map.of("stage", "cancelling", "message", "用户请求终止，当前步骤后停止"));
            return;
        }
        AnalysisStateStore.AnalysisState state = stateStore.get(analysisId);
        if (state == null || isTerminal(state.status())) {
            return;
        }
        LinkedHashMap<String, Object> steps = stateStore.parseSteps(state);
        steps.put(EXIT_KEY, exitOf("guard:cancelled", "用户手动终止分析（挂起中取消）"));
        stateStore.updateProgress(analysisId, state.currentStep(), stateStore.writeSteps(steps),
                AnalysisStateStore.STATUS_CANCELLED);
        analysisStore.finish(analysisId, "cancelled", null, 0);
        events.publish(analysisId, EventTypes.DONE, Map.of(
                "exitReason", "guard:cancelled",
                "exitDetail", "用户手动终止分析",
                "cancelled", true));
        events.complete(analysisId);
    }

    // ==================== 主干执行 ====================

    void execute(String analysisId) {
        long runStart = System.currentTimeMillis();
        String exitReason = "unknown";
        String exitDetail = "";
        // 终结（done/error/cancelled）才关闭 SSE emitter；waiting 挂起保留连接等用户
        boolean terminated = false;
        try {
            Map<String, Object> analysis = analysisStore.get(analysisId);
            if (analysis == null) {
                throw new IllegalStateException("分析记录不存在: " + analysisId);
            }
            String question = str(analysis.get("question"));
            String type = str(analysis.get("type"));
            String depth = str(analysis.get("depth"));
            List<String> loopSteps = flowRouter.loopSteps(depth);

            AnalysisStateStore.AnalysisState state = stateStore.get(analysisId);
            LinkedHashMap<String, Object> steps = stateStore.parseSteps(state);
            long carriedActiveMs = metaActiveMs(steps);
            String lastDoneStep = currentStepOf(steps, loopSteps, state);

            stateStore.updateStatus(analysisId, AnalysisStateStore.STATUS_RUNNING);

            for (String step : loopSteps) {
                // 断点恢复：steps_output 已含的步骤不重跑
                if (steps.containsKey(step)) {
                    continue;
                }

                // ---- checkpoint：暂停/取消（非阻塞，暂停=落库退出） ----
                ControlState cs = control(analysisId);
                if (cs == ControlState.CANCELLED) {
                    exitReason = "guard:cancelled";
                    exitDetail = "用户手动终止分析";
                    break;
                }
                if (cs == ControlState.PAUSED) {
                    log.info("[{}] 用户暂停，状态落库退出", analysisId);
                    steps.put(EXIT_KEY, exitOf("waiting:user", "用户暂停分析"));
                    steps.put(META_KEY, metaOf(carriedActiveMs + elapsed(runStart)));
                    stateStore.updateProgress(analysisId, lastDoneStep, stateStore.writeSteps(steps),
                            AnalysisStateStore.STATUS_WAITING);
                    return;
                }

                // ---- 时长护栏：累计活跃时长超上限 → 超时完成（输出已完成部分） ----
                long activeMs = carriedActiveMs + elapsed(runStart);
                if (budgetGuard.isOverDuration(activeMs)) {
                    exitReason = "guard:timeout";
                    exitDetail = "分析活跃时长超 " + props.getBudget().getMaxDurationMinutes()
                            + " 分钟上限，输出已完成部分";
                    log.info("[{}] {}", analysisId, exitDetail);
                    break;
                }

                // ---- contextAdequacy：关键槽位确定性检查（未降级时缺数据 → ask:followup 挂起） ----
                if (!collector.isDegraded(analysisId)) {
                    List<QuestionItem> missing = collector.nextQuestions(analysisId);
                    if (!missing.isEmpty()) {
                        suspendForFollowup(analysisId, step, steps, carriedActiveMs, runStart, missing,
                                "分析推进到「" + FlowRouter.stepLabel(step) + "」需要以下关键信息");
                        return; // 退出释放线程，等用户回答触发 resume
                    }
                }

                events.publish(analysisId, EventTypes.ANALYSIS_STAGE, Map.of("stage", step));

                // ---- 三段递进外部数据（Task 10）：assess 前查调研报告引用 → 联网搜索 → 完整调研提示。
                // deep 路径默认启用（standard 同享，按需引用；light 的 assess-light 不消费外部数据）。
                // 引用命中时 search_count 保持 0（零成本可验证）；结果存 steps["_external"] 供提示词与报告共用。
                if ("assess".equals(step) && !steps.containsKey("_external")) {
                    ExternalRef ext = externalData.refer(analysisId, question);
                    steps.put("_external", ext);
                    stateStore.updateProgress(analysisId, lastDoneStep, stateStore.writeSteps(steps),
                            AnalysisStateStore.STATUS_RUNNING);
                }

                // ---- 工作记忆压缩：槽位 JSON + 上一步 JSON（绝不拼原始对话文本） ----
                // assess / redteam / revise 额外注入校准区块（分桶系数 + 分位 + 保鲜度）
                boolean withCalibration = "assess".equals(step) || "redteam".equals(step);
                String contextJson = buildContextJson(analysisId, type, withCalibration);
                String prevJson = prevStepJson(loopSteps, steps, step);
                try {
                    Object output = callStep(analysisId, step, contextJson, prevJson, steps, type,
                            carriedActiveMs, runStart);
                    steps.put(step, output);
                    lastDoneStep = step;
                    steps.put(META_KEY, metaOf(carriedActiveMs + elapsed(runStart)));
                    stateStore.updateProgress(analysisId, step, stateStore.writeSteps(steps),
                            AnalysisStateStore.STATUS_RUNNING);

                    // 三档情景生成 → scenario 事件（完整数据：参数/里程碑/降档条件）
                    if (output instanceof AssessOutput assess) {
                        publishScenario(analysisId, assess.scenarios());
                    }

                    // LLM 自声明缺关键数据（模板3 assess 的 missingCritical）→ 挂起追问
                    List<QuestionItem> declared = declaredMissing(output);
                    if (!declared.isEmpty() && !collector.isDegraded(analysisId)) {
                        collector.ensureKeySlots(analysisId, declared);
                        suspendForFollowup(analysisId, step, steps, carriedActiveMs, runStart, declared,
                                "可行性评估发现关键信息缺失");
                        return;
                    }
                } catch (BudgetExceededException e) {
                    exitReason = "guard:budget";
                    exitDetail = "模型调用达上限 " + budgetGuard.effectiveLimit(analysisId) + " 次，输出已完成部分";
                    log.warn("[{}] {}", analysisId, exitDetail);
                    break;
                } catch (Exception e) {
                    exitReason = "error:" + step;
                    exitDetail = String.valueOf(e.getMessage());
                    log.warn("[{}] 步骤 {} 失败: {}", analysisId, step, exitDetail, e);
                    break;
                }
            }

            if ("unknown".equals(exitReason)) {
                exitReason = "model-decision:completed";
                exitDetail = "流程步骤全部完成";
            }
            terminated = !"waiting:user".equals(exitReason);
            long costMs = carriedActiveMs + elapsed(runStart);
            steps.put(EXIT_KEY, exitOf(exitReason, exitDetail));
            steps.put(META_KEY, metaOf(costMs));

            if ("guard:cancelled".equals(exitReason)) {
                stateStore.updateProgress(analysisId, lastDoneStep, stateStore.writeSteps(steps),
                        AnalysisStateStore.STATUS_CANCELLED);
                analysisStore.finish(analysisId, "cancelled", null, costMs);
                events.publish(analysisId, EventTypes.DONE, Map.of(
                        "exitReason", exitReason, "exitDetail", exitDetail, "cancelled", true));
                log.info("[{}] 分析已取消", analysisId);
            } else if (exitReason.startsWith("error")) {
                stateStore.updateProgress(analysisId, lastDoneStep, stateStore.writeSteps(steps),
                        AnalysisStateStore.STATUS_ERROR);
                analysisStore.finish(analysisId, "error", null, costMs);
                events.publish(analysisId, EventTypes.ERROR, Map.of(
                        "message", exitDetail, "exitReason", exitReason));
                log.warn("[{}] 分析失败: {} ({})", analysisId, exitDetail, exitReason);
            } else {
                // model-decision:* / guard:timeout / guard:budget → 拼装报告（部分完成也输出已完成部分）
                long reportId = saveReport(analysisId, question, type, analysis, steps);
                stateStore.updateProgress(analysisId, lastDoneStep, stateStore.writeSteps(steps),
                        AnalysisStateStore.STATUS_DONE);
                analysisStore.finish(analysisId, "done", reportId, costMs);
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("reportId", reportId);
                done.put("exitReason", exitReason);
                done.put("exitDetail", exitDetail);
                events.publish(analysisId, EventTypes.DONE, done);
                log.info("[{}] 分析完成: 退出原因={} 活跃耗时={}ms 报告id={}", analysisId, exitReason, costMs, reportId);
            }
        } catch (Exception e) {
            terminated = true;
            log.warn("[{}] 分析异常: {}", analysisId, e.getMessage(), e);
            try {
                stateStore.updateStatus(analysisId, AnalysisStateStore.STATUS_ERROR);
                analysisStore.finish(analysisId, "error", null, 0);
            } catch (Exception ignored) {
            }
            events.publish(analysisId, EventTypes.ERROR, Map.of(
                    "message", String.valueOf(e.getMessage()), "exitReason", "error:loop"));
        } finally {
            active.remove(analysisId);
            controls.remove(analysisId);
            if (terminated) {
                events.complete(analysisId);
            }
        }
    }

    // ==================== 挂起（等用户回答） ====================

    /**
     * 缺数据挂起：发 ask:followup 事件、状态落库 status=waiting、return 退出释放线程。
     * 用户回答后由 ChatController 触发 resume，从 current_step 断点继续（本步骤未产出，重跑）。
     */
    private void suspendForFollowup(String analysisId, String step, LinkedHashMap<String, Object> steps,
                                    long carriedActiveMs, long runStart,
                                    List<QuestionItem> questions, String context) {
        steps.put(EXIT_KEY, exitOf("waiting:user", "分析中断等待用户补充信息（断点步骤: " + step + "）"));
        steps.put(META_KEY, metaOf(carriedActiveMs + elapsed(runStart)));
        stateStore.updateProgress(analysisId, step, stateStore.writeSteps(steps),
                AnalysisStateStore.STATUS_WAITING);
        String pending = toJson(new PendingQuestions("followup", questions, LocalDateTime.now().format(TS)));
        stateStore.updatePending(analysisId, pending, AnalysisStateStore.STATUS_WAITING);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("questions", questions);
        payload.put("context", context);
        events.publish(analysisId, EventTypes.ASK_FOLLOWUP, payload);
        log.info("[{}] 缺数据挂起（断点步骤={}，追问 {} 项），线程释放等恢复", analysisId, step, questions.size());
    }

    // ==================== 步骤调用（模板3 完整版拆段 + 红队收敛循环） ====================

    private Object callStep(String analysisId, String step, String contextJson, String prevJson,
                            LinkedHashMap<String, Object> steps, String type,
                            long carriedActiveMs, long runStart) {
        // 流式思考 → analysis:thinking 事件（前端实时展示，页面不再干等）
        Consumer<String> onThinking = seg -> events.publish(analysisId, EventTypes.ANALYSIS_THINKING,
                Map.of("text", seg, "step", step));
        return switch (step) {
            case "assess" -> llm.generateJsonStreaming(analysisId, "assess", prompts.assessSystem(),
                    prompts.buildAssessPrompt(contextJson, prevJson, calibrationJson(type),
                            externalPromptBlock(steps)), AssessOutput.class,
                    prompts.templateHash("assess"), onThinking);
            case "assess-light" -> llm.generateJsonStreaming(analysisId, "assess-light", prompts.assessLightSystem(),
                    prompts.buildAssessLightPrompt(contextJson, prevJson), AssessLightOutput.class,
                    prompts.templateHash("assess-light"), onThinking);
            case "path" -> llm.generateJsonStreaming(analysisId, "path", prompts.pathSystem(),
                    prompts.buildPathPrompt(contextJson, prevJson), PathOutput.class,
                    prompts.templateHash("path"), onThinking);
            case "gap" -> llm.generateJsonStreaming(analysisId, "gap", prompts.gapSystem(),
                    prompts.buildGapPrompt(contextJson, prevJson), GapOutput.class,
                    prompts.templateHash("gap"), onThinking);
            case "risk" -> llm.generateJsonStreaming(analysisId, "risk", prompts.riskSystem(),
                    prompts.buildRiskPrompt(contextJson, prevJson), RiskOutput.class,
                    prompts.templateHash("risk"), onThinking);
            case "redteam" -> runRedteamLoop(analysisId, steps, contextJson, type, carriedActiveMs, runStart);
            default -> throw new IllegalStateException("未知分析步骤: " + step);
        };
    }

    /**
     * 红队收敛循环（deep 路径）：质疑 → 修订 → 复审，最多 MAX_REDTEAM_ROUNDS 轮。
     *
     * 对抗强度分层（把能确定性判定的部分从模型手里收回）：
     * - 代码裁判：RedTeamAssertions 校验三档单调性/B 档是否真按校准系数修正/路径总周期一致性，
     *   命中即作为 major 并入质疑清单（severity 由代码硬编码，模型无权降级）；
     * - 模型红队：四问唱反调（历史数据支撑/最薄弱假设/劝退理由/自欺档位），severity 自报。
     *
     * 收敛条件收紧："空质疑"不判收敛（视为未经实质审查）；上一轮修订若未见实质变化，
     * 复审即使全 minor 也不判收敛（防"改措辞即过关"的假收敛）。修订轮计入模型调用预算。
     *
     * 输入隔离：红队提示词只含结构化结论（assess 结论/三档 + path 阶段 + gap 的 kill criteria +
     * risk 清单 + 槽位 + 校准系数 + 历轮修订说明），绝不含推理过程/thinking/steps_output 中间文本。
     */
    private RedTeamMeta runRedteamLoop(String analysisId, LinkedHashMap<String, Object> steps,
                                       String contextJson, String type, long carriedActiveMs, long runStart) {
        String calJson = calibrationJson(type);
        CalibrationService.Calibration cal = calibration.getCalibration(type);
        List<RedTeamChallenge> all = new ArrayList<>();   // 跨轮累计（含已被修订解决的 major）
        Set<String> seenPoints = new HashSet<>();        // 累计去重键（同一条质疑多轮重复只记一次）
        List<RedTeamChallenge> last = List.of();
        int round = 0;
        int emptyRounds = 0;
        int codeMajorCount = 0;
        int noSubstanceRounds = 0;
        boolean lastRevisionNoSubstance = false;
        // 红队/修订也走流式思考（前端可见红队也在"思考"）
        Consumer<String> redteamThinking = seg -> events.publish(analysisId, EventTypes.ANALYSIS_THINKING,
                Map.of("text", seg, "step", "redteam"));
        while (round < MAX_REDTEAM_ROUNDS) {
            round++;
            // ---- 时长护栏：红队循环内同样受 3 分钟活跃时长约束（防整体被拖过上限） ----
            if (budgetGuard.isOverDuration(carriedActiveMs + elapsed(runStart))) {
                return meta(round, false, last, all, emptyRounds, codeMajorCount, noSubstanceRounds, true,
                        "红队第 " + round + " 轮前分析活跃时长已达上限，保留当前结论");
            }

            // ---- 代码确定性校验（不消耗模型预算，severity 硬编码 major） ----
            List<RedTeamChallenge> codeChallenges = assertions.check(
                    assessOf(steps), pathOf(steps), cal.coefficient());

            // ---- 模型红队质疑（输入隔离：结论 JSON + 槽位 + 校准系数） ----
            RedTeamOutput rt;
            try {
                rt = llm.generateJsonStreaming(analysisId, "redteam", prompts.redteamSystem(),
                        prompts.buildRedteamPrompt(redteamViewJson(steps), contextJson, calJson),
                        RedTeamOutput.class, prompts.templateHash("redteam"), redteamThinking);
            } catch (BudgetExceededException e) {
                if (round == 1) {
                    throw e; // 首轮红队都跑不动 = 整个分析超预算，向上按 guard:budget 退出
                }
                return meta(round, false, last, all, emptyRounds, codeMajorCount, noSubstanceRounds, true,
                        "预算耗尽，红队在第 " + round + " 轮停止（上次质疑保留）");
            }
            List<RedTeamChallenge> modelChallenges =
                    rt == null || rt.challenges() == null ? List.of() : rt.challenges();

            // ---- 合并：代码裁判 + 模型质疑（模型无法改写代码判定条目） ----
            // 本轮判定用不去重的 merged：只要当前结论仍违反确定性校验，major 就必须存在（否则会假收敛）
            List<RedTeamChallenge> merged = new ArrayList<>(codeChallenges);
            merged.addAll(modelChallenges);
            last = merged;
            // 跨轮累计（报告与红队体检用）去重：同一冲突多轮重复出现只记一次，避免计数虚高与报告重复
            for (RedTeamChallenge c : merged) {
                if (c != null && c.point() != null && seenPoints.add(c.point())) {
                    all.add(c);
                    if (LoopModels.SOURCE_CODE.equals(c.source())) {
                        codeMajorCount++;
                    }
                }
            }
            events.publish(analysisId, EventTypes.REDTEAM_ISSUE, Map.of(
                    "round", round, "challenges", merged, "codeChallenges", codeChallenges));

            // ---- 空质疑：不判收敛（"没发现问题" ≠ "通过审查"） ----
            if (merged.isEmpty()) {
                emptyRounds++;
                log.info("[{}] 红队第 {} 轮未提出实质质疑，不判收敛", analysisId, round);
                return meta(round, false, last, all, emptyRounds, codeMajorCount, noSubstanceRounds, false,
                        "红队未提出实质质疑（未经实质审查，不作为通过依据）");
            }

            List<RedTeamChallenge> majors = merged.stream()
                    .filter(c -> c != null && LoopModels.isMajor(c.severity()))
                    .toList();
            if (majors.isEmpty()) {
                if (lastRevisionNoSubstance) {
                    log.info("[{}] 红队第 {} 轮无严重质疑，但上轮修订无实质变化，不判收敛", analysisId, round);
                    return meta(round, false, last, all, emptyRounds, codeMajorCount, noSubstanceRounds, false,
                            "红队第 " + round + " 轮无严重质疑，但上一轮修订未见实质变化，质疑未真正解除");
                }
                log.info("[{}] 红队第 {} 轮无严重质疑，收敛", analysisId, round);
                return meta(round, true, last, all, emptyRounds, codeMajorCount, noSubstanceRounds, false,
                        "红队 " + round + " 轮未发现严重质疑（" + countMinor(last) + " 条次要质疑列入已知局限）");
            }

            // ---- 预算预检：修订需 1 次调用（复审由下一轮循环开头重新判定），不足则如实停止并披露 ----
            if (budgetGuard.usedCalls(analysisId) + 1 > budgetGuard.effectiveLimit(analysisId)) {
                return meta(round, false, last, all, emptyRounds, codeMajorCount, noSubstanceRounds, true,
                        "预算不足以继续修订（剩余严重质疑 " + majors.size() + " 项未解决），保留当前结论");
            }

            // ---- 修订轮（major 质疑发回修订，复用素材；计入预算） ----
            RevisionOutput rev;
            try {
                rev = llm.generateJsonStreaming(analysisId, "revise", prompts.reviseSystem(),
                        prompts.buildRevisePrompt(contextJson, redteamViewJson(steps),
                                toJson(majors), calJson),
                        RevisionOutput.class, prompts.templateHash("revise"), redteamThinking);
            } catch (BudgetExceededException e) {
                return meta(round, false, last, all, emptyRounds, codeMajorCount, noSubstanceRounds, true,
                        "修订轮预算耗尽（严重质疑 " + majors.size() + " 项未解决），保留当前结论");
            }
            boolean changed = applyRevision(steps, rev);
            // "实质修订" = 数值有变化，且代码判定的确定性冲突真正减少；
            // 只改措辞或微调数值而冲突照旧（如现实档仍不合校准系数）→ 不算实质修订；
            // 当轮无代码冲突时只能凭数值变化判断
            int remaining = assertions.check(assessOf(steps), pathOf(steps), cal.coefficient()).size();
            boolean substance = changed && (codeChallenges.isEmpty() || remaining < codeChallenges.size());
            lastRevisionNoSubstance = !substance;
            if (!substance) {
                noSubstanceRounds++;
            }
            stateStore.updateProgress(analysisId, "redteam", stateStore.writeSteps(steps),
                    AnalysisStateStore.STATUS_RUNNING);
            // 修订后的三档再发一次 scenario 事件（会话页可见修订结果）
            Object revised = steps.get("assess");
            if (revised != null) {
                publishScenario(analysisId,
                        om.convertValue(revised, AssessOutput.class).scenarios());
            }
            log.info("[{}] 红队第 {} 轮 {} 项严重质疑 → 修订轮完成（数值变更={} 剩余确定性冲突 {}→{}），进入复审",
                    analysisId, round, majors.size(), changed, codeChallenges.size(), remaining);
        }
        return meta(round, false, last, all, emptyRounds, codeMajorCount, noSubstanceRounds, true,
                "达红队轮次上限 " + MAX_REDTEAM_ROUNDS + "，保留当前结论（严重质疑经多轮修订仍未完全解除）");
    }

    /** 红队收敛元数据装配（last = 最后一轮质疑；all = 跨轮累计） */
    private static RedTeamMeta meta(int rounds, boolean converged, List<RedTeamChallenge> last,
                                    List<RedTeamChallenge> all, int emptyRounds, int codeMajorCount,
                                    int noSubstanceRounds, boolean budgetStopped, String note) {
        return new RedTeamMeta(rounds, converged, last, note, all, emptyRounds, codeMajorCount,
                noSubstanceRounds, budgetStopped);
    }

    private static int countMinor(List<RedTeamChallenge> list) {
        return (int) list.stream().filter(c -> c != null && !LoopModels.isMajor(c.severity())).count();
    }

    /**
     * 红队输入隔离视图：挑结构化结论（assess 结论/三档 + path 阶段 + gap 的 kill criteria + risk 清单），
     * 外加历轮修订说明（供复审聚焦"改了什么"）——不含推理过程/thinking/中间文本。
     */
    private String redteamViewJson(Map<String, Object> steps) {
        Map<String, Object> view = new LinkedHashMap<>();
        AssessOutput a = assessOf(steps);
        if (a != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("conclusion", a.conclusion());
            m.put("scenarios", a.scenarios() == null ? List.of() : a.scenarios());
            view.put("feasibility", m);
        }
        PathOutput p = pathOf(steps);
        if (p != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stages", p.stages() == null ? List.of() : p.stages());
            m.put("totalWeeks", p.totalWeeks());
            view.put("path", m);
        }
        // 能力缺口（kill criteria）与风险清单：红队"最薄弱假设 / 谁会劝你别做"两问的判据
        Object gapObj = steps.get("gap");
        if (gapObj != null) {
            GapOutput gap = om.convertValue(gapObj, GapOutput.class);
            List<Map<String, Object>> gaps = new ArrayList<>();
            if (gap != null && gap.gaps() != null) {
                for (GapItem g : gap.gaps()) {
                    if (g == null) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("item", g.item());
                    m.put("current", g.current());
                    m.put("target", g.target());
                    m.put("killCriteria", g.killCriteria());
                    gaps.add(m);
                }
            }
            if (!gaps.isEmpty()) {
                view.put("gaps", gaps);
            }
        }
        Object riskObj = steps.get("risk");
        if (riskObj != null) {
            RiskOutput risk = om.convertValue(riskObj, RiskOutput.class);
            List<Map<String, Object>> risks = new ArrayList<>();
            if (risk != null && risk.risks() != null) {
                for (RiskItem r : risk.risks()) {
                    if (r == null) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("risk", r.risk());
                    m.put("probability", r.probability());
                    m.put("impact", r.impact());
                    risks.add(m);
                }
            }
            if (!risks.isEmpty()) {
                view.put("risks", risks);
            }
        }
        // 历轮修订说明：REVISE 模板声明"供红队复审聚焦"，此处真正回喂（此前声明了却未传，导致复审盲审）
        Object notes = steps.get(REVISION_KEY);
        if (notes instanceof List<?> list && !list.isEmpty()) {
            view.put("previousRevisions", list);
        }
        return toJson(view);
    }

    /**
     * 修订轮结果写回 steps：assess（结论/三档）与 path（阶段）就地更新，dataSources 等未受质疑部分保留。
     * 返回是否发生实质变更——"只改措辞"的假修订必须被识别，否则复审会据其误判收敛。
     */
    private boolean applyRevision(LinkedHashMap<String, Object> steps, RevisionOutput rev) {
        if (rev == null) {
            return false;
        }
        // 修订说明先留痕（供下一轮红队复审聚焦，无论是否实质变更）
        if (rev.revisionNote() != null && !rev.revisionNote().isBlank()) {
            revisionNotes(steps).add(rev.revisionNote());
        }
        boolean changed = false;
        AssessOutput old = assessOf(steps);
        if (old != null && (rev.conclusion() != null || rev.scenarios() != null && !rev.scenarios().isEmpty())) {
            String conclusion = rev.conclusion() != null && !rev.conclusion().isBlank()
                    ? rev.conclusion() : old.conclusion();
            List<Scenario> scenarios = rev.scenarios() != null && !rev.scenarios().isEmpty()
                    ? rev.scenarios() : old.scenarios();
            if (!sameText(old.conclusion(), conclusion) || !sameScenarios(old.scenarios(), scenarios)) {
                changed = true;
                steps.put("assess", new AssessOutput(conclusion, old.dataSources(), scenarios,
                        old.missingCritical(), joinNotes(old.notes(), rev.revisionNote())));
            }
        }
        PathOutput oldPath = pathOf(steps);
        if (rev.stages() != null && !rev.stages().isEmpty()
                && !sameStages(oldPath == null ? null : oldPath.stages(), rev.stages())) {
            changed = true;
            // 阶段周期须完整：任一阶段缺周期则不重算总周期（保留原值），避免总周期被静默算小
            boolean complete = rev.stages().stream().allMatch(s -> s != null && s.weeks() != null);
            Double total = complete
                    ? rev.stages().stream().mapToDouble(PathStage::weeks).sum()
                    : oldPath == null ? null : oldPath.totalWeeks();
            String notes = oldPath != null && oldPath.notes() != null && !oldPath.notes().isBlank()
                    ? oldPath.notes() : "红队修订后更新";
            steps.put("path", new PathOutput(rev.stages(), total, notes));
        }
        return changed;
    }

    /** 文本实质变更判定（忽略首尾空白差异） */
    private static boolean sameText(String a, String b) {
        return Objects.equals(a == null ? null : a.strip(), b == null ? null : b.strip());
    }

    /** 三档实质变更判定：比较档位数与各档关键数值（投入/周期/概率） */
    private static boolean sameScenarios(List<Scenario> a, List<Scenario> b) {
        List<Scenario> x = a == null ? List.of() : a;
        List<Scenario> y = b == null ? List.of() : b;
        if (x.size() != y.size()) {
            return false;
        }
        for (int i = 0; i < x.size(); i++) {
            Scenario p = x.get(i), q = y.get(i);
            if (p == null || q == null
                    || !Objects.equals(p.weeklyHours(), q.weeklyHours())
                    || !Objects.equals(p.duration(), q.duration())
                    || !Objects.equals(p.probability(), q.probability())) {
                return false;
            }
        }
        return true;
    }

    /** 路径阶段实质变更判定：比较阶段数与各阶段周期/名称 */
    private static boolean sameStages(List<PathStage> a, List<PathStage> b) {
        List<PathStage> x = a == null ? List.of() : a;
        List<PathStage> y = b == null ? List.of() : b;
        if (x.size() != y.size()) {
            return false;
        }
        for (int i = 0; i < x.size(); i++) {
            PathStage p = x.get(i), q = y.get(i);
            if (p == null || q == null || !Objects.equals(p.weeks(), q.weeks())
                    || !sameText(p.name(), q.name())) {
                return false;
            }
        }
        return true;
    }

    /** 历轮修订说明（steps 保留键；挂起恢复后为 List，运行期为 ArrayList） */
    @SuppressWarnings("unchecked")
    private static List<String> revisionNotes(Map<String, Object> steps) {
        Object v = steps.get(REVISION_KEY);
        if (v instanceof List<?> list) {
            return (List<String>) list;
        }
        List<String> created = new ArrayList<>();
        steps.put(REVISION_KEY, created);
        return created;
    }

    /** assess 步骤产物（运行期为 record、挂起恢复后为 Map，统一 convertValue） */
    private AssessOutput assessOf(Map<String, Object> steps) {
        Object o = steps.get("assess");
        return o == null ? null : om.convertValue(o, AssessOutput.class);
    }

    /** path 步骤产物（运行期为 record、挂起恢复后为 Map，统一 convertValue） */
    private PathOutput pathOf(Map<String, Object> steps) {
        Object o = steps.get("path");
        return o == null ? null : om.convertValue(o, PathOutput.class);
    }

    /** 三档情景事件（完整数据：名称/投入/周期/概率/里程碑/降档条件） */
    private void publishScenario(String analysisId, List<Scenario> scenarios) {
        if (scenarios == null || scenarios.isEmpty()) {
            return;
        }
        events.publish(analysisId, EventTypes.SCENARIO, Map.of("scenarios", scenarios));
    }

    /** LLM 自声明缺失槽位 → 追问项（最多 3 项，去空） */
    private List<QuestionItem> declaredMissing(Object output) {
        if (!(output instanceof AssessOutput assess) || assess.missingCritical() == null) {
            return List.of();
        }
        return assess.missingCritical().stream()
                .filter(m -> m != null && m.slot() != null && !m.slot().isBlank())
                .map(m -> new QuestionItem(
                        SlotVocabulary.normalize(m.slot()),
                        m.question() == null || m.question().isBlank()
                                ? "请补充：" + m.slot() : m.question(),
                        true))
                .limit(CollectorStateMachine.MAX_QUESTIONS_PER_ROUND)
                .toList();
    }

    // ==================== 提示词上下文（工作记忆压缩 + 确定性计算 + 校准/保鲜度注入） ====================

    private String buildContextJson(String analysisId, String type, boolean withCalibration) {
        Map<String, Object> ctx = collector.promptContext(analysisId);
        Map<String, Object> computed = computedNumbers(analysisId);
        if (!computed.isEmpty()) {
            ctx.put("computed", computed); // 代码算的确定性数字，LLM 直接引用不自算
        }
        if (withCalibration) {
            // 校准区块：分桶系数 + P25/P50/P75 分位 + 冷启动标记 + asOf 保鲜度（模板3/4 注入）
            ctx.put("calibration", calibration.promptView(type));
        }
        // 画像保鲜度（Task 9）：画像 updated_at 超 6 个月 → "此数据已 X 个月未校准"注入提示词
        String profileAsOf = profile.latestUpdatedAt();
        if (profileAsOf != null) {
            CalibrationService.Freshness f = CalibrationService.freshness(profileAsOf);
            if (f.stale()) {
                Map<String, Object> pf = new LinkedHashMap<>();
                pf.put("asOf", profileAsOf);
                pf.put("warning", f.warning());
                ctx.put("profileFreshness", pf);
            }
        }
        return toJson(ctx);
    }

    /** 校准区块 JSON（assess/redteam/revise 提示词的独立校准段） */
    private String calibrationJson(String type) {
        return toJson(calibration.promptView(type));
    }

    /** 确定性计算（CalcTool）：总投入 = weeklyHours × 周数（deadline 推算），失败静默跳过 */
    private Map<String, Object> computedNumbers(String analysisId) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            SlotRecord weekly = collector.slot(analysisId, SlotVocabulary.WEEKLY_HOURS);
            SlotRecord deadline = collector.slot(analysisId, SlotVocabulary.DEADLINE);
            if (weekly != null && weekly.filled() && weekly.value() != null) {
                Double h = firstNumber(weekly.value());
                if (h != null) {
                    out.put("weeklyHours", h);
                }
            }
            if (deadline != null && deadline.filled() && deadline.value() != null) {
                try {
                    long days = calcTool.daysBetween(calcTool.today(), deadline.value().strip());
                    out.put("weeksToDeadline", Math.round(days / 7.0 * 100.0) / 100.0);
                } catch (Exception ignored) {
                    // 非 ISO 日期（如"三个月后"）无法确定性推算，跳过
                }
            }
            if (out.containsKey("weeklyHours") && out.containsKey("weeksToDeadline")) {
                double total = calcTool.projectTotal((Double) out.get("weeklyHours"), (Double) out.get("weeksToDeadline"));
                out.put("totalHours", total);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 上一步 JSON 输出（只带上一步，不带全历史）；首个分析步骤的上一步为 collect/intent 产出 */
    private String prevStepJson(List<String> loopSteps, Map<String, Object> steps, String currentStep) {
        List<String> ordered = new ArrayList<>(List.of("intent", "collect"));
        ordered.addAll(loopSteps);
        int idx = ordered.indexOf(currentStep);
        for (int i = idx - 1; i >= 0; i--) {
            String prev = ordered.get(i);
            if (steps.containsKey(prev)) {
                return toJson(steps.get(prev));
            }
        }
        return "{}";
    }

    /** 外部数据引用块文本（steps["_external"] 运行期为 record、挂起恢复后为 Map，统一 convertValue） */
    private String externalPromptBlock(Map<String, Object> steps) {
        Object ext = steps.get("_external");
        if (ext == null) {
            return "（无）";
        }
        ExternalRef ref = ext instanceof ExternalRef e ? e : om.convertValue(ext, ExternalRef.class);
        String block = ref.promptBlock();
        return block == null || block.isBlank() ? "（无）" : block;
    }

    /** 外部数据引用（steps["_external"] 运行期为 record、挂起恢复后为 Map，统一 convertValue） */
    private ExternalRef externalRefOf(Map<String, Object> steps) {
        Object ext = steps.get("_external");
        if (ext == null) {
            return null;
        }
        return ext instanceof ExternalRef e ? e : om.convertValue(ext, ExternalRef.class);
    }

    // ==================== 报告拼装（模板3 完整版 + 红队/已知局限/第四档/校准保鲜度） ====================

    private long saveReport(String analysisId, String question, String type,
                            Map<String, Object> analysis, LinkedHashMap<String, Object> steps) {
        String md = assembleReport(analysisId, question, type, analysis, steps);
        String title = "可行性分析：" + truncate(question, 40);
        // 预测后验：最终版三档量化预期落库（一档一行；红队修订后的最终版本）
        Object assessObj = steps.get("assess");
        if (assessObj != null) {
            expectations.saveScenarios(analysisId,
                    om.convertValue(assessObj, AssessOutput.class).scenarios());
        }
        long reportId = analysisStore.saveReport(analysisId, title, md);
        // 报告 Markdown 双级落盘（目标目录失败降级 data/reports）+ file_path 回写；
        // 落盘失败不阻断报告生成主链路（报告已在库中）
        try {
            java.nio.file.Path file = reportWriter.write(question, str(analysis.get("type")),
                    str(analysis.get("depth")), md);
            analysisStore.updateReportFilePath(reportId, file.toString());
        } catch (Exception e) {
            log.warn("[{}] 报告落盘失败（报告仍在库中，可在线回看）: {}", analysisId, e.getMessage());
        }
        return reportId;
    }

    private String assembleReport(String analysisId, String question, String type,
                                  Map<String, Object> analysis, LinkedHashMap<String, Object> steps) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> exit = exitOfView(steps);
        sb.append("# 可行性分析：").append(question).append("\n\n");
        sb.append("> 类型：").append(str(analysis.get("type")))
                .append(" ｜ 深度：").append(str(analysis.get("depth")));
        if (exit != null) {
            sb.append(" ｜ 退出：").append(str(exit.get("reason")));
        }
        sb.append("\n\n");

        // ---- 结论（light / standard+deep 两种形态） ----
        Object lightObj = steps.get("assess-light");
        Object assessObj = steps.get("assess");
        if (lightObj != null) {
            appendLight(sb, lightObj);
        } else if (assessObj != null) {
            appendAssess(sb, assessObj);
        } else {
            sb.append("## 分析未完成\n\n（超时/预算/错误中断，未产出可行性结论；已采集信息见下。）\n\n");
        }

        // ---- 第四档"不建议推进"（红队严重质疑未收敛 + 校准系数<0.4 双重确认） ----
        if (noGoTriggered(type, steps)) {
            appendNoGo(sb, type, steps);
        }

        // ---- path / gap / risk ----
        if (steps.containsKey("path")) {
            appendPath(sb, steps.get("path"));
        }
        if (steps.containsKey("gap")) {
            appendGap(sb, steps.get("gap"));
        }
        if (steps.containsKey("risk")) {
            appendRisk(sb, steps.get("risk"));
        }

        // ---- 红队质疑 + 已知局限（deep 路径） ----
        Object redteamObj = steps.get("redteam");
        if (redteamObj != null) {
            appendRedteam(sb, redteamObj);
        }

        // ---- 已采集信息 + 假设 ----
        Map<String, Object> ctx = collector.promptContext(analysisId);
        Object slots = ctx.get("slots");
        if (slots instanceof Map<?, ?> slotMap && !slotMap.isEmpty()) {
            sb.append("## 已采集信息\n\n");
            slotMap.forEach((k, v) -> sb.append("- ").append(k).append("：").append(v).append("\n"));
            sb.append("\n");
        }
        Object assumptions = ctx.get("assumptions");
        if (assumptions instanceof List<?> list && !list.isEmpty()) {
            sb.append("## 假设与默认值\n\n");
            list.forEach(a -> sb.append("- 基于假设：").append(a).append("（未提供，按默认值处理）\n"));
            sb.append("\n");
        }

        // ---- 外部数据引用（Task 10：调研报告引用/联网搜索/完整调研提示） ----
        appendExternal(sb, steps);

        // ---- 校准与数据保鲜度（Task 8/9） ----
        appendCalibration(sb, type);

        // ---- 数据说明（确定性计算） ----
        Map<String, Object> computed = computedNumbers(analysisId);
        if (!computed.isEmpty()) {
            sb.append("## 数据说明\n\n");
            sb.append("- 总投入等数字由确定性计算工具求值（经计算校验）：");
            computed.forEach((k, v) -> sb.append(k).append("=").append(v).append(" "));
            sb.append("\n\n");
        }
        if (exit != null) {
            sb.append("## 退出原因\n\n- ").append(str(exit.get("reason"))).append("：")
                    .append(str(exit.get("detail"))).append("\n\n");
        }
        sb.append("---\n*由 decision-agent 生成，仅供参考；\"该不该做\"的决策权在用户。*\n");
        return sb.toString();
    }

    private void appendLight(StringBuilder sb, Object obj) {
        AssessLightOutput light = om.convertValue(obj, AssessLightOutput.class);
        sb.append("## 结论\n\n").append(str(light.conclusion())).append("\n\n");
        sb.append("## 建议\n\n").append(str(light.suggestion())).append("\n\n");
        if (light.keyRisks() != null && !light.keyRisks().isEmpty()) {
            sb.append("## 主要风险\n\n");
            light.keyRisks().forEach(r -> sb.append("- ").append(r).append("\n"));
            sb.append("\n");
        }
    }

    /** 可行性结论 + 数据来源（asOf）+ 完整三档情景（里程碑/验收/降档条件，总投入经计算校验） */
    private void appendAssess(StringBuilder sb, Object obj) {
        AssessOutput assess = om.convertValue(obj, AssessOutput.class);
        sb.append("## 可行性结论\n\n").append(str(assess.conclusion())).append("\n\n");

        if (assess.dataSources() != null && !assess.dataSources().isEmpty()) {
            sb.append("**数据来源**（含时点 asOf，过期数据已提示）\n\n");
            for (DataSource d : assess.dataSources()) {
                if (d == null) {
                    continue;
                }
                sb.append("- ").append(str(d.source()));
                if (d.asOf() != null && !d.asOf().isBlank()) {
                    sb.append("（asOf ").append(d.asOf()).append("）");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (assess.scenarios() != null && !assess.scenarios().isEmpty()) {
            sb.append("## 三档情景\n\n");
            for (int i = 0; i < assess.scenarios().size(); i++) {
                Scenario s = assess.scenarios().get(i);
                if (s == null) {
                    continue;
                }
                sb.append("### ").append(scenarioHeading(i, s)).append("\n");
                if (s.probability() != null) {
                    sb.append("- 达成概率：").append(s.probability()).append("\n");
                }
                if (s.weeklyHours() != null && s.duration() != null) {
                    // 数字经 CalcTool 代码求值（经计算校验），不由 LLM 心算
                    double total = calcTool.projectTotal(s.weeklyHours(), s.duration());
                    sb.append("- 投入：").append(s.weeklyHours()).append(" 小时/周 × ")
                            .append(s.duration()).append(" 周 = 总投入 ").append(total)
                            .append(" 小时（经计算校验）\n");
                } else if (s.weeklyHours() != null || s.duration() != null) {
                    sb.append("- 投入：").append(s.weeklyHours() == null ? "?" : s.weeklyHours())
                            .append(" 小时/周 × ").append(s.duration() == null ? "?" : s.duration())
                            .append(" 周\n");
                }
                if (s.milestones() != null) {
                    for (int m = 0; m < s.milestones().size(); m++) {
                        var ms = s.milestones().get(m);
                        if (ms == null) {
                            continue;
                        }
                        sb.append("- 里程碑：M").append(m + 1).append(" ")
                                .append(str(ms.name())).append("——").append(str(ms.criteria()));
                        if (ms.demoteCondition() != null && !ms.demoteCondition().isBlank()) {
                            sb.append("（降档：").append(ms.demoteCondition()).append("）");
                        }
                        sb.append("\n");
                    }
                }
                if (s.demoteTrigger() != null && !s.demoteTrigger().isBlank()) {
                    sb.append("- 降档条件：").append(s.demoteTrigger()).append("\n");
                }
                sb.append("\n");
            }
        }
        if (assess.notes() != null && !assess.notes().isBlank()) {
            sb.append("> ").append(assess.notes()).append("\n\n");
        }
    }

    /** 三档标题：从 name（如"A 乐观"）提取档位字母与标签；缺省按 A/B/C + 乐观/现实/保守 兜底 */
    private static String scenarioHeading(int idx, Scenario s) {
        String name = str(s.name()).strip();
        int i = Math.min(Math.max(idx, 0), 2);
        String fallbackGrade = String.valueOf((char) ('A' + i));
        String fallbackLabel = switch (i) {
            case 0 -> "乐观";
            case 1 -> "现实";
            default -> "保守";
        };
        if (name.isEmpty()) {
            return fallbackGrade + " 档 · " + fallbackLabel;
        }
        String grade = name.substring(0, 1).toUpperCase(Locale.ROOT);
        if (!grade.matches("[A-C]")) {
            grade = fallbackGrade;
        }
        String label = name.length() > 1 ? name.substring(1).strip() : fallbackLabel;
        if (label.isEmpty()) {
            label = fallbackLabel;
        }
        return grade + " 档 · " + label;
    }

    /**
     * 第四档"不建议推进"触发条件（多条件独立触发，任一命中即可）：
     * ① 红队未收敛 且 校准系数 &lt; 0.4（原双重确认）
     * ② 未解决的严重质疑 ≥ 3 条
     * ③ 现实档（B）概率 &lt; 0.25 且 校准系数 &lt; 0.6
     * ④ 累计 ≥ 2 轮修订均无实质变化（只在措辞上应付质疑）
     * 未触发 ≠ 结论可靠：只是证据强度未达"建议放弃"。
     */
    private boolean noGoTriggered(String type, Map<String, Object> steps) {
        return !noGoReasons(type, steps).isEmpty();
    }

    /** 触发"不建议推进"的具体理由（空 = 未触发）；多条可同时命中，报告据此逐条披露 */
    private List<String> noGoReasons(String type, Map<String, Object> steps) {
        RedTeamMeta meta = redteamMeta(steps);
        if (meta == null) {
            return List.of(); // 无红队路径（light/standard）→ 不触发
        }
        List<String> reasons = new ArrayList<>();
        CalibrationService.Calibration cal = calibration.getCalibration(type);
        List<RedTeamChallenge> majors = meta.challenges() == null ? List.of() : meta.challenges().stream()
                .filter(c -> c != null && LoopModels.isMajor(c.severity()))
                .toList();
        if (!meta.converged() && cal.coefficient() < CalibrationService.NO_GO_THRESHOLD) {
            reasons.add("红队未达收敛条件，且" + cal.type() + "桶历史完成率 " + cal.coefficient()
                    + " 低于 " + CalibrationService.NO_GO_THRESHOLD);
        }
        if (majors.size() >= NO_GO_UNRESOLVED_MAJOR_MIN) {
            reasons.add("未解决的严重质疑达 " + majors.size() + " 条（阈值 " + NO_GO_UNRESOLVED_MAJOR_MIN + "）");
        }
        if (intOf(meta.noSubstanceRounds()) >= 2) {
            reasons.add("累计 " + intOf(meta.noSubstanceRounds()) + " 轮修订未真正减少确定性冲突");
        }
        if (realisticProbabilityLow(steps) && cal.coefficient() < NO_GO_COEF_SOFT) {
            reasons.add("现实档达成概率低于 " + NO_GO_PROBABILITY_MIN + "，且" + cal.type() + "桶完成率 "
                    + cal.coefficient() + " 低于 " + NO_GO_COEF_SOFT);
        }
        return reasons;
    }

    /** 现实档（B，三档第二档）概率是否低于"不该推进"阈值 */
    private boolean realisticProbabilityLow(Map<String, Object> steps) {
        AssessOutput a = assessOf(steps);
        if (a == null || a.scenarios() == null || a.scenarios().size() < 2) {
            return false;
        }
        Scenario b = a.scenarios().get(1);
        return b != null && b.probability() != null && b.probability() < NO_GO_PROBABILITY_MIN;
    }

    private static int intOf(Integer v) {
        return v == null ? 0 : v;
    }

    /** redteam 步骤产物（同一运行内为 record；挂起恢复后为 LinkedHashMap，统一 convertValue） */
    private RedTeamMeta redteamMeta(Map<String, Object> steps) {
        Object rt = steps.get("redteam");
        if (rt == null) {
            return null;
        }
        if (rt instanceof RedTeamMeta meta) {
            return meta;
        }
        return om.convertValue(rt, RedTeamMeta.class);
    }

    private void appendNoGo(StringBuilder sb, String type, Map<String, Object> steps) {
        List<String> reasons = noGoReasons(type, steps);
        RedTeamMeta meta = redteamMeta(steps);
        List<RedTeamChallenge> challenges = meta != null && meta.challenges() != null
                ? meta.challenges() : List.of();
        sb.append("## 第四档 · 不建议以当前形式推进\n\n");
        sb.append("依据下列触发条件，此目标不建议以当前形式推进。\n\n");
        sb.append("**触发理由**\n\n");
        for (String r : reasons) {
            sb.append("- ").append(r).append("\n");
        }
        sb.append("\n**未解决的严重质疑**\n\n");
        for (RedTeamChallenge c : challenges) {
            if (c != null && LoopModels.isMajor(c.severity())) {
                sb.append("- ").append(str(c.point())).append("\n");
            }
        }
        sb.append("\n**什么条件下重新考虑**\n\n");
        for (RedTeamChallenge c : challenges) {
            if (c != null && LoopModels.isMajor(c.severity())
                    && c.mitigation() != null && !c.mitigation().isBlank()) {
                sb.append("- ").append(c.mitigation()).append("（满足后可重新发起分析）\n");
            }
        }
        sb.append("\n");
    }

    /** 红队质疑区：执行情况如实披露 + 全部质疑（含代码校验判定与历轮已被修订的项） */
    private void appendRedteam(StringBuilder sb, Object obj) {
        RedTeamMeta meta = om.convertValue(obj, RedTeamMeta.class);
        sb.append("## 红队质疑\n\n");
        sb.append("> 红队共 ").append(meta.rounds()).append(" 轮质疑：")
                .append(meta.converged() ? "未发现严重质疑" : "未达收敛条件").append("。")
                .append(meta.note() == null ? "" : meta.note()).append("\n\n");
        // 执行情况：机制是否被预算/轮次上限截断、是否出现空转与假修订，一并如实披露
        List<String> exec = new ArrayList<>();
        if (intOf(meta.emptyRounds()) > 0) {
            exec.add(intOf(meta.emptyRounds()) + " 轮未提出实质质疑（未经实质审查）");
        }
        if (intOf(meta.codeMajorCount()) > 0) {
            exec.add("代码确定性校验判定 " + intOf(meta.codeMajorCount()) + " 条严重问题");
        }
        if (intOf(meta.noSubstanceRounds()) > 0) {
            exec.add(intOf(meta.noSubstanceRounds()) + " 轮修订未见实质变化");
        }
        if (Boolean.TRUE.equals(meta.budgetStopped())) {
            exec.add("受预算/轮次上限截断，未跑满完整收敛闭环");
        }
        if (!exec.isEmpty()) {
            sb.append("> 执行情况：").append(String.join("；", exec)).append("。\n\n");
        }
        if (!meta.converged()) {
            sb.append("> 说明：未达收敛条件不等于结论已被推翻；同理，未触发「不建议推进」也不等于结论可靠。\n\n");
        }
        // 取跨轮累计质疑（含已被修订解决的 major），避免单轮视角丢失历史质疑
        List<RedTeamChallenge> challenges = meta.allChallenges() != null && !meta.allChallenges().isEmpty()
                ? meta.allChallenges()
                : (meta.challenges() == null ? List.of() : meta.challenges());
        if (challenges.isEmpty()) {
            sb.append("- 红队未提出实质质疑（未经实质审查，不作为通过依据）。\n\n");
            return;
        }
        for (RedTeamChallenge c : challenges) {
            if (c == null) {
                continue;
            }
            boolean major = LoopModels.isMajor(c.severity());
            sb.append("- **[").append(str(c.severity()))
                    .append(LoopModels.SOURCE_CODE.equals(c.source()) ? " · 代码校验" : "")
                    .append("]** ").append(str(c.point()));
            if (c.mitigation() != null && !c.mitigation().isBlank()) {
                sb.append(" → ").append(major ? "修订方向" : "应对").append("：").append(c.mitigation());
            }
            sb.append("\n");
        }
        sb.append("\n");

        // minor 质疑 → 已知局限区（不触发修订轮，不无限消耗预算）
        List<RedTeamChallenge> minors = challenges.stream()
                .filter(c -> c != null && !LoopModels.isMajor(c.severity()))
                .toList();
        if (!minors.isEmpty()) {
            sb.append("## 已知局限\n\n");
            sb.append("以下次要质疑不足以推翻结论，作为已知局限保留（知晓即可）：\n\n");
            for (RedTeamChallenge c : minors) {
                sb.append("- ").append(str(c.point()));
                if (c.mitigation() != null && !c.mitigation().isBlank()) {
                    sb.append("（应对：").append(c.mitigation()).append("）");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
    }

    /** 外部数据引用区（Task 10）：调研报告引用（零成本，search_count=0）/ 联网搜索结果 / 完整调研提示 */
    private void appendExternal(StringBuilder sb, Map<String, Object> steps) {
        ExternalRef ref = externalRefOf(steps);
        if (ref == null || ref.kind() == null) {
            return;
        }
        switch (ref.kind()) {
            case "referral" -> {
                sb.append("## 外部数据引用\n\n");
                sb.append("已引用调研 Agent 已有报告（零搜索成本，search_count=0）：\n\n");
                if (ref.sources() != null) {
                    for (ExternalRef.Source s : ref.sources()) {
                        sb.append("- 调研报告《").append(str(s.title())).append("》");
                        if (s.asOf() != null && !s.asOf().isBlank()) {
                            sb.append("（asOf ").append(s.asOf()).append("）");
                        }
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }
            case "search" -> {
                sb.append("## 外部数据引用\n\n");
                int n = ref.sources() == null ? 0 : ref.sources().size();
                sb.append("未命中已有调研报告，联网搜索结果 ").append(n).append(" 条：\n\n");
                if (ref.sources() != null) {
                    for (ExternalRef.Source s : ref.sources()) {
                        sb.append("- ").append(str(s.title()));
                        if (s.asOf() != null && !s.asOf().isBlank()) {
                            sb.append("（asOf ").append(s.asOf()).append("）");
                        }
                        if (s.url() != null && !s.url().isBlank()) {
                            sb.append(" ").append(s.url());
                        }
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }
            case "none" -> sb.append("## 调研提示\n\n").append(str(ref.note())).append("\n\n");
            default -> { }
        }
    }

    /** 校准与数据保鲜度区：分桶系数 + 分位 + 冷启动/过期提示（报告可见，供用户判断数据可信度） */
    private void appendCalibration(StringBuilder sb, String type) {
        try {
            CalibrationService.Calibration cal = calibration.getCalibration(type);
            sb.append("## 校准与数据保鲜度\n\n");
            sb.append("- ").append(cal.type()).append("桶校准系数：").append(cal.coefficient())
                    .append("（").append(cal.sampleSize()).append(" 案，asOf ")
                    .append(cal.asOf() == null ? "无" : cal.asOf()).append("）——B 现实档按此系数修正\n");
            if (cal.percentiles() != null) {
                sb.append("- 个人库历史分位：P25=").append(cal.percentiles().p25())
                        .append(" / P50=").append(cal.percentiles().p50())
                        .append(" / P75=").append(cal.percentiles().p75())
                        .append("（三档分档依据）\n");
            }
            if (cal.coldStart()) {
                sb.append("- 冷启动：样本不足，使用全局默认系数 ")
                        .append(CalibrationService.DEFAULT_COEFFICIENT)
                        .append("，建议录入行为数据校准\n");
            }
            if (cal.stale()) {
                sb.append("- ⚠ ").append(String.format("此数据已 %d 个月未校准，可能不代表当前状态",
                        cal.staleMonths())).append("\n");
            }
            String profileAsOf = profile.latestUpdatedAt();
            if (profileAsOf != null) {
                CalibrationService.Freshness pf = CalibrationService.freshness(profileAsOf);
                if (pf.stale()) {
                    sb.append("- ⚠ 画像数据（asOf ").append(profileAsOf).append("）：")
                            .append(pf.warning()).append("\n");
                }
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("校准区拼装失败（跳过）: {}", e.getMessage());
        }
    }

    private void appendPath(StringBuilder sb, Object obj) {
        PathOutput path = om.convertValue(obj, PathOutput.class);
        sb.append("## 路径设计\n\n");
        if (path.stages() != null) {
            for (int i = 0; i < path.stages().size(); i++) {
                var st = path.stages().get(i);
                sb.append(i + 1).append(". **").append(str(st.name())).append("**（")
                        .append(st.weeks() == null ? "?" : st.weeks()).append(" 周）：")
                        .append(str(st.goal())).append("\n");
                if (st.validation() != null && !st.validation().isBlank()) {
                    sb.append("   - 验收：").append(st.validation()).append("\n");
                }
            }
        }
        if (path.totalWeeks() != null) {
            sb.append("\n合计约 ").append(path.totalWeeks()).append(" 周。");
        }
        sb.append("\n\n");
    }

    private void appendGap(StringBuilder sb, Object obj) {
        GapOutput gap = om.convertValue(obj, GapOutput.class);
        sb.append("## 差距清单\n\n");
        if (gap.gaps() != null) {
            for (var g : gap.gaps()) {
                sb.append("- **").append(str(g.item())).append("**（").append(str(g.severity()))
                        .append("）：").append(str(g.current())).append(" → ").append(str(g.target())).append("\n");
                if (g.killCriteria() != null && !g.killCriteria().isBlank()) {
                    sb.append("  - kill criteria：").append(g.killCriteria()).append("\n");
                }
            }
        }
        sb.append("\n");
    }

    private void appendRisk(StringBuilder sb, Object obj) {
        RiskOutput risk = om.convertValue(obj, RiskOutput.class);
        sb.append("## 风险与对策\n\n");
        if (risk.risks() != null) {
            for (var r : risk.risks()) {
                sb.append("- ").append(str(r.risk())).append("（概率 ").append(str(r.probability()))
                        .append(" × 影响 ").append(str(r.impact())).append("）→ ")
                        .append(str(r.mitigation())).append("\n");
            }
        }
        sb.append("\n");
    }

    // ==================== 工具 ====================

    private ControlState control(String analysisId) {
        ControlState c = controls.get(analysisId);
        return c == null ? ControlState.RUNNING : c;
    }

    private static boolean isTerminal(String status) {
        return AnalysisStateStore.STATUS_DONE.equals(status)
                || AnalysisStateStore.STATUS_ERROR.equals(status)
                || AnalysisStateStore.STATUS_CANCELLED.equals(status);
    }

    private String currentStepOf(LinkedHashMap<String, Object> steps, List<String> loopSteps,
                                 AnalysisStateStore.AnalysisState state) {
        String last = null;
        List<String> ordered = new ArrayList<>(List.of("intent", "collect"));
        ordered.addAll(loopSteps);
        for (String s : ordered) {
            if (steps.containsKey(s)) {
                last = s;
            }
        }
        return last != null ? last : (state == null ? null : state.currentStep());
    }

    private static long metaActiveMs(Map<String, Object> steps) {
        Object meta = steps.get(META_KEY);
        if (meta instanceof Map<?, ?> m && m.get("activeMs") instanceof Number n) {
            return n.longValue();
        }
        return 0;
    }

    private static Map<String, Object> metaOf(long activeMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeMs", activeMs);
        return m;
    }

    private static Map<String, Object> exitOf(String reason, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reason", reason);
        m.put("detail", detail);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> exitOfView(Map<String, Object> steps) {
        Object exit = steps.get(EXIT_KEY);
        return exit instanceof Map ? (Map<String, Object>) exit : null;
    }

    private static long elapsed(long runStart) {
        return System.currentTimeMillis() - runStart;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String joinNotes(String oldNotes, String revisionNote) {
        String a = oldNotes == null ? "" : oldNotes.strip();
        String b = revisionNote == null ? "" : revisionNote.strip();
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return a + "；红队修订：" + b;
    }

    private static Double firstNumber(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(text);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String toJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
