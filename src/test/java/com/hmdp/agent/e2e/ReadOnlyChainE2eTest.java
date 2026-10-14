package com.hmdp.agent.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.agent.e2e.E2eFixtures.Call;
import com.hmdp.agent.e2e.E2eFixtures.ScriptedLlm;
import com.hmdp.agent.e2e.E2eFixtures.Turn;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import dev.lifeagent.event.AgentEvent;
import dev.lifeagent.event.ToolCallEvent;
import dev.lifeagent.loop.LoopResult;
import dev.lifeagent.loop.LoopStopReason;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level end-to-end tests: 「附近找个火锅店」 runs through the real production stack -
 * {@link AgentLoop} + {@code HookChain} + {@code ToolRegistry} + {@code ShopTools} +
 * {@code SpringShopCatalog} - down to recording fakes of the three application services.
 *
 * <p>The only fake above the services is the {@link ScriptedLlm} model. No Spring context, no MySQL,
 * no H2, no Redis, no socket. Each nested class maps to one requirement of
 * {@code docs/tasks/W2-6-e2e-readonly.md}; {@code docs/W2-6-E2E-READONLY.md} points at the method
 * that proves each one.</p>
 */
class ReadOnlyChainE2eTest {

    private static ShopType type(long id, String name) {
        return new ShopType().setId(id).setName(name);
    }

    private static Shop shop(long id, String name, long typeId, Long avgPrice, Integer score, Double distance) {
        return new Shop()
                .setId(id)
                .setName(name)
                .setTypeId(typeId)
                .setAvgPrice(avgPrice)
                .setScore(score)
                .setDistance(distance);
    }

    /** The default shop-type table these tests resolve categories against. */
    private static ShopType[] defaultTypes() {
        return new ShopType[] {type(1, "美食"), type(2, "火锅"), type(3, "KTV")};
    }

    private static String searchArgs(String category, int radius, Integer maxPrice) {
        return maxPrice == null
                ? "{\"category\":\"" + category + "\",\"radius\":" + radius + "}"
                : "{\"category\":\"" + category + "\",\"radius\":" + radius + ",\"maxPrice\":" + maxPrice + "}";
    }

    private static List<String> shopIds(JsonNode shops) {
        List<String> ids = new ArrayList<>();
        for (JsonNode shop : shops) {
            ids.add(shop.path("shopId").asText());
        }
        return ids;
    }

    private static List<String> shopNames(JsonNode shops) {
        List<String> names = new ArrayList<>();
        for (JsonNode shop : shops) {
            names.add(shop.path("name").asText());
        }
        return names;
    }

    // ------------------------------------------------------------------ 1. 整条链路

    @Nested
    class FullChain {

        @Test
        void nearbyHotpotRunsPlanCallResultAnswerAndAnswers() {
            E2eHarness harness = E2eHarness.create(defaultTypes())
                    .respondWith(shop(1, "顺时针火锅", 2, 88L, 46, 100.0));
            ScriptedLlm llm = new ScriptedLlm(
                    Turn.tools(call("c1", "search_nearby", searchArgs("火锅", 500, null))),
                    Turn.answer("为你找到 1 家 500 米内的火锅店：顺时针火锅（人均 88 元，评分 4.6）。"));

            LoopResult result = harness.run(llm, "附近找个火锅店");

            assertEquals(LoopStopReason.ANSWERED, result.stopReason(), "整条只读链路应给出最终回答");
            assertTrue(result.hasAnswer());
            assertEquals(
                    List.of("plan", "tool_call", "tool_result", "answer"),
                    harness.eventNames(),
                    "事件序列必须精确为 plan -> tool_call -> tool_result -> answer");
            assertEquals(2, llm.streamCalls(), "模型恰好被调用两次：一次要工具，一次给回答");
            // The real adapter and the real registry were on the path: the service was queried once.
            assertEquals(1, harness.shopService.callCount("quaryByShop"), "search_nearby 应经真实适配器落到假服务一次");
            // The real HookChain ran too: its recording post-tool hook observed the result.
            assertEquals(1, harness.postToolObservations.size(), "真实 HookChain 的 post-tool 钩子应被触发一次");
            assertTrue(harness.postToolObservations.get(0).contains("search_nearby"),
                    harness.postToolObservations.get(0));
        }
    }

    // ------------------------------------------------------------------ 2. 半径生效

    @Nested
    class RadiusFilter {

        @Test
        void radius500KeepsOnlyTheShopWithin500Meters() {
            Shop near = shop(1, "近火锅", 2, 60L, 42, 100.0);
            Shop mid = shop(2, "中火锅", 2, 60L, 42, 800.0);
            Shop far = shop(3, "远火锅", 2, 60L, 42, 2000.0);
            E2eHarness harness = E2eHarness.create(defaultTypes()).respondWith(near, mid, far);
            ScriptedLlm llm = new ScriptedLlm(
                    Turn.tools(call("c1", "search_nearby", searchArgs("火锅", 500, null))),
                    Turn.answer("只剩 1 家在 500 米内。"));

            harness.run(llm, "附近找个火锅店");

            // The service handed the adapter all three; the model must have received exactly one.
            assertEquals(1, harness.shopService.callCount("quaryByShop"));
            JsonNode received = llm.lastToolResultContent();
            assertTrue(received.isArray(), "工具结果应是店铺数组");
            assertEquals(1, received.size(), "半径 500 只有 1 家，模型收到的结果里必须只有 1 家");
            assertEquals(List.of("近火锅"), shopNames(received));
        }
    }

    // ------------------------------------------------------------------ 3. 预算生效

    @Nested
    class BudgetFilter {

        @Test
        void maxPriceCapsTheResultAndNullDoesNotFilter() {
            E2eHarness capped = E2eHarness.create(defaultTypes()).respondWith(
                    shop(1, "便宜火锅", 2, 50L, 40, 10.0),
                    shop(2, "贵火锅", 2, 90L, 40, 20.0));
            ScriptedLlm cappedLlm = new ScriptedLlm(
                    Turn.tools(call("c1", "search_nearby", searchArgs("火锅", 5000, 80))),
                    Turn.answer("按人均 80 元筛选后只剩便宜火锅。"));
            capped.run(cappedLlm, "找个人均 80 以内的火锅");

            JsonNode cappeded = cappedLlm.lastToolResultContent();
            assertEquals(1, cappeded.size(), "maxPrice=80 时 90 元的店必须被过滤掉");
            assertEquals(List.of("便宜火锅"), shopNames(cappeded));

            E2eHarness uncapped = E2eHarness.create(defaultTypes()).respondWith(
                    shop(1, "便宜火锅", 2, 50L, 40, 10.0),
                    shop(2, "贵火锅", 2, 90L, 40, 20.0));
            ScriptedLlm uncappedLlm = new ScriptedLlm(
                    Turn.tools(call("c1", "search_nearby", searchArgs("火锅", 5000, null))),
                    Turn.answer("没有预算上限，两家都推荐。"));
            uncapped.run(uncappedLlm, "附近找个火锅店");

            JsonNode all = uncappedLlm.lastToolResultContent();
            assertEquals(2, all.size(), "maxPrice=null 时预算不过滤");
            assertEquals(List.of("便宜火锅", "贵火锅"), shopNames(all));
        }
    }

    // ------------------------------------------------------------------ 4. 品类解析生效

    @Nested
    class CategoryResolution {

        @Test
        void hotpotIsResolvedToItsTypeIdThroughTheRealTypeService() {
            E2eHarness harness = E2eHarness.create(defaultTypes())
                    .respondWith(shop(1, "顺时针火锅", 2, 88L, 46, 100.0));
            ScriptedLlm llm = new ScriptedLlm(
                    Turn.tools(call("c1", "search_nearby", searchArgs("火锅", 500, null))),
                    Turn.answer("找到了。"));

            harness.run(llm, "附近找个火锅店");

            // The real adapter consulted the real type service, not a hard-coded map: once to
            // resolve 火锅 -> typeId=2, once to reverse-map the result's typeId back to a name.
            assertEquals(2, harness.shopTypeService.callCount("quaryById"), "品类名应经品类服务真解析与反查");
            Object[] args = harness.shopService.argumentsOf("quaryByShop").get(0);
            assertEquals(Integer.valueOf(2), args[0], "火锅 应解析为 typeId=2 再查店");
        }

        @Test
        void unknownCategoryReturnsEmptyWithoutThrowingOrQueryingShops() {
            E2eHarness harness = E2eHarness.create(defaultTypes());
            ScriptedLlm llm = new ScriptedLlm(
                    Turn.tools(call("c1", "search_nearby", searchArgs("不存在的品类", 500, null))),
                    Turn.answer("没有找到这类店铺。"));

            LoopResult result = harness.run(llm, "附近找个不存在的品类");

            assertEquals(LoopStopReason.ANSWERED, result.stopReason(), "解析不到品类不应抛，链路照常走完");
            assertEquals(0, harness.shopService.callCount("quaryByShop"), "解析不到品类时不该查店");
            JsonNode received = llm.lastToolResultContent();
            assertTrue(received.isArray(), "解析不到应返回空数组而不是错误");
            assertEquals(0, received.size(), "解析不到品类时模型收到空列表");
        }
    }

    // ------------------------------------------------------------------ 5. 只读性

    @Nested
    class ReadOnly {

        @Test
        void theWholeChainNeverCallsAWriteMethod() {
            E2eHarness harness = E2eHarness.create(defaultTypes())
                    .respondWith(shop(1, "顺时针火锅", 2, 88L, 46, 100.0))
                    .shopDetail(shop(1, "顺时针火锅", 2, 88L, 46, 100.0));
            ScriptedLlm llm = new ScriptedLlm(
                    Turn.tools(call("c1", "search_nearby", searchArgs("火锅", 500, null))),
                    Turn.tools(call("c2", "shop_detail", "{\"shopId\":1}")),
                    Turn.answer("详情已说明。"));

            harness.run(llm, "附近找个火锅店，看下详情");

            List<String> writes = harness.writeCalls();
            assertTrue(writes.isEmpty(), () -> "只读链路触发了写方法：" + writes);
            // Sanity: the read path really did run, so an empty write list is not vacuous.
            assertEquals(1, harness.shopService.callCount("quaryByShop"));
            assertEquals(1, harness.shopService.callCount("quaryById"));
        }
    }

    // ------------------------------------------------------------------ 6. 熔断生效

    @Nested
    class CircuitBreaker {

        @Test
        void fourthCallAfterThreeFailuresNeverEntersTheBusinessMethod() {
            E2eHarness harness = E2eHarness.create(defaultTypes())
                    .searchAlwaysFails(new IllegalStateException("下游数据库不可用"));
            ScriptedLlm llm = new ScriptedLlm(
                    Turn.tools(call("c1", "search_nearby", searchArgs("火锅", 500, null))),
                    Turn.tools(call("c2", "search_nearby", searchArgs("火锅", 500, null))),
                    Turn.tools(call("c3", "search_nearby", searchArgs("火锅", 500, null))),
                    Turn.tools(call("c4", "search_nearby", searchArgs("火锅", 500, null))),
                    Turn.answer("这个工具暂时不可用。"));

            LoopResult result = harness.run(llm, "附近找个火锅店");

            assertEquals(LoopStopReason.ANSWERED, result.stopReason());
            // Three real failures entered the business method; the fourth was short-circuited.
            assertEquals(3, harness.shopService.callCount("quaryByShop"),
                    "连续失败 3 次后第 4 次不得再进入业务方法");
            assertEquals(4, harness.events.stream().filter(e -> e instanceof ToolCallEvent).count(),
                    "四次 tool_call 都应出现（熔断仍是可观测的调用）");
            JsonNode fourth = llm.lastToolResultContent();
            assertTrue(fourth.isTextual() && fourth.asText().contains("熔断"),
                    () -> "第 4 次结果应是熔断提示，实际为：" + fourth);
        }
    }

    // ------------------------------------------------------------------ 7. 手动 trace

    @Nested
    class Trace {

        @Test
        void printTheFullConversationAsReadableEvidence() {
            E2eHarness harness = E2eHarness.create(defaultTypes())
                    .respondWith(shop(1, "顺时针火锅", 2, 88L, 46, 100.0));
            ScriptedLlm llm = new ScriptedLlm(
                    Turn.tools(call("c1", "search_nearby", searchArgs("火锅", 500, null))),
                    Turn.answer("为你找到 1 家 500 米内的火锅店：顺时针火锅（人均 88 元，评分 4.6）。"));

            LoopResult result = harness.run(llm, "附近找个火锅店");

            String trace = renderTrace(harness, llm, result);
            System.out.println(trace);

            assertTrue(trace.contains("⓵ plan"), trace);
            assertTrue(trace.contains("⓶ tool_call search_nearby"), trace);
            assertTrue(trace.contains("顺时针火锅"), trace);
            assertTrue(trace.contains("⓸ answer"), trace);
            assertEquals(LoopStopReason.ANSWERED, result.stopReason());
        }
    }

    /** Renders the session's events and tool arguments as the "it ran" evidence. */
    static String renderTrace(E2eHarness harness, ScriptedLlm llm, LoopResult result) {
        StringBuilder out = new StringBuilder();
        out.append("===== W2-6 只读闭环 trace：附近找个火锅店 =====\n");
        out.append("用户: 附近找个火锅店\n");
        out.append("stopReason: ").append(result.stopReason())
                .append("  toolCalls=").append(result.toolCalls())
                .append("  answer=\"").append(result.finalText()).append("\"\n");
        out.append("--- 事件序列 -----\n");
        String[] marks = {"⓵", "⓶", "⓷", "⓸", "⓹", "⓺"};
        int index = 0;
        for (AgentEvent event : harness.events) {
            String mark = index < marks.length ? marks[index] : "·";
            out.append(mark).append(' ').append(E2eHarness.eventName(event));
            if (event instanceof ToolCallEvent callEvent) {
                out.append(" ").append(callEvent.toolName())
                        .append(" ").append(callEvent.arguments());
            }
            out.append('\n');
            index++;
        }
        out.append("--- 模型最终收到的工具结果 -----\n");
        out.append(llm.lastToolResultContent().toPrettyString()).append('\n');
        return out.toString();
    }

    private static Call call(String id, String name, String json) {
        return E2eFixtures.call(id, name, json);
    }
}
