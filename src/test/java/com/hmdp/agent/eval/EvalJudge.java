package com.hmdp.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The rule-based judge: turns one trajectory into a {@link EvalVerdict}.
 *
 * <p>Deliberately not an LLM. Every rule reads an artefact the rig recorded:</p>
 *
 * <ol>
 *   <li><strong>Hard constraints</strong> are checked against the authoritative data the read path
 *       returned ({@code distance}, {@code avgPrice}) or the world's own table - never against what
 *       the answer claims. A shop the model listed beyond the radius is a violation even when the
 *       answer says "都在 500 米内".</li>
 *   <li><strong>Tool correctness</strong> is checked on the real call list: every expected tool was
 *       called, no forbidden tool was called, no unknown tool was called, per-tool minimum counts are
 *       met and the argument bounds hold.</li>
 *   <li><strong>Intent coverage</strong> is checked on the answer text: the required shops appear in
 *       it (by name), the required voucher titles appear, and for no-solution / refusal tasks the
 *       answer reports that instead of inventing a shop.</li>
 * </ol>
 *
 * <p>A run counts as a <em>success</em> only when all three hold and no rule failed. The judge is
 * therefore falsifiable by construction: {@link EvalJudgeTest} feeds it deliberately broken
 * trajectories and asserts it says FAIL, which is what stops it from being vacuously true.</p>
 */
final class EvalJudge {

    private EvalJudge() {
    }

    /**
     * Judges one run.
     *
     * @param task the task the run was for
     * @param world the world the run actually used (the source of distance/price truth)
     * @param run  the recorded trajectory
     * @return the verdict
     */
    static EvalVerdict judge(EvalTask task, EvalWorld world, EvalStack.EvalRun run) {
        EvalVerdict.Builder builder = new EvalVerdict.Builder(task.id());
        long inputTokens = 0;
        long outputTokens = 0;
        for (var usage : run.usages()) {
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
        }
        builder.counts(run.toolCallCount(), run.durationNanos(), (int) inputTokens, (int) outputTokens);

        checkTools(task, run, builder);
        checkConstraints(task, world, run, builder);
        checkIntent(task, world, run, builder);
        return builder.build();
    }

    // ------------------------------------------------------------------ tools

    private static void checkTools(EvalTask task, EvalStack.EvalRun run, EvalVerdict.Builder builder) {
        List<String> called = run.calledToolNames();
        Set<String> distinct = new LinkedHashSet<>(called);

        for (String expected : task.expectedTools()) {
            if (!distinct.contains(expected)) {
                builder.toolCorrect(false);
                builder.fail("该调的工具没调：" + expected + "（实际调用 " + called + "）");
            }
        }
        for (String forbidden : task.forbiddenTools()) {
            if (distinct.contains(forbidden)) {
                builder.toolCorrect(false);
                builder.fail("不该调的工具被调了：" + forbidden);
            }
        }
        for (String name : distinct) {
            if (!isRegistered(name)) {
                builder.toolCorrect(false);
                builder.fail("调用了未知工具：" + name);
            }
        }
        for (EvalTask.MinCalls min : task.minToolCalls()) {
            int actual = countOf(called, min.tool());
            if (actual < min.count()) {
                builder.toolCorrect(false);
                builder.fail(min.tool() + " 调用次数不足：" + actual + " < " + min.count());
            }
        }
        if (run.toolCallCount() > task.toolCallCeiling()) {
            builder.toolCorrect(false);
            builder.fail("工具调用超出上限：" + run.toolCallCount() + " > " + task.toolCallCeiling());
        }
        for (EvalTask.ParamCheck check : task.paramChecks()) {
            checkArgument(task, run, builder, check);
        }
    }

    private static boolean isRegistered(String name) {
        return name.equals("search_nearby") || name.equals("shop_detail") || name.equals("voucher_list");
    }

    private static int countOf(List<String> called, String tool) {
        int count = 0;
        for (String name : called) {
            if (name.equals(tool)) {
                count++;
            }
        }
        return count;
    }

    private static void checkArgument(
            EvalTask task, EvalStack.EvalRun run, EvalVerdict.Builder builder, EvalTask.ParamCheck check) {
        List<JsonNode> arguments = new ArrayList<>();
        for (EvalStack.ObservedCall call : run.calls()) {
            if (call.toolName().equals(check.tool()) && call.arguments() != null) {
                arguments.add(call.arguments());
            }
        }
        if (arguments.isEmpty()) {
            // A missing tool is already reported by the expected-tool rule; do not double-count.
            return;
        }
        List<JsonNode> toCheck = check.allCalls() ? arguments : List.of(arguments.get(0));
        for (JsonNode args : toCheck) {
            JsonNode value = args.path(check.path());
            if (value.isMissingNode()) {
                builder.toolCorrect(false);
                builder.fail("参数缺失：" + check.tool() + "." + check.path() + "（实际 " + args + "）");
                continue;
            }
            if (check.lte() != null && value.isNumber() && value.asInt() > check.lte()) {
                builder.toolCorrect(false);
                builder.fail("参数超界：" + check.tool() + "." + check.path() + "=" + value.asInt()
                        + " > " + check.lte());
            }
            if (check.gte() != null && value.isNumber() && value.asInt() < check.gte()) {
                builder.toolCorrect(false);
                builder.fail("参数过小：" + check.tool() + "." + check.path() + "=" + value.asInt()
                        + " < " + check.gte());
            }
            if (check.equals() != null) {
                String actual = value.isTextual() ? value.asText() : String.valueOf(value.asLong());
                if (!actual.equals(check.equals())) {
                    builder.toolCorrect(false);
                    builder.fail("参数不符：" + check.tool() + "." + check.path() + "=" + actual
                            + " ≠ " + check.equals());
                }
            }
            if (!check.allowed().isEmpty()) {
                String actual = value.isTextual() ? value.asText() : String.valueOf(value.asLong());
                if (!check.allowed().contains(actual)) {
                    builder.toolCorrect(false);
                    builder.fail("参数不在允许集合：" + check.tool() + "." + check.path() + "=" + actual
                            + " ∉ " + check.allowed());
                }
            }
        }
    }

    // ------------------------------------------------------------- constraints

    /**
     * Verifies the hard constraints against the data the read path returned.
     *
     * <p>Two independent sources are combined on purpose. The <em>shops the answer names</em> are
     * checked against the world's own {@code distance}/{@code avgPrice}, which catches a model that
     * presents an out-of-radius shop; the <em>tool results</em> are checked too, which catches a tool
     * that leaked an out-of-radius shop into the model's context in the first place.</p>
     */
    private static void checkConstraints(
            EvalTask task, EvalWorld world, EvalStack.EvalRun run, EvalVerdict.Builder builder) {
        Integer radius = task.numberConstraint(EvalTask.RADIUS);
        Integer budget = task.numberConstraint(EvalTask.BUDGET);
        Integer declaredRadius = task.numberConstraint(EvalTask.SELF_RADIUS);
        boolean selfRadius = task.hasConstraint(EvalTask.SELF_RADIUS);
        String category = task.constraint(EvalTask.CATEGORY) == null
                ? null : task.constraint(EvalTask.CATEGORY).value();

        boolean checkable = radius != null || budget != null || selfRadius || category != null;
        builder.constraintChecked(checkable);
        if (!checkable) {
            return;
        }

        String answer = run.answer();

        // (1) an advertised shop that breaks a hard constraint is a violation, wherever it came from.
        for (long id : world.shopIdsNamedIn(answer)) {
            EvalWorld.EvalShop shop = world.shop(id);
            if (radius != null && shop.distance() > radius) {
                builder.constraintViolated(true);
                builder.violatedShop("回答点名的 " + shop.name() + " 距离 " + shop.distance()
                        + " 米 > 半径 " + radius + " 米");
            }
            if (budget != null && shop.avgPrice() > budget) {
                builder.constraintViolated(true);
                builder.violatedShop("回答点名的 " + shop.name() + " 人均 " + shop.avgPrice()
                        + " 元 > 预算 " + budget + " 元");
            }
            if (category != null && !categoryMatches(shop, category)) {
                builder.constraintViolated(true);
                builder.violatedShop("回答点名的 " + shop.name() + " 品类 " + shop.category()
                        + " 不属于 " + category);
            }
        }

        // (2) a shop the tool returned that breaks a declared constraint means the filter leaked.
        if (radius != null || budget != null || category != null) {
            for (EvalStack.ObservedCall call : run.calls()) {
                if (!call.toolName().equals("search_nearby")) {
                    continue;
                }
                for (long id : searchedShopIds(call.result())) {
                    EvalWorld.EvalShop shop = world.shop(id);
                    if (shop == null) {
                        continue;
                    }
                    if (radius != null && shop.distance() > radius) {
                        builder.constraintViolated(true);
                        builder.violatedShop("搜索结果含超半径的 " + shop.name() + "（" + shop.distance() + " 米）");
                    }
                    if (budget != null && shop.avgPrice() > budget) {
                        builder.constraintViolated(true);
                        builder.violatedShop("搜索结果含超预算的 " + shop.name() + "（" + shop.avgPrice() + " 元）");
                    }
                }
            }
        }

        // (3) a vague "附近" must be made concrete, and stay within the tool's own ceiling.
        if (selfRadius) {
            JsonNode args = run.argumentsOf("search_nearby");
            if (args == null || !args.path("radius").isNumber()) {
                builder.fail("含糊半径的任务没有声明半径（search_nearby 的 radius 缺失）");
            } else {
                int declared = args.path("radius").asInt();
                if (declared < 1 || declared > EvalWorld.MAX_RADIUS_METERS) {
                    builder.fail("声明的半径超出工具上限：" + declared);
                }
                if (task.declaredRadiusMax() != null && declared > task.declaredRadiusMax()) {
                    builder.fail("声明的半径过大：" + declared + " > " + task.declaredRadiusMax());
                }
                for (long id : world.shopIdsNamedIn(answer)) {
                    EvalWorld.EvalShop shop = world.shop(id);
                    if (shop.distance() > declared) {
                        builder.constraintViolated(true);
                        builder.violatedShop("回答点名的 " + shop.name() + " 距离 " + shop.distance()
                                + " 米 > 自己声明的半径 " + declared + " 米");
                    }
                }
            }
        }

        // (4) radius and budget must actually reach the tool, not be applied in prose afterwards.
        if (radius != null) {
            assertArgumentBound(task, run, builder, "search_nearby", "radius", radius);
        }
        if (budget != null) {
            assertArgumentBound(task, run, builder, "search_nearby", "maxPrice", budget);
        }
    }

    private static void assertArgumentBound(
            EvalTask task, EvalStack.EvalRun run, EvalVerdict.Builder builder,
            String tool, String field, int bound) {
        JsonNode args = run.argumentsOf(tool);
        if (args == null) {
            return;
        }
        JsonNode value = args.path(field);
        if (!value.isNumber()) {
            builder.toolCorrect(false);
            builder.fail("约束没有落到工具参数：" + tool + "." + field + " 缺失（实际 " + args + "）");
            return;
        }
        if (value.asInt() > bound) {
            builder.toolCorrect(false);
            builder.fail("工具参数比约束更宽：" + tool + "." + field + "=" + value.asInt() + " > " + bound);
        }
    }

    private static boolean categoryMatches(EvalWorld.EvalShop shop, String category) {
        if (category.contains("与") || category.contains("和") || category.contains("、")) {
            for (String part : category.split("[与和、]")) {
                if (part.trim().equals(shop.category())) {
                    return true;
                }
            }
            return false;
        }
        return shop.category().equals(category);
    }

    /** @return the shop ids a {@code search_nearby} result JSON carried. */
    private static List<Long> searchedShopIds(JsonNode result) {
        List<Long> ids = new ArrayList<>();
        if (result == null || !result.isArray()) {
            return ids;
        }
        for (JsonNode shop : result) {
            JsonNode id = shop.path("shopId");
            if (id.isTextual()) {
                try {
                    ids.add(Long.valueOf(id.asText()));
                } catch (NumberFormatException ignored) {
                    // A non-numeric id simply cannot be looked up in the world.
                }
            } else if (id.isNumber()) {
                ids.add(id.asLong());
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------ intent

    /**
     * Checks the intent expressed by the answer text.
     *
     * <p>These are the only rules that read prose, and each one is a substring test against a value
     * taken from the world or the task file - not a judgement of quality.</p>
     */
    private static void checkIntent(
            EvalTask task, EvalWorld world, EvalStack.EvalRun run, EvalVerdict.Builder builder) {
        String answer = run.answer() == null ? "" : run.answer();

        for (String phrase : task.forbiddenAnswerPhrases()) {
            if (answer.contains(phrase)) {
                builder.fail("回答出现禁止表述：" + phrase);
            }
        }
        for (String phrase : task.requiredAnswerPhrases()) {
            if (!answer.contains(phrase)) {
                builder.fail("回答缺少必需表述：" + phrase);
            }
        }

        switch (task.judgement()) {
            case EvalTask.JUDGE_NO_SOLUTION -> checkNoSolution(task, answer, builder);
            case EvalTask.JUDGE_REFUSE, EvalTask.JUDGE_CLARIFY -> checkNoToolIntent(task, run, answer, builder);
            default -> checkSatisfyAll(task, world, run, answer, builder);
        }
    }

    private static void checkSatisfyAll(
            EvalTask task, EvalWorld world, EvalStack.EvalRun run, String answer, EvalVerdict.Builder builder) {
        List<Long> named = world.shopIdsNamedIn(answer);
        Set<Long> namedSet = new LinkedHashSet<>(named);
        boolean intentOk = true;

        for (long id : task.mustPresentShopIds()) {
            if (!namedSet.contains(id)) {
                intentOk = false;
                builder.fail("回答没有提到应给出的店：" + world.shop(id).name() + "(" + id + ")");
            }
        }
        for (long id : task.forbiddenShopIds()) {
            if (namedSet.contains(id)) {
                builder.fail("回答点名了不该出现的店：" + world.shop(id).name() + "(" + id + ")");
            }
        }
        if (!task.mustPresentCategories().isEmpty()) {
            for (String category : task.mustPresentCategories()) {
                boolean covered = false;
                for (long id : namedSet) {
                    EvalWorld.EvalShop shop = world.shop(id);
                    if (shop != null && shop.category().equals(category)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    intentOk = false;
                    builder.fail("回答没有覆盖品类：" + category);
                }
            }
        }
        if (task.requiredShopCount() > 0 && namedSet.size() < task.requiredShopCount()) {
            intentOk = false;
            builder.fail("回答列出的店太少：" + namedSet.size() + " < " + task.requiredShopCount());
        }
        if (task.minCompliantNamed() != null) {
            int compliant = 0;
            for (long id : namedSet) {
                if (task.compliantShopIds().contains(id)) {
                    compliant++;
                }
            }
            if (compliant < task.minCompliantNamed()) {
                intentOk = false;
                builder.fail("回答里合规的店太少：" + compliant + " < " + task.minCompliantNamed());
            }
        }
        for (String title : task.expectedVoucherTitles()) {
            if (!answer.contains(title)) {
                intentOk = false;
                builder.fail("回答没有带出真实券名：" + title);
            }
        }
        if (Boolean.TRUE.equals(task.expectVouchers()) && task.expectedVoucherTitles().isEmpty()
                && !mentionsAnyVoucher(world, answer)) {
            intentOk = false;
            builder.fail("任务期望回答里有券，但回答没提到任何真实券名");
        }
        if (Boolean.FALSE.equals(task.expectVouchers())) {
            for (String title : world.allVoucherTitles()) {
                if (answer.contains(title)) {
                    intentOk = false;
                    builder.fail("世界数据里该店没有券，回答却报出了券名：" + title);
                }
            }
        }
        builder.intentCovered(intentOk);
    }

    private static boolean mentionsAnyVoucher(EvalWorld world, String answer) {
        for (String title : world.allVoucherTitles()) {
            if (answer.contains(title)) {
                return true;
            }
        }
        return false;
    }

    /** For "no solution" tasks: the answer must say nothing matched, and must not invent a shop. */
    private static void checkNoSolution(EvalTask task, String answer, EvalVerdict.Builder builder) {
        boolean reported = false;
        for (String phrase : task.requiredAnswerPhrases()) {
            if (answer.contains(phrase)) {
                reported = true;
            }
        }
        if (!reported && !containsAnyNegative(answer)) {
            builder.fail("无解任务没有如实报告『没找到』");
        }
        builder.intentCovered(reported || containsAnyNegative(answer));
    }

    /** For chat / out-of-scope tasks: no tool may run, and the answer must address the user. */
    private static void checkNoToolIntent(
            EvalTask task, EvalStack.EvalRun run, String answer, EvalVerdict.Builder builder) {
        if (run.toolCallCount() != 0) {
            builder.toolCorrect(false);
            builder.fail("该任务不该调用任何工具，实际调用了 " + run.toolCallCount() + " 次");
        }
        boolean addressed = !answer.isBlank();
        for (String phrase : task.requiredAnswerPhrases()) {
            if (!answer.contains(phrase)) {
                builder.fail("回答缺少必需表述：" + phrase);
                addressed = false;
            }
        }
        builder.intentCovered(addressed);
    }

    private static boolean containsAnyNegative(String answer) {
        for (String phrase : List.of("没有", "没找到", "未找到", "抱歉", "无", "超出", "不提供", "无法")) {
            if (answer.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
