package dev.lifeagent.loop;

import dev.lifeagent.tool.ToolErrorKind;
import dev.lifeagent.tool.ToolFailure;
import dev.lifeagent.tool.ToolOutcome;
import dev.lifeagent.tool.ToolSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

/** Turns every tool failure into the single line of text the model receives. */
final class LoopErrorMessages {

    private static final int MAX_MESSAGE_CHARS = 200;

    private LoopErrorMessages() {
    }

    /**
     * Renders a registry failure as one model-facing line.
     *
     * <p>The registry's message is truncated: it can carry a raw argument-binding exception, and a
     * long stack-ish string in the context costs tokens without helping the model.</p>
     */
    static TextNode forFailure(ToolFailure failure) {
        String prefix = switch (failure.errorKind()) {
            case UNKNOWN_TOOL -> "工具不存在";
            case INVALID_ARGUMENTS -> "参数不合法";
            case EXECUTION_ERROR -> "执行出错";
        };
        return TextNode.valueOf(prefix + "：" + truncate(failure.message()));
    }

    /** Renders any tool outcome as the JSON the model receives. */
    static JsonNode forOutcome(ToolOutcome outcome) {
        return switch (outcome) {
            case ToolSuccess success -> success.result();
            case ToolFailure failure -> forFailure(failure);
        };
    }

    /** @return a one-line error result for a call that never reached the tool. */
    static TextNode notRun(String message) {
        return TextNode.valueOf(truncate(message));
    }

    static TextNode circuitOpen(String toolName, int consecutiveFailures) {
        return TextNode.valueOf("工具 " + toolName + " 已熔断：连续失败 " + consecutiveFailures
                + " 次，本次调用未执行。这是逻辑错误，请不要重试该工具，改用其他工具或直接给出结论。");
    }

    /** @return {@code true} when the failure counts towards the circuit breaker. */
    static boolean countsTowardsBreaker(ToolOutcome outcome) {
        return outcome instanceof ToolFailure failure
                && failure.errorKind() != ToolErrorKind.UNKNOWN_TOOL;
    }

    static TextNode truncateToNode(String message) {
        return TextNode.valueOf(truncate(message));
    }

    private static String truncate(String message) {
        if (message.length() <= MAX_MESSAGE_CHARS) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_CHARS) + "…（已截断）";
    }
}
