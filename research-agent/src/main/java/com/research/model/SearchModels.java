package com.research.model;

import java.util.List;

public class SearchModels {

    public record SearchItem(
            String title,
            String url,
            String body,
            String siteName,
            String publishTime,
            double rankScore,
            String platform,
            String query
    ) {}

    public record SearchSpec(
            String query,
            String reason,
            String platform,
            String timeRange
    ) {}

    public record StrategyData(
            String topicAnalysis,
            String findingsSummary,
            List<String> informationGaps,
            boolean enough,
            String nextStep,
            List<SearchSpec> searches
    ) {
        public boolean wantReport() {
            return enough || "report".equalsIgnoreCase(nextStep == null ? "" : nextStep);
        }
    }
}
