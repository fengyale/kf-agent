package com.research.service;

import com.research.config.AgentProperties;
import com.research.model.ReviewModels.ReviewIssue;
import com.research.model.ReviewModels.ReviewResult;
import com.research.model.ReviewModels.VerificationResult;
import com.research.model.SearchModels.SearchItem;
import com.research.model.SearchModels.SearchSpec;
import com.research.model.SearchModels.StrategyData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class ResearchLoop {

    private static final Logger log = LoggerFactory.getLogger(ResearchLoop.class);

    private final AgentProperties props;
    private final TaskStore store;
    private final EventPublisher events;
    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final ResultProcessor processor;
    private final ReviewService reviewService;
    private final ReportWriter reportWriter;
    private final SearchClient searchClient;

    private final ExecutorService loopExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "research-loop");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService searchPool = Executors.newFixedThreadPool(5, r -> {
        Thread t = new Thread(r, "search-worker");
        t.setDaemon(true);
        return t;
    });

    // 任务控制：paused=轮间挂起等待恢复，cancelled=用户终止。key 缺失 = 正常运行
    private final ConcurrentHashMap<String, Control> controls = new ConcurrentHashMap<>();

    enum ControlState { RUNNING, PAUSED, CANCELLED }

    static class Control {
        volatile ControlState state = ControlState.RUNNING;
    }

    private Control control(String taskId) {
        return controls.computeIfAbsent(taskId, k -> new Control());
    }

    public void pause(String taskId) {
        control(taskId).state = ControlState.PAUSED;
        events.publish(taskId, "task:paused", Map.of("message", "用户暂停，将在本轮搜索结束后挂起"));
    }

    public void resume(String taskId) {
        Control c = controls.get(taskId);
        if (c != null) c.state = ControlState.RUNNING;
        events.publish(taskId, "task:resumed", Map.of("message", "已恢复运行"));
    }

    public void cancel(String taskId) {
        control(taskId).state = ControlState.CANCELLED;
        events.publish(taskId, "task:cancel-requested", Map.of("message", "用户请求终止，将在当前步骤后停止"));
    }

    /** 轮间检查点：返回 false 表示已取消；暂停时阻塞等待（最长 10 分钟防死锁） */
    private boolean checkpoint(String taskId, int round) throws InterruptedException {
        Control c = control(taskId);
        long waited = 0;
        while (c.state == ControlState.PAUSED) {
            if (waited >= 600_000) return false; // 暂停超时视为放弃，避免线程永久占用
            Thread.sleep(500);
            waited += 500;
        }
        return c.state != ControlState.CANCELLED;
    }

    public ResearchLoop(AgentProperties props, TaskStore store, EventPublisher events,
                        LlmClient llm, PromptBuilder prompts, ResultProcessor processor,
                        ReviewService reviewService, ReportWriter reportWriter, SearchClient searchClient) {
        this.props = props;
        this.store = store;
        this.events = events;
        this.llm = llm;
        this.prompts = prompts;
        this.processor = processor;
        this.reviewService = reviewService;
        this.reportWriter = reportWriter;
        this.searchClient = searchClient;
    }

    public String submit(String topic) {
        String taskId = UUID.randomUUID().toString().substring(0, 12);
        store.create(taskId, topic);
        events.publish(taskId, "task:created", Map.of(
                "taskId", taskId,
                "topic", topic,
                "budget", Map.of(
                        "note", "护栏为兜底上限，轮次与搜索次数由模型自主决定",
                        "maxRounds", props.getBudget().getMaxRounds(),
                        "maxSearchesPerRound", props.getBudget().getMaxSearchesPerRound(),
                        "maxTotalSearches", props.getBudget().getMaxTotalSearches(),
                        "minNewFindingsPerRound", props.getBudget().getMinNewFindingsPerRound(),
                        "timeoutMinutes", props.getBudget().getTimeoutMinutes())));
        loopExecutor.submit(() -> run(taskId, topic));
        return taskId;
    }

    public List<Map<String, Object>> listTasks() {
        return store.listAll();
    }

    void run(String taskId, String topic) {
        long start = System.currentTimeMillis();
        List<SearchItem> pool = new ArrayList<>();
        Set<String> searched = new LinkedHashSet<>();
        int totalSearches = 0;
        int round = 0;
        // 方案B：独立裁判驳回计数器，连续驳回达到上限则强制终止
        int verifierRejections = 0;
        // 退出原因：model-decision（模型自主判断信息充分）为常态，guard:*（护栏止损）为异常兜底
        String exitReason = "unknown";
        String exitDetail = "";
        try {
            events.publish(taskId, "node:running",
                    Map.of("nodeId", "node-topic", "name", "主题解析", "round", 0, "kind", "规划"));
            events.publish(taskId, "node:detail", Map.of(
                    "nodeId", "node-topic", "input", topic, "output", "主题已接收并解析", "costMs", 0));

            AgentProperties.Budget budget = props.getBudget();
            long deadline = start + budget.getTimeoutMinutes() * 60_000L;

            while (true) {
                round++;

                if (!checkpoint(taskId, round)) {
                    exitReason = "user:cancelled";
                    exitDetail = "用户手动终止任务";
                    log.info("[{}] 用户取消，收工", taskId);
                    events.publish(taskId, "round:end", Map.of(
                            "round", round, "newFindings", 0, "totalSearches", totalSearches));
                    break;
                }

                events.publish(taskId, "round:start", Map.of("round", round));

                if (System.currentTimeMillis() > deadline) {
                    log.info("[{}] 超时护栏触发，进入成稿", taskId);
                    exitReason = "guard:timeout";
                    exitDetail = "任务超时，强制收工";
                    events.publish(taskId, "round:end", Map.of(
                            "round", round, "newFindings", 0, "totalSearches", totalSearches));
                    break;
                }

                String strategyNodeId = "node-r" + round + "-strategy";
                events.publish(taskId, "node:running", Map.of(
                        "nodeId", strategyNodeId, "name", "第" + round + "轮策略", "round", round, "kind", "策略与筛选"));

                String strategyPrompt = round == 1
                        ? prompts.buildFirstStrategy(topic)
                        : prompts.buildNextStrategy(topic, pool, List.copyOf(searched));
                StrategyData strategy;
                try {
                    long t0 = System.currentTimeMillis();
                    strategy = llm.generateJson(prompts.strategySystem(), strategyPrompt, StrategyData.class);
                    Map<String, Object> detail = new HashMap<>();
                    detail.put("nodeId", strategyNodeId);
                    detail.put("input", round == 1 ? topic : "已有 " + pool.size() + " 条资料的缺口分析");
                    detail.put("output", summarizeStrategy(strategy));
                    detail.put("costMs", System.currentTimeMillis() - t0);
                    detail.put("model", llm.modelName());
                    detail.put("systemPrompt", prompts.strategySystem());
                    detail.put("userPrompt", strategyPrompt);
                    events.publish(taskId, "node:detail", detail);
                } catch (Exception e) {
                    exitReason = "error:strategy";
                    exitDetail = String.valueOf(e.getMessage());
                    events.publish(taskId, "node:error", Map.of(
                            "nodeId", strategyNodeId, "message", String.valueOf(e.getMessage())));
                    events.publish(taskId, "round:end", Map.of(
                            "round", round, "newFindings", 0, "totalSearches", totalSearches));
                    break;
                }

                if (strategy.wantReport()) {
                    // 方案B：独立裁判核验，否决策略师的"信息已足够"判断，防止王婆卖瓜
                    log.info("[{}] 第{}轮策略师判断信息足够，启动独立裁判核验", taskId, round);
                    VerificationResult verdict;
                    try {
                        verdict = reviewService.verifyEnough(topic, pool);
                    } catch (Exception e) {
                        log.warn("[{}] 裁判核验调用失败，降级采信策略师判断: {}", taskId, e.getMessage());
                        // 核验失败时降级：采信策略师的判断，避免因裁判不可用卡死
                        exitReason = "model-decision:enough+verifier-failed";
                        exitDetail = strategy.findingsSummary() == null ? "模型判断信息已充分（裁判核验失败，降级采信）" : strategy.findingsSummary();
                        events.publish(taskId, "round:end", Map.of(
                                "round", round, "newFindings", 0, "totalSearches", totalSearches));
                        break;
                    }
                    if (verdict.sufficient()) {
                        log.info("[{}] 裁判通过：信息确实充分，进入成稿", taskId);
                        exitReason = "model-decision:enough+verified";
                        exitDetail = strategy.findingsSummary() == null ? "模型判断信息已充分，独立裁判核验通过" : strategy.findingsSummary();
                        events.publish(taskId, "round:end", Map.of(
                                "round", round, "newFindings", 0, "totalSearches", totalSearches));
                        break;
                    } else {
                        verifierRejections++;
                        log.info("[{}] 裁判驳回（第{}次）：{}，缺口={}，继续搜索", taskId, verifierRejections,
                                verdict.reason(), verdict.criticalGaps());
                        events.publish(taskId, "verify:rejected", Map.of(
                                "round", round, "rejectionCount", verifierRejections,
                                "reason", verdict.reason(), "criticalGaps", verdict.criticalGaps()));
                        if (verifierRejections >= 3) {
                            log.info("[{}] 裁判连续驳回{}次，强制终止（护栏⑤）", taskId, verifierRejections);
                            exitReason = "guard:verifier-rejected";
                            exitDetail = "独立裁判已连续驳回" + verifierRejections + "次，强制收工";
                            events.publish(taskId, "round:end", Map.of(
                                    "round", round, "newFindings", 0, "totalSearches", totalSearches));
                            break;
                        }
                        // 裁判驳回但未达上限，继续循环（下一轮策略师会重新评估或判 enough）
                    }
                }

                List<SearchSpec> specs = pickSearches(strategy, searched, budget, totalSearches);
                if (specs.isEmpty()) {
                    // 无新搜索可执行：总预算耗尽属护栏，模型给出空计划属自主决策
                    if (totalSearches >= budget.getMaxTotalSearches()) {
                        exitReason = "guard:total-searches";
                        exitDetail = "搜索预算封顶（" + totalSearches + " 次）";
                    } else {
                        exitReason = "model-decision:empty-plan";
                        exitDetail = "模型未给出新的有效搜索，视为信息已足够";
                    }
                    events.publish(taskId, "round:end", Map.of(
                            "round", round, "newFindings", 0, "totalSearches", totalSearches));
                    break;
                }
                specs.forEach(s -> searched.add(normalize(s.query())));

                int newFindings = executeRound(taskId, round, specs, pool);
                totalSearches += specs.size();
                store.updateProgress(taskId, round, totalSearches);
                events.publish(taskId, "round:end", Map.of(
                        "round", round, "newFindings", newFindings, "totalSearches", totalSearches));

                if (round >= budget.getMaxRounds()) {
                    log.info("[{}] 护栏②轮次上限，收工", taskId);
                    exitReason = "guard:max-rounds";
                    exitDetail = "达到轮次兜底上限（" + budget.getMaxRounds() + " 轮）";
                    break;
                }
                if (totalSearches >= budget.getMaxTotalSearches()) {
                    log.info("[{}] 护栏③总搜索预算封顶，收工", taskId);
                    exitReason = "guard:total-searches";
                    exitDetail = "搜索预算封顶（" + budget.getMaxTotalSearches() + " 次）";
                    break;
                }
                if (newFindings < budget.getMinNewFindingsPerRound()) {
                    log.info("[{}] 护栏④边际收益不足（新增{}条），收工", taskId, newFindings);
                    exitReason = "guard:marginal-gain";
                    exitDetail = "本轮净增 " + newFindings + " 条，边际收益不足";
                    break;
                }
            }

            // 退出原因上报：区分"模型自主判断"与"护栏止损"，verifierRejections 记录方案B裁判驳回次数
            events.publish(taskId, "loop:exit", Map.of(
                    "reason", exitReason, "detail", exitDetail,
                    "rounds", round, "totalSearches", totalSearches, "poolSize", pool.size(),
                    "verifierRejections", verifierRejections));

            if ("user:cancelled".equals(exitReason)) {
                store.finish(taskId, "cancelled", null, round, totalSearches,
                        System.currentTimeMillis() - start);
                events.publish(taskId, "task:done", Map.of(
                        "exitReason", exitReason, "exitDetail", exitDetail,
                        "reportPath", "", "totalCostMs", System.currentTimeMillis() - start,
                        "searchCount", totalSearches, "rounds", round));
                return;
            }

            if (pool.isEmpty()) {
                throw new IllegalStateException("未能获取到任何有效搜索资料（检查 SEARCH_API_KEY 与网络），无法生成报告");
            }

            String finalReport = drafting(taskId, topic, pool, start);
            round = Math.max(round, 1);

            java.nio.file.Path path = reportWriter.write(taskId, topic, finalReport, pool,
                    round, totalSearches, System.currentTimeMillis() - start);
            store.finish(taskId, "done", path.toString(), round, totalSearches,
                    System.currentTimeMillis() - start);
            events.publish(taskId, "task:done", Map.of(
                    "exitReason", exitReason,
                    "exitDetail", exitDetail,
                    "reportPath", path.toString(),
                    "totalCostMs", System.currentTimeMillis() - start,
                    "searchCount", totalSearches,
                    "rounds", round));
            log.info("[{}] 调研完成: 轮数={} 搜索={}次 资料={}条 耗时={}ms 退出原因={}",
                    taskId, round, totalSearches, pool.size(), System.currentTimeMillis() - start, exitReason);
        } catch (Exception e) {
            log.warn("[{}] 任务失败: {}", taskId, e.getMessage(), e);
            store.finish(taskId, "failed", null, round, totalSearches, System.currentTimeMillis() - start);
            events.publish(taskId, "task:failed", Map.of(
                    "exitReason", exitReason.equals("unknown") ? "error:task" : exitReason,
                    "message", String.valueOf(e.getMessage())));
        } finally {
            events.complete(taskId);
        }
    }

    private int executeRound(String taskId, int round, List<SearchSpec> specs, List<SearchItem> pool) {
        List<CompletableFuture<List<SearchItem>>> futures = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            SearchSpec spec = specs.get(i);
            String nodeId = "node-r" + round + "-search" + (i + 1);
            events.publish(taskId, "search:start", Map.of(
                    "nodeId", nodeId, "query", spec.query(),
                    "platform", nullToEmpty(spec.platform()), "reason", nullToEmpty(spec.reason())));
            long t0 = System.currentTimeMillis();
            CompletableFuture<List<SearchItem>> future = CompletableFuture
                    .supplyAsync(() -> doSearch(taskId, nodeId, spec), searchPool)
                    .thenApply(raw -> {
                        List<SearchItem> kept = processor.filter(raw);
                        events.publish(taskId, "search:done", Map.of(
                                "nodeId", nodeId,
                                "resultCount", raw.size(),
                                "keptCount", kept.size(),
                                "costMs", System.currentTimeMillis() - t0,
                                "topTitles", topTitles(kept)));
                        return kept;
                    });
            futures.add(future);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<SearchItem> all = new ArrayList<>();
        futures.forEach(f -> all.addAll(f.join()));
        int added = processor.mergeInto(pool, all);

        events.publish(taskId, "node:detail", Map.of(
                "nodeId", "node-r" + round + "-eval",
                "input", "本轮 " + specs.size() + " 条搜索，返回 " + all.size() + " 条结果",
                "output", "过滤合并后净增 " + added + " 条（结果池累计 " + pool.size() + " 条）",
                "costMs", 0));
        return added;
    }

    private String drafting(String taskId, String topic, List<SearchItem> pool, long start) throws Exception {
        events.publish(taskId, "node:running", Map.of(
                "nodeId", "node-report", "name", "报告生成", "round", 0, "kind", "成稿"));
        long t0 = System.currentTimeMillis();
        CompletableFuture<String> draftFuture = llm.streamText(
                prompts.reportSystem(), prompts.buildReport(topic, pool),
                text -> events.publish(taskId, "thinking", Map.of("text", text)),
                text -> events.publish(taskId, "token", Map.of("text", text)));
        String draft = draftFuture.get(10, TimeUnit.MINUTES);
        events.publish(taskId, "node:detail", Map.of(
                "nodeId", "node-report",
                "input", "资料池 " + pool.size() + " 条",
                "output", "报告初稿 " + draft.length() + " 字",
                "costMs", System.currentTimeMillis() - t0,
                "model", llm.modelName()));

        events.publish(taskId, "node:running", Map.of(
                "nodeId", "node-review", "name", "审稿修订", "round", 0, "kind", "质量审稿"));
        long t1 = System.currentTimeMillis();
        ReviewResult review;
        try {
            review = reviewService.review(topic, draft, pool);
        } catch (Exception e) {
            events.publish(taskId, "node:error", Map.of(
                    "nodeId", "node-review", "message", "审稿调用失败: " + e.getMessage()));
            events.publish(taskId, "node:detail", Map.of(
                    "nodeId", "node-review", "input", "初稿全文", "output", "审稿失败，直接采用初稿",
                    "costMs", System.currentTimeMillis() - t1, "model", llm.modelName()));
            return draft;
        }

        List<ReviewIssue> issues = review.issues() == null ? List.of() : review.issues();
        events.publish(taskId, "review:issue", Map.of("issues", issues));
        for (ReviewIssue issue : issues) {
            store.saveReviewIssue(taskId, issue.type(), issue.description(), issue.section());
        }
        events.publish(taskId, "node:detail", Map.of(
                "nodeId", "node-review",
                "input", "初稿全文 + 资料池 " + pool.size() + " 条",
                "output", "发现 " + issues.size() + " 个问题（A类写作缺陷 " + review.typeA().size()
                        + " / B类原料缺口 " + review.typeB().size() + "）",
                "costMs", System.currentTimeMillis() - t1,
                "model", llm.modelName()));

        if (!review.needRevise() || issues.isEmpty()) {
            log.info("[{}] 审稿无问题，初稿即为终稿", taskId);
            return draft;
        }

        List<ReviewIssue> bIssues = review.typeB();
        if (!bIssues.isEmpty()) {
            supplement(taskId, bIssues, pool, start);
        }

        events.publish(taskId, "revise:start", Map.of("round", 1));
        CompletableFuture<String> reviseFuture = reviewService.revise(topic, pool, draft, issues,
                text -> events.publish(taskId, "thinking", Map.of("text", text)),
                text -> events.publish(taskId, "token", Map.of("text", text)));
        String revised = reviseFuture.get(10, TimeUnit.MINUTES);
        events.publish(taskId, "revise:done", Map.of("round", 1));
        return revised;
    }

    private void supplement(String taskId, List<ReviewIssue> bIssues, List<SearchItem> pool, long start) {
        try {
            Set<String> searched = new LinkedHashSet<>();
            List<SearchSpec> specs = reviewService.supplement(bIssues, List.of());
            specs = specs.stream()
                    .filter(s -> s.query() != null && !s.query().isBlank())
                    .limit(2)
                    .toList();
            for (int i = 0; i < specs.size(); i++) {
                SearchSpec spec = specs.get(i);
                String nodeId = "node-sup-" + (i + 1);
                events.publish(taskId, "search:start", Map.of(
                        "nodeId", nodeId, "query", spec.query(),
                        "platform", nullToEmpty(spec.platform()), "reason", "B类缺口补搜: " + nullToEmpty(spec.reason())));
                long t0 = System.currentTimeMillis();
                List<SearchItem> raw = doSearch(taskId, nodeId, spec);
                List<SearchItem> kept = processor.filter(raw);
                processor.mergeInto(pool, kept);
                events.publish(taskId, "search:done", Map.of(
                        "nodeId", nodeId, "resultCount", raw.size(), "keptCount", kept.size(),
                        "costMs", System.currentTimeMillis() - t0, "topTitles", topTitles(kept)));
            }
            log.info("[{}] B类缺口补搜完成，结果池增至 {} 条", taskId, pool.size());
        } catch (Exception e) {
            log.warn("[{}] B类补搜失败，跳过: {}", taskId, e.getMessage());
        }
    }

    private List<SearchItem> doSearch(String taskId, String nodeId, SearchSpec spec) {
        try {
            int count = props.getBudget().getCountPerSearch();
            String platform = spec.platform() == null ? "web" : spec.platform();
            List<String> sites = SearchClient.PLATFORM_SITES.getOrDefault(platform, List.of());
            return sites.isEmpty()
                    ? searchClient.search(spec.query(), count, spec.timeRange())
                    : searchClient.searchScoped(spec.query(), sites, count, spec.timeRange());
        } catch (Exception e) {
            events.publish(taskId, "node:error", Map.of(
                    "nodeId", nodeId, "message", "搜索失败: " + e.getMessage()));
            return List.of();
        }
    }

    private List<SearchSpec> pickSearches(StrategyData strategy, Set<String> searched,
                                          AgentProperties.Budget budget, int totalSearches) {
        if (strategy.searches() == null) return List.of();
        int allowed = Math.min(budget.getMaxSearchesPerRound(), budget.getMaxTotalSearches() - totalSearches);
        if (allowed <= 0) return List.of();
        return strategy.searches().stream()
                .filter(s -> s != null && s.query() != null && !s.query().isBlank())
                .filter(s -> !searched.contains(normalize(s.query())))
                .limit(allowed)
                .toList();
    }

    private String summarizeStrategy(StrategyData strategy) {
        int count = strategy.searches() == null ? 0 : strategy.searches().size();
        String gaps = strategy.informationGaps() == null ? "" : String.join("；", strategy.informationGaps());
        return "搜索计划 " + count + " 条"
                + (gaps.isEmpty() ? "" : "，缺口: " + gaps)
                + (strategy.wantReport() ? "，判断信息足够 → 直接成稿" : "");
    }

    private List<String> topTitles(List<SearchItem> items) {
        return items.stream().limit(3).map(SearchItem::title).toList();
    }

    private String normalize(String query) {
        return query == null ? "" : query.strip().toLowerCase().replaceAll("\\s+", "");
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}