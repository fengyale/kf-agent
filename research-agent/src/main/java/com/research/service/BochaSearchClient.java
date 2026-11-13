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
import java.util.Map;

public class BochaSearchClient implements SearchClient {

    private static final Logger log = LoggerFactory.getLogger(BochaSearchClient.class);
    private static final String ENDPOINT = "https://api.bochaai.com/v1/web-search";
    private static final Map<String, String> FRESHNESS = Map.of(
            "OneDay", "oneDay", "OneWeek", "oneWeek", "OneMonth", "oneMonth", "OneYear", "oneYear");

    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper om = new ObjectMapper();

    public BochaSearchClient(AgentProperties props) {
        this.apiKey = props.getSearch().getBocha().getApiKey();
    }

    @Override
    public List<SearchItem> search(String query, int count, String timeRange) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置搜索 API Key：请设置环境变量 BOCHA_API_KEY（博查搜索）后重启服务");
        }
        try {
            ObjectNode body = om.createObjectNode();
            body.put("query", query);
            body.put("summary", true);
            body.put("count", count);
            body.put("freshness", FRESHNESS.getOrDefault(timeRange, "oneYear"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("博查搜索 HTTP " + resp.statusCode() + ": "
                        + (resp.body() == null ? "" : resp.body().substring(0, Math.min(200, resp.body().length()))));
            }
            return parse(om.readTree(resp.body()), query);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("博查搜索失败[" + query + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SearchItem> searchScoped(String query, List<String> sites, int count, String timeRange) {
        log.warn("博查暂无站点定向参数（Sites），降级为普通搜索: {}", sites);
        return search(query, count, timeRange);
    }

    private List<SearchItem> parse(JsonNode root, String query) {
        List<SearchItem> items = new ArrayList<>();
        for (JsonNode n : root.path("data").path("webPages").path("value")) {
            String title = firstNonBlank(n.path("name").asText(""), n.path("title").asText(""));
            String url = n.path("url").asText("");
            String text = firstNonBlank(n.path("summary").asText(""), n.path("snippet").asText(""));
            if (title.isBlank() || url.isBlank() || text.isBlank()) {
                continue;
            }
            items.add(new SearchItem(title, url, text, n.path("siteName").asText(""),
                    n.path("dateLastCrawled").asText(""), 0.5, "web", query));
        }
        log.info("博查搜索[{}] 返回 {} 条", query, items.size());
        return items;
    }

    private String firstNonBlank(String a, String b) {
        return !a.isBlank() ? a : b;
    }
}
