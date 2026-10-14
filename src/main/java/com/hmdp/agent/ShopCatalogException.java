package com.hmdp.agent;

/**
 * Raised when a backing service call cannot be turned into a tool result.
 *
 * <p>It is a {@link RuntimeException} on purpose: the agent-core {@code ToolRegistry} catches any
 * {@code Exception} thrown by a tool handler and surfaces it as an
 * {@code EXECUTION_ERROR} tool outcome, so a business failure stays model-visible instead of
 * silently returning a half-filled result.</p>
 */
public class ShopCatalogException extends RuntimeException {

    public ShopCatalogException(String message) {
        super(message);
    }
}
