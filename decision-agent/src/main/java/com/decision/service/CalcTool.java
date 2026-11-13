package com.decision.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * 确定性计算工具：可行性分析中的算术（时间投入×周期总量、成本测算、里程碑日期推算）
 * 由代码求值，不由 LLM 心算。LLM 只生成计算意图（算式/参数），结果可标注"经计算校验"。
 *
 * evaluate() 为安全求值：手写递归下降解析，仅允许数字、+ - * /、括号与时间单位后缀，
 * 任何其它字符（字母、方法调用、注入文本）一律拒绝，无脚本引擎执行风险。
 */
@Component
public class CalcTool {

    // 时间单位换算表（以小时为基准）
    private static final List<String> UNIT_TOKENS = List.of(
            "hours", "hour", "mins", "min", "weeks", "week", "days", "day", "小时", "分钟", "周", "天", "h", "d", "w", "m");

    /** 安全算术表达式求值：仅数字/加减乘除/括号；数字后可带时间单位后缀（如 "10h * 24" 中的 h 被剥离）。 */
    public double evaluate(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("算式为空");
        }
        return new Parser(expr).parse();
    }

    /** 时间单位换算：convert(2, "d", "h") = 48。单位支持 min/h/d/w 及中文（分钟/小时/天/周）。 */
    public double convert(double value, String fromUnit, String toUnit) {
        double from = unitHours(fromUnit);
        double to = unitHours(toUnit);
        if (to == 0) {
            throw new IllegalArgumentException("目标单位非法: " + toUnit);
        }
        return value * from / to;
    }

    /** 周投入总量：每周 hoursPerWeek 小时 × weeks 周 = 总小时数。 */
    public double projectTotal(double hoursPerWeek, double weeks) {
        return round2(hoursPerWeek * weeks);
    }

    /** 成本测算：单价 × 数量。 */
    public double cost(double unitPrice, double quantity) {
        return round2(unitPrice * quantity);
    }

    // ==================== 日期工具 ====================

    public String addDays(String date, long days) {
        return parseDate(date).plusDays(days).toString();
    }

    public String addWeeks(String date, long weeks) {
        return parseDate(date).plusWeeks(weeks).toString();
    }

    public String addMonths(String date, long months) {
        return parseDate(date).plusMonths(months).toString();
    }

    public boolean isAfter(String dateA, String dateB) {
        return parseDate(dateA).isAfter(parseDate(dateB));
    }

    public boolean isBefore(String dateA, String dateB) {
        return parseDate(dateA).isBefore(parseDate(dateB));
    }

    /** 日期间隔天数：to - from（to 晚于 from 时为正）。 */
    public long daysBetween(String from, String to) {
        return ChronoUnit.DAYS.between(parseDate(from), parseDate(to));
    }

    /** 日期间隔周数：to - from。 */
    public long weeksBetween(String from, String to) {
        return ChronoUnit.WEEKS.between(parseDate(from), parseDate(to));
    }

    /** 今天日期（ISO yyyy-MM-dd）。 */
    public String today() {
        return LocalDate.now().toString();
    }

    // ==================== 内部实现 ====================

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("日期为空，期望格式 yyyy-MM-dd");
        }
        try {
            return LocalDate.parse(date.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式非法: " + date + "（期望 yyyy-MM-dd）", e);
        }
    }

    private static double unitHours(String unit) {
        if (unit == null) {
            throw new IllegalArgumentException("单位为空");
        }
        return switch (unit.strip().toLowerCase(Locale.ROOT)) {
            case "min", "m", "分钟" -> 1.0 / 60;
            case "h", "hour", "hours", "小时" -> 1.0;
            case "d", "day", "days", "天" -> 24.0;
            case "w", "week", "weeks", "周" -> 24.0 * 7;
            default -> throw new IllegalArgumentException("未知时间单位: " + unit + "（支持 min/h/d/w 及中文）");
        };
    }

    /** 递归下降解析器：expression := term (('+'|'-') term)* ；term := factor (('*'|'/') factor)* */
    private static final class Parser {
        private final String s;
        private int pos = -1;
        private int ch;

        Parser(String s) {
            this.s = s;
            next();
        }

        void next() {
            ch = (++pos < s.length()) ? s.charAt(pos) : -1;
        }

        boolean eat(int c) {
            while (ch == ' ' || ch == '\t') next();
            if (ch == c) {
                next();
                return true;
            }
            return false;
        }

        double parse() {
            double v = expression();
            while (ch == ' ' || ch == '\t') next();
            if (ch != -1) {
                throw new IllegalArgumentException("算式含非法字符: '" + (char) ch + "'（仅支持数字/+ - * / /括号/时间单位）");
            }
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException("算式结果非法: " + v);
            }
            return v;
        }

        double expression() {
            double v = term();
            for (; ; ) {
                if (eat('+')) v += term();
                else if (eat('-')) v -= term();
                else return v;
            }
        }

        double term() {
            double v = factor();
            for (; ; ) {
                if (eat('*')) v *= factor();
                else if (eat('/')) {
                    double divisor = factor();
                    if (divisor == 0) {
                        throw new IllegalArgumentException("除数为零");
                    }
                    v /= divisor;
                } else return v;
            }
        }

        double factor() {
            if (eat('+')) return +factor();
            if (eat('-')) return -factor();
            double v;
            int startPos = this.pos;
            if (eat('(')) {
                v = expression();
                if (!eat(')')) {
                    throw new IllegalArgumentException("括号不匹配");
                }
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') next();
                try {
                    v = Double.parseDouble(s.substring(startPos, this.pos));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("数字格式非法: " + s.substring(startPos, this.pos));
                }
            } else {
                throw new IllegalArgumentException("算式含非法字符: '" + (ch == -1 ? "结尾" : String.valueOf((char) ch)) + "'");
            }
            skipUnitSuffix();
            return v;
        }

        /** 数字/括号项后的时间单位后缀剥离（"10h"、"2.5 小时" 等，单位不参与数值）。 */
        void skipUnitSuffix() {
            while (ch == ' ' || ch == '\t') {
                // 单位与数字之间允许空白，但仅当紧跟的是单位词时才消费
                int save = pos;
                next();
                if (!startsWithUnit()) {
                    pos = save;
                    ch = s.charAt(pos);
                    return;
                }
                break;
            }
            if (startsWithUnit()) {
                String rest = s.substring(pos);
                for (String unit : UNIT_TOKENS) {
                    if (rest.startsWith(unit)) {
                        pos += unit.length();
                        next();
                        return;
                    }
                }
            }
        }

        boolean startsWithUnit() {
            if (ch == -1) return false;
            String rest = s.substring(pos);
            for (String unit : UNIT_TOKENS) {
                if (rest.startsWith(unit)) {
                    return true;
                }
            }
            return false;
        }
    }
}
