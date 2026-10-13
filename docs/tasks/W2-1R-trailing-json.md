# 任务卡 W2-1R — 修复工具参数尾部 JSON 被静默吞掉

难度：日常
交给：Codex（原执行器返工）
返工轮次：第 1 轮

## 背景

W2-1 异源复核（`docs/reviews/W2-1-review.md`）判**有条件通过**，唯一 must-fix：
`ToolUseAccumulator` 默认 Jackson 对尾随 token 宽容，两份 JSON 拼接时会静默返回第一份。

管理者已用 agent-core 精确 classpath 独立复现：

```
DEFAULT ObjectMapper.readTree("{...}{...}") → 静默返回第一份
启用 FAIL_ON_TRAILING_TOKENS → MismatchedInputException
```

这违反任务卡「解析失败必须明确报错、不得静默返回半截结果」。

## 只许写

- `agent-core/src/main/java/dev/lifeagent/llm/ToolUseAccumulator.java`
- `agent-core/src/test/java/dev/lifeagent/llm/ToolUseAccumulatorTest.java`
- `agent-core/src/test/java/dev/lifeagent/llm/LlmAdaptersTest.java`
- `agent-core/SELF-REVIEW-W2-1.md`（追加返工节）

禁止改 MCP 包、根项目、依赖、既有断言。

## 必须修

1. 默认 ObjectMapper 启用 `DeserializationFeature.FAIL_ON_TRAILING_TOKENS`（或等价严格耗尽校验）。
2. 测试：`append("{\"city\":\"上\"}")` + `append("{\"city\":\"上海\"}")` 后 End，
   必须抛 `LlmProtocolException`，不得返回第一份。
3. Anthropic 测试：`content_block_start.input` 非空 + 后续 `input_json_delta` 拼成两个 JSON，
   必须明确失败，不得静默截断。
4. 保持之前 46 条全绿且只能增加不能减少。

## 验收

```bash
mvn -f agent-core/pom.xml -B -ntp verify
mvn -B -ntp verify
```

## 自审

追加 `SELF-REVIEW-W2-1.md`：文件:行、测试名、真实结果。
做变异验证：临时去掉 `FAIL_ON_TRAILING_TOKENS`，新增两条至少一条必须变红；还原后全绿。

不 commit、不 push。
