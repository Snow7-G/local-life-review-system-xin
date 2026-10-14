package dev.lifeagent.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * One registered tool: the wire metadata, the argument schema and the handler.
 *
 * <p>Immutable. The schema is deep-copied on the way in and on the way out, because
 * {@link ToolRegistry#modelVisibleTools()} hands schemas to the model layer and a caller that
 * mutated one in place would silently change what every later request advertises.</p>
 *
 * @param name            wire name the model calls
 * @param description     model-facing description ("when to use / when not to use")
 * @param permission      damage level
 * @param argumentsType   the record the arguments bind to
 * @param argumentsSchema the generated JSON Schema, see {@link JsonSchemaGenerator}
 * @param handler         the code that runs the tool
 */
public record ToolDefinition(
        String name,
        String description,
        Permission permission,
        Class<?> argumentsType,
        ObjectNode argumentsSchema,
        ToolHandler handler) {

    public ToolDefinition {
        name = requireText(name, "name");
        description = requireText(description, "description");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(argumentsType, "argumentsType");
        if (!argumentsType.isRecord()) {
            throw new ToolDeclarationException("Tool " + name + " must take a record argument, but "
                    + argumentsType.getName() + " is not a record");
        }
        Objects.requireNonNull(argumentsSchema, "argumentsSchema");
        argumentsSchema = argumentsSchema.deepCopy();
        Objects.requireNonNull(handler, "handler");
    }

    /**
     * Creates a definition and generates its schema.
     *
     * @param argumentsType the record the arguments bind to
     * @return a new definition
     * @throws ToolDeclarationException if the name or description is blank, or the argument record
     *                                  cannot be expressed as JSON Schema
     */
    public static ToolDefinition of(
            String name,
            String description,
            Permission permission,
            Class<?> argumentsType,
            ToolHandler handler) {
        Objects.requireNonNull(argumentsType, "argumentsType");
        return new ToolDefinition(name, description, permission, argumentsType,
                JsonSchemaGenerator.schemaFor(argumentsType), handler);
    }

    /** @return a deep copy of the argument schema. */
    @Override
    public ObjectNode argumentsSchema() {
        return argumentsSchema.deepCopy();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ToolDeclarationException("Tool " + field + " must not be blank");
        }
        return value;
    }
}
