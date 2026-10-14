package dev.lifeagent.hook;

/**
 * Base type for the four hook points (manual §5). Every hook has a name so a failure can be
 * reported without leaking the implementation class into the model's context.
 *
 * <p>A hook is allowed to throw; {@link HookChain} catches it and treats it as
 * {@link HookResult#block(String)}. Hooks must not perform I/O that can block indefinitely, and
 * must not call back into the loop.</p>
 */
public interface Hook {

    /** @return a short, stable name used in failure messages. */
    String name();
}
