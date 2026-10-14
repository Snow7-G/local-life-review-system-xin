package dev.lifeagent.loop;

import dev.lifeagent.hook.TurnContext;
import dev.lifeagent.tool.Permission;

/**
 * The single place the loop asks "may this tool call run?".
 *
 * <p>{@link Permission#READ} calls never reach a gate - the loop lets them through directly. Every
 * {@link Permission#WRITE}/{@link Permission#HIGH_RISK} call is gated, and the default
 * implementation {@link #denyAll()} refuses them, so the loop is safe with nothing wired up.</p>
 *
 * <p><strong>W5 owns the real flow.</strong> The confirmation token (digest-bound, five-minute TTL,
 * single use) and the UI that collects the user's answer are W5; this card only defines the seam and
 * the {@link Defer} path that suspends the session.</p>
 */
@FunctionalInterface
public interface PermissionGate {

    /**
     * @param context    the current step
     * @param call       the tool call about to run
     * @param permission the call's declared damage level ({@code WRITE} or {@code HIGH_RISK})
     * @return allow, deny, or defer to the user
     */
    PermissionDecision check(TurnContext context, ToolCallRequest call, Permission permission);

    /**
     * The fail-closed default: every write and high-risk call is refused.
     *
     * <p>Deliberately not a permissive no-op - an unwired permission layer must block money-moving
     * and state-changing tools rather than silently run them.</p>
     *
     * @return a gate that denies everything
     */
    static PermissionGate denyAll() {
        return (context, call, permission) -> PermissionDecision.deny(
                "工具 " + call.toolName() + " 需要 " + permission + " 权限，确认流程尚未接入（W5），已按拒绝处理");
    }
}
