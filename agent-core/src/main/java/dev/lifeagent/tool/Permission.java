package dev.lifeagent.tool;

/**
 * How much damage a tool may do when the model picks it.
 *
 * <p>Declared on {@link AgentTool#permission()} so that layers above the registry (the W2-3 ReAct
 * loop, the W5 confirmation-token UI) can gate execution from metadata alone, without inspecting
 * individual tool implementations. This card defines the three levels and the declarations that use
 * them; it does not implement the gating.</p>
 */
public enum Permission {

    /** Read-only lookup; safe to run without asking the user. */
    READ,

    /** Mutates server state, but the change is recoverable. */
    WRITE,

    /** Irreversible or money-moving; must be confirmed by the user before it runs. */
    HIGH_RISK
}
