package com.hmdp.agent.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rule-based verdict for one task run.
 *
 * <p>Every field is computed from observable artefacts - the loop's events, the real tool arguments,
 * the tool results, and the exact {@code distance}/{@code avgPrice} the read path returned - never
 * from the model's own prose. The prose is used for exactly two things a rule engine cannot see:
 * whether the answer names a shop, and whether it claims an action nobody took.</p>
 *
 * @param taskId            the task being judged
 * @param success           {@code true} when the run passes every rule for its judgement type
 * @param intentCovered     the task's intent was expressed (shops named, constraint declared, refusal)
 * @param constraintChecked whether this run had a constraint to verify at all
 * @param constraintViolated {@code true} when a returned shop breaks a declared constraint
 * @param toolCorrect       the tools called were exactly the ones allowed and required
 * @param reasons           why the run failed, in plain language
 * @param violatedShops     the shop ids that broke a constraint, with what they broke
 * @param toolCalls         how many tool calls the run issued
 * @param durationNanos     wall-clock duration of the turn
 * @param inputTokens       tokens the scripted model reported for the whole turn
 * @param outputTokens      tokens the scripted model reported for the whole turn
 */
record EvalVerdict(
        String taskId,
        boolean success,
        boolean intentCovered,
        boolean constraintChecked,
        boolean constraintViolated,
        boolean toolCorrect,
        List<String> reasons,
        List<String> violatedShops,
        int toolCalls,
        long durationNanos,
        int inputTokens,
        int outputTokens) {

    /** A builder so {@link EvalJudge} can accumulate findings without a dozen positional arguments. */
    static final class Builder {

        private final String taskId;
        private final List<String> reasons = new ArrayList<>();
        private final List<String> violatedShops = new ArrayList<>();
        private boolean intentCovered;
        private boolean constraintChecked;
        private boolean constraintViolated;
        private boolean toolCorrect = true;
        private int toolCalls;
        private long durationNanos;
        private int inputTokens;
        private int outputTokens;

        Builder(String taskId) {
            this.taskId = taskId;
        }

        Builder intentCovered(boolean value) {
            this.intentCovered = value;
            return this;
        }

        Builder constraintChecked(boolean value) {
            this.constraintChecked = value;
            return this;
        }

        Builder constraintViolated(boolean value) {
            this.constraintViolated = value;
            return this;
        }

        Builder toolCorrect(boolean value) {
            this.toolCorrect = value;
            return this;
        }

        Builder fail(String reason) {
            reasons.add(reason);
            return this;
        }

        Builder violatedShop(String description) {
            violatedShops.add(description);
            return this;
        }

        Builder counts(int toolCalls, long durationNanos, int inputTokens, int outputTokens) {
            this.toolCalls = toolCalls;
            this.durationNanos = durationNanos;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            return this;
        }

        EvalVerdict build() {
            boolean success = reasons.isEmpty() && intentCovered && toolCorrect && !constraintViolated;
            return new EvalVerdict(taskId, success, intentCovered, constraintChecked, constraintViolated,
                    toolCorrect, List.copyOf(reasons), List.copyOf(violatedShops),
                    toolCalls, durationNanos, inputTokens, outputTokens);
        }
    }

    /** @return a one-line human summary for the run log. */
    String summary() {
        if (success) {
            return "PASS  " + taskId + "  calls=" + toolCalls + "  reasons=[]";
        }
        return "FAIL  " + taskId + "  calls=" + toolCalls + "  reasons=" + reasons;
    }

    /** A stable map for the machine-readable report. */
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("success", success);
        map.put("intentCovered", intentCovered);
        map.put("constraintChecked", constraintChecked);
        map.put("constraintViolated", constraintViolated);
        map.put("toolCorrect", toolCorrect);
        map.put("reasons", reasons);
        map.put("violatedShops", violatedShops);
        map.put("toolCalls", toolCalls);
        map.put("durationMillis", durationNanos / 1_000_000.0);
        map.put("inputTokens", inputTokens);
        map.put("outputTokens", outputTokens);
        return map;
    }
}
