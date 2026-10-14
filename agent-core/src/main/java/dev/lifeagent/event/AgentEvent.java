package dev.lifeagent.event;

/**
 * One thing that happened inside a turn, in the shape an SSE endpoint will eventually forward.
 *
 * <p>Sealed on purpose: the six members below are the whole contract (manual §5.4), and the
 * transport that turns them into {@code text/event-stream} frames lives outside agent-core. The
 * loop only ever pushes them through a {@link java.util.function.Consumer}; it never knows whether
 * anyone is listening.</p>
 *
 * <p>Ordering contract the loop upholds:</p>
 * <ul>
 *   <li>{@link ToolCallEvent} for a tool-use id is always emitted before the matching
 *       {@link ToolResultEvent}, and every tool result has a preceding call;</li>
 *   <li>{@link AnswerEvent} appears at most once per turn and, when it appears, it is the last
 *       event.</li>
 * </ul>
 */
public sealed interface AgentEvent permits
        PlanEvent, ToolCallEvent, ToolResultEvent, ThinkingEvent, ConfirmRequiredEvent, AnswerEvent {
}
