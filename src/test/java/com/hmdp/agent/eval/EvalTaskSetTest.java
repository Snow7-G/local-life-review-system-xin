package com.hmdp.agent.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural checks on the 50-task set.
 *
 * <p>These read the JSON files directly, so a task that names a shop the world does not have, or
 * that expects a tool the registry does not declare, fails here instead of quietly grading every run
 * as a failure. They also pin the coverage mix the task card asks for.</p>
 */
class EvalTaskSetTest {

    /** The task card's coverage buckets and how many of each the set must hold. */
    private static final List<String> REQUIRED_BUCKETS = List.of(
            "single_constraint", "multi_constraint", "ambiguous", "no_solution", "chit_chat", "injection");

    @Test
    void theSetHoldsFiftyUniqueTasksAcrossAllSixBuckets() {
        List<EvalTask> tasks = EvalTask.loadAll();
        assertEquals(50, tasks.size(), "任务集必须正好 50 条");

        Set<String> ids = new LinkedHashSet<>();
        for (EvalTask task : tasks) {
            assertTrue(ids.add(task.id()), "任务 id 重复：" + task.id());
        }

        Set<String> buckets = new LinkedHashSet<>();
        for (EvalTask task : tasks) {
            buckets.add(task.category());
        }
        assertEquals(new LinkedHashSet<>(REQUIRED_BUCKETS), buckets, "覆盖桶必须正好是这六类");

        for (String bucket : REQUIRED_BUCKETS) {
            long count = tasks.stream().filter(task -> task.category().equals(bucket)).count();
            assertTrue(count >= 6, "桶 " + bucket + " 太薄，只有 " + count + " 条");
        }
    }

    @Test
    void everyTaskIsAnswerableWithTheThreeReadOnlyTools() {
        EvalWorld world = EvalWorld.main();
        Set<String> registered = Set.of("search_nearby", "shop_detail", "voucher_list");
        for (EvalTask task : EvalTask.loadAll()) {
            for (String tool : task.expectedTools()) {
                assertTrue(registered.contains(tool), task.id() + " 期望了未注册的工具：" + tool);
            }
            for (String tool : task.forbiddenTools()) {
                assertTrue(registered.contains(tool), task.id() + " 禁用了未注册的工具：" + tool);
            }
            for (EvalTask.PlannedCall call : task.plan()) {
                assertTrue(registered.contains(call.tool()),
                        task.id() + " 的剧本用了未注册的工具：" + call.tool());
            }
            // A task with no expected tool must be an explicitly tool-free one.
            if (task.expectedTools().isEmpty()) {
                assertFalse(task.plan().stream().anyMatch(call -> !call.tool().isEmpty()),
                        task.id() + " 没有 expectedTools 却写了工具剧本");
            }
            // Every named shop must exist in the world the task runs against.
            EvalWorld taskWorld = EvalWorld.named(task.world());
            for (long id : task.mustPresentShopIds()) {
                assertTrue(taskWorld.shop(id) != null, task.id() + " 点名了不存在的店：" + id);
            }
            for (long id : task.compliantShopIds()) {
                assertTrue(taskWorld.shop(id) != null, task.id() + " 的合规集含不存在的店：" + id);
            }
            for (long id : task.forbiddenShopIds()) {
                assertTrue(world.shop(id) != null, task.id() + " 的禁用集含不存在的店：" + id);
            }
            assertFalse(task.reason().isBlank(), task.id() + " 缺少入库理由");
        }
    }

    /**
     * The compliant set in every file must equal what the catalogue actually yields for that task's
     * numeric constraints. This is the check that keeps the data file and the world from drifting
     * apart: change a shop's distance in {@link EvalWorld} without regenerating the tasks and this
     * fails, instead of silently grading against a stale expectation.
     *
     * <p>Only tasks whose expectation is a fixed catalogue query are checked here: the numeric
     * buckets (single/multi constraint) and the no-solution tasks. The ambiguous bucket is
     * deliberately open-ended - its expectation is "the answer must stay inside whatever the run
     * declared" - and the chat/injection buckets have no shop set at all, so all three are validated
     * by their own tests instead.</p>
     */
    @Test
    void compliantSetsMatchTheWorldForEveryFullyNumericTask() {
        EvalWorld world = EvalWorld.main();
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (EvalTask task : EvalTask.loadAll()) {
            if (task.constraints().isEmpty() || task.hasConstraint(EvalTask.SELF_RADIUS)
                    || task.hasConstraint(EvalTask.HAS_VOUCHER)) {
                continue;
            }
            if (!List.of("single_constraint", "multi_constraint", "no_solution").contains(task.category())) {
                continue;
            }
            if (!EvalTask.JUDGE_SATISFY_ALL.equals(task.judgement())
                    && !EvalTask.JUDGE_NO_SOLUTION.equals(task.judgement())) {
                continue;
            }
            Integer radius = task.numberConstraint(EvalTask.RADIUS);
            Integer budget = task.numberConstraint(EvalTask.BUDGET);
            EvalTask.Constraint category = task.constraint(EvalTask.CATEGORY);
            if (radius == null && budget == null && category == null) {
                continue;
            }
            List<Long> expected = new ArrayList<>();
            for (EvalWorld.EvalShop shop : world.shops()) {
                if (radius != null && shop.distance() > radius) {
                    continue;
                }
                if (budget != null && shop.avgPrice() > budget) {
                    continue;
                }
                if (category != null && !category.value().equals(shop.category())) {
                    String value = category.value();
                    List<String> parts = new ArrayList<>();
                    for (String part : value.split("[与和、]")) {
                        parts.add(part.trim());
                    }
                    if (!parts.contains(shop.category())) {
                        continue;
                    }
                }
                expected.add(shop.id());
            }
            checked++;
            List<Long> reported = new ArrayList<>(task.compliantShopIds());
            if (!expected.equals(reported)) {
                mismatches.add(task.id() + " 期望 " + expected + "，数据文件写的是 " + reported);
            }
        }
        assertTrue(checked >= 20, "被核验的任务太少，只查了 " + checked + " 条");
        assertTrue(mismatches.isEmpty(), "任务集与世界数据不一致：\n" + String.join("\n", mismatches));
    }

    @Test
    void everyJudgementTypeIsExercised() {
        Set<String> judgements = new LinkedHashSet<>();
        for (EvalTask task : EvalTask.loadAll()) {
            judgements.add(task.judgement());
        }
        assertTrue(judgements.contains(EvalTask.JUDGE_SATISFY_ALL), "缺少 satisfy_all 任务");
        assertTrue(judgements.contains(EvalTask.JUDGE_NO_SOLUTION), "缺少 no_solution 任务");
        assertTrue(judgements.contains(EvalTask.JUDGE_CLARIFY), "缺少 clarify 任务");
        assertTrue(judgements.contains(EvalTask.JUDGE_REFUSE), "缺少 refuse 任务");
    }
}
