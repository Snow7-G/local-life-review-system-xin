package dev.lifeagent.loop;

/** Why a ReAct turn stopped. Explicit so callers never infer the ending from the text. */
public enum LoopStopReason {

    /** The model produced a final answer. */
    ANSWERED,

    /** The tool-call or token budget was exhausted; the model was asked to wrap up with what it had. */
    BUDGET_EXHAUSTED,

    /** The tool-call ceiling for this task was reached. */
    MAX_STEPS,

    /** A hook blocked the turn; the block reason is the final answer. */
    BLOCKED,

    /** The model call or stream itself failed. */
    ERROR,

    /** The loop paused for user input and the session was stored. */
    WAITING_USER
}
