package com.decision.personal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画像服务（profile k/v CRUD）+ 冷启动画像引导（spec Requirement: 冷启动画像引导）。
 * profile 为空（且未标记引导完成）时提供 3~5 题引导（时间投入现状/当前方向/硬约束，可跳过），
 * 填充初始画像，避免每次分析从零问起。引导完成标记存 _guidanceDone（下划线前缀键不对外展示）。
 */
@Service
public class ProfileService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String GUIDANCE_DONE_KEY = "_guidanceDone";

    /** 引导问题（3~5 题，可跳过）：key 对应槽位词汇表字段，答案直接入画像 */
    public record GuidanceQuestion(String key, String question) {}

    private static final List<GuidanceQuestion> GUIDANCE = List.of(
            new GuidanceQuestion("availableTime", "每周大概能稳定投入多少小时（工作/学习/副业合计）？"),
            new GuidanceQuestion("currentStatus", "当前的主业和方向是什么？处于什么阶段？"),
            new GuidanceQuestion("constraints", "有哪些硬约束（家庭/通勤/预算/身体）？"),
            new GuidanceQuestion("learningHistory", "过去半年坚持完成过什么事？大概投入了多少？"));

    private final JdbcTemplate jt;

    public ProfileService(JdbcTemplate jt) {
        this.jt = jt;
    }

    /** 全部画像（过滤内部标记键） */
    public LinkedHashMap<String, String> all() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        jt.query("SELECT k, v FROM profile ORDER BY k",
                rs -> {
                    String k = rs.getString(1);
                    if (!k.startsWith("_")) {
                        out.put(k, rs.getString(2));
                    }
                });
        return out;
    }

    public boolean isEmpty() {
        return all().isEmpty();
    }

    public boolean guidanceDone() {
        List<String> rows = jt.query("SELECT v FROM profile WHERE k=?",
                (rs, i) -> rs.getString(1), GUIDANCE_DONE_KEY);
        return !rows.isEmpty() && "1".equals(rows.get(0));
    }

    public void markGuidanceDone() {
        upsert(GUIDANCE_DONE_KEY, "1");
    }

    public void put(String k, String v) {
        if (k == null || k.isBlank()) {
            throw new IllegalArgumentException("画像键不能为空");
        }
        upsert(k.strip(), v == null ? "" : v.strip());
    }

    /** 冷启动引导问题（可跳过） */
    public List<GuidanceQuestion> guidanceQuestions() {
        return GUIDANCE;
    }

    /** 画像最近更新时间（数据保鲜度：Task 9 注入提示词，超 6 个月未更新降权提示） */
    public String latestUpdatedAt() {
        List<String> rows = jt.query("SELECT MAX(updated_at) FROM profile", (rs, i) -> rs.getString(1));
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0);
    }

    private void upsert(String k, String v) {
        int updated = jt.update("UPDATE profile SET v=?, updated_at=? WHERE k=?",
                v, LocalDateTime.now().format(TS), k);
        if (updated == 0) {
            jt.update("INSERT INTO profile(k, v, updated_at) VALUES (?, ?, ?)",
                    k, v, LocalDateTime.now().format(TS));
        }
    }
}
