package dev.lifeagent.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks one model-callable tool.
 *
 * <p>Applied to a public method that takes <em>exactly one</em> {@code record} parameter (the
 * arguments) and returns any value Jackson can serialize (the tool result). {@link ToolRegistry}
 * turns the annotation into a registered tool: {@link #name()} is the wire name the model calls,
 * {@link #description()} and the generated argument schema are what the model sees.</p>
 *
 * <pre>{@code
 * @AgentTool(name = "search_nearby", permission = Permission.READ,
 *     description = "按位置和品类搜附近的店。用户提到距离、附近、周边时用；只问某家店的详情时别用")
 * public List<ShopBrief> searchNearby(SearchNearbyArgs args) { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool {

    /** Unique wire name; registering the same name twice is rejected. */
    String name();

    /**
     * Model-facing text describing what the tool does and, importantly, when to use it and when not
     * to. Written in Chinese because it is prompt text sent to the model, not code documentation.
     */
    String description();

    /**
     * Damage level of the tool. Deliberately has no default: every declaration states its own
     * permission instead of silently inheriting one.
     */
    Permission permission();
}
