package com.hmdp.agent.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * The negative control group: trajectories that are broken on purpose.
 *
 * <p>Every evaluation needs a control that <em>must</em> fail, otherwise a judge that always answers
 * PASS looks identical to a judge that works. Each control below breaks exactly one rule of
 * {@link EvalJudge} and records which rule it is supposed to trip, so the suite can assert both that
 * the run failed and that it failed <em>for the intended reason</em>.</p>
 *
 * <p>These controls are part of the run, not a side test: they are written into the same
 * {@code eval/report/results.json} and listed in the same {@code summary.md}, so the machine-readable
 * evidence shows a mixed outcome rather than an all-green table nobody can falsify.</p>
 */
final class EvalControls {

    /** One deliberately broken trajectory and the rule it is intended to trip. */
    record Control(String id, String taskId, String intendedRule, String sabotage,
                   EvalScriptedLlm model) {

        /** Runs the control against its task's world and judges it. */
        EvalVerdict judge() {
            EvalTask task = EvalControls.task(taskId);
            EvalStack stack = EvalStack.forWorld(task.world());
            EvalStack.EvalRun run = stack.run(task.input(), model);
            return EvalJudge.judge(task, stack.world(), run);
        }
    }

    private EvalControls() {
    }

    private static EvalTask task(String id) {
        for (EvalTask task : EvalTask.loadAll()) {
            if (task.id().equals(id)) {
                return task;
            }
        }
        throw new IllegalArgumentException("任务集里没有 " + id);
    }

    private static EvalTask.PlannedCall call(String tool, String argumentsJson) {
        try {
            return new EvalTask.PlannedCall(tool, new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(argumentsJson));
        } catch (Exception broken) {
            throw new IllegalArgumentException(broken);
        }
    }

    private static EvalScriptedLlm.Turn calls(EvalTask.PlannedCall... calls) {
        return EvalScriptedLlm.Turn.tools(List.of(calls));
    }

    /** The full control group: one control per judgeable rule. */
    static List<Control> all() {
        List<Control> controls = new ArrayList<>();
        controls.add(new Control("NC-01", "S01", "约束规则：超半径的店不得被报出",
                "把 640 米的老码头当成 500 米内的结果推荐给用户",
                EvalScriptedLlm.of("NC-01",
                        calls(call("search_nearby", "{\"category\":\"火锅\",\"radius\":500}")),
                        EvalScriptedLlm.Turn.answer("推荐 老码头火锅，人均 150 元，距离 640 米。"))));
        controls.add(new Control("NC-02", "S06", "工具规则：该调的没调",
                "任务要查店铺详情，模型一次工具都没调，直接编答案",
                EvalScriptedLlm.of("NC-02",
                        EvalScriptedLlm.Turn.answer("顺时针火锅在示例市示例路 101 号，人均 88 元。"))));
        controls.add(new Control("NC-03", "S01", "工具规则：参数比约束更宽",
                "用户要 500 米内，模型把 radius 放大到 2000 米",
                EvalScriptedLlm.of("NC-03",
                        calls(call("search_nearby", "{\"category\":\"火锅\",\"radius\":2000}")),
                        EvalScriptedLlm.Turn.answer("顺时针火锅，人均 88 元，距离 120 米。"))));
        controls.add(new Control("NC-04", "S07", "工具规则：不该调的调了 / 参数不符",
                "查 201 号店的券，模型却去查 101 号店并套用它的券名",
                EvalScriptedLlm.of("NC-04",
                        calls(call("voucher_list", "{\"shopId\":101}")),
                        EvalScriptedLlm.Turn.answer("这家有 88元代100元券。"))));
        controls.add(new Control("NC-05", "S07", "意图规则：不得编造券名",
                "模型报出一张世界数据里不存在的券",
                EvalScriptedLlm.of("NC-05",
                        calls(call("voucher_list", "{\"shopId\":201}")),
                        EvalScriptedLlm.Turn.answer("有「五折欢唱券」，全单半价。"))));
        controls.add(new Control("NC-06", "C01", "工具规则：闲聊不该调工具",
                "用户只说『你好』，模型却先搜了一轮",
                EvalScriptedLlm.of("NC-06",
                        calls(call("search_nearby", "{\"category\":\"美食\",\"radius\":1000}")),
                        EvalScriptedLlm.Turn.answer("你好！附近有几家店…"))));
        controls.add(new Control("NC-07", "N01", "意图规则：无解任务不得点名店铺",
                "100 米内确实没有火锅店，模型还是报了顺时针火锅",
                EvalScriptedLlm.of("NC-07",
                        calls(call("search_nearby", "{\"category\":\"火锅\",\"radius\":100}")),
                        EvalScriptedLlm.Turn.answer("好的，附近有 顺时针火锅，人均 88 元。"))));
        controls.add(new Control("NC-08", "S01", "工具规则：不得调用未注册的工具",
                "模型调了一个不存在的写工具 place_order",
                EvalScriptedLlm.of("NC-08",
                        calls(call("place_order", "{\"shopId\":101}")),
                        EvalScriptedLlm.Turn.answer("已为你下单 顺时针火锅。"))));
        controls.add(new Control("NC-09", "N06", "意图规则：工具答不了的维度不得编造",
                "工具不返回营业时间，模型却声称 24 小时营业",
                EvalScriptedLlm.of("NC-09",
                        calls(call("search_nearby", "{\"category\":\"火锅\",\"radius\":3000}")),
                        EvalScriptedLlm.Turn.answer("顺时针火锅，人均 88 元，24小时营业。"))));
        return List.copyOf(controls);
    }

    /** Runs them all and returns the verdicts in order. */
    static List<EvalVerdict> judgeAll() {
        List<EvalVerdict> verdicts = new ArrayList<>();
        for (Control control : all()) {
            verdicts.add(control.judge());
        }
        return List.copyOf(verdicts);
    }
}
