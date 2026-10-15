package com.hmdp.agent.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the task set through the real read-only chain and judges every run.
 *
 * <p>Segment A only: the model is always {@link EvalScriptedLlm}, so no request ever leaves the
 * process. The runner is the only place that knows about {@code pass^k} - it repeats each task
 * {@link EvalMetrics#PASS_K} times, judging each run independently, and hands the collected verdicts
 * to {@link EvalMetrics#summarize}.</p>
 */
final class EvalRunner {

    /** The whole evaluation: every task, every repeat, plus the aggregate. */
    record EvalResult(
            List<EvalTask> tasks,
            Map<String, List<EvalVerdict>> verdictsByTask,
            List<EvalStack.EvalRun> runsByTask,
            EvalMetrics.Summary summary) {

        /** Every verdict, flattened in task order. */
        List<EvalVerdict> allVerdicts() {
            List<EvalVerdict> all = new ArrayList<>();
            for (EvalTask task : tasks) {
                all.addAll(verdictsByTask.getOrDefault(task.id(), List.of()));
            }
            return all;
        }
    }

    private final List<EvalTask> tasks;

    EvalRunner(List<EvalTask> tasks) {
        this.tasks = List.copyOf(tasks);
    }

    /** The default: every task in the set. */
    static EvalRunner defaultSet() {
        return new EvalRunner(EvalTask.loadAll());
    }

    /** Runs every task {@code repeats} times with the faithful scripted model. */
    EvalResult runAll(int repeats) {
        return runAll(repeats, EvalRunner::scriptedModelFor);
    }

    /**
     * Runs every task {@code repeats} times, judging each run.
     *
     * <p>The model factory is a parameter so a test can swap in a deliberately broken trajectory -
     * which is how the falsifiability suite shows the judge reacts to failure instead of always
     * saying PASS.</p>
     *
     * @param repeats how many runs per task ({@code pass^k})
     * @param models  the model to use for a task
     */
    EvalResult runAll(int repeats, java.util.function.Function<EvalTask, EvalScriptedLlm> models) {
        Map<String, List<EvalVerdict>> verdictsByTask = new LinkedHashMap<>();
        List<EvalStack.EvalRun> firstRuns = new ArrayList<>();
        for (EvalTask task : tasks) {
            EvalStack stack = EvalStack.forWorld(task.world());
            List<EvalVerdict> verdicts = new ArrayList<>();
            for (int repeat = 0; repeat < repeats; repeat++) {
                EvalStack.EvalRun run = stack.run(task.input(), models.apply(task));
                verdicts.add(EvalJudge.judge(task, stack.world(), run));
                if (repeat == 0) {
                    firstRuns.add(run);
                }
            }
            verdictsByTask.put(task.id(), List.copyOf(verdicts));
        }
        EvalMetrics.Summary summary = EvalMetrics.summarize(tasks, verdictsByTask);
        return new EvalResult(tasks, Map.copyOf(verdictsByTask), List.copyOf(firstRuns), summary);
    }

    /** Runs every task once - the smoke pass - with the faithful scripted model. */
    EvalResult runOnce() {
        return runAll(1);
    }

    /**
     * The scripted model for a task.
     *
     * <p>A tool-free task (chat, refusal) gets its own written answer and no tool calls - there is no
     * tool result to render. Every other task gets the trajectory its task file declares, followed by
     * an answer rendered from the JSON the tools actually returned, so the answer can only contain
     * facts the read path produced. Both are scripted - see {@link EvalScriptedLlm}.</p>
     */
    static EvalScriptedLlm scriptedModelFor(EvalTask task) {
        if (task.toolFree()) {
            return EvalScriptedLlm.faithful(task, task.scriptedAnswer() == null ? "" : task.scriptedAnswer());
        }
        return EvalScriptedLlm.expressing(task);
    }
}
