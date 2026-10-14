package dev.lifeagent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Checks raw tool arguments against the JSON Schema produced by {@link JsonSchemaGenerator}.
 *
 * <p>Validation runs on the raw {@link JsonNode} <em>before</em> binding, because binding is lossy:
 * Jackson would happily turn a missing {@code int radius} into {@code 0} and a missing
 * {@code String category} into {@code null}, so a post-binding check could no longer tell "absent"
 * from "explicitly zero". See {@link ToolRegistry#invoke} for how this guarantees that a rejected
 * call never reaches the business method.</p>
 *
 * <h2>Supported keywords</h2>
 * <p>Exactly the ones {@link JsonSchemaGenerator} emits: {@code type}, {@code properties},
 * {@code required}, {@code additionalProperties}, {@code items}, {@code enum}. Unknown keywords are
 * ignored rather than guessed at.</p>
 *
 * <h2>Null</h2>
 * <p>{@code null} is accepted for any property that is not required (that is what "wrapper types are
 * optional" means on the wire), and rejected for required properties, whose generated type
 * ({@code integer}, {@code string}, ...) does not include {@code null}.</p>
 *
 * <p>Problem strings are Chinese because they are model-facing: {@link ToolRegistry} hands them
 * back as the tool result.</p>
 */
public final class ToolArgumentValidator {

    private ToolArgumentValidator() {
    }

    /**
     * Validates arguments against a schema.
     *
     * @param schema    the generated argument schema
     * @param arguments the raw arguments as sent by the model
     * @return every problem found, in document order; empty when the arguments are valid
     */
    public static List<String> validate(JsonNode schema, JsonNode arguments) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(arguments, "arguments");
        List<String> problems = new ArrayList<>();
        validateNode(schema, arguments, "参数", problems);
        return List.copyOf(problems);
    }

    /** Renders a JSON-Schema type as the Chinese label used in user-facing problems. */
    private static String typeLabel(String jsonSchemaType) {
        return switch (jsonSchemaType) {
            case "integer" -> "整数";
            case "number" -> "数字";
            case "string" -> "字符串";
            case "boolean" -> "布尔值";
            case "object" -> "对象";
            case "array" -> "数组";
            case "null" -> "空值";
            default -> jsonSchemaType;
        };
    }

    private static void validateNode(JsonNode schema, JsonNode value, String location, List<String> problems) {
        String type = schema.path("type").asText("");
        if (!matchesType(type, value)) {
            problems.add(location + " 类型不符：应为 " + typeLabel(type) + "，实际是 " + describe(value));
            return;
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray() && value.isTextual()
                && !containsText(enumValues, value.textValue())) {
            problems.add(location + " 取值不合法：只能是 " + enumValues + " 之一");
        }
        switch (type) {
            case "object" -> validateObject(schema, value, location, problems);
            case "array" -> validateArray(schema, value, location, problems);
            default -> {
                // Scalars carry nothing else to check.
            }
        }
    }

    private static void validateObject(JsonNode schema, JsonNode value, String location, List<String> problems) {
        JsonNode properties = schema.path("properties");
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode name : required) {
                String field = name.asText();
                if (!value.has(field)) {
                    // Nested objects must say which one is incomplete; at the root the field name is enough.
                    String path = "参数".equals(location) ? field : location + "." + field;
                    problems.add("缺少必填参数：" + path);
                }
            }
        }
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            JsonNode child = field.getValue();
            JsonNode propertySchema = properties.get(name);
            if (propertySchema == null) {
                JsonNode additional = schema.get("additionalProperties");
                if (additional != null && additional.isObject()) {
                    if (child.isNull()) {
                        problems.add(location + "." + name + " 不能为 null");
                    } else {
                        validateNode(additional, child, location + "." + name, problems);
                    }
                } else if (additional != null && additional.isBoolean() && !additional.booleanValue()) {
                    problems.add("不认识的参数：" + name);
                }
                continue;
            }
            if (child.isNull()) {
                if (isRequired(required, name)) {
                    problems.add(location + "." + name + " 不能为 null");
                }
                continue;
            }
            validateNode(propertySchema, child, location + "." + name, problems);
        }
    }

    private static void validateArray(JsonNode schema, JsonNode value, String location, List<String> problems) {
        JsonNode items = schema.get("items");
        if (items == null) {
            return;
        }
        for (int index = 0; index < value.size(); index++) {
            JsonNode element = value.get(index);
            String elementLocation = location + "[" + index + "]";
            if (element.isNull()) {
                problems.add(elementLocation + " 不能为 null");
                continue;
            }
            validateNode(items, element, elementLocation, problems);
        }
    }

    private static boolean isRequired(JsonNode required, String name) {
        if (!required.isArray()) {
            return false;
        }
        for (JsonNode entry : required) {
            if (entry.asText().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsText(JsonNode values, String text) {
        for (JsonNode value : values) {
            if (value.isTextual() && value.textValue().equals(text)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            default -> true;
        };
    }

    private static String describe(JsonNode value) {
        return switch (value.getNodeType()) {
            case OBJECT -> "对象";
            case ARRAY -> "数组";
            case STRING -> "字符串";
            case NUMBER -> "数字";
            case BOOLEAN -> "布尔值";
            case NULL -> "null";
            default -> value.getNodeType().name();
        };
    }
}
