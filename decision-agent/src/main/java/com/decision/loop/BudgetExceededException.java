package com.decision.loop;

/**
 * 成本护栏拦截异常：单次分析模型调用超上限（默认 8 次：采集追问+分析+红队+修订合计）。
 * 由 BudgetGuard.beforeModelCall 抛出，FeasibilityLoop 捕获后按 guard:budget 退出（输出已完成部分）。
 */
public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String message) {
        super(message);
    }
}
