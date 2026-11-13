package com.decision.external;

import com.decision.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 调研报告引用（spec Requirement: 调研报告引用，Task 10.1）：
 * 只读跨库查 research-agent/data/research.db 的 report 表（title 主题匹配 + markdown 全文关键词命中），
 * 返回 [{id,title,markdown,asOf}]；research.db 文件不存在或表不存在时降级扫描
 * 调研报告目录（F:\learn\ai转型计划个人学习\research-agent调研报告\*.md，读文件头/首行标题做关键词匹配）。
 *
 * 引用即零成本零延迟（不触发联网搜索），由 ExternalDataService 完成三段递进编排。
 *
 * 关键词提取策略（中文无空格分词）：
 * - 英文/数字：按非字母数字拆分为词（长度 >=2）
 * - 中文连续串：滑动 2-gram/3-gram 切分（长度 >=2），控制关键词总数避免 SQL 过长
 * 任一关键词命中 title 或 markdown 即视为命中，按命中关键词数降序取前 N 条。
 */
@Service
public class ReportReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReportReferralService.class);

    /** 单次最多返回的引用报告条数（引用摘要注入提示词，控制上下文体积） */
    private static final int MAX_REFS = 2;
    /** 引用内容摘要上限（全文注入会撑爆提示词，截取头部作为引用块） */
    private static final int REF_SNIPPET = 1500;
    /** 关键词上限（SQL LIKE 条件数与 md 匹配量） */
    private static final int MAX_KEYWORDS = 12;

    /** 引用结果：id 为 research.db 主键（md 兜底时为 0）、asOf 取 created_at（md 兜底时取文件修改时间） */
    public record ReportRef(Long id, String title, String markdown, String asOf, String source) {}

    private final AgentProperties props;

    public ReportReferralService(AgentProperties props) {
        this.props = props;
    }

    /** 按主题查找可引用的调研报告（research.db 优先，md 目录兜底）；无命中返回空列表 */
    public List<ReportRef> findByTopic(String topic) {
        List<String> kws = keywords(topic);
        if (kws.isEmpty()) {
            return List.of();
        }
        List<ReportRef> fromDb = queryResearchDb(kws);
        if (!fromDb.isEmpty()) {
            return fromDb;
        }
        List<ReportRef> fromMd = scanMarkdownDir(kws);
        if (!fromMd.isEmpty()) {
            return fromMd;
        }
        return List.of();
    }

    // ==================== research.db 跨库只读查询 ====================

    private List<ReportRef> queryResearchDb(List<String> kws) {
        Path db = Path.of(props.getData().getResearchDbPath());
        if (!Files.exists(db)) {
            log.info("调研库不存在（{}），降级扫描调研报告目录", db);
            return List.of();
        }
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = conn.createStatement()) {
            // 表不存在（建表用 CREATE TABLE IF NOT EXISTS 兜底，跨库只读不建表）→ 直接降级
            if (!tableExists(st, "report")) {
                log.info("调研库无 report 表（{}），降级扫描调研报告目录", db);
                return List.of();
            }
            String where = buildLikeWhere(kws, "title", "markdown");
            String sql = "SELECT id, title, markdown, created_at FROM report WHERE " + where
                    + " ORDER BY length(title) ASC, id DESC LIMIT " + MAX_REFS;
            List<ReportRef> refs = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    String md = rs.getString("markdown");
                    if (md == null || md.isBlank()) {
                        continue;
                    }
                    String title = firstNonBlank(rs.getString("title"), firstTitleLine(md), "调研报告");
                    String asOf = firstNonBlank(rs.getString("created_at"), fileMtimeAsOf(db));
                    refs.add(new ReportRef(rs.getLong("id"), title, snippet(md), asOf,
                            "research-agent 调研库"));
                }
            }
            if (refs.isEmpty()) {
                log.info("调研库无主题命中（关键词 {}），降级扫描调研报告目录", kws.size());
            }
            return refs;
        } catch (SQLException e) {
            log.warn("调研库查询失败（{}），降级扫描调研报告目录: {}", db, e.getMessage());
            return List.of();
        }
    }

    private static boolean tableExists(Statement st, String table) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
            return rs.next();
        }
    }

    // ==================== Markdown 目录兜底 ====================

    /** 扫描调研报告目录 *.md：标题取文件名（去日期前缀）或首行 # 标题，内容全文做关键词匹配 */
    private List<ReportRef> scanMarkdownDir(List<String> kws) {
        Path dir = Path.of(props.getData().getResearchMarkdownDir());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ReportRef> hits = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().toLowerCase().endsWith(".md")).toList()) {
                try {
                    String content = Files.readString(f, StandardCharsets.UTF_8);
                    int matched = countMatches(content, kws);
                    if (matched > 0) {
                        String title = firstTitleLine(content);
                        if (title == null) {
                            title = filenameTitle(f.getFileName().toString());
                        }
                        hits.add(new ReportRef(0L, title, snippet(content), fileMtimeAsOf(f),
                                "调研报告目录"));
                    }
                } catch (IOException e) {
                    log.debug("调研报告目录文件读取失败（跳过）: {} {}", f, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("调研报告目录扫描失败: {}", e.getMessage());
            return List.of();
        }
        // 按命中关键词数降序，取前 N
        hits.sort((a, b) -> Integer.compare(countMatches(b.markdown(), kws), countMatches(a.markdown(), kws)));
        return hits.size() > MAX_REFS ? hits.subList(0, MAX_REFS) : hits;
    }

    // ==================== 关键词与匹配工具 ====================

    /** 主题 → 关键词（英文/数字词 + 中文 2-gram/3-gram），去重限数 */
    List<String> keywords(String topic) {
        if (topic == null || topic.isBlank()) {
            return List.of();
        }
        Set<String> kws = new LinkedHashSet<>();
        for (String w : topic.toLowerCase().split("[^a-z0-9]+")) {
            if (w.length() >= 2) {
                kws.add(w);
            }
        }
        for (String seg : topic.split("[a-zA-Z0-9\\s]+")) {
            String s = seg.replaceAll("[\\s\\p{Punct}]+", "");
            if (s.length() < 2) {
                continue;
            }
            if (s.length() <= 4) {
                kws.add(s);
                continue;
            }
            for (int len = 2; len <= 3; len++) {
                for (int i = 0; i + len <= s.length(); i++) {
                    kws.add(s.substring(i, i + len));
                }
            }
        }
        List<String> list = new ArrayList<>(kws);
        return list.size() > MAX_KEYWORDS ? list.subList(0, MAX_KEYWORDS) : list;
    }

    private static String buildLikeWhere(List<String> kws, String... columns) {
        StringBuilder sb = new StringBuilder();
        for (String kw : kws) {
            if (sb.length() > 0) {
                sb.append(" OR ");
            }
            sb.append('(');
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) {
                    sb.append(" OR ");
                }
                sb.append(columns[i]).append(" LIKE '%").append(escapeLike(kw)).append("%'");
            }
            sb.append(')');
        }
        return sb.toString();
    }

    private static String escapeLike(String s) {
        return s.replace("'", "''").replace("%", "\\%").replace("_", "\\_");
    }

    private static int countMatches(String text, List<String> kws) {
        if (text == null) {
            return 0;
        }
        String lower = text.toLowerCase();
        int n = 0;
        for (String kw : kws) {
            if (lower.contains(kw.toLowerCase())) {
                n++;
            }
        }
        return n;
    }

    /** 内容摘录：截取头部 REF_SNIPPET 字符（引用块注入提示词用，全文留给完整调研） */
    static String snippet(String markdown) {
        if (markdown == null) {
            return "";
        }
        String s = markdown.strip();
        return s.length() > REF_SNIPPET ? s.substring(0, REF_SNIPPET) + "\n…（完整报告见调研 Agent）" : s;
    }

    /** 首行 # 标题（md 兜底与标题兜底共用） */
    static String firstTitleLine(String markdown) {
        if (markdown == null) {
            return null;
        }
        for (String line : markdown.split("\n", 5)) {
            String t = line.strip();
            if (t.startsWith("# ")) {
                return t.substring(2).strip();
            }
        }
        return null;
    }

    /** md 兜底的文件名标题：去扩展名与日期前缀（如 20260831-0125-xxx.md → xxx） */
    private static String filenameTitle(String filename) {
        String base = filename;
        if (base.toLowerCase().endsWith(".md")) {
            base = base.substring(0, base.length() - 3);
        }
        base = base.replaceFirst("^\\d{8}-\\d{4}-?", "");
        return base.isBlank() ? filename : base;
    }

    /** 文件修改时间 asOf（YYYY-MM-DD） */
    private static String fileMtimeAsOf(Path f) {
        try {
            return Files.getLastModifiedTime(f).toInstant().toString().substring(0, 10);
        } catch (IOException e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String firstNonBlank(String a, String b, String c) {
        String r = firstNonBlank(a, b);
        return r != null && !r.isBlank() ? r : c;
    }
}