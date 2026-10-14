package com.hmdp.agent.transport;

import dev.lifeagent.event.AgentEvent;
import dev.lifeagent.loop.AgentLoop;

import java.util.function.Consumer;

/**
 * Builds an {@link AgentLoop} whose event sink is the caller's.
 *
 * <p>{@code AgentLoop}'s sink is fixed at construction, while an SSE request needs a sink that writes
 * to <em>its</em> emitter. This seam exchanges the immutable sink for a per-request loop without
 * agent-core changing, and it is what lets the transport tests inject a loop backed by a fake
 * {@code LlmClient}.</p>
 */
@FunctionalInterface
public interface AgentLoopFactory {

    /**
     * @param sink where the loop pushes its six event types
     * @return a loop that reports into {@code sink}
     */
    AgentLoop create(Consumer<AgentEvent> sink);

    /** One loop invocation for one request. */
    @FunctionalInterface
    interface Step {

        /**
         * @param loop the loop built with this request's sink
         * @return how the turn ended
         */
        dev.lifeagent.loop.LoopResult run(AgentLoop loop);
    }
}
