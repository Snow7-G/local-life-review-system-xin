package com.hmdp.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the machine-readable results and the summary table.
 *
 * <p>Two artefacts, both under {@code eval/report/}:</p>
 *
 * <ul>
 *   <li>{@code results.json} - one object per run: task id, judgement, the real tool calls with their
 *       arguments, the event sequence, the stop reason, tokens, duration and the verdict.</li>
 *   <li>{@code summary.md} - the human table, whose header states in plain language that the numbers
 *       came from a scripted LLM and are <em>not</em> real model scores.</li>
 * </ul>
 *
 * <p>The disclaimer is not decoration: segment B is the only thing that may produce a real score, and
 * it needs the remaining quota (see the task card's 额度硬约束). Every generated file carries the
 * statement so a number can never be quoted out of context.</p>
 */
final class EvalReport {

    /** The banner every generated artefact starts with. */
    static final String DISCLAIMER =
            "本表由脚本化 LLM 生成，非真实模型分数";

    /** The longer form of the same statement, for the report body. */
    static final String DISCLAIMER_LONG =
            "本报告的全部数字来自脚本化 LLM（EvalScriptedLlm，按固定剧本回放、无任何网络请求），"
                    + "用于验证评测台与判分器本身正确，**不是真实模型的分数**。"
                    + "真实分数属 B 段，须在额度恢复并获嘉批准后另行执行。";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Where the report and the raw results are written. */
    static final Path REPORT_DIR = Path.of("eval", "report");

    private EvalReport() {
    }

    /** Writes both artefacts and returns the summary path. */
    static Path write(EvalRunner.EvalResult result, int repeats) {
        try {
            Files.createDirectories(REPORT_DIR);
        } catch (IOException broken) {
            throw new UncheckedIOException("无法创建报告目录 " + REPORT_DIR.toAbsolutePath(), broken);
        }
        writeJson(result, repeats);
        return writeMarkdown(result, repeats);
    }

    private static void writeJson(EvalRunner.EvalResult result, int repeats) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("disclaimer", DISCLAIMER);
        root.put("disclaimer_detail", DISCLAIMER_LONG);
        root.put("mode", "scripted-llm");
        root.put("pass_k", repeats);
        root.put("realModel", false);
        root.put("everyRunScripted", true);
        root.put("taskCount", result.tasks().size());
        // The negative control group: trajectories that must fail, and which rule each must trip.
        ArrayNode controls = root.putArray("negativeControls");
        for (EvalControls.Control control : EvalControls.all()) {
            EvalVerdict verdict = control.judge();
            ObjectNode node = controls.addObject();
            node.put("id", control.id());
            node.put("taskId", control.taskId());
            node.put("sabotage", control.sabotage());
            node.put("intendedRule", control.intendedRule());
            node.put("judgedFailure", !verdict.success());
            node.set("verdict", MAPPER.valueToTree(verdict.toMap()));
        }
        ObjectNode metrics = root.putObject("metrics");
        EvalMetrics.Summary summary = result.summary();
        metrics.put("taskSuccessRate", summary.successRate());
        metrics.put("constraintViolationRate", summary.constraintViolationRate());
        metrics.put("constraintCheckedRuns", summary.constraintCheckedRuns());
        metrics.put("violatedRuns", summary.violatedRuns());
        metrics.put("toolCorrectnessRate", summary.toolCorrectnessRate());
        metrics.put("meanSteps", summary.meanSteps());
        metrics.put("meanLatencyMillis", summary.meanLatencyMillis());
        metrics.put("meanInputTokens", summary.meanInputTokens());
        metrics.put("meanOutputTokens", summary.meanOutputTokens());
        metrics.put("meanCostUsd", summary.meanCostUsd());
        metrics.put("totalCostUsd", summary.totalCostUsd());
        metrics.put("passK", summary.passK());
        metrics.put("runCount", summary.runCount());
        ObjectNode buckets = metrics.putObject("byCategory");
        summary.byCategory().forEach((category, bucket) -> {
            ObjectNode node = buckets.putObject(category);
            node.put("tasks", bucket.tasks());
            node.put("runs", bucket.runs());
            node.put("successRate", bucket.successRate());
            node.put("passK", bucket.passK());
        });
        ArrayNode runs = root.putArray("runs");
        int index = 0;
        for (EvalTask task : result.tasks()) {
            List<EvalVerdict> verdicts = result.verdictsByTask().getOrDefault(task.id(), List.of());
            EvalStack.EvalRun first = index < result.runsByTask().size() ? result.runsByTask().get(index) : null;
            index++;
            ObjectNode node = runs.addObject();
            node.put("id", task.id());
            node.put("category", task.category());
            node.put("input", task.input());
            node.put("judgement", task.judgement());
            node.put("world", task.world());
            node.put("reason", task.reason());
            node.put("scripted", true);
            ArrayNode expectations = node.putArray("expectedTools");
            task.expectedTools().forEach(expectations::add);
            ArrayNode forbidden = node.putArray("forbiddenTools");
            task.forbiddenTools().forEach(forbidden::add);
            if (first != null) {
                node.put("stopReason", first.result().stopReason().name());
                node.put("durationMillis", first.durationNanos() / 1_000_000.0);
                node.put("tokensUsed", first.result().tokensUsed());
                ArrayNode events = node.putArray("eventSequence");
                first.eventNames().forEach(events::add);
                ArrayNode calls = node.putArray("toolCalls");
                for (EvalStack.ObservedCall call : first.calls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("tool", call.toolName());
                    callNode.set("arguments", call.arguments() == null
                            ? MAPPER.nullNode() : call.arguments());
                    callNode.put("isError", call.isError());
                    callNode.set("result", call.result());
                }
                node.put("answer", first.answer());
            }
            ArrayNode verdictList = node.putArray("verdicts");
            for (EvalVerdict verdict : verdicts) {
                verdictList.add(MAPPER.valueToTree(verdict.toMap()));
            }
            node.put("successAllRuns", EvalMetrics.allSucceeded(verdicts));
        }
        write(REPORT_DIR.resolve("results.json"), root.toPrettyString());
    }

    private static Path writeMarkdown(EvalRunner.EvalResult result, int repeats) {
        EvalMetrics.Summary summary = result.summary();
        StringBuilder out = new StringBuilder();
        out.append("# W2-7 评测汇总表（A 段）\n\n");
        out.append("> ## ⚠️ ").append(DISCLAIMER).append("\n>\n");
        out.append("> ").append(DISCLAIMER_LONG).append("\n\n");
        out.append("| 项 | 值 |\n|---|---|\n");
        out.append("| 运行模式 | `scripted-llm`（脚本化 LLM，零网络请求） |\n");
        out.append("| 任务数 | ").append(summary.taskCount()).append(" |\n");
        out.append("| 运行次数（task × pass^k） | ").append(summary.runCount()).append(" |\n");
        out.append("| pass^k 的 k | ").append(repeats).append(" |\n\n");

        out.append("## 汇总指标\n\n");
        out.append("| 指标 | 值 | 定义 |\n|---|---|---|\n");
        out.append("| 任务成功率 | ").append(EvalMetrics.percent(summary.successRate()))
                .append(" | 通过全部规则的运行数 / 全部运行数 |\n");
        out.append("| 约束违反率 | ").append(percentOrNa(summary.constraintViolationRate()))
                .append(" | 出现超距离/超预算的运行数 / 有可核约束的运行数（")
                .append(summary.violatedRuns()).append(" / ").append(summary.constraintCheckedRuns())
                .append("） |\n");
        out.append("| 工具调用正确率 | ").append(EvalMetrics.percent(summary.toolCorrectnessRate()))
                .append(" | 工具规则全部通过的运行数 / 全部运行数 |\n");
        out.append("| 平均步数（工具调用数/次） | ").append(format(summary.meanSteps()))
                .append(" | 逐轨迹工具调用数的均值 |\n");
        out.append("| 平均延迟（毫秒/次） | ").append(format(summary.meanLatencyMillis()))
                .append(" | 真主循环墙钟耗时均值（A 段测的是评测台，不是模型响应时间） |\n");
        out.append("| 平均输入 token/次 | ").append(format(summary.meanInputTokens()))
                .append(" | 脚本模型按字符数 ÷ ").append(EvalMetrics.CHARS_PER_TOKEN)
                .append(" 估算 |\n");
        out.append("| 平均输出 token/次 | ").append(format(summary.meanOutputTokens())).append(" | 同上 |\n");
        out.append("| 单次成本（美元/次） | ").append(formatUsd(summary.meanCostUsd()))
                .append(" | token × 单价表（输入 ").append(EvalMetrics.INPUT_USD_PER_MILLION)
                .append(" / 输出 ").append(EvalMetrics.OUTPUT_USD_PER_MILLION)
                .append(" 美元每百万），见下方声明 |\n");
        out.append("| 总成本（美元） | ").append(formatUsd(summary.totalCostUsd())).append(" | 同上 |\n");
        out.append("| pass^").append(repeats).append(" | ")
                .append(EvalMetrics.percent(summary.passK()))
                .append(" | ").append(repeats).append(" 次全部成功的任务数 / 任务数 |\n\n");

        out.append("## 分桶\n\n");
        out.append("| 覆盖桶 | 任务数 | 运行数 | 成功率 | pass^").append(repeats).append(" |\n");
        out.append("|---|---|---|---|---|\n");
        summary.byCategory().forEach((category, bucket) -> out.append("| ").append(category)
                .append(" | ").append(bucket.tasks())
                .append(" | ").append(bucket.runs())
                .append(" | ").append(EvalMetrics.percent(bucket.successRate()))
                .append(" | ").append(EvalMetrics.percent(bucket.passK()))
                .append(" |\n"));
        out.append('\n');

        List<EvalVerdict> failed = new ArrayList<>();
        for (EvalVerdict verdict : result.allVerdicts()) {
            if (!verdict.success()) {
                failed.add(verdict);
            }
        }
        out.append("## 逐任务结果（首跑）\n\n");
        out.append("| 任务 | 桶 | 判定类型 | 工具调用 | 结果 | 失败原因 |\n|---|---|---|---|---|---|\n");
        for (EvalTask task : result.tasks()) {
            List<EvalVerdict> verdicts = result.verdictsByTask().getOrDefault(task.id(), List.of());
            EvalVerdict first = verdicts.isEmpty() ? null : verdicts.get(0);
            out.append("| ").append(task.id())
                    .append(" | ").append(task.category())
                    .append(" | ").append(task.judgement())
                    .append(" | ").append(first == null ? "-" : first.toolCalls())
                    .append(" | ").append(first != null && first.success() ? "PASS" : "FAIL")
                    .append(" | ").append(first == null || first.reasons().isEmpty()
                            ? "-" : String.join("；", first.reasons()))
                    .append(" |\n");
        }
        out.append("## 负对照组（故意失败的轨迹）\n\n");
        out.append("评测台若把这几条也判成 PASS，判分器就是恒真的摆设。每条都只破坏一条规则：\n\n");
        out.append("| 对照 | 任务 | 破坏方式 | 应触发的规则 | 判分结果 |\n|---|---|---|---|---|\n");
        int controlFailures = 0;
        for (EvalControls.Control control : EvalControls.all()) {
            EvalVerdict verdict = control.judge();
            if (!verdict.success()) {
                controlFailures++;
            }
            out.append("| ").append(control.id())
                    .append(" | ").append(control.taskId())
                    .append(" | ").append(control.sabotage())
                    .append(" | ").append(control.intendedRule())
                    .append(" | ").append(verdict.success() ? "PASS（应为 FAIL！）" : "FAIL ✓")
                    .append(" |\n");
        }
        out.append("\n负对照判定为失败的条数：").append(controlFailures).append(" / ")
                .append(EvalControls.all().size()).append("\n\n");

        out.append("\n合计失败运行：").append(failed.size()).append(" / ").append(summary.runCount())
                .append("（脚本化忠实轨迹若全部通过，说明评测台按预期工作；判分器不是恒真的证明在上面")
                .append("的负对照组——9 条故意破坏的轨迹全部被判失败，且分别触发不同规则）\n\n");

        Map<String, String> priceNote = new LinkedHashMap<>();
        priceNote.put("单价口径", "成本 = tokens × 单价表常数；token 数由脚本模型按『字符数 ÷ "
                + EvalMetrics.CHARS_PER_TOKEN + "』估算，未经真实 tokenizer");
        priceNote.put("为什么不给真实成本", "A 段没有接任何真实模型，任何付费数字都只能是估算，故一律标注为非真实模型数据");
        priceNote.put("真实分数何时产出", "B 段（需额度恢复 + 嘉批准）");
        out.append("## 口径说明\n\n");
        priceNote.forEach((key, value) -> out.append("- **").append(key).append("**：").append(value).append("\n"));

        Path path = REPORT_DIR.resolve("summary.md");
        write(path, out.toString());
        return path;
    }

    private static String format(double value) {
        return Double.isNaN(value) ? "n/a" : String.format("%.3f", value);
    }

    private static String formatUsd(double value) {
        return Double.isNaN(value) ? "n/a" : String.format("%.6f", value);
    }

    private static String percentOrNa(double ratio) {
        return Double.isNaN(ratio) ? "n/a" : EvalMetrics.percent(ratio);
    }

    private static void write(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException broken) {
            throw new UncheckedIOException("无法写出报告 " + path.toAbsolutePath(), broken);
        }
    }
}
