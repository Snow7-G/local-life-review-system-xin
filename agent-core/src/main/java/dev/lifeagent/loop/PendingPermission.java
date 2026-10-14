package dev.lifeagent.loop;

import dev.lifeagent.tool.Permission;

import java.util.Objects;

/**
 * The tool call a suspended session is waiting on.
 *
 * @param request    the call the model asked for
 * @param permission the call's declared damage level
 * @param question   what to ask the user
 */
public record PendingPermission(ToolCallRequest request, Permission permission, String question) {

    public PendingPermission {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(question, "question");
    }
}
