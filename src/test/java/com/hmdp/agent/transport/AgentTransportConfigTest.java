package com.hmdp.agent.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lifeagent.event.AgentEvent;
import dev.lifeagent.loop.AgentLoop;
import dev.lifeagent.loop.SessionStore;
import dev.lifeagent.tool.ShopCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Checks the Spring wiring of the transport layer <em>without</em> booting the web application and
 * without any network call.
 *
 * <p>No {@code @SpringBootTest}: the root project has no test-scoped database or Redis, and this card
 * must not add one. The configuration is instantiated in a plain context with a stub
 * {@link ShopCatalog}, which is enough to prove the beans exist, the session store is shared, and no
 * bean blocks or reaches out. The end-to-end loop behaviour is covered by
 * {@link AgentConversationSseTest} with a fake {@code LlmClient}.</p>
 */
class AgentTransportConfigTest {

    private static final String KEY = "sk-fake-key-for-wiring-only-42";

    private static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setEnvironment(new MockEnvironment()
                .withProperty("hmdp.agent.llm.api-key", KEY)
                .withProperty("hmdp.agent.llm.endpoint", "https://api.example.test/v1")
                .withProperty("hmdp.agent.llm.provider", "openai")
                .withProperty("hmdp.agent.llm.model", "test-model")
                .withProperty("hmdp.agent.prompt.system-prompt", "系统提示"));
        context.registerBean(ShopCatalog.class, AgentTransportFixtures.CountingCatalog::new);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.register(AgentTransportConfig.class);
        context.refresh();
        return context;
    }

    @Test
    void wiredBeansArePresentAndTheSessionStoreIsASingleton() {
        try (AnnotationConfigApplicationContext context = context()) {
            assertNotNull(context.getBean(AgentConversationController.class));
            assertNotNull(context.getBean(AgentEventStream.class));
            assertSame(context.getBean(SessionStore.class), context.getBean(SessionStore.class),
                    "session store 必须是单例，挂起/恢复才看得到同一份状态");
        }
    }

    @Test
    void theLoopFactoryBuildsALoopBoundToTheGivenSink() {
        try (AnnotationConfigApplicationContext context = context()) {
            AgentLoopFactory factory = context.getBean(AgentLoopFactory.class);
            List<AgentEvent> events = new ArrayList<>();

            AgentLoop loop = factory.create(events::add);

            // Construction must never call the model or block; the sink is wired, not invoked.
            assertNotNull(loop);
            assertEquals(0, events.size(), "构建 loop 不应产生任何事件");
            // The assertion message promises shared state, so it must be proven with a second loop:
            // two loops from the same factory must carry the *same* breaker instance, otherwise the
            // "three consecutive failures" count would be per-request instead of per-process.
            AgentLoop second = factory.create(events::add);
            assertSame(loop.breaker(), second.breaker(),
                    "工厂里的所有 loop 必须共享同一个熔断器");
        }
    }

    @Test
    void settingsFallBackToDefaultsAndRejectBlankOverrides() {
        AgentTransportSettings settings = new AgentTransportSettings("默认模型", "默认提示");

        assertEquals("默认模型", settings.modelOr(null));
        assertEquals("默认模型", settings.modelOr("  "));
        assertEquals("指定模型", settings.modelOr("指定模型"));
        assertEquals("默认提示", settings.systemPromptOr(null));
        assertNull(new AgentTransportSettings(null, null).modelOr(null));
        assertNull(new AgentTransportSettings(null, null).systemPromptOr(null));
    }

    @Test
    void applicationYamlNeverCarriesALiteralCredential() throws Exception {
        String yaml;
        try (var in = getClass().getClassLoader().getResourceAsStream("application.yaml")) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertFalse(yaml.contains(KEY), "application.yaml 不得包含任何真实密钥");
        Matcher credential = Pattern
                .compile("(?im)^\\s*(api[-_]?key|password|secret|token)\\s*:\\s*(\\S.*)$")
                .matcher(yaml);
        while (credential.find()) {
            assertEquals("${", credential.group(2).substring(0, 2),
                    credential.group(1) + " 的值必须是环境变量占位");
        }
    }
}
