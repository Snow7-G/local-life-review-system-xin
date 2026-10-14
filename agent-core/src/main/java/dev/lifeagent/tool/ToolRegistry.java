package dev.lifeagent.tool;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the tools the model may call, and is the only way to run them.
 *
 * <p>Business-agnostic: the registry knows nothing about shops, reviews or Spring. It takes tool
 * definitions (from {@link ToolDefinition} or from any object whose methods carry
 * {@link AgentTool}), exports them in the wire shape the LLM providers expect, and executes a call
 * in a fixed order:</p>
 *
 * <ol>
 *   <li>unknown name &rarr; {@link ToolErrorKind#UNKNOWN_TOOL};</li>
 *   <li>arguments validated against the generated schema &rarr; on failure
 *       {@link ToolErrorKind#INVALID_ARGUMENTS} and <strong>the business method is never entered</strong>;</li>
 *   <li>arguments bound to the record &rarr; binding failure is also
 *       {@link ToolErrorKind#INVALID_ARGUMENTS};</li>
 *   <li>handler runs; a throwing handler becomes {@link ToolErrorKind#EXECUTION_ERROR}.</li>
 * </ol>
 *
 * <p>Validation deliberately happens on the raw {@link JsonNode} before binding: binding is lossy
 * (a missing {@code int} becomes {@code 0}), so validating afterwards could not tell a missing
 * required field from an explicit zero.</p>
 *
 * <p>Not thread-safe for {@code register}; reading tools and invoking them after registration is
 * safe as long as register happens at startup, before the loop starts (W2-3).</p>
 */
public final class ToolRegistry {

    /** Strict on purpose: trailing junk in arguments is a malformed tool call, not a valid one. */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ObjectMapper mapper;
    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();

    /** Creates a registry with the default (strict) mapper. */
    public ToolRegistry() {
        this(MAPPER);
    }

    /**
     * Creates a registry that binds arguments with the given mapper.
     *
     * @param mapper the mapper used for binding and result serialization
     */
    public ToolRegistry(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Registers a tool.
     *
     * @param definition the tool to add
     * @return this registry, for chaining
     * @throws DuplicateToolException if the name is already taken
     */
    public ToolRegistry register(ToolDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        ToolDefinition existing = definitions.putIfAbsent(definition.name(), definition);
        if (existing != null) {
            throw new DuplicateToolException(definition.name());
        }
        return this;
    }

    /**
     * Registers every {@link AgentTool}-annotated method of {@code provider}.
     *
     * <p>Each method must be public, take exactly one record argument and return something
     * serializable. Methods are registered in name order so the exported tool list does not depend
     * on reflection ordering. Registration is atomic per method: an invalid declaration throws and
     * leaves earlier tools registered, so fail fast at startup rather than at call time.</p>
     *
     * @param provider the object holding the annotated methods
     * @return this registry, for chaining
     * @throws ToolDeclarationException if an annotated method cannot be a tool
     * @throws DuplicateToolException   if two of them share a name
     */
    public ToolRegistry registerTools(Object provider) {
        Objects.requireNonNull(provider, "provider");
        List<Method> annotated = new ArrayList<>();
        for (Method method : provider.getClass().getMethods()) {
            if (method.isSynthetic() || method.isBridge() || !method.isAnnotationPresent(AgentTool.class)) {
                continue;
            }
            annotated.add(method);
        }
        annotated.sort(Comparator.comparing(method -> method.getAnnotation(AgentTool.class).name()));
        for (Method method : annotated) {
            register(definitionFor(provider, method));
        }
        return this;
    }

    /** @return the tool with that name, or empty. */
    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    /**
     * @param name the wire name
     * @return the tool with that name
     * @throws UnknownToolException if no such tool is registered
     */
    public ToolDefinition tool(String name) {
        ToolDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new UnknownToolException(name);
        }
        return definition;
    }

    /** @return the registered tools in registration order. */
    public List<ToolDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    /** @return how many tools are registered. */
    public int size() {
        return definitions.size();
    }

    /**
     * Builds the tool list in OpenAI's wire shape:
     * {@code {"type":"function","function":{name,description,parameters}}}.
     *
     * @return a fresh list; mutating it does not affect the registry
     */
    public List<JsonNode> modelVisibleTools() {
        List<JsonNode> tools = new ArrayList<>(definitions.size());
        for (ToolDefinition definition : definitions.values()) {
            ObjectNode function = MAPPER.createObjectNode();
            function.put("name", definition.name());
            function.put("description", definition.description());
            function.set("parameters", definition.argumentsSchema());
            ObjectNode tool = MAPPER.createObjectNode();
            tool.put("type", "function");
            tool.set("function", function);
            tools.add(tool);
        }
        return List.copyOf(tools);
    }

    /**
     * Builds the tool list in Anthropic's wire shape: {@code {name,description,input_schema}}.
     *
     * <p>Same definitions, different envelope - the two providers disagree on both the wrapper and
     * the schema key, so the registry emits each shape directly instead of making callers reshape
     * {@link #modelVisibleTools()}.</p>
     *
     * @return a fresh list; mutating it does not affect the registry
     */
    public List<JsonNode> anthropicModelVisibleTools() {
        List<JsonNode> tools = new ArrayList<>(definitions.size());
        for (ToolDefinition definition : definitions.values()) {
            ObjectNode tool = MAPPER.createObjectNode();
            tool.put("name", definition.name());
            tool.put("description", definition.description());
            tool.set("input_schema", definition.argumentsSchema());
            tools.add(tool);
        }
        return List.copyOf(tools);
    }

    /**
     * Runs a tool by name.
     *
     * @param toolName  the name the model called
     * @param arguments the raw arguments from the model
     * @return a success or a model-visible failure; never throws for a bad call
     */
    public ToolOutcome invoke(String toolName, JsonNode arguments) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(arguments, "arguments");
        ToolDefinition definition = definitions.get(toolName);
        if (definition == null) {
            return new ToolFailure(toolName, ToolErrorKind.UNKNOWN_TOOL, "未知工具：" + toolName);
        }
        return invoke(definition, arguments);
    }

    /**
     * Runs a tool that is already resolved.
     *
     * @param definition the tool to run
     * @param arguments  the raw arguments from the model
     * @return a success or a model-visible failure; never throws for a bad call
     */
    public ToolOutcome invoke(ToolDefinition definition, JsonNode arguments) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(arguments, "arguments");
        String name = definition.name();
        List<String> problems = ToolArgumentValidator.validate(definition.argumentsSchema(), arguments);
        if (!problems.isEmpty()) {
            return new ToolFailure(name, ToolErrorKind.INVALID_ARGUMENTS,
                    "参数校验失败：" + String.join("；", problems));
        }
        Object bound;
        try {
            bound = mapper.convertValue(arguments, definition.argumentsType());
        } catch (IllegalArgumentException e) {
            return new ToolFailure(name, ToolErrorKind.INVALID_ARGUMENTS,
                    "参数校验失败：参数不符合 " + definition.argumentsType().getSimpleName()
                            + "：" + e.getMessage());
        }
        Object result;
        try {
            result = definition.handler().execute(bound);
        } catch (Exception e) {
            return new ToolFailure(name, ToolErrorKind.EXECUTION_ERROR,
                    "工具执行失败：" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        return new ToolSuccess(name, mapper.valueToTree(result));
    }

    private static ToolDefinition definitionFor(Object provider, Method method) {
        AgentTool annotation = method.getAnnotation(AgentTool.class);
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new ToolDeclarationException("@AgentTool method must be public: " + method);
        }
        if (method.getReturnType() == void.class) {
            throw new ToolDeclarationException("@AgentTool method must return a value: " + method);
        }
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length != 1 || !parameters[0].isRecord()) {
            throw new ToolDeclarationException("@AgentTool method " + method.getName()
                    + " must take exactly one record argument, but takes " + parameters.length
                    + " parameter(s)");
        }
        ToolHandler handler = arguments -> {
            try {
                return method.invoke(provider, arguments);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Error error) {
                    throw error;
                }
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw e;
            }
        };
        return ToolDefinition.of(annotation.name(), annotation.description(), annotation.permission(),
                parameters[0], handler);
    }
}
