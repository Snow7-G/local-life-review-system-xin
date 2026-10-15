package com.hmdp.agent.eval;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evaluation rig end to end: all 50 tasks, driven through the real read-only chain by the
 * scripted LLM, judged by rules, and written out as a report.
 *
 * <p><strong>Segment A only.</strong> Every number this test produces comes from
 * {@link EvalScriptedLlm}, which replays a fixed trajectory and makes no network request, so the
 * report it writes is a statement about the rig and the judge - never a model score. The report's own
 * header says so.</p>
 */
class ReadOnlyEvalTest {

    /** pass^k repeats per task; the same constant the report prints. */
    private static final int REPEATS = EvalMetrics.PASS_K;

    @Test
    void allFiftyTasksRunThroughTheRealReadOnlyChainAndEveryRunIsJudged() {
        EvalRunner.EvalResult result = EvalRunner.defaultSet().runAll(REPEATS);

        assertEquals(50, result.tasks().size(), "任务集应恰好 50 条");
        assertEquals(50 * REPEATS, result.summary().runCount(),
                "每条任务应产出 pass^k 次运行");

        // Every run has a verdict, and every verdict carries the numbers the report needs.
        for (EvalTask task : result.tasks()) {
            List<EvalVerdict> verdicts = result.verdictsByTask().get(task.id());
            assertTrue(verdicts != null && verdicts.size() == REPEATS,
                    task.id() + " 的运行数应为 " + REPEATS);
            for (EvalVerdict verdict : verdicts) {
                assertFalse(Double.isNaN(verdict.durationNanos()),
                        task.id() + " 没有记录到耗时");
            }
        }

        // The rig really exercised the production chain: the adapter was asked for shop types and
        // the search path ran through it, not through a stub of the adapter.
        assertTrue(result.summary().meanSteps() > 0, "平均步数应大于 0");
        assertTrue(result.summary().meanLatencyMillis() >= 0, "平均延迟应可测量");
    }

    @Test
    void theScriptedBaselinePassesEveryTaskExactlyOnce() {
        EvalRunner.EvalResult result = EvalRunner.defaultSet().runOnce();
        List<String> failures = new ArrayList<>();
        for (EvalVerdict verdict : result.allVerdicts()) {
            if (!verdict.success()) {
                failures.add(verdict.taskId() + " -> " + verdict.reasons());
            }
        }
        assertTrue(failures.isEmpty(),
                "脚本化忠实轨迹应当全绿，说明评测台与判分器都按预期工作；失败项：\n"
                        + String.join("\n", failures));
        assertEquals(1.0, result.summary().successRate(), 1e-9);
        assertEquals(1.0, result.summary().toolCorrectnessRate(), 1e-9);
        assertEquals(0.0, result.summary().constraintViolationRate(), 1e-9);
        assertEquals(1.0, result.summary().passK(), 1e-9);
    }

    @Test
    void theReadOnlyChainNeverTouchesAWriteMethod() {
        // One world reused for every task so the recorded service calls cover the whole run.
        EvalWorld world = EvalWorld.main();
        EvalStack stack = new EvalStack(world);
        for (EvalTask task : EvalTask.loadAll()) {
            if (task.toolFree()) {
                continue;
            }
            stack.run(task.input(), EvalRunner.scriptedModelFor(task));
        }
        assertTrue(stack.services().writeCalls().isEmpty(),
                () -> "只读链路调到了写方法：" + stack.services().writeCalls());
        // Sanity: the read path really did run, so an empty write list is not vacuous.
        assertTrue(stack.services().shop.calls().contains("quaryByShop"),
                "search_nearby 应经真实适配器落到假服务");
        assertTrue(stack.services().shopType.calls().contains("quaryById"),
                "品类解析应经真实适配器落到假服务");
    }

    @Test
    void theReportIsWrittenWithTheScriptedLlmDisclaimer() throws IOException {
        EvalRunner.EvalResult result = EvalRunner.defaultSet().runAll(REPEATS);
        Path summaryPath = EvalReport.write(result, REPEATS);

        assertTrue(Files.exists(summaryPath), "汇总表应落在 eval/report/");
        String summary = Files.readString(summaryPath, StandardCharsets.UTF_8);
        assertTrue(summary.contains(EvalReport.DISCLAIMER),
                () -> "汇总表必须带醒目声明，实际未找到：" + EvalReport.DISCLAIMER);
        assertTrue(summary.contains("非真实模型分数"), "声明必须写明不是真实模型分数");
        assertFalse(summary.contains("真实模型分数："), "不得出现『真实模型分数：…』这类可被误引的写法");

        Path resultsPath = EvalReport.REPORT_DIR.resolve("results.json");
        assertTrue(Files.exists(resultsPath), "机器可读结果应落在 eval/report/results.json");
        String json = Files.readString(resultsPath, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"disclaimer\""), "results.json 必须带声明字段");
        assertTrue(json.contains("\"scripted\" : true"), "每条轨迹都要标为脚本化");
        assertTrue(json.contains("\"everyRunScripted\" : true"), "整份结果必须声明全部轨迹都是脚本生成的");
        assertTrue(json.contains("negativeControls"), "报告必须包含负对照组");
        assertTrue(summary.contains("负对照组"), "汇总表必须列出负对照组");
        assertFalse(json.contains("\"realModel\": true"), "本轮不得声称是真实模型结果");
    }

    /**
     * The judge is only trustworthy if broken trajectories really fail, so the rig is also run with a
     * sabotaged model: every task's script is replaced by one that names a shop the search never
     * returned. The aggregate must then drop below the scripted baseline instead of staying at 100%.
     */
    @Test
    void sabotagedTrajectoriesDragTheAggregateDown() {
        EvalRunner.EvalResult honest = EvalRunner.defaultSet().runOnce();
        EvalRunner.EvalResult sabotaged = EvalRunner.defaultSet().runAll(1, task -> EvalScriptedLlm.of(
                task.id(), EvalScriptedLlm.Turn.answer("推荐 海底捞，人均 200 元，距离 4800 米，超值！")));

        double honestRate = honest.summary().successRate();
        double sabotagedRate = sabotaged.summary().successRate();

        assertEquals(1.0, honestRate, 1e-9, "忠实轨迹应 100% 通过");
        assertTrue(sabotagedRate < 0.25,
                () -> "被破坏的轨迹必须大幅拉低成功率，实际 " + sabotagedRate);
        assertTrue(sabotagedRate < honestRate, "破坏版必须低于忠实版，否则判分器恒真");
    }

    /**
     * The negative control group must fail - every one of them - and each must trip the rule it was
     * built to trip. This is the assertion that the summary table's greens mean something: the same
     * judge that passes 50 faithful trajectories also rejects these.
     */
    @Test
    void everyNegativeControlIsJudgedAsAFailure() {
        List<EvalVerdict> verdicts = EvalControls.judgeAll();
        assertEquals(EvalControls.all().size(), verdicts.size());

        List<String> escaped = new ArrayList<>();
        for (EvalVerdict verdict : verdicts) {
            if (verdict.success()) {
                escaped.add(verdict.taskId());
            }
            assertFalse(verdict.reasons().isEmpty(),
                    verdict.taskId() + " 被判失败却没有给出原因");
        }
        assertTrue(escaped.isEmpty(), "这些故意破坏的轨迹竟然通过了判分：" + escaped);

        // The controls must not all trip the same rule, or the suite would only be testing one path.
        Set<String> distinctReasons = new LinkedHashSet<>();
        for (EvalVerdict verdict : verdicts) {
            distinctReasons.add(verdict.reasons().get(0));
        }
        assertTrue(distinctReasons.size() >= 6,
                () -> "负对照应触发多种不同的规则，实际只有 " + distinctReasons.size() + " 种：" + distinctReasons);
    }

    /** The report must name the coverage mix, since the task card requires the ratio to be documented. */
    @Test
    void theReportDocumentsEveryCoverageBucket() throws IOException {
        // Written with the same k as the other report test so the artefact does not depend on the
        // order JUnit happens to pick: whichever runs last leaves the same file behind.
        EvalRunner.EvalResult result = EvalRunner.defaultSet().runAll(REPEATS);
        Path summaryPath = EvalReport.write(result, REPEATS);
        String summary = Files.readString(summaryPath, StandardCharsets.UTF_8);

        Set<String> buckets = new LinkedHashSet<>(result.summary().byCategory().keySet());
        assertEquals(6, buckets.size(), "报告应覆盖六个桶，实际 " + buckets);
        for (String bucket : buckets) {
            assertTrue(summary.contains(bucket), "汇总表缺少桶：" + bucket);
        }
        for (String column : List.of("任务成功率", "约束违反率", "工具调用正确率", "平均步数",
                "平均延迟", "单次成本", "pass^")) {
            assertTrue(summary.contains(column), "汇总表缺少指标行：" + column);
        }
    }
}
