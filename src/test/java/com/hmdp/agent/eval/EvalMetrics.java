package com.hmdp.agent.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The metric definitions and the aggregation the report is built from.
 *
 * <p>The definitions are fixed here once and never re-derived per report, which is what the task card
 * means by "同一份口径":</p>
 *
 * <ul>
 *   <li><strong>任务成功率 (task success rate)</strong> = successful runs / all runs. A run is
 *       successful when {@link EvalJudge} found no failed rule: every hard constraint satisfied,
 *       intent covered, tool calls correct.</li>
 *   <li><strong>约束违反率 (constraint violation rate)</strong> = runs with at least one violation /
 *       runs that had a checkable constraint. A violation is a shop presented or returned with
 *       {@code distance > radius} or {@code avgPrice > budget}, read from the world's data.</li>
 *   <li><strong>工具调用正确率 (tool-call correctness)</strong> = runs whose tool-call rules all
 *       passed / all runs, counting one run per task.</li>
 *   <li><strong>平均步数 (mean steps)</strong> = mean number of tool calls per run.</li>
 *   <li><strong>平均延迟 (mean latency)</strong> = mean wall-clock milliseconds per run, measured
 *       around the real loop. In segment A this measures the rig, not a provider's response time.</li>
 *   <li><strong>单次成本 (cost per run)</strong> = tokens × unit price. The token counts come from the
 *       scripted model's character-based estimate and the price table below is a stated constant, so
 *       this number is a <em>rig</em> figure, not a model bill. The report says so in its header.</li>
 *   <li><strong>pass^k</strong> = the fraction of tasks whose k consecutive runs all succeeded, with
 *       k = {@value #PASS_K}. It is stricter than the success rate because one flaky run fails that
 *       whole task.</li>
 * </ul>
 */
final class EvalMetrics {

    /** Repeats per task for pass^k. */
    static final int PASS_K = 3;

    /** The stated character-per-token constant the scripted model uses. Not a tokenizer. */
    static final int CHARS_PER_TOKEN = 4;

    /** Unit price in USD per 1M tokens, as a single stated constant, so the arithmetic is auditable. */
    static final double INPUT_USD_PER_MILLION = 3.0;

    /** @see #INPUT_USD_PER_MILLION */
    static final double OUTPUT_USD_PER_MILLION = 15.0;

    private EvalMetrics() {
    }

    /** The aggregate of many runs: the numbers the report table shows. */
    record Summary(
            int taskCount,
            int runCount,
            double successRate,
            double constraintViolationRate,
            int constraintCheckedRuns,
            int violatedRuns,
            double toolCorrectnessRate,
            double meanSteps,
            double meanLatencyMillis,
            double meanInputTokens,
            double meanOutputTokens,
            double meanCostUsd,
            double totalCostUsd,
            double passK,
            int passKTaskCount,
            Map<String, Bucket> byCategory) {
    }

    /** The same aggregate restricted to one coverage bucket. */
    record Bucket(int tasks, int runs, double successRate, double passK) {
    }

    /** Aggregates verdicts, keyed back to their task, into the report's summary. */
    static Summary summarize(List<EvalTask> tasks, Map<String, List<EvalVerdict>> verdictsByTask) {
        List<EvalVerdict> all = new ArrayList<>();
        for (List<EvalVerdict> runs : verdictsByTask.values()) {
            all.addAll(runs);
        }
        long successes = 0;
        long violations = 0;
        long checked = 0;
        long toolsOk = 0;
        long steps = 0;
        long latencyNanos = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        for (EvalVerdict verdict : all) {
            if (verdict.success()) {
                successes++;
            }
            if (verdict.constraintChecked()) {
                checked++;
            }
            if (verdict.constraintViolated()) {
                violations++;
            }
            if (verdict.toolCorrect()) {
                toolsOk++;
            }
            steps += verdict.toolCalls();
            latencyNanos += verdict.durationNanos();
            inputTokens += verdict.inputTokens();
            outputTokens += verdict.outputTokens();
        }
        int runCount = all.size();
        Map<String, Bucket> byCategory = new LinkedHashMap<>();
        for (EvalTask task : tasks) {
            List<EvalVerdict> runs = verdictsByTask.getOrDefault(task.id(), List.of());
            if (runs.isEmpty()) {
                continue;
            }
            Bucket existing = byCategory.get(task.category());
            int tasksSoFar = existing == null ? 0 : existing.tasks();
            int runsSoFar = existing == null ? 0 : existing.runs();
            long successSoFar = existing == null ? 0 : Math.round(existing.successRate() * existing.runs());
            long passSoFar = existing == null ? 0 : Math.round(existing.passK() * existing.tasks());
            byCategory.put(task.category(), new Bucket(
                    tasksSoFar + 1,
                    runsSoFar + runs.size(),
                    (double) (successSoFar + countSuccesses(runs)) / (runsSoFar + runs.size()),
                    (double) (passSoFar + (allSucceeded(runs) ? 1 : 0)) / (tasksSoFar + 1)));
        }
        long passKTasks = 0;
        for (EvalTask task : tasks) {
            List<EvalVerdict> runs = verdictsByTask.getOrDefault(task.id(), List.of());
            if (!runs.isEmpty() && allSucceeded(runs)) {
                passKTasks++;
            }
        }
        double cost = costUsd(inputTokens, outputTokens);
        return new Summary(
                tasks.size(),
                runCount,
                ratio(successes, runCount),
                checked == 0 ? Double.NaN : (double) violations / checked,
                (int) checked,
                (int) violations,
                ratio(toolsOk, runCount),
                runCount == 0 ? Double.NaN : (double) steps / runCount,
                runCount == 0 ? Double.NaN : latencyNanos / 1_000_000.0 / runCount,
                runCount == 0 ? Double.NaN : (double) inputTokens / runCount,
                runCount == 0 ? Double.NaN : (double) outputTokens / runCount,
                runCount == 0 ? Double.NaN : cost / runCount,
                cost,
                ratio(passKTasks, tasks.size()),
                tasks.size(),
                byCategory);
    }

    private static long countSuccesses(List<EvalVerdict> runs) {
        long count = 0;
        for (EvalVerdict verdict : runs) {
            if (verdict.success()) {
                count++;
            }
        }
        return count;
    }

    /** @return {@code true} when every run of the task succeeded (the pass^k condition). */
    static boolean allSucceeded(List<EvalVerdict> runs) {
        if (runs.isEmpty()) {
            return false;
        }
        for (EvalVerdict verdict : runs) {
            if (!verdict.success()) {
                return false;
            }
        }
        return true;
    }

    /** @return the USD cost of those token counts under the stated price table. */
    static double costUsd(long inputTokens, long outputTokens) {
        return inputTokens / 1_000_000.0 * INPUT_USD_PER_MILLION
                + outputTokens / 1_000_000.0 * OUTPUT_USD_PER_MILLION;
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? Double.NaN : (double) numerator / denominator;
    }

    /** Formats a ratio as a percentage, or {@code n/a} when it has no denominator. */
    static String percent(double ratio) {
        return Double.isNaN(ratio) ? "n/a" : String.format("%.1f%%", ratio * 100);
    }
}
