package com.decision.personal;

import com.decision.collector.IntentService;
import com.decision.personal.BehaviorLogService.BehaviorRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分桶校准系数（spec Requirement: 个人上下文库与分桶校准系数）。
 *
 * 校准只用行为日志（实际打卡/提交记录），剔除自评数据（is_self_eval=1 仅展示不参与计算）；
 * 按问题类型分桶（日常事务/学习计划/远期规划/商业经营）各自维护完成率，避免不同类型互相污染。
 *
 * - 冷启动：桶内非自评记录 < 4 条时用全局乐观系数 0.7 并显著标注（coldStart=true，"建议校准"）
 * - 完成率：Σ实际/Σ计划 加权（单条超计划封顶，防一条超额掩盖多条未达）；无量化值退化为完成条数比例
 * - 分位：≥4 条量化记录时计算 P25/P50/P75（三档情景 A/B/C 分档依据优先用它）
 * - 数据保鲜度（Task 9）：asOf 取最近一条行为记录时间，超 6 个月未校准输出降权提示
 */
@Service
public class CalibrationService {

    private static final Logger log = LoggerFactory.getLogger(CalibrationService.class);

    /** 全局乐观默认系数（冷启动） */
    public static final double DEFAULT_COEFFICIENT = 0.7;
    /** 起用真实数据的最低样本量 */
    public static final int MIN_SAMPLE = 4;
    /** 数据保鲜度阈值：超 N 个月未校准降权提示 */
    public static final int STALE_MONTHS = 6;
    /** 第四档"不建议推进"的校准系数门槛（与红队严重质疑双重确认） */
    public static final double NO_GO_THRESHOLD = 0.4;

    /** 四个问题类型桶 */
    public static final List<String> BUCKETS = List.of(
            IntentService.TYPE_DAILY, IntentService.TYPE_LEARNING,
            IntentService.TYPE_LONGTERM, IntentService.TYPE_BUSINESS);

    /** 校准结果：coefficient 完成率系数；coldStart=冷启动默认值；asOf=最近行为记录时间 */
    public record Calibration(String type, double coefficient, int sampleSize, boolean coldStart,
                              String asOf, boolean stale, int staleMonths,
                              BucketPercentiles percentiles, String note) {}

    /** 个人库历史分位（P25 保守 / P50 现实 / P75 乐观，三档分档依据） */
    public record BucketPercentiles(double p25, double p50, double p75) {}

    /** 数据保鲜度：stale + 月数 + 提示语（"此数据已 X 个月未校准"） */
    public record Freshness(boolean stale, int months, String warning) {}

    private final BehaviorLogService behaviorLog;

    public CalibrationService(BehaviorLogService behaviorLog) {
        this.behaviorLog = behaviorLog;
    }

    /** 按问题类型取校准系数（type 归一到四桶；空桶冷启动 0.7） */
    public Calibration getCalibration(String type) {
        String bucket = IntentService.normalizeType(type);
        List<BehaviorRecord> records = behaviorLog.listForCalibration(bucket);
        int sampleSize = records.size();
        String asOf = latestAsOf(records);
        Freshness freshness = freshness(asOf);

        if (sampleSize < MIN_SAMPLE) {
            String note = "建议校准：" + bucket + "桶非自评行为数据不足 " + MIN_SAMPLE
                    + " 条（当前 " + sampleSize + " 条），使用全局默认系数 " + DEFAULT_COEFFICIENT;
            if (freshness.stale()) {
                note += "；" + freshness.warning();
            }
            return new Calibration(bucket, DEFAULT_COEFFICIENT, sampleSize, true, asOf,
                    freshness.stale(), freshness.months(), null, note);
        }

        double coefficient = completionRate(records);
        BucketPercentiles percentiles = percentiles(records);
        String note = bucket + "桶实际完成率（" + sampleSize + " 案，asOf " + (asOf == null ? "无" : asOf) + "）";
        if (freshness.stale()) {
            note += "；" + freshness.warning();
        }
        return new Calibration(bucket, coefficient, sampleSize, false, asOf,
                freshness.stale(), freshness.months(), percentiles, note);
    }

    /** 四桶校准总览（行为数据页展示用） */
    public List<Calibration> allBuckets() {
        List<Calibration> out = new ArrayList<>();
        for (String b : BUCKETS) {
            out.add(getCalibration(b));
        }
        return out;
    }

    /** 校准区块的提示词视图（注入模板3/4：分位 + 保鲜度一并携带） */
    public Map<String, Object> promptView(String type) {
        Calibration cal = getCalibration(type);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("type", cal.type());
        view.put("coefficient", cal.coefficient());
        view.put("sampleSize", cal.sampleSize());
        view.put("coldStart", cal.coldStart());
        view.put("asOf", cal.asOf());
        view.put("percentiles", cal.percentiles());
        view.put("note", cal.note());
        return view;
    }

    // ==================== 计算 ====================

    /** 实际完成率：Σmin(实际,计划)/Σ计划 加权；无量化值退化为 completed 条数比例；钳到 [0,1] */
    private double completionRate(List<BehaviorRecord> records) {
        double plannedSum = 0;
        double actualSum = 0;
        int completed = 0;
        for (BehaviorRecord r : records) {
            if (r.completed()) {
                completed++;
            }
            if (r.plannedValue() != null && r.plannedValue() > 0 && r.actualValue() != null) {
                plannedSum += r.plannedValue();
                actualSum += Math.min(r.actualValue(), r.plannedValue());
            }
        }
        double rate;
        if (plannedSum > 0) {
            rate = actualSum / plannedSum;
        } else {
            rate = records.isEmpty() ? DEFAULT_COEFFICIENT : (double) completed / records.size();
        }
        return round2(Math.max(0, Math.min(1, rate)));
    }

    /** 个人库历史分位：单条完成比 min(实际/计划,1)，≥MIN_SAMPLE 条量化记录才算得出 */
    private BucketPercentiles percentiles(List<BehaviorRecord> records) {
        List<Double> ratios = new ArrayList<>();
        for (BehaviorRecord r : records) {
            if (r.plannedValue() != null && r.plannedValue() > 0 && r.actualValue() != null) {
                ratios.add(Math.min(r.actualValue() / r.plannedValue(), 1.0));
            }
        }
        if (ratios.size() < MIN_SAMPLE) {
            return null;
        }
        ratios.sort(Comparator.naturalOrder());
        return new BucketPercentiles(
                round2(ratios.get(percentileIndex(ratios.size(), 0.25))),
                round2(ratios.get(percentileIndex(ratios.size(), 0.50))),
                round2(ratios.get(percentileIndex(ratios.size(), 0.75))));
    }

    /** 最近邻法分位下标 */
    private static int percentileIndex(int n, double p) {
        return (int) Math.min(n - 1, Math.round(p * (n - 1)));
    }

    /** 最近一条行为记录时间（occurred_at 优先，缺省 created_at） */
    private static String latestAsOf(List<BehaviorRecord> records) {
        String latest = null;
        for (BehaviorRecord r : records) {
            String t = r.occurredAt() != null && !r.occurredAt().isBlank() ? r.occurredAt() : r.createdAt();
            if (t != null && (latest == null || t.compareTo(latest) > 0)) {
                latest = t;
            }
        }
        return latest;
    }

    // ==================== 数据保鲜度（Task 9） ====================

    /** asOf 距今超 STALE_MONTHS 个月 → stale + "此数据已 X 个月未校准"提示（注入提示词与报告） */
    public static Freshness freshness(String asOf) {
        if (asOf == null || asOf.isBlank()) {
            return new Freshness(false, 0, null);
        }
        try {
            LocalDate d = LocalDate.parse(asOf.strip().substring(0, Math.min(10, asOf.strip().length())));
            long months = ChronoUnit.MONTHS.between(d, LocalDate.now());
            if (months >= STALE_MONTHS) {
                return new Freshness(true, (int) months, "此数据已 " + months + " 个月未校准，可能不代表当前状态");
            }
            return new Freshness(false, (int) Math.max(months, 0), null);
        } catch (Exception e) {
            log.debug("asOf 解析失败（不判过期）: {}", asOf);
            return new Freshness(false, 0, null);
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
