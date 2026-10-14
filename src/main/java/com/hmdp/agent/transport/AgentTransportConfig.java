package com.hmdp.agent.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lifeagent.hook.HookChain;
import dev.lifeagent.llm.AnthropicMessagesAdapter;
import dev.lifeagent.llm.LlmClient;
import dev.lifeagent.llm.LlmHttpConfig;
import dev.lifeagent.llm.OpenAiCompatibleAdapter;
import dev.lifeagent.llm.RetryPolicy;
import dev.lifeagent.loop.AgentLoop;
import dev.lifeagent.loop.InMemorySessionStore;
import dev.lifeagent.loop.PermissionGate;
import dev.lifeagent.loop.SessionStore;
import dev.lifeagent.tool.ShopCatalog;
import dev.lifeagent.tool.ShopTools;
import dev.lifeagent.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires the read-only planner loop behind the SSE endpoint.
 *
 * <p>Every credential comes from an environment variable: {@code AGENT_LLM_API_KEY} is read into a
 * local, put into a request header and never logged, echoed or defaulted to a real value. Nothing
 * here changes business state; the only tools registered are agent-core's read-only shop tools.</p>
 *
 * <p>The {@link AgentLoopFactory} bean is the seam the transport tests replace with a loop backed by
 * a fake {@code LlmClient}, so no test ever reaches the network. The factory closes over one loop
 * instance whose {@link SessionStore} and circuit breaker are shared with every loop it builds, so
 * suspend/resume and the breaker see one consistent state.</p>
 */
@Configuration
public class AgentTransportConfig {

    /** Placeholder so the app boots without a key; a real call then fails closed as a data error. */
    private static final String NO_KEY_PLACEHOLDER = "UNSET";

    /**
     * @param settings     non-secret defaults (model, system prompt)
     * @param sessionStore the shared session store
     * @param stream       the frame mapper
     * @param mapper       Jackson, for the optional {@code origin} argument
     * @return the SSE conversation controller
     */
    @Bean
    public AgentConversationController agentConversationController(
            AgentLoopFactory loopFactory,
            SessionStore sessionStore,
            AgentEventStream stream,
            ObjectMapper mapper,
            AgentTransportSettings settings) {
        return new AgentConversationController(loopFactory, sessionStore, stream, mapper, settings);
    }

    /**
     * @param apiKey       the provider key, from the environment; never logged
     * @param endpoint     the provider base URL
     * @param provider     {@code anthropic} or an OpenAI-compatible provider
     * @param shopCatalog  the read-only catalog adapter
     * @param sessionStore the shared session store
     * @return the per-request loop factory the controller drives
     */
    @Bean
    public AgentLoopFactory agentLoopFactory(
            @Value("${hmdp.agent.llm.api-key}") String apiKey,
            @Value("${hmdp.agent.llm.endpoint}") String endpoint,
            @Value("${hmdp.agent.llm.provider}") String provider,
            ShopCatalog shopCatalog,
            SessionStore sessionStore) {
        LlmClient llm = llmClient(apiKey, endpoint, provider);
        ToolRegistry registry = new ToolRegistry().registerTools(new ShopTools(shopCatalog));
        HookChain hooks = HookChain.empty();
        // Fail closed: the read-only tools never reach a gate, and any write/high-risk tool would be
        // refused if one were ever registered.
        PermissionGate gate = PermissionGate.denyAll();
        AgentLoop breakerSource = AgentLoop.create(llm, registry, hooks, gate);
        return sink -> new AgentLoop(llm, registry, hooks, gate, sessionStore,
                breakerSource.breaker(), sink);
    }

    /**
     * @param model        the default model id
     * @param systemPrompt the planner's system prompt
     * @return the non-secret endpoint defaults
     */
    @Bean
    public AgentTransportSettings agentTransportSettings(
            @Value("${hmdp.agent.llm.model}") String model,
            @Value("${hmdp.agent.prompt.system-prompt}") String systemPrompt) {
        return new AgentTransportSettings(model, systemPrompt);
    }

    /**
     * @return the in-process session store the SSE endpoint reads {@code confirm_required} back from
     */
    @Bean
    public SessionStore agentSessionStore() {
        return new InMemorySessionStore();
    }

    /**
     * @param mapper Spring's mapper, shared with the event stream
     * @return the six-event -&gt; SSE frame mapper
     */
    @Bean
    public AgentEventStream agentEventStream(ObjectMapper mapper) {
        return new AgentEventStream(mapper);
    }

    private static LlmClient llmClient(String apiKey, String endpoint, String provider) {
        Map<String, String> headers = new LinkedHashMap<>();
        String key = apiKey == null || apiKey.isBlank() ? NO_KEY_PLACEHOLDER : apiKey;
        if ("anthropic".equalsIgnoreCase(provider)) {
            headers.put("x-api-key", key);
            headers.put("anthropic-version", "2023-06-01");
            return new AnthropicMessagesAdapter(new LlmHttpConfig(URI.create(endpoint), headers,
                    Duration.ofMinutes(2), RetryPolicy.defaults()));
        }
        headers.put("Authorization", "Bearer " + key);
        return new OpenAiCompatibleAdapter(new LlmHttpConfig(URI.create(endpoint), headers,
                Duration.ofMinutes(2), RetryPolicy.defaults()));
    }
}
