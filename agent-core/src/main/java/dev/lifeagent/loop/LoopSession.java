package dev.lifeagent.loop;

import dev.lifeagent.llm.Message;
import dev.lifeagent.llm.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything a paused turn needs to be resumed later: the conversation so far, how much budget was
 * spent, and what the loop is waiting for.
 *
 * <p>Mutable by design - resuming a session is "append the user's answer, replay the interrupted
 * step, carry on". {@link #copy()} exists so the loop can publish a consistent snapshot to a
 * {@link SessionStore} without exposing its working instance.</p>
 *
 * <p>{@link #suspendedCalls()} is the whole interrupted step, not just the call waiting for
 * confirmation: Anthropic requires every {@code tool_use} of an assistant message to be answered
 * before the next assistant message, so the loop must remember all of them to keep the history
 * valid across the pause.</p>
 *
 * <p>W2-3 keeps this in memory only. The TTL and per-user cap the plan's Δ5 adds are a W3/W6 concern
 * and live in the Redis-backed store, not here.</p>
 */
public final class LoopSession {

    /** Where a session stands. */
    public enum Status {
        /** The loop is running (or about to). */
        RUNNING,

        /** The loop stopped waiting for the user's answer. */
        WAITING_USER,

        /** The turn finished - answered, blocked or over budget. */
        DONE
    }

    private final String sessionId;
    private final List<Message> messages = new ArrayList<>();
    private LoopBudget budget;
    private int toolCallsUsed;
    private int tokensUsed;
    private Status status = Status.RUNNING;
    private PendingPermission pendingPermission;
    private List<ToolCallRequest> suspendedCalls = List.of();
    private int resumeCount;

    /**
     * @param sessionId the session id; also the circuit-breaker partition key
     * @param history   the messages to start from
     * @param budget    the ceilings the resumed turn must keep enforcing
     */
    public LoopSession(String sessionId, List<Message> history, LoopBudget budget) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.sessionId = sessionId;
        this.messages.addAll(Objects.requireNonNull(history, "history"));
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    /**
     * @param sessionId the session id
     * @param history   the messages to start from
     */
    public LoopSession(String sessionId, List<Message> history) {
        this(sessionId, history, LoopBudget.defaults());
    }

    /** @param sessionId the session id. */
    public LoopSession(String sessionId) {
        this(sessionId, List.of());
    }

    /** @return the session id. */
    public String sessionId() {
        return sessionId;
    }

    /** @return a defensive copy of the conversation so far. */
    public List<Message> messages() {
        return List.copyOf(messages);
    }

    void append(Message message) {
        messages.add(Objects.requireNonNull(message, "message"));
    }

    /** @return the ceilings this session enforces. */
    public LoopBudget budget() {
        return budget;
    }

    void applyBudget(LoopBudget value) {
        this.budget = Objects.requireNonNull(value, "budget");
    }

    /** @return how many tool calls this session has issued in total, executed or not. */
    public int toolCallsUsed() {
        return toolCallsUsed;
    }

    /** @return how many tokens this session has accumulated in total. */
    public int tokensUsed() {
        return tokensUsed;
    }

    /** @return the accumulated usage. */
    public TokenUsage usage() {
        return new TokenUsage(tokensUsed, 0);
    }

    void recordToolCalls(int count) {
        toolCallsUsed = Math.addExact(toolCallsUsed, count);
    }

    void recordTokens(TokenUsage usage) {
        tokensUsed = Math.addExact(tokensUsed, usage.totalTokens());
    }

    /** @return the session's status. */
    public Status status() {
        return status;
    }

    void markStatus(Status status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    /** @return the call the session is waiting on, or {@code null}. */
    public PendingPermission pendingPermission() {
        return pendingPermission;
    }

    /** @return the interrupted step's calls, in request order; empty when nothing is suspended. */
    public List<ToolCallRequest> suspendedCalls() {
        return List.copyOf(suspendedCalls);
    }

    void suspend(PendingPermission pending, List<ToolCallRequest> calls) {
        this.pendingPermission = Objects.requireNonNull(pending, "pending");
        this.suspendedCalls = List.copyOf(Objects.requireNonNull(calls, "calls"));
        this.status = Status.WAITING_USER;
    }

    /** @return whether the loop is waiting for the user. */
    public boolean isWaitingForUser() {
        return status == Status.WAITING_USER && pendingPermission != null;
    }

    /** @return how many times this session has been resumed. */
    public int resumeCount() {
        return resumeCount;
    }

    /** Clears the pending state and marks the session running again. */
    void markResumed() {
        status = Status.RUNNING;
        pendingPermission = null;
        suspendedCalls = List.of();
        resumeCount++;
    }

    /**
     * Builds an independent copy, including its own message list.
     *
     * @return a snapshot safe to hand to a {@link SessionStore}
     */
    public LoopSession copy() {
        LoopSession copy = new LoopSession(sessionId, messages, budget);
        copy.toolCallsUsed = toolCallsUsed;
        copy.tokensUsed = tokensUsed;
        copy.status = status;
        copy.pendingPermission = pendingPermission;
        copy.suspendedCalls = suspendedCalls;
        copy.resumeCount = resumeCount;
        return copy;
    }
}
