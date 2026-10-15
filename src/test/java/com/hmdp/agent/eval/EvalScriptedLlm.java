package com.hmdp.agent.eval;

import dev.lifeagent.llm.LlmClient;
import dev.lifeagent.llm.LlmException;
import dev.lifeagent.llm.LlmRequest;
import dev.lifeagent.llm.LlmStreamEvent;
import dev.lifeagent.llm.Message;
import dev.lifeagent.llm.MessageDelta;
import dev.lifeagent.llm.Role;
import dev.lifeagent.llm.StopReason;
import dev.lifeagent.llm.TextDelta;
import dev.lifeagent.llm.TokenUsage;
import dev.lifeagent.llm.ToolUseArgumentsDelta;
import dev.lifeagent.llm.ToolUseEnd;
import dev.lifeagent.llm.ToolUseStart;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The scripted LLM that drives the evaluation rig in segment A.
 *
 * <p><strong>This is not a real model.</strong> It replays a fixed trajectory per task: the planned
 * tool calls first, then a final answer. It exists to prove the rig runs end to end and that the
 * rule-based judge really distinguishes a passing trajectory from a failing one - never to produce a
 * model score. The report this generates says so in its header, and there is no code path here that
 * reaches a network.</p>
 *
 * <p>Two counters make a rig bug visible rather than silent: {@link #requests()} records every
 * request, so a test can assert what the model actually received, and the turn budget stops a
 * scripted model that is asked for more turns than it has (which would otherwise loop forever).</p>
 */
final class EvalScriptedLlm implements LlmClient {

    /** One scripted turn: either tool calls or a final answer. */
    record Turn(String answer, List<EvalTask.PlannedCall> calls, boolean derived) {

        static Turn tools(List<EvalTask.PlannedCall> calls) {
            return new Turn("", calls, false);
        }

        static Turn answer(String text) {
            return new Turn(text, List.of(), false);
        }

        /** A final answer to be rendered from the tool results of this request. */
        static Turn derivedAnswer() {
            return new Turn("", List.of(), true);
        }
    }

    private final String taskId;
    private final List<Turn> script;
    private final List<LlmRequest> requests = new ArrayList<>();
    private final List<TokenUsage> usages = new ArrayList<>();
    private int index;

    EvalScriptedLlm(String taskId, List<Turn> script) {
        this.taskId = taskId;
        this.script = List.copyOf(script);
    }

    /**
     * The script a faithful trajectory for {@code task} would follow: the plan's tool calls in one
     * turn (they are all {@code READ}, so the loop runs them in parallel), then the final answer.
     *
     * @param answer the text of the final answer
     */
    static EvalScriptedLlm faithful(EvalTask task, String answer) {
        List<Turn> turns = new ArrayList<>();
        if (!task.plan().isEmpty()) {
            turns.add(Turn.tools(task.plan()));
        }
        turns.add(Turn.answer(answer));
        return new EvalScriptedLlm(task.id(), turns);
    }

    /** A model that replays the task's plan and then renders whatever the tools returned. */
    static EvalScriptedLlm expressing(EvalTask task) {
        return faithfulExpressing(task);
    }

    /** Builds a model that replays the given turns verbatim. */
    static EvalScriptedLlm of(String taskId, Turn... turns) {
        return new EvalScriptedLlm(taskId, List.of(turns));
    }

    /** The turns a faithful trajectory for {@code task} would produce, for callers that re-run them. */
    static List<Turn> turnsFor(EvalTask task, String answer) {
        List<Turn> turns = new ArrayList<>();
        if (!task.plan().isEmpty()) {
            turns.add(Turn.tools(task.plan()));
        }
        turns.add(answer == null ? Turn.derivedAnswer() : Turn.answer(answer));
        return List.copyOf(turns);
    }

    @Override
    public void stream(LlmRequest request, Consumer<? super LlmStreamEvent> events) {
        requests.add(request);
        if (index >= script.size()) {
            throw new LlmException("scripted model exhausted for " + taskId
                    + "：模型被要求了第 " + (index + 1) + " 轮，但只写了 " + script.size() + " 轮");
        }
        Turn turn = script.get(index++);
        String text = turn.derived() ? renderAnswer(request) : turn.answer();
        if (!text.isEmpty()) {
            events.accept(new TextDelta(text));
        }
        int callNumber = 0;
        for (EvalTask.PlannedCall call : turn.calls()) {
            String callId = taskId + "-c" + (index) + "-" + (++callNumber);
            events.accept(new ToolUseStart(callId, call.tool()));
            events.accept(new ToolUseArgumentsDelta(callId, call.argsJson()));
            events.accept(new ToolUseEnd(callId));
        }
        StopReason reason = turn.calls().isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE;
        TokenUsage usage = usageFor(request, turn);
        usages.add(usage);
        events.accept(new MessageDelta(reason, usage));
    }

    /**
     * A deterministic stand-in for a provider's token accounting.
     *
     * <p>Segment A must not invent a model's cost, so the rig does the only honest thing it can: it
     * counts the characters it was handed and divides by a stated constant. The constant (4 chars per
     * token) and the price table live in {@link EvalMetrics}, and the report labels every derived
     * number as a scripted-LLM figure rather than a model figure.</p>
     */
    private static TokenUsage usageFor(LlmRequest request, Turn turn) {
        int inputChars = request.systemPrompt() == null ? 0 : request.systemPrompt().length();
        for (Message message : request.messages()) {
            inputChars += message.role() == Role.TOOL ? 400 : 60;
        }
        int outputChars = (turn.derived() ? 200 : turn.answer().length()) + turn.calls().size() * 40;
        return new TokenUsage(inputChars / EvalMetrics.CHARS_PER_TOKEN, outputChars / EvalMetrics.CHARS_PER_TOKEN);
    }

    /**
     * A faithful trajectory whose final answer is written from the tool results the loop actually
     * returned.
     *
     * <p>This is the honest shape for a scripted model: the first turn issues the task's planned
     * calls, and the second turn renders the JSON that came back into prose. The answer therefore
     * cannot claim a shop, a price, a distance or a voucher the read path never produced - which is
     * exactly what the judge then verifies.</p>
     */
    static EvalScriptedLlm faithfulExpressing(EvalTask task) {
        List<Turn> turns = new ArrayList<>();
        turns.add(Turn.tools(task.plan()));
        turns.add(Turn.derivedAnswer());
        return new EvalScriptedLlm(task.id(), turns);
    }

    /**
     * Renders the tool results carried by {@code request} into an answer.
     *
     * <p>Only values present in the results appear, so this doubles as a guard: a rig that lost the
     * tool result would produce an empty answer, and the intent rules would fail loudly rather than
     * passing on a fabricated one.</p>
     */
    static String renderAnswer(LlmRequest request) {
        StringBuilder out = new StringBuilder();
        for (Message message : request.messages()) {
            if (message.role() != Role.TOOL) {
                continue;
            }
            for (var block : message.content()) {
                if (block instanceof dev.lifeagent.llm.ToolResultBlock result) {
                    appendResult(out, result.content(), result.isError());
                }
            }
        }
        if (out.length() == 0) {
            return "抱歉，没有查到你需要的店铺信息。";
        }
        return out.toString();
    }

    private static void appendResult(StringBuilder out, com.fasterxml.jackson.databind.JsonNode content,
            boolean isError) {
        if (isError || !content.isArray() && !content.isObject()) {
            out.append("查询没能完成。");
            return;
        }
        if (content.isArray()) {
            if (content.isEmpty()) {
                out.append("抱歉，没有找到符合条件的店。");
                return;
            }
            for (com.fasterxml.jackson.databind.JsonNode node : content) {
                if (node.hasNonNull("name") && node.has("avgPrice")) {
                    out.append("店铺：").append(node.path("name").asText());
                    if (node.hasNonNull("avgPrice")) {
                        out.append("，人均 ").append(node.path("avgPrice").asInt()).append(" 元");
                    }
                    if (node.hasNonNull("score")) {
                        out.append("，评分 ").append(node.path("score").asDouble());
                    }
                    if (node.hasNonNull("category")) {
                        out.append("，品类 ").append(node.path("category").asText());
                    }
                    out.append("。\n");
                } else if (node.hasNonNull("title")) {
                    out.append("可用券：").append(node.path("title").asText())
                            .append("（").append(node.path("payValue").asInt()).append(" 元抵 ")
                            .append(node.path("actualValue").asInt()).append(" 元）。\n");
                }
            }
            return;
        }
        if (content.hasNonNull("name")) {
            out.append("店铺：").append(content.path("name").asText());
            if (content.hasNonNull("address")) {
                out.append("，地址 ").append(content.path("address").asText());
            }
            if (content.hasNonNull("avgPrice")) {
                out.append("，人均 ").append(content.path("avgPrice").asInt()).append(" 元");
            }
            out.append("。\n");
        }
    }

    /** Every request the loop made, in order. */
    List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    /** The recorded usage per turn, in call order. */
    List<TokenUsage> usages() {
        return List.copyOf(usages);
    }

    int calls() {
        return index;
    }
}
