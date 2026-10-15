package com.hmdp.agent.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the judge is falsifiable rather than a rubber stamp.
 *
 * <p>This is the load-bearing test of A 段: a judge that always says PASS would make the whole
 * evaluation worthless, so every rule gets a deliberately broken trajectory here and the test asserts
 * the judge says FAIL <em>and says why</em>. The mirror image is checked too - the faithful
 * trajectories must pass - so a judge that always says FAIL is caught as well.</p>
 *
 * <p>Each case drives the real read-only chain. The breakage is in the scripted trajectory (a wrong
 * argument, a forbidden tool, a shop the search never returned, an invented voucher), never in the
 * W2-6 wiring or the production adapter.</p>
 */
class EvalJudgeTest {

    private static EvalTask task(String id) {
        for (EvalTask task : EvalTask.loadAll()) {
            if (task.id().equals(id)) {
                return task;
            }
        }
        throw new IllegalArgumentException("任务集里没有 " + id);
    }

    private static EvalStack.EvalRun run(EvalTask task, EvalScriptedLlm.Turn... turns) {
        EvalStack stack = EvalStack.forWorld(task.world());
        return stack.run(task.input(), EvalScriptedLlm.of(task.id(), turns));
    }

    private static EvalVerdict judge(EvalTask task, EvalStack.EvalRun run) {
        return EvalJudge.judge(task, EvalWorld.named(task.world()), run);
    }

    private static EvalScriptedLlm.Turn calls(EvalTask.PlannedCall... calls) {
        return EvalScriptedLlm.Turn.tools(List.of(calls));
    }

    /** A planned call whose arguments the test writes inline, the way a model would stream them. */
    private static EvalTask.PlannedCall call(String tool, String argumentsJson) {
        return new EvalTask.PlannedCall(tool, json(argumentsJson));
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
        } catch (Exception broken) {
            throw new IllegalArgumentException(broken);
        }
    }

    // ------------------------------------------------------------- the mirror

    @Test
    void theFaithfulTrajectoryOfEveryConstrainedTaskPasses() {
        List<String> failures = new ArrayList<>();
        for (EvalTask task : EvalTask.loadAll()) {
            List<EvalScriptedLlm.Turn> turns = EvalScriptedLlm.turnsFor(task, null);
            EvalStack.EvalRun run = run(task, turns.toArray(new EvalScriptedLlm.Turn[0]));
            EvalVerdict verdict = judge(task, run);
            if (!verdict.success()) {
                failures.add(task.id() + " -> " + verdict.reasons());
            }
        }
        assertTrue(failures.isEmpty(), "忠实轨迹竟然判失败，判分器或任务集有问题：\n" + String.join("\n", failures));
    }

    // ------------------------------------------------- failures must be caught

    @Test
    void aShopBeyondTheRadiusIsAViolation() {
        EvalTask task = task("S01");
        EvalStack.EvalRun run = run(task,
                calls(call("search_nearby", "{\"category\":\"火锅\",\"radius\":500}")),
                EvalScriptedLlm.Turn.answer("给你推荐 老码头火锅，人均 150 元，距离 640 米。"));
        EvalVerdict verdict = judge(task, run);

        assertFalse(verdict.success(), "超半径的店被报出来，必须判失败");
        assertTrue(verdict.constraintViolated(), "应记为约束违反");
        assertTrue(verdict.violatedShops().stream().anyMatch(text -> text.contains("老码头火锅")),
                () -> "违反项应点名老码头火锅，实际 " + verdict.violatedShops());
        assertFalse(verdict.intentCovered(), "应给出的 101 没出现，意图未覆盖");
    }

    @Test
    void callingAForbiddenToolIsAToolError() {
        EvalTask task = task("S01");
        EvalStack.EvalRun run = run(task,
                calls(call("search_nearby", "{\"category\":\"火锅\",\"radius\":500}")),
                calls(call("shop_detail", "{\"shopId\":101}")),
                EvalScriptedLlm.Turn.answer("顺时针火锅，人均 88 元，距离 120 米。"));
        EvalVerdict verdict = judge(task, run);

        assertFalse(verdict.success());
        assertFalse(verdict.toolCorrect());
        assertTrue(verdict.reasons().stream().anyMatch(text -> text.contains("shop_detail")),
                () -> "失败原因应点名 shop_detail，实际 " + verdict.reasons());
    }

    @Test
    void skippingTheExpectedToolIsAToolError() {
        EvalTask task = task("S06");
        EvalStack.EvalRun run = run(task,
                EvalScriptedLlm.Turn.answer("顺时针火锅，示例市示例路 101 号，人均 88 元。"));
        EvalVerdict verdict = judge(task, run);

        assertFalse(verdict.success());
        assertFalse(verdict.toolCorrect());
        assertTrue(verdict.reasons().stream().anyMatch(text -> text.contains("shop_detail")),
                () -> "应报告 shop_detail 没被调用，实际 " + verdict.reasons());
    }

    @Test
    void aWidthThatExceedsTheConstraintIsCaughtOnTheToolArguments() {
        EvalTask task = task("S01");
        EvalStack.EvalRun run = run(task,
                calls(call("search_nearby", "{\"category\":\"火锅\",\"radius\":2000}")),
                EvalScriptedLlm.Turn.answer("顺时针火锅，人均 88 元，距离 120 米。"));
        EvalVerdict verdict = judge(task, run);

        assertFalse(verdict.success(), "半径参数比约束更宽，必须判失败");
        assertTrue(verdict.reasons().stream().anyMatch(text -> text.contains("radius")),
                () -> "应报告半径参数超界，实际 " + verdict.reasons());
    }

    @Test
    void aWrongShopIdArgumentIsCaught() {
        EvalTask task = task("S07");
        EvalStack.EvalRun run = run(task,
                calls(call("voucher_list", "{\"shopId\":101}")),
                EvalScriptedLlm.Turn.answer("这家店有 88元代100元券，39 元抵 60 元。"));
        EvalVerdict verdict = judge(task, run);

        assertFalse(verdict.success(), "查错店 id，必须判失败");
        assertTrue(verdict.reasons().stream().anyMatch(text -> text.contains("shopId")),
                () -> "应报告 shopId 参数不符，实际 " + verdict.reasons());
    }

    @Test
    void anInventedVoucherTitleIsCaught() {
        EvalTask task = task("S07");
        EvalStack.EvalRun run = run(task,
                calls(call("voucher_list", "{\"shopId\":201}")),
                EvalScriptedLlm.Turn.answer("麦乐迪KTV 现在有「五折欢唱券」，全单半价。"));
        EvalVerdict verdict = judge(task, run);

        assertFalse(verdict.success(), "编造的券名必须判失败");
        assertTrue(verdict.reasons().stream().anyMatch(text -> text.contains("券名")),
                () -> "应报告缺少真实券名，实际 " + verdict.reasons());
    }

    @Test
    void namingAShopOnANoSolutionsTaskFails() {
        EvalTask task = task("N01");
        EvalStack.EvalRun run = run(task,
                calls(call("search_nearby", "{\"category\":\"火锅\",\"radius\":100}")),
                EvalScriptedLlm.Turn.answer("鼸了的，附近有 顺时针火锅，人均 88 元。"));
        EvalVerdict verdict = judge(task, run);

        assertFalse(verdict.success(), "无解任务点名了店铺，必须判失败");
        assertTrue(verdict.reasons().stream().anyMatch(text -> text.contains("顺时针火锅"))
                        || verdict.violatedShops().stream().anyMatch(text -> text.contains("顺时针火锅")),
                () -> "应点名顺时针火锅，reasons=" + verdict.reasons()
                        + " violations=" + verdict.violatedShops());
    }

    @Test
    void reportingNothingFoundIsAcceptedOnANoSolutionsTask() {
        EvalTask task = task("N01");
        EvalStack.EvalRun run = run(task,
                calls(call("search_nearby", "{\"category\":\"火锅\",\"radius\":100}")),
                EvalScriptedLlm.Turn.answer("抱歉，100 米内没有找到符合条件火锅店。"));
        EvalVerdict verdict = judge(task, run);

        assertTrue(verdict.success(), () -> "如实报告没找到应当通过，实际 " + verdict.reasons());
        assertEquals(0, verdict.violatedShops().size());
    }

    @Test
    void callingAnyToolOnAChatTaskFails() {
        EvalTask task = task("C01");
        EvalStack.EvalRun run = run(task,
                calls(call("search_nearby", "{\"category\":\"美食\",\"radius\":1000}")),
                EvalScriptedLlm.Turn.answer("你好！附近有几家店…"));
        EvalVerdict verdict = judge(task, run);

        assertFalse(verdict.success(), "闲聊任务调了工具，必须判失败");
        assertFalse(verdict.toolCorrect());
        assertTrue(verdict.reasons().stream().anyMatch(text -> text.contains("不该调用")),
                () -> "应报告不该调用工具，实际 " + verdict.reasons());
    }

    @Test
    void anUnknownToolNameIsCaught() {
        EvalTask task = task("S01");
        EvalStack.EvalRun run = run(task,
                calls(call("place_order", "{\"shopId\":101}")),
                EvalScriptedLlm.Turn.answer("顺时针火锅，人均 88 元。"));
        EvalVerdict verdict = judge(task, run);

        assertFalse(verdict.success(), "调用了未注册的写工具，必须判失败");
        assertTrue(verdict.reasons().stream().anyMatch(text -> text.contains("未知工具")
                        || text.contains("place_order")),
                () -> "应报告未知工具，实际 " + verdict.reasons());
    }

    @Test
    void anInventedOpeningHoursClaimIsCaught() {
        EvalTask task = task("N06");
        EvalStack.EvalRun run = run(task,
                calls(call("search_nearby", "{\"category\":\"火锅\",\"radius\":3000}")),
                EvalScriptedLlm.Turn.answer("顺时针火锅，人均 88 元，24小时营业，随时可以去。"));
        EvalVerdict verdict = judge(task, run);

        assertFalse(verdict.success(), "工具答不了的营业时间被编出来，必须判失败");
        assertTrue(verdict.reasons().stream().anyMatch(text -> text.contains("营业")),
                () -> "应报告禁止表述，实际 " + verdict.reasons());
    }

    /**
     * The summary of the falsification suite: each broken trajectory must land as a distinct failure
     * reason, which is what makes the judge a set of rules rather than a coin flip.
     */
    @Test
    void theJudgeDistinguishesSeveralIndependentFailureModes() {
        List<String> failingReasons = new ArrayList<>();
        failingReasons.add(failureReasonOf("S01",
                calls(call("search_nearby", "{\"category\":\"火锅\",\"radius\":500}")),
                EvalScriptedLlm.Turn.answer("推荐 老码头火锅，640 米。")));
        failingReasons.add(failureReasonOf("S01",
                calls(call("search_nearby", "{\"category\":\"火锅\",\"radius\":500}")),
                calls(call("voucher_list", "{\"shopId\":101}")),
                EvalScriptedLlm.Turn.answer("顺时针火锅，人均 88 元。")));
        failingReasons.add(failureReasonOf("S06",
                EvalScriptedLlm.Turn.answer("顺时针火锅，人均 88 元。")));
        failingReasons.add(failureReasonOf("C01",
                calls(call("search_nearby", "{\"category\":\"美食\",\"radius\":1000}")),
                EvalScriptedLlm.Turn.answer("你好！")));

        assertEquals(4, failingReasons.size());
        for (String reason : failingReasons) {
            assertFalse(reason.isBlank(), "失败的轨迹必须给出原因，不能只有 PASS/FAIL");
        }
        assertEquals(4, new java.util.LinkedHashSet<>(failingReasons).size(),
                () -> "四种破坏应产生四种不同的失败原因，实际 " + failingReasons);
    }

    private static String failureReasonOf(String taskId, EvalScriptedLlm.Turn... turns) {
        EvalTask task = task(taskId);
        EvalVerdict verdict = judge(task, run(task, turns));
        assertFalse(verdict.success(), taskId + " 的破坏轨迹竟然判为通过");
        return String.join(" | ", verdict.reasons());
    }
}
