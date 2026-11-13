package com.decision.model;

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
}
