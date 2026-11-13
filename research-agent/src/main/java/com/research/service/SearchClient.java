package com.research.service;

import com.research.model.SearchModels.SearchItem;

import java.util.List;
import java.util.Map;

public interface SearchClient {

    Map<String, List<String>> PLATFORM_SITES = Map.of(
            "web", List.of(),
            "zhihu", List.of("zhihu.com"),
            "bilibili", List.of("bilibili.com"),
            "xiaohongshu", List.of("xiaohongshu.com"));

    List<SearchItem> search(String query, int count, String timeRange);

    List<SearchItem> searchScoped(String query, List<String> sites, int count, String timeRange);
}
