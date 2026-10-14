package dev.lifeagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generates the JSON Schema of a tool's arguments from its argument record.
 *
 * <p>This replaces hand-written schemas (手册 §5.3): the record is the single source of truth, so
 * the schema cannot drift away from the code that binds the arguments.</p>
 *
 * <h2>Shape</h2>
 * <p>The root schema is always an object with {@code type}, {@code properties}, {@code required} and
 * {@code additionalProperties: false}. Property order follows the record component declaration
 * order, which keeps exported tool lists stable and diff-friendly.</p>
 *
 * <h2>Required</h2>
 * <p>Every component is listed in {@code required} <em>except</em> those declared as a nullable
 * wrapper ({@code Integer}, {@code Long}, {@code Double}, {@code Boolean}, ...). A wrapper carries
 * the "may be omitted" meaning used by 手册 §5.3's {@code Integer maxPrice} "可不填", so the model
 * is allowed to leave it out; primitives and reference types ({@code String}, enums, arrays,
 * nested records) have no such marker and are therefore required. Validation enforces exactly
 * that - see {@link ToolArgumentValidator}.</p>
 *
 * <h2>Type mapping</h2>
 * <ul>
 *   <li>{@code String}, {@code char}/{@code Character} &rarr; {@code string}</li>
 *   <li>{@code byte}/{@code short}/{@code int}/{@code long} and their wrappers, {@code BigInteger}
 *       &rarr; {@code integer}</li>
 *   <li>{@code float}/{@code double} and their wrappers, {@code BigDecimal} &rarr; {@code number}</li>
 *   <li>{@code boolean}/{@code Boolean} &rarr; {@code boolean}</li>
 *   <li>enum &rarr; {@code string} plus the {@code enum} list of constant names</li>
 *   <li>array, {@code List}/{@code Set}/{@code Collection} &rarr; {@code array} with {@code items}</li>
 *   <li>{@code Map<String, V>} &rarr; {@code object} with {@code additionalProperties} describing
 *       {@code V}</li>
 *   <li>nested record &rarr; recursive {@code object}</li>
 * </ul>
 * <p>Any other type is a declaration bug and throws {@link ToolDeclarationException} rather than
 * silently emitting a permissive or empty schema.</p>
 */
public final class JsonSchemaGenerator {

    /** Node factory only; no data binding happens here. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSchemaGenerator() {
    }

    /**
     * Builds the argument schema of a tool-argument record.
     *
     * @param argumentsType the record type used as the single tool parameter
     * @return a fresh, mutable copy of the schema; never {@code null}
     * @throws ToolDeclarationException if the type is not a record, uses an unsupported field type,
     *                                  or references itself cyclically
     */
    public static ObjectNode schemaFor(Class<?> argumentsType) {
        Objects.requireNonNull(argumentsType, "argumentsType");
        return objectSchema(argumentsType, new LinkedHashSet<>());
    }

    private static ObjectNode objectSchema(Class<?> recordType, Set<Class<?>> visiting) {
        if (!recordType.isRecord()) {
            throw new ToolDeclarationException("Tool arguments must be a record, but "
                    + recordType.getName() + " is not");
        }
        if (!visiting.add(recordType)) {
            throw new ToolDeclarationException("Tool arguments must not reference themselves "
                    + "cyclically: " + recordType.getName());
        }
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        String typeDescription = descriptionOf(recordType.getAnnotation(Desc.class));
        if (typeDescription != null) {
            schema.put("description", typeDescription);
        }
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (RecordComponent component : recordType.getRecordComponents()) {
            ObjectNode property = schemaForType(component.getGenericType(), component.getType(), visiting);
            String description = descriptionOf(component.getAnnotation(Desc.class));
            if (description == null && component.getAccessor() != null) {
                description = descriptionOf(component.getAccessor().getAnnotation(Desc.class));
            }
            if (description != null) {
                property.put("description", description);
            }
            properties.set(component.getName(), property);
            if (isRequiredComponent(component)) {
                required.add(component.getName());
            }
        }
        schema.put("additionalProperties", false);
        visiting.remove(recordType);
        return schema;
    }

    private static ObjectNode schemaForType(Type type, Class<?> raw, Set<Class<?>> visiting) {
        if (raw == String.class || raw == char.class || raw == Character.class) {
            return scalar("string");
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return scalar("boolean");
        }
        if (raw == byte.class || raw == Byte.class || raw == short.class || raw == Short.class
                || raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == BigInteger.class) {
            return scalar("integer");
        }
        if (raw == float.class || raw == Float.class || raw == double.class || raw == Double.class
                || raw == BigDecimal.class) {
            return scalar("number");
        }
        if (raw.isEnum()) {
            ObjectNode schema = scalar("string");
            ArrayNode values = schema.putArray("enum");
            for (Object constant : raw.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            return schema;
        }
        if (raw.isArray()) {
            return arraySchema(raw.getComponentType(), raw.getComponentType(), visiting);
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return arraySchema(elementTypeOf(type, raw), elementTypeOf(type, raw), visiting);
        }
        if (Map.class.isAssignableFrom(raw)) {
            return mapSchema(type, raw, visiting);
        }
        if (raw.isRecord()) {
            return objectSchema(raw, visiting);
        }
        throw new ToolDeclarationException("Unsupported tool argument type " + type.getTypeName()
                + "; supported: primitives and wrappers, String, enum, array, List/Set/Collection, "
                + "Map<String, V>, nested record");
    }

    private static ObjectNode scalar(String jsonType) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", jsonType);
        return schema;
    }

    private static ObjectNode arraySchema(Type elementType, Class<?> elementClass, Set<Class<?>> visiting) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "array");
        schema.set("items", schemaForType(elementType, elementClass, visiting));
        return schema;
    }

    private static ObjectNode mapSchema(Type type, Class<?> raw, Set<Class<?>> visiting) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        if (!(type instanceof ParameterizedType parameterized)) {
            throw new ToolDeclarationException("Tool argument Map " + raw.getName()
                    + " must declare its types, e.g. Map<String, String>");
        }
        Type[] arguments = parameterized.getActualTypeArguments();
        if (arguments.length != 2 || rawClassOf(arguments[0]) != String.class) {
            throw new ToolDeclarationException("Tool argument Map keys must be String, but "
                    + type.getTypeName() + " uses " + typeNameOf(arguments, 0)
                    + "; JSON object keys are always strings");
        }
        schema.set("additionalProperties", schemaForType(arguments[1], rawClassOf(arguments[1]), visiting));
        return schema;
    }

    /**
     * Element type of a collection, or a declaration failure when it is a raw {@code List}: without
     * the type argument the generated {@code items} would have to accept anything, which is exactly
     * the "silently permissive" schema this class refuses to emit.
     */
    private static Class<?> elementTypeOf(Type type, Class<?> raw) {
        if (!(type instanceof ParameterizedType parameterized)
                || parameterized.getActualTypeArguments().length != 1) {
            throw new ToolDeclarationException("Tool argument collection " + raw.getName()
                    + " must declare its element type, e.g. List<String>");
        }
        Class<?> element = rawClassOf(parameterized.getActualTypeArguments()[0]);
        if (element == null) {
            throw new ToolDeclarationException("Unsupported tool argument element type "
                    + parameterized.getActualTypeArguments()[0].getTypeName());
        }
        return element;
    }

    private static Class<?> rawClassOf(Type type) {
        return type instanceof Class<?> clazz ? clazz
                : type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz
                        ? clazz
                        : null;
    }

    private static String typeNameOf(Type[] arguments, int index) {
        return index < arguments.length ? arguments[index].getTypeName() : "<missing>";
    }

    /**
     * A component is required unless its declared type is a <em>nullable wrapper</em>.
     *
     * <p>手册 §5.3 models an optional argument as a wrapper ({@code Integer maxPrice}, "可不填"),
     * so a component whose type is {@code Integer}/{@code Long}/... can be omitted by the model and
     * therefore must not appear in {@code required}. Everything else — primitives <em>and</em>
     * reference types such as {@code String}, enums, arrays and nested records — is required,
     * because nothing in the declaration marks it as optional. Validation mirrors this rule:
     * {@code null} is accepted only for properties that are not required.</p>
     */
    private static boolean isRequiredComponent(RecordComponent component) {
        return !NULLABLE_WRAPPERS.contains(component.getType());
    }

    /** Wrapper types that carry an explicit "may be omitted" meaning here (手册 §5.3). */
    private static final Set<Class<?>> NULLABLE_WRAPPERS = Set.of(
            Integer.class, Long.class, Short.class, Byte.class,
            Float.class, Double.class, Boolean.class, Character.class,
            BigInteger.class, BigDecimal.class);

    private static String descriptionOf(Desc desc) {
        if (desc == null) {
            return null;
        }
        String value = desc.value();
        return value == null || value.isBlank() ? null : value;
    }
}
