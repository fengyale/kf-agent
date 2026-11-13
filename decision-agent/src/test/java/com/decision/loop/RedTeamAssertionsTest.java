package com.decision.loop;

import com.decision.model.LoopModels.AssessOutput;
import com.decision.model.LoopModels.PathOutput;
import com.decision.model.LoopModels.PathStage;
import com.decision.model.LoopModels.RedTeamChallenge;
import com.decision.model.LoopModels.Scenario;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 红队确定性校验单测（纯代码，不调模型不联网）：
 * 覆盖"代码裁判"的四类判定——现实档是否真按校准系数修正、三档参数单调性、
 * 路径总周期一致性，以及全合规结论不产生噪音质疑。
 *
 * 意义：这些判定由代码硬编码为 major，模型无法把它们降级为 minor 从而绕过修订轮。
 */
class RedTeamAssertionsTest {

    private final RedTeamAssertions assertions = new RedTeamAssertions();

    private static Scenario sc(String name, double hours, double weeks, double prob) {
        return new Scenario(name, hours, weeks, prob, List.of(), "投入不足时降档");
    }

    /** 合规样本：A 15h/24周/0.65 + B 10.5h/34周/0.4（= 15×0.7）+ C 6h/60周/0.2 */
    private static AssessOutput consistent() {
        return new AssessOutput("可行", List.of(), List.of(
                sc("A 乐观", 15, 24, 0.65),
                sc("B 现实", 10.5, 34, 0.4),
                sc("C 保守", 6, 60, 0.2)), List.of(), null);
    }

    /** 阶段求和 = 8 + 16 = 24 周 */
    private static PathOutput path(double totalWeeks) {
        return new PathOutput(List.of(
                new PathStage("筹备", 8.0, "搭好基础", "能否独立完成最小练习"),
                new PathStage("推进", 16.0, "达到可用", "能否交付一个完整作品")), totalWeeks, null);
    }

    @Test
    void flagsRealisticScenarioNotCalibrated() {
        // B 档照抄乐观档投入（15h），未按校准系数 0.7 修正 → 代码须判为 major
        AssessOutput assess = new AssessOutput("可行", List.of(), List.of(
                sc("A 乐观", 15, 24, 0.65),
                sc("B 现实", 15, 24, 0.6),
                sc("C 保守", 6, 60, 0.2)), List.of(), null);

        List<RedTeamChallenge> out = assertions.check(assess, path(24), 0.7);

        assertTrue(hasMajor(out, "校准系数"),
                "B 档未按校准系数修正须被代码判为 major：" + points(out));
    }

    @Test
    void flagsNonMonotonicProbability() {
        // A 档概率低于 B 档：档位与概率矛盾 → 代码须判为 major
        AssessOutput assess = new AssessOutput("可行", List.of(), List.of(
                sc("A 乐观", 15, 24, 0.3),
                sc("B 现实", 10.5, 34, 0.6),
                sc("C 保守", 6, 60, 0.2)), List.of(), null);

        List<RedTeamChallenge> out = assertions.check(assess, path(24), 0.7);

        assertTrue(hasMajor(out, "递减"),
                "三档概率非单调须被代码判为 major：" + points(out));
    }

    @Test
    void flagsPathTotalMismatch() {
        // 阶段求和 24 周，总周期却写 40 周 → 代码须判为 major
        List<RedTeamChallenge> out = assertions.check(consistent(), path(40), 0.7);

        assertTrue(hasMajor(out, "总周期"),
                "阶段求和与总周期不一致须被代码判为 major：" + points(out));
    }

    @Test
    void passesConsistentConclusionWithNoCodeNoise() {
        List<RedTeamChallenge> out = assertions.check(consistent(), path(24), 0.7);

        assertTrue(out.isEmpty(), "全合规结论不应产生代码质疑：" + points(out));
    }

    @Test
    void flagsMissingScenario() {
        // 只给两档 → 无法形成乐观/现实/保守对照，后续数值校验无意义
        AssessOutput assess = new AssessOutput("可行", List.of(), List.of(
                sc("A 乐观", 15, 24, 0.65),
                sc("B 现实", 10.5, 34, 0.4)), List.of(), null);

        List<RedTeamChallenge> out = assertions.check(assess, path(24), 0.7);

        assertTrue(hasMajor(out, "三档情景不完整"), "缺档须被代码判为 major：" + points(out));
    }

    @Test
    void flagsStageMissingWeeks() {
        // 阶段缺周期 → 总周期无法核算（原实现按 null→0 求和会静默算小）
        PathOutput incomplete = new PathOutput(List.of(
                new PathStage("筹备", 8.0, "搭好基础", "验收"),
                new PathStage("推进", null, "达到可用", "验收")), 24.0, null);

        List<RedTeamChallenge> out = assertions.check(consistent(), incomplete, 0.7);

        assertTrue(hasMajor(out, "缺少周期"), "阶段缺周期须被代码判为 major：" + points(out));
    }

    private static boolean hasMajor(List<RedTeamChallenge> list, String keyword) {
        return list.stream().anyMatch(c -> c != null
                && "major".equals(c.severity())
                && c.point() != null && c.point().contains(keyword));
    }

    private static String points(List<RedTeamChallenge> list) {
        return list.stream().map(RedTeamChallenge::point).toList().toString();
    }
}
