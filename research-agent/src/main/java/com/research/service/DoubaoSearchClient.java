package com.research.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.research.config.AgentProperties;
import com.research.model.SearchModels.SearchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class DoubaoSearchClient implements SearchClient {

    private static final Logger log = LoggerFactory.getLogger(DoubaoSearchClient.class);
    private static final String ENDPOINT = "https://open.feedcoopapi.com/search_api/web_search";
    private static final Set<String> TIME_RANGES = Set.of("OneDay", "OneWeek", "OneMonth", "OneYear");

    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper om = new ObjectMapper();

    public DoubaoSearchClient(AgentProperties props) {
        this.apiKey = props.getSearch().getDoubao().getApiKey();
        this.timeout = props.getSearch().getDoubao().getTimeout();
    }

    @Override
    public List<SearchItem> search(String query, int count, String timeRange) {
        return doSearch(query, null, count, timeRange);
    }

    @Override
    public List<SearchItem> searchScoped(String query, List<String> sites, int count, String timeRange) {
        return doSearch(query, sites, count, timeRange);
    }

    private List<SearchItem> doSearch(String query, List<String> sites, int count, String timeRange) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置搜索 API Key：请设置环境变量 SEARCH_API_KEY（豆包搜索）后重启服务");
        }
        try {
            ObjectNode body = om.createObjectNode();
            body.put("Query", query);
            body.put("SearchType", "web");
            body.put("Count", count);
            ObjectNode filter = body.putObject("Filter");
            filter.put("NeedContent", true);
            if (sites != null && !sites.isEmpty()) {
                filter.put("Sites", String.join("|", sites));
            }
            filter.put("AuthInfoLevel", 0);
            body.put("TimeRange", normalizeTime(timeRange));
            body.put("ContentFormats", "markdown");
            body.putObject("QueryControl").put("QueryRewrite", false);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("豆包搜索 HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
            }
            return parse(om.readTree(resp.body()), query, sites);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("豆包搜索失败[" + query + "]: " + e.getMessage(), e);
        }
    }

    private List<SearchItem> parse(JsonNode root, String query, List<String> sites) {
        List<SearchItem> items = new ArrayList<>();
        String platform = platformOf(sites);
        for (JsonNode n : root.path("Result").path("WebResults")) {
            String title = n.path("Title").asText("");
            String url = n.path("Url").asText("");
            String summary = n.path("Summary").asText("");
            String content = n.path("Content").asText("");
            String text = !summary.isBlank() ? summary : content;
            if (title.isBlank() || url.isBlank() || text.isBlank()) {
                continue;
            }
            double rank = n.path("RankScore").asDouble(0.5);
            items.add(new SearchItem(title, url, text, n.path("SiteName").asText(""),
                    n.path("PublishTime").asText(""), rank, platform, query));
        }
        log.info("豆包搜索[{}] 返回 {} 条", query, items.size());
        return items;
    }

    private String platformOf(List<String> sites) {
        if (sites == null || sites.isEmpty()) return "web";
        for (var entry : PLATFORM_SITES.entrySet()) {
            if (entry.getValue().equals(sites)) return entry.getKey();
        }
        return "web";
    }

    private String normalizeTime(String timeRange) {
        if (timeRange == null) return "OneYear";
        if (TIME_RANGES.contains(timeRange)) return timeRange;
        if (timeRange.matches("\\d{4}-\\d{2}-\\d{2}\\.\\.\\d{4}-\\d{2}-\\d{2}")) return timeRange;
        return "OneYear";
    }

    private String truncate(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) + "..." : s);
    }
}
