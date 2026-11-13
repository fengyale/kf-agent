package com.decision.loop;

import com.decision.model.LoopModels;
import com.decision.model.LoopModels.AssessOutput;
import com.decision.model.LoopModels.PathOutput;
import com.decision.model.LoopModels.PathStage;
import com.decision.model.LoopModels.RedTeamChallenge;
import com.decision.model.LoopModels.Scenario;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 红队确定性校验（自审机制的"代码裁判"）：把可由代码判定的硬冲突做成断言，
 * 命中即作为 major 质疑并入红队质疑清单。
 *
 * 设计要点：这些条目的 severity 由代码硬编码为 major，模型无权降级/改写，
 * 因此"把严重问题标成 minor 以绕过修订轮"的规避路径被堵死；同时这批检查
 * 是确定性的（同一输入必然同一结论），不消耗模型预算、不影响录制回放序号。
 *
 * 覆盖（只读 assess/path 的结构化数值，不调用模型）：
 * 1. 三档齐全性
 * 2. 概率合法性 + A&gt;B&gt;C 单调
 * 3. 周投入 A≥B≥C / 周期 A≤B≤C 单调
 * 4. B 现实档是否真按校准系数修正（核心：防"假现实档"）
 * 5. path 阶段周期求和与总周期一致（防总量失真）
 */
@Service
public class RedTeamAssertions {

    /** B 档投入比与校准系数允许的最大偏差（占 A 档比例） */
    private static final double COEF_TOLERANCE = 0.15;
    /** 阶段周期求和与总周期允许偏差（周） */
    private static final double WEEKS_TOLERANCE = 1.0;

    /**
     * 对当前 assess/path 结论做确定性校验。
     *
     * @param assess      可行性评估输出（三档情景）
     * @param path        路径设计输出（阶段与总周期）
     * @param coefficient 该问题类型桶的校准系数（冷启动为全局默认值）
     * @return 代码判定为 major 的质疑清单（可能为空）
     */
    public List<RedTeamChallenge> check(AssessOutput assess, PathOutput path, double coefficient) {
        List<RedTeamChallenge> out = new ArrayList<>();
        if (assess == null) {
            return out;
        }
        List<Scenario> sc = assess.scenarios() == null ? List.of() : assess.scenarios();
        if (sc.size() < 3) {
            out.add(major("三档情景不完整：仅给出 " + sc.size()
                            + " 档，无法形成乐观/现实/保守的对照（缺档位无法判断方案稳健性）",
                    "补齐三档：A 乐观（全力投入）、B 现实（按校准系数修正）、C 保守（最低可持续投入）"));
            return out; // 结构不全时后续数值校验无意义
        }
        Scenario a = sc.get(0), b = sc.get(1), c = sc.get(2);

        checkProbability(out, a, b, c);
        checkWeeklyHours(out, a, b, c);
        checkDuration(out, a, b, c);
        checkRealisticCalibrated(out, a, b, coefficient);
        checkPathTotal(out, path);
        return out;
    }

    /** 概率：须落在 (0,1) 且 A &gt; B &gt; C（否则"现实档"概率可能被虚高） */
    private void checkProbability(List<RedTeamChallenge> out, Scenario a, Scenario b, Scenario c) {
        Double pa = a.probability(), pb = b.probability(), pc = c.probability();
        if (pa == null || pb == null || pc == null) {
            out.add(major("三档概率缺失，无法判断乐观偏差是否已被校准",
                    "为三档分别给出 0~1 的达成概率"));
            return;
        }
        if (pa <= 0 || pa >= 1 || pb <= 0 || pb >= 1 || pc <= 0 || pc >= 1) {
            out.add(major("三档概率超出 0~1 合法区间（" + pa + " / " + pb + " / " + pc + "）",
                    "按实际达成可能性给出 0~1 概率"));
        }
        if (!(pa > pb && pb > pc)) {
            out.add(major("三档概率未呈 A>B>C 递减（" + pa + " / " + pb + " / " + pc
                            + "）：档位高低与达成概率矛盾，至少有一档概率失真",
                    "按投入递减重新标定概率，A 乐观档概率须高于 B 现实档、高于 C 保守档"));
        }
    }

    /** 周投入：A ≥ B ≥ C（乐观档投入应不低于现实档、现实档不低于保守档） */
    private void checkWeeklyHours(List<RedTeamChallenge> out, Scenario a, Scenario b, Scenario c) {
        Double wa = a.weeklyHours(), wb = b.weeklyHours(), wc = c.weeklyHours();
        if (wa == null || wb == null || wc == null) {
            out.add(major("三档周投入缺失，无法核算总投入与排期",
                    "为三档分别给出每周投入小时数"));
            return;
        }
        if (!(wa >= wb && wb >= wc)) {
            out.add(major("三档周投入未呈 A≥B≥C 递减（" + wa + " / " + wb + " / " + wc
                            + " 小时）：保守档投入反超乐观档，与档位定义矛盾",
                    "按投入递减重排三档：A 档最高、C 档为最低可持续投入"));
        }
    }

    /** 周期：A ≤ B ≤ C（投入越低周期越长，与周投入单调方向互补） */
    private void checkDuration(List<RedTeamChallenge> out, Scenario a, Scenario b, Scenario c) {
        Double da = a.duration(), db = b.duration(), dc = c.duration();
        if (da == null || db == null || dc == null) {
            out.add(major("三档周期缺失，无法判断排期是否与实际执行水平匹配",
                    "为三档分别给出预计周期"));
            return;
        }
        if (!(da <= db && db <= dc)) {
            out.add(major("三档周期未呈 A≤B≤C 递增（" + da + " / " + db + " / " + dc
                            + "）：投入更低的档位反而周期更短，参数自相矛盾",
                    "按投入-周期反比关系重排：投入越低周期应越长"));
        }
    }

    /** B 现实档是否真按校准系数修正（防"名义现实、实际乐观"） */
    private void checkRealisticCalibrated(List<RedTeamChallenge> out, Scenario a, Scenario b, double coefficient) {
        Double wa = a.weeklyHours(), wb = b.weeklyHours();
        if (wa == null || wb == null || wa <= 0) {
            return; // 投入缺失已在 checkWeeklyHours 报出
        }
        double ratio = wb / wa;
        if (Math.abs(ratio - coefficient) > COEF_TOLERANCE) {
            out.add(major(String.format(
                            "B 现实档周投入 %s 与 A 乐观档 %s 之比为 %.2f，与个人校准系数 %.2f 偏离超过 ±%.2f："
                                    + "B 档未按实际历史执行水平修正（按当前排期推进即按乐观档自我高估）",
                            wb, wa, ratio, coefficient, COEF_TOLERANCE),
                    String.format("B 档周投入按校准系数改为约 %.1f 小时（A 档 %.1f × %.2f），并据此重算周期",
                            wa * coefficient, wa, coefficient)));
        }
    }

    /** path 一致性：阶段周期求和须与总周期一致，且不得有阶段缺周期（否则总周期被静默算错） */
    private void checkPathTotal(List<RedTeamChallenge> out, PathOutput path) {
        if (path == null || path.stages() == null || path.stages().isEmpty()) {
            return;
        }
        long missing = path.stages().stream().filter(s -> s == null || s.weeks() == null).count();
        if (missing > 0) {
            out.add(major("路径有 " + missing + " 个阶段缺少周期数值，总周期无法核算（合计会被静默算小）",
                    "补齐每个阶段的周期，再由周期求和得出总周期"));
            return;
        }
        if (path.totalWeeks() == null) {
            return;
        }
        double sum = path.stages().stream().mapToDouble(PathStage::weeks).sum();
        if (Math.abs(sum - path.totalWeeks()) > WEEKS_TOLERANCE) {
            out.add(major(String.format("路径总周期 %.1f 周与各阶段求和 %.1f 周不一致（差 %.1f 周）",
                            path.totalWeeks(), sum, Math.abs(sum - path.totalWeeks())),
                    "以各阶段周期求和为准修正总周期，或补齐缺失阶段"));
        }
    }

    private static RedTeamChallenge major(String point, String mitigation) {
        return new RedTeamChallenge(point, "major", mitigation, LoopModels.SOURCE_CODE);
    }
}
