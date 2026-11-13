package com.research.model;

import java.util.List;

public class ReviewModels {

    public record ReviewIssue(String type, String description, String section) {}

    public record ReviewResult(List<ReviewIssue> issues, boolean needRevise) {

        public boolean hasIssues() {
            return issues != null && !issues.isEmpty();
        }

        public List<ReviewIssue> typeA() {
            return issues == null ? List.of() : issues.stream()
                    .filter(i -> "A".equalsIgnoreCase(i.type())).toList();
        }

        public List<ReviewIssue> typeB() {
            return issues == null ? List.of() : issues.stream()
                    .filter(i -> "B".equalsIgnoreCase(i.type())).toList();
        }
    }

    /** 方案B：独立裁判的终止核验结果，用于否决策略师的"信息足够"判断 */
    public record VerificationResult(boolean sufficient, String reason, java.util.List<String> criticalGaps) {}
}