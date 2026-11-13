package com.decision.loop;

import com.decision.model.LoopModels.Scenario;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 预测后验落库（spec Requirement: 预测后验）：
 * 分析完成时把最终版三档量化预期写入 analysis_expectation（一档一行：
 * scenario=档名，expectation=量化预期描述，probability=达成概率），
 * 回顾时用户填写实际结果（actual_result/reviewed_at），为 v2 Brier score
 * 预测校准曲线留数据种子。
 *
 * 一律先删后插：红队修订轮会更新三档，报告落盘时以最终版为准，不留中间版本。
 */
@Service
public class ExpectationStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jt;

    public ExpectationStore(JdbcTemplate jt) {
        this.jt = jt;
    }

    /** 三档量化预期落库（一档一行），无三档（light 路径/中断）时静默跳过 */
    public void saveScenarios(String analysisId, List<Scenario> scenarios) {
        if (scenarios == null || scenarios.isEmpty()) {
            return;
        }
        jt.update("DELETE FROM analysis_expectation WHERE analysis_id=?", analysisId);
        String now = LocalDateTime.now().format(TS);
        for (Scenario s : scenarios) {
            if (s == null) {
                continue;
            }
            String expectation = expectationText(s);
            jt.update("""
                            INSERT INTO analysis_expectation(analysis_id, scenario, expectation, probability, created_at)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    analysisId, str(s.name()), expectation, s.probability(), now);
        }
    }

    /** 量化预期描述：投入 × 周期 + 里程碑数（供回顾时对照"当时预期是什么"） */
    private static String expectationText(Scenario s) {
        StringBuilder sb = new StringBuilder();
        if (s.weeklyHours() != null && s.duration() != null) {
            sb.append("投入 ").append(s.weeklyHours()).append(" 小时/周 × ").append(s.duration()).append(" 周");
        }
        if (s.milestones() != null && !s.milestones().isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("，");
            }
            sb.append("里程碑 ").append(s.milestones().size()).append(" 个");
        }
        if (sb.isEmpty()) {
            sb.append(str(s.name()));
        }
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
