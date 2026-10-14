package com.hmdp.agent.e2e;

import com.hmdp.agent.SearchOrigin;
import com.hmdp.agent.SpringShopCatalog;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IVoucherService;
import dev.lifeagent.event.AgentEvent;
import dev.lifeagent.event.AnswerEvent;
import dev.lifeagent.event.ConfirmRequiredEvent;
import dev.lifeagent.event.PlanEvent;
import dev.lifeagent.event.ThinkingEvent;
import dev.lifeagent.event.ToolCallEvent;
import dev.lifeagent.event.ToolResultEvent;
import dev.lifeagent.hook.HookChain;
import dev.lifeagent.hook.PostToolHook;
import dev.lifeagent.loop.AgentLoop;
import dev.lifeagent.loop.InMemorySessionStore;
import dev.lifeagent.loop.LoopBudget;
import dev.lifeagent.loop.LoopConfig;
import dev.lifeagent.loop.LoopResult;
import dev.lifeagent.loop.LoopSession;
import dev.lifeagent.loop.PermissionGate;
import dev.lifeagent.tool.ShopTools;
import dev.lifeagent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Wires the real read-only stack for one end-to-end turn.
 *
 * <p>The only fake is the {@code LlmClient}; everything between the loop and the "database" is a
 * production class: {@link AgentLoop} + {@link HookChain} + {@link ToolRegistry} + {@link ShopTools}
 * + {@link SpringShopCatalog}. The three application services below the adapter are recording
 * {@link java.lang.reflect.Proxy} stubs, so no MySQL, Redis or socket is involved.</p>
 *
 * <pre>
 *   ScriptedLlm
 *       |  LlmRequest (history + tool result JSON)
 *       v
 *   AgentLoop --- HookChain (empty + one recording post-tool hook)
 *       |  ToolCallRequest
 *       v
 *   ToolRegistry -> ShopTools -> SpringShopCatalog   (real adapter)
 *                                     |  quaryByShop / quaryById / queryVoucherOfShop / quaryById
 *                                     v
 *                        RecordingService(IShopService / IVoucherService / IShopTypeService)
 * </pre>
 *
 * <p>The loop's event sink is fixed at construction, so a loop is built per run through
 * {@link #loopOn(AgentLoop)} / {@link #run} - exactly the seam {@code AgentTransportConfig} uses. All
 * loops built by one harness share one circuit breaker, the same way a process shares one.</p>
 */
final class E2eHarness {

    /** The configured search origin; the adapter rejects a null/blank origin. */
    static final SearchOrigin ORIGIN = new SearchOrigin(120.15, 30.31);

    /** The breaker-holder loop is never run; this client fails loudly if that ever changes. */
    private static final dev.lifeagent.llm.LlmClient NEVER_CALLED = (request, events) -> {
        throw new AssertionError("breaker-holder loop 不应被运行");
    };

    final E2eFixtures.RecordingService<IShopService> shopService =
            new E2eFixtures.RecordingService<>(IShopService.class);
    final E2eFixtures.RecordingService<IVoucherService> voucherService =
            new E2eFixtures.RecordingService<>(IVoucherService.class);
    final E2eFixtures.RecordingService<IShopTypeService> shopTypeService =
            new E2eFixtures.RecordingService<>(IShopTypeService.class);

    /** The exact events the loop pushed, in emission order. */
    final List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
    /** One line per post-tool hook observation, proving the hook point really ran. */
    final List<String> postToolObservations = Collections.synchronizedList(new ArrayList<>());

    private final SpringShopCatalog catalog;
    private final ToolRegistry registry;
    private final HookChain hooks;
    private final AgentLoop breakerSource;

    private E2eHarness(List<ShopType> types, SearchOrigin origin) {
        shopTypeService.on("quaryById", Result.ok(new ArrayList<>(types)));
        shopService.on("quaryByShop", (E2eFixtures.Answer<Result>) args -> Result.ok(List.of()));
        voucherService.on("queryVoucherOfShop", (E2eFixtures.Answer<Result>) args -> Result.ok(List.of()));

        this.catalog = new SpringShopCatalog(
                shopService.service(), voucherService.service(), shopTypeService.service(), origin);
        this.registry = new ToolRegistry().registerTools(new ShopTools(catalog));

        PostToolHook recorder = (context, callId, toolName, isError, content) ->
                postToolObservations.add(toolName + " isError=" + isError + " content=" + content);
        this.hooks = new HookChain(List.of(), List.of(), List.of(), List.of(recorder));

        // denyAll is the production default; the read-only tools never reach the gate.
        // This "source" loop is never run - it only hands out the shared breaker, so its model
        // client can be a stub that fails if it is ever actually asked for a turn.
        this.breakerSource = AgentLoop.create(NEVER_CALLED, registry, hooks, PermissionGate.denyAll());
    }

    /** The real production stack with no origin override (the configured default). */
    static E2eHarness create(ShopType... types) {
        return new E2eHarness(Arrays.asList(types), ORIGIN);
    }

    static E2eHarness withOrigin(SearchOrigin origin, ShopType... types) {
        return new E2eHarness(Arrays.asList(types), origin);
    }

    /** Makes every {@code quaryByShop} answer with {@code shops}. */
    E2eHarness respondWith(Shop... shops) {
        List<Shop> list = new ArrayList<>(Arrays.asList(shops));
        return respondWith(() -> list);
    }

    /** Makes every {@code quaryByShop} answer with the list the supplier produces at call time. */
    E2eHarness respondWith(Supplier<List<Shop>> shops) {
        shopService.on("quaryByShop", (E2eFixtures.Answer<Result>) args -> Result.ok(shops.get()));
        return this;
    }

    /** Makes every {@code quaryByShop} fail, the way a broken downstream would. */
    E2eHarness searchAlwaysFails(RuntimeException failure) {
        shopService.on("quaryByShop", failure);
        return this;
    }

    /** Makes {@code quaryById} answer one shop, for the {@code shop_detail} path. */
    E2eHarness shopDetail(Shop shop) {
        shopService.on("quaryById", Result.ok(shop));
        return this;
    }

    /** Builds a real loop bound to {@code llm}, sharing this harness's breaker and event sink. */
    AgentLoop loopOn(E2eFixtures.ScriptedLlm llm) {
        return new AgentLoop(llm, registry, hooks, PermissionGate.denyAll(),
                new InMemorySessionStore(), breakerSource.breaker(), events::add);
    }

    /** Runs the real loop once with the default budget. */
    LoopResult run(E2eFixtures.ScriptedLlm llm, String userMessage) {
        return run(llm, userMessage, LoopBudget.defaults());
    }

    /** Runs the real loop once, injecting the scripted model and a budget. */
    LoopResult run(E2eFixtures.ScriptedLlm llm, String userMessage, LoopBudget budget) {
        return runOn(loopOn(llm), null, userMessage, budget);
    }

    /** Runs a caller-built loop; {@code session} may be {@code null} for a fresh turn. */
    LoopResult runOn(AgentLoop loop, LoopSession session, String userMessage, LoopBudget budget) {
        return loop.run(new LoopConfig("e2e-session", "你是探店规划助手", null, userMessage, budget), session);
    }

    SpringShopCatalog catalog() {
        return catalog;
    }

    ToolRegistry registry() {
        return registry;
    }

    List<String> writeCalls() {
        List<String> writes = new ArrayList<>();
        writes.addAll(shopService.writeCalls());
        writes.addAll(voucherService.writeCalls());
        writes.addAll(shopTypeService.writeCalls());
        return writes;
    }

    /** The event type names the loop pushed, in order, e.g. {@code plan, tool_call, ...}. */
    List<String> eventNames() {
        List<String> names = new ArrayList<>();
        for (AgentEvent event : events) {
            names.add(eventName(event));
        }
        return names;
    }

    /** The event's short contract name (the ones the SSE frames use). */
    static String eventName(AgentEvent event) {
        return switch (event) {
            case PlanEvent ignored -> "plan";
            case ToolCallEvent ignored -> "tool_call";
            case ToolResultEvent ignored -> "tool_result";
            case ThinkingEvent ignored -> "thinking";
            case ConfirmRequiredEvent ignored -> "confirm_required";
            case AnswerEvent ignored -> "answer";
        };
    }
}
