package com.research.service;

import com.research.model.ReviewModels.ReviewIssue;
import com.research.model.ReviewModels.ReviewResult;
import com.research.model.ReviewModels.VerificationResult;
import com.research.model.SearchModels.SearchItem;
import com.research.model.SearchModels.SearchSpec;
import com.research.model.SearchModels.StrategyData;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Service
public class ReviewService {

    private final LlmClient llm;
    private final PromptBuilder prompts;

    public ReviewService(LlmClient llm, PromptBuilder prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    public ReviewResult review(String topic, String draft, List<SearchItem> pool) {
        return llm.generateJson(prompts.reviewSystem(), prompts.buildReview(topic, draft, pool), ReviewResult.class);
    }

    public CompletableFuture<String> revise(String topic, List<SearchItem> pool, String draft,
                                            List<ReviewIssue> issues,
                                            Consumer<String> onThinking, Consumer<String> onToken) {
        return llm.streamText(prompts.reportSystem(), prompts.buildReportRevise(topic, pool, draft, issues),
                onThinking, onToken);
    }

    public List<SearchSpec> supplement(List<ReviewIssue> bIssues, List<String> searchedQueries) {
        StrategyData data = llm.generateJson(prompts.strategySystem(),
                prompts.buildSupplement(bIssues, searchedQueries), StrategyData.class);
        if (data.searches() == null) return List.of();
        return data.searches().stream()
                .filter(s -> s != null && s.query() != null && !s.query().isBlank())
                .toList();
    }

    /** 方案B：独立裁判核验，否决策略师的"信息已足够"判断，防止王婆卖瓜 */
    public VerificationResult verifyEnough(String topic, List<SearchItem> pool) {
        return llm.generateJson(prompts.verifierSystem(), prompts.buildVerifyEnough(topic, pool),
                VerificationResult.class);
    }
}