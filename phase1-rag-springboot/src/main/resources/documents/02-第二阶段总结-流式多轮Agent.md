# 第 2 阶段学习总结：流式响应 + 多轮对话 + AI Agent

> **目标**：把 RAG 系统从"单次问答"升级成"像 ChatGPT 那样的连续对话"，再进化成"会自己调用工具的 Agent"
> **技术栈**：SSE 流式、对话历史管理、问题重写、手写 ReAct Agent
> **关键洞察**：① RAG 的天花板是知识库本身 ② Agent = 大脑决策 + 工具执行

---

## 一、核心概念（大白话）

### 1. 流式响应（SSE）：让答案逐字蹦出来

**问题**：现在 AI 回答要等 5-10 秒，屏幕一片空白，用户以为卡死了。

**解决**：像 ChatGPT 那样，一个字一个字蹦出来（打字机效果）。

**技术原理**：
```
非流式（旧）：
  LLM 生成完整个答案 → 一次性返回 → 用户看到（等 10 秒）

流式（新）：
  LLM 每生成一小段 → 立刻推给用户 → 用户实时看到（1 秒就有第一个字）
```

**核心价值**：
- 总时间其实差不多，但**首字延迟**从 10 秒降到 1 秒
- 用户看到"在动"，不会以为程序卡死
- 体感速度快很多

**SSE vs WebSocket 的区别**：
- **SSE**（我们用的）：服务器 → 浏览器**单向**推送（像收音机广播）
- **WebSocket**：双向实时通信（像打电话）
- AI 回答是单向的，SSE 刚好够用，而且更简单

---

### 2. 多轮对话：让 AI "记住"聊了什么

**问题**：AI 天生是"金鱼记忆"——每次提问都是独立的，不记得上一句。

```
你：Spring Boot 默认用什么连接池？
AI：HikariCP。

你：它有什么优点？
AI：？？？ "它"是谁？我不记得刚才聊过 HikariCP。
```

**解决**：每次提问，把历史对话也一起发给 AI。

**技术原理**：
```
单轮（AI 断片）：
  发给 AI = "它有什么优点？"
  AI 懵了：它是谁？

多轮（带历史）：
  发给 AI = "
    [历史] 用户：Spring Boot 默认用什么连接池？
    [历史] AI：HikariCP。
    [当前] 用户：它有什么优点？
  "
  AI 看懂了：哦，"它"=HikariCP。
```

**一句话**：AI 不是真的记住了，是我们每次都"提醒"它。

**两个关键设计**：
- **会话 ID**：区分不同对话（就像微信每个聊天窗口是独立的）
- **滑动窗口**：只保留最近 5 轮（否则对话 100 轮，历史太长会超出 AI 容量）

---

### 3. 问题重写：解决"检索失焦"

**问题更深层**：多轮对话有两个阶段——

```
RAG 流程：
  【阶段 1：检索】去知识库搜片段  ← 像用搜索引擎
  【阶段 2：生成】AI 拿着片段写答案  ← AI 登场
```

之前只给**阶段 2** 加了历史，**阶段 1** 没加，所以：

```
你：它有什么优点？

【阶段 1 检索】：
  拿"它有什么优点"去搜 → 没有"HikariCP"关键词 → 搜错了
  
【阶段 2 生成】：
  AI 知道"它"=HikariCP（因为有历史）
  但拿到的片段是错的（阶段 1 搜错了）→ 答案跑偏
```

**核心矛盾**：
- **生成有记忆**（我们给了历史）✅
- **检索没记忆**（搜索时只用了孤零零的"它有什么优点"）❌

**解决：问题重写**

在检索之前，先让 AI 把含糊的问题改写成完整的：

```
原始问题："它有什么优点？"
   ↓ 让 AI 结合历史改写
改写后："HikariCP 有什么优点？"
   ↓ 用这个完整问题去搜索
搜索引擎现在有关键词了 → 搜得准 ✅
```

**代价**：每轮多一次 LLM 调用（延迟+成本增加）。生产环境可以用更快的小模型专门做重写。

---

### 4. ⭐ 最深刻的一课：RAG 的天花板是知识库本身

**发现过程**：
- 实测"它有什么优点？"（历史里"它"=HikariCP）
- 问题重写【完美成功】：`「它有什么优点？」->「HikariCP 有什么优点？」`（日志证实）
- 但答案仍然跑偏，答成了"Spring Boot 的优点"

**排查**：
```bash
grep -rn "HikariCP" data/documents/
# 结果：只有 2 句话（"默认连接池""监控工具"），没有任何"HikariCP 优点"内容
```

**真相**：知识库里根本没有"HikariCP 优点"这块内容，检索只能匹配最接近的"Spring Boot 特性"，答案就跑偏了。

**核心结论**：
> **文档里没有的内容，再完美的检索、重写、Prompt 都变不出来。**
> **RAG 不"创造"知识，只"找到并组织"已有知识。**

**对照验证**（证明重写技术没问题）：
- 问"Redis max-wait 是什么意思？"（知识库里**有**这块内容）
- 重写对 + 检索准 + 库里有 → 答对 ✅

**正确的排查顺序**：
```
答案不对？
  1. 先 grep 文档 → 知识库有没有这块内容？
       没有 → 补文档（再多调参也没用）
       有  → 继续往下
  2. 看检索 → /query/details 看片段对不对
       不对 → 调 Embedding/chunk/重写
       对   → 继续往下
  3. 看 Prompt → 是不是引导有问题
```

### 5. AI Agent：从"问答机器人"到"智能体"

**RAG vs Agent 的本质区别**

你之前学的 RAG，流程是**写死的**：

```
用户提问 → 检索 → 生成 → 返回
（每次都走这条路，一步不差）
```

Agent 的核心是——**LLM 自己决定下一步做什么**：

```
用户提问
   ↓
LLM 思考："这个问题我该怎么处理？"
   ├─ 需要查知识库？ → 调用检索工具
   ├─ 需要算数？ → 调用计算器工具
   ├─ 需要查实时信息？ → 调用搜索工具
   ├─ 信息够了？ → 直接回答
   └─ 需要多步？ → 一步步来，每步后再思考
```

**关键差异**：

| | RAG | Agent |
|---|-----|-------|
| **流程** | 固定（检索→生成）| 动态（LLM 自己决定）|
| **能力** | 只能查知识库 | 可调用多种工具 |
| **思考** | 不思考，照做 | 边做边思考下一步 |
| **类比** | 自动售货机（投币出货）| 助理（你说需求，他自己想办法）|

**一句话精髓**：
> **Agent = LLM 大脑负责"决策和拆解"，工具负责"傻瓜式执行"。**
> **LLM 把问题拆成若干步，每一步决定调用哪个工具；工具执行完把结果返回；LLM 拿到结果继续决策下一步——大脑动脑，手脚干活，循环配合，直到完成。**

**工具调用的技术原理**

LLM 本身不能真的执行代码，工具调用的机制是：

```
1. 你告诉 LLM："你有这些工具，需要时告诉我调用哪个"
   ↓
2. LLM 决定调用 → 输出特殊格式：{"type":"tool_call","tool":"calculator","input":"8*10"}
   ↓
3. 你的程序解析输出，真的执行 calculator(8*10)
   ↓
4. 把结果"80"再发回给 LLM
   ↓
5. LLM 拿到结果，继续思考或给出最终答案
```

**ReAct 循环（Reasoning + Acting）**

Agent 最经典的工作模式：

```
循环：
  Thought（思考）：我现在需要做什么？
  Action（行动）：调用某个工具
  Observation（观察）：看工具返回的结果
  → 回到 Thought，继续判断
  
直到：信息足够 → 给出最终答案
```

**真实例子（项目实测）**：

用户问："知识库里 Redis 的 max-active 是多少？把它乘以 10 是多少？"

```
第1步 Thought：需要先查 max-active 的值
      Action：调用 knowledge_search("Redis max-active")
      Observation：检索到 max-active=8

第2步 Thought：现在知道是 8，需要乘以 10
      Action：调用 calculator("8 * 10")
      Observation：80

第3步 Thought：信息够了，可以回答
      Final Answer："max-active 是 8，乘以 10 等于 80"
```

**这个"先查知识库 → 再用计算器 → 综合回答"的三步规划，没有任何一行代码写死，全是 LLM 自己想出来的。**

**现实应用场景**

- **电商智能客服**：查订单 + 查物流 + 查政策 + 计算时间 → 判断能否退货
- **企业数据分析**：text2sql 查数据库 + 计算器算百分比 → 生成报告
- **编程助手**（Claude Code）：Read 文件 + Write 代码 + Bash 编译 + 修复错误
- **智能差旅**：查机票 + 查酒店 + 查差旅标准 + 查日历 → 规划出差

**什么时候用 RAG，什么时候用 Agent？**

| 你的需求 | 用什么 |
|---------|--------|
| "Spring Boot 怎么配置 Redis？"（查文档就够） | **RAG** |
| "我的订单能退吗？"（要查订单+算时间+查政策） | **Agent** |
| 单一数据源、固定流程 | **RAG** |
| 多数据源、动态多步、要调用各种系统 | **Agent** |

---

## 二、技术实现架构

### 完整技术栈

```
前端：原生 JS + fetch + ReadableStream（因为 EventSource 不支持 POST）
后端：Spring Boot + LangChain4j + 自定义流式方法
通信：SSE（Server-Sent Events）
存储：ConversationService（内存 Map，滑动窗口）
```

### 三层架构

```
Controller 层：/chat/stream
   ↓ SseEmitter + 独立线程池
Service 层：queryStreamWithHistory()
   ↓ 问题重写 → 检索 → 流式生成 → 存历史
Model 层：IflytekClaudeChatModel.generateStream()
   ↓ HTTP SSE 流式调用 Claude API
```

---

##三、关键代码解析

### 后端核心：RagService.queryStreamWithHistory()

**作用**：多轮 + 流式 + 问题重写的集大成方法。

```java
public void queryStreamWithHistory(
    String conversationId,          // 会话ID，区分不同对话
    String question,                // 当前问题
    Consumer<String> onPartial,     // 回调：每收到一段
    Consumer<String> onComplete,    // 回调：完整答案
    Consumer<Throwable> onError     // 回调：出错
) {
    // Step 0: 问题重写（结合历史）
    List<ChatMessage> history = conversationService.getHistory(conversationId);
    String searchQuery = rewriteQuestion(history, question);
    // 重写："它有什么优点？" → "HikariCP 有什么优点？"

    // Step 1: 用改写后的问题检索（检索层也有记忆了）
    List<TextSegment> relevantSegments = retrieveRelevantSegments(searchQuery);

    // Step 2: 拼 Prompt（用原始问题，保持对话自然）
    String augmentedPrompt = buildAugmentedPrompt(question, relevantSegments);

    // Step 3: 组装消息列表 = 历史 + 当前问题
    List<ChatMessage> messages = new ArrayList<>(history);
    messages.add(UserMessage.from(augmentedPrompt));

    // Step 4: 流式调用 LLM
    IflytekClaudeChatModel streamModel = (IflytekClaudeChatModel) chatModel;
    
    // 包装 onComplete：完成时自动存历史
    Consumer<String> wrappedComplete = fullAnswer -> {
        conversationService.addExchange(conversationId, question, fullAnswer);
        onComplete.accept(fullAnswer);
    };

    streamModel.generateStream(messages, onPartial, wrappedComplete, onError);
}
```

**关键设计点**：
1. **检索用 `searchQuery`（改写后），Prompt 用 `question`（原始）**
   - 检索准确 + 对话自然
2. **`wrappedComplete` 自动存历史**
   - 上层（Controller）无需关心历史管理
3. **第一轮无历史时，`rewriteQuestion` 直接返回原问题**
   - 省一次 LLM 调用

---

### 后端接口：RagController.chatStream()

**作用**：包装成 HTTP SSE 接口。

```java
@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter chatStream(@RequestBody ChatRequest request) {
    SseEmitter emitter = new SseEmitter(120_000L);  // 120秒超时

    // 独立线程跑，不阻塞 Web 线程
    streamExecutor.execute(() -> {
        ragService.queryStreamWithHistory(
            request.getConversationId(),
            request.getQuestion(),
            
            // onPartial：每收到一段，立刻推给前端
            partial -> {
                try {
                    emitter.send(SseEmitter.event().data(partial));
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            },
            
            // onComplete：全部完成，关闭连接
            full -> emitter.complete(),
            
            // onError：出错，带错误关闭
            emitter::completeWithError
        );
    });

    return emitter;  // 立刻返回，不阻塞
}
```

**关键知识点**：
- **`SseEmitter`**：Spring 的 SSE 工具，保持 HTTP 长连接持续推数据
- **独立线程池**：`queryStreamWithHistory` 是阻塞的（等 LLM 吐字），不能占用 Web 线程
- **立刻返回 `emitter`**：HTTP 连接保持打开，后台持续推数据

---

### 前端核心：会话管理 + 流式接收

**生成会话 ID**：
```javascript
// 页面加载时生成，刷新 = 新会话，不刷新 = 继续对话
const conversationId = 'session_' + Date.now();
```

**调用流式多轮接口**：
```javascript
async function ask() {
    const question = input.value.trim();
    addMessage(question, 'user');

    const botMsg = addMessage('', 'bot');
    botMsg.classList.add('cursor');  // 闪烁光标
    let fullText = '';

    const resp = await fetch('/api/rag/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            conversationId: conversationId,  // ← 每次都带上
            question: question
        })
    });

    // 读取流式响应
    const reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });

        // SSE 数据以 \n\n 分隔每个事件
        const events = buffer.split('\n\n');
        buffer = events.pop();  // 最后一段可能不完整，留到下次

        for (const event of events) {
            const text = event.split('\n')
                .filter(l => l.startsWith('data:'))
                .map(l => l.slice(5))  // 去掉 "data:" 前缀
                .join('\n');
            
            fullText += text;
            botMsg.textContent = fullText;  // 逐段更新显示
            chat.scrollTop = chat.scrollHeight;
        }
    }

    botMsg.classList.remove('cursor');  // 移除光标
}
```

**关键流程**：
1. **`reader.read()`** 读取字节流（服务器推一段，这里读一段）
2. **`decoder.decode()`** 字节 → UTF-8 文本
3. **`buffer.split('\n\n')`** 按 SSE 规范切分事件（每个事件以空行结尾）
4. **`buffer = events.pop()`** 处理跨读取边界（最后一段可能不完整）
5. **提取 `data:` 内容，累积显示** → 打字机效果

**为什么不用 `EventSource`（浏览器原生 SSE API）？**
- `EventSource` 只支持 GET 请求，不能发 POST
- 我们的接口需要 POST body（带 `conversationId` 和 `question`）
- 所以用 `fetch` + `ReadableStream` 手动接收

---

## 四、完整数据流（从点击到显示）

这是理解整个系统最重要的流程图：

```
【前端】用户输入"它有什么优点？"，点发送
   ↓
   生成请求 { conversationId: "session_1719214567890", question: "它有什么优点？" }
   ↓
   fetch('/api/rag/chat/stream', { POST body })
   ↓
【后端 Controller】收到请求
   ↓
   创建 SseEmitter，启动独立线程
   ↓
【后端 RagService】queryStreamWithHistory
   ↓
   Step 0: 取历史（发现上一轮是"Spring Boot 默认连接池？" → "HikariCP"）
   ↓
   Step 0: 问题重写："它有什么优点？" → "HikariCP 有什么优点？"
   ↓
   Step 1: 用改写后的问题检索 → 找到片段
   ↓
   Step 2: 拼 Prompt（用原始问题 + 片段）
   ↓
   Step 3: 组装消息列表 = [历史Q1, A1, 当前Q]
   ↓
   Step 4: 调用 LLM 流式生成
   ↓
【Claude API】一段段吐字
   "Hik" → "ariCP" → " 的" → "优" → "点" → ...
   ↓
【后端 IflytekClaudeChatModel】每收到一段
   ↓
   回调 onPartial("Hik")
   ↓
【后端 Controller】onPartial 里调 emitter.send("Hik")
   ↓
   通过 SSE 推给前端：data:Hik\n\n
   ↓
【前端】reader.read() 收到字节流
   ↓
   解码 → 切分 → 提取 "Hik"
   ↓
   fullText += "Hik"
   ↓
   botMsg.textContent = fullText（显示 "Hik"）
   ↓
   （继续循环，直到 LLM 生成完）
   ↓
【后端】onComplete 触发
   ↓
   把这轮 Q&A 存进 ConversationService
   ↓
   emitter.complete() 关闭 SSE 连接
   ↓
【前端】reader.read() 返回 { done: true }
   ↓
   退出 while 循环，移除闪烁光标
```

**一句话总结**：问题经过重写、检索、生成，答案逐段从 LLM 流到前端，同时后端在"记笔记"（存历史），整个流程像一条"生产线"。

---

## 五、关键设计亮点总结

| 层 | 关键设计 | 为什么这样设计 |
|---|---------|---------------|
| **Service** | `wrappedComplete` 包装 | 流式完成时自动存历史，上层无需关心 |
| **Service** | 检索用 `searchQuery`，Prompt 用 `question` | 检索准确 + 对话自然 |
| **Controller** | 独立线程池 `streamExecutor` | 不阻塞 Web 线程，并发能力强 |
| **Controller** | `SseEmitter` 立刻返回 | HTTP 长连接保持，后台持续推 |
| **前端** | `buffer.split('\n\n')` + `pop()` | 处理 SSE 事件跨读取边界 |
| **前端** | 每次都发 `conversationId` | 后端靠它区分会话、找历史 |
| **前端** | 页面加载时生成 ID | 刷新 = 新会话，不刷新 = 继续对话 |

---

## 六、AI Agent 实现（手写 ReAct）

### 架构设计

选择**手写 ReAct 而非 LangChain4j 内置 Agent**，原因：
- 原理透明：能看清每一步思考-行动-观察
- 不依赖 Function Calling：避免讯飞网关可能不支持的坑
- 学得最扎实：自己实现一遍，比用框架黑盒更懂 Agent 本质

**核心架构**：
```
Tool（接口）
  ├── CalculatorTool      计算器（自写四则运算递归下降解析器）
  └── KnowledgeSearchTool 知识库检索（复用 RagService.searchKnowledge）

AgentService（ReAct 循环核心）
  └── run(question)
       循环最多 5 次：
         1. buildPrompt（问题 + 工具说明 + 草稿纸）
         2. 问 LLM "下一步做什么"
         3. 解析 LLM 输出的 JSON 决定
         4. tool_call → 执行工具，结果记入草稿纸，继续循环
            final_answer → 返回，结束

RagController
  └── POST /api/rag/agent
```

### 关键代码：AgentService.run()

```java
public String run(String question) {
    StringBuilder scratchpad = new StringBuilder();  // 草稿纸：记录每步

    for (int step = 1; step <= MAX_STEPS; step++) {
        // 1. 构造 Prompt（问题 + 工具说明 + 之前的步骤）
        String prompt = buildPrompt(question, scratchpad.toString());

        // 2. 问 LLM "下一步做什么"
        String llmOutput = chatModel.generate(prompt).content().text();

        // 3. 解析 LLM 的 JSON 决定
        JsonNode decision = parseDecision(llmOutput);

        if ("final_answer".equals(decision.path("type").asText())) {
            return decision.path("content").asText();  // 跳出循环
        }

        if ("tool_call".equals(decision.path("type").asText())) {
            String toolName = decision.path("tool").asText();
            String toolInput = decision.path("input").asText();
            
            // 4. 真正执行工具
            Tool tool = tools.get(toolName);
            String observation = tool.execute(toolInput);
            
            // 5. 把这一步记入草稿纸，供下一轮参考
            scratchpad.append("思考与行动：调用了工具 ").append(toolName)
                    .append("，输入「").append(toolInput).append("」\n");
            scratchpad.append("观察结果：").append(observation).append("\n\n");
        }
    }
    
    return "抱歉，我思考了多步仍无法得出答案，请尝试换个问法。";
}
```

### 三个关键设计

**① 草稿纸（scratchpad）**

记录每步"思考-行动-观察"，每轮都把它发给 LLM，让 LLM "记得"前面做过什么。这是 ReAct 的核心。

```
第1步后草稿纸：
  思考与行动：调用了工具 knowledge_search，输入「Redis max-active」
  观察结果：【片段1】max-active: 8

第2步时 LLM 看到这个草稿纸 → 知道已经查到 8 了 → 决定下一步算 8*10
```

**② JSON 约定格式**

因为不用 Function Calling，我们用 Prompt 约定 LLM 必须输出固定 JSON：

```
如果需要调用工具：
{"type":"tool_call","tool":"工具名","input":"工具输入"}

如果信息已足够，给出最终答案：
{"type":"final_answer","content":"你的回答"}
```

程序才能解析。`parseDecision` 做了容错——提取第一个 `{` 到最后一个 `}` 之间的内容，跳过 LLM 输出的 ` ```json ` 干扰。

**③ MAX_STEPS 防死循环**

LLM 可能陷入"反复调工具"，限制最多 5 步。

### 工具实现：CalculatorTool

**为什么自己写解析器？**

Java 15+ 移除了内置的 JavaScript 引擎（Nashorn），不能用 `ScriptEngine` 执行表达式了。自己实现一个简单的四则运算解析器（递归下降）：

```java
private double evaluate(String expr) {
    return new Parser(expr).parseExpression();
}

// 表达式 = 项 (('+' | '-') 项)*
// 项 = 因子 (('*' | '/') 因子)*
// 因子 = 数字 | '(' 表达式 ')'
```

支持 `+`、`-`、`*`、`/` 和括号，遵循运算优先级。

### 工具实现：KnowledgeSearchTool

复用 `RagService.searchKnowledge()`：

```java
public String searchKnowledge(String question) {
    List<TextSegment> segments = retrieveRelevantSegments(question);
    if (segments.isEmpty()) {
        return "知识库中没有找到相关信息。";
    }
    // 只返回原始片段文本，不调 LLM 生成（Agent 的主 LLM 会综合）
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < segments.size(); i++) {
        sb.append("【片段").append(i + 1).append("】")
          .append(segments.get(i).text()).append("\n\n");
    }
    return sb.toString();
}
```

**为什么不调 LLM 生成？**

Agent 的主 LLM 会自己综合检索到的片段，工具只需返回"原材料"。如果在工具里再调一次 LLM 生成，就重复了。

### 实测日志（完整的 Agent 思考过程）

```
Agent 收到问题: 知识库里 Redis 连接池的 max-active 是多少？把它乘以 10 是多少？

--- Agent 第 1 步 ---
LLM 输出: {"type":"tool_call","tool":"knowledge_search","input":"Redis 连接池 max-active"}
调用工具: knowledge_search | 输入: Redis 连接池 max-active
工具返回: 【片段1】...max-active: 8...

--- Agent 第 2 步 ---
LLM 输出: {"type":"tool_call","tool":"calculator","input":"8 * 10"}
调用工具: calculator | 输入: 8 * 10
工具返回: 80

--- Agent 第 3 步 ---
LLM 输出: {"type":"final_answer","content":"max-active 是 8，乘以 10 等于 80"}
Agent 得出最终答案
```

**关键观察**：
- ✅ 工具选择正确（查文档用 knowledge_search，算数用 calculator）
- ✅ 多步规划（先查再算）
- ✅ 第 2 步的"8"是从第 1 步结果里自己提取的
- ✅ 知道何时停止（第 3 步判断信息够了，主动 final_answer）

**这个"先查知识库 → 再用计算器 → 综合回答"的三步规划，没有任何一行代码写死，全是 LLM 自己想出来的。**

---

## 七、Docker 实战（受阻但有收获）

### 尝试：Qdrant 持久化向量库

**目标**：摆脱内存存储（重启丢数据），用 Qdrant 持久化。

**Docker 基础**（成功学会）：
- **Docker 是什么**：软件的"集装箱"，一行命令跑起来，不用安装配置
- **类比**：预制菜套餐（锅、食材、说明书全在一个盒子里）
- **实操**：
  ```bash
  docker run -d --name qdrant -p 6333:6333 -v ./data/qdrant_storage:/qdrant/storage qdrant/qdrant
  docker ps  # 查看运行中的容器
  ```

**踩坑**（致命）：
- langchain4j 0.31.0 的 `QdrantEmbeddingStore` 有 bug
- `findRelevant` 方法重算 score 时拿到空向量（Qdrant 默认不返回原始向量）
- 报错：`Length of vector a (0) must be equal to b (512)`
- 换 `search()` API 也没用（内部还是调 `findRelevant`）

**决策**：回退到 `InMemoryEmbeddingStore`，Qdrant 留待后续用更新版本或换 Chroma/PGVector。

**收获**：
- Docker 实操（run/ps/stop/-p/-v）
- 向量库 collection 概念、维度匹配
- 版本兼容性的教训

---

## 八、第 2 阶段成果

### 技术能力

✅ **流式响应 SSE**：打字机效果，用户体验质的飞跃  
✅ **多轮对话**：记住上下文，支持连续提问  
✅ **问题重写**：解决检索失焦，让检索层也有记忆  
✅ **AI Agent**：手写 ReAct 循环，工具调用，多步规划  
✅ **Docker 实操**：装容器、跑服务、端口映射、数据卷  
✅ **完整 Web 界面**：第一个真正可用的 AI 对话网页  

### 核心洞察（最有价值）

⭐ **RAG 的天花板是知识库本身**
- 文档里没有的内容，再完美的技术都变不出来
- RAG 不创造知识，只找到并组织已有知识
- 排查顺序：先看知识库有没有 → 再看检索准不准 → 最后看 Prompt

⭐ **RAG vs Agent 的本质区别**
- RAG = 固定流程（检索→生成），只会一招
- Agent = LLM 自主决策（调哪个工具/几步/何时停），大脑动脑手脚干活
- 类比：自动售货机 vs 助理

这两个洞察比任何调参技巧都重要——很多人做半年才悟到。

### 项目文件

```
phase1-rag-springboot/
├── src/main/java/com/learning/rag/
│   ├── service/
│   │   ├── RagService.java                  # 新增 queryStreamWithHistory() + searchKnowledge()
│   │   └── ConversationService.java         # 新增（对话历史管理）
│   ├── agent/                               # 新增包（Agent 相关）
│   │   ├── Tool.java                        # 工具接口
│   │   ├── AgentService.java                # ReAct 循环核心
│   │   ├── CalculatorTool.java              # 计算器工具
│   │   └── KnowledgeSearchTool.java         # 知识库检索工具
│   ├── controller/
│   │   └── RagController.java               # 新增 /chat/stream + /agent
│   └── model/
│       └── IflytekClaudeChatModel.java      # 新增 generateStream()
├── src/main/resources/static/
│   └── index.html                           # 完全重写（多轮+流式）
├── LEARNING_LOG.md                          # 更新第 2 阶段进展
├── PHASE2_SUMMARY.md                        # 本文档
└── ...
```

---

## 九、接下来可以做什么

### 1. 项目产品化
- 部署上线（让别人能访问）
- 知识库换成有价值的内容（你的技术笔记或开源项目文档）
- 优化界面（代码高亮、Markdown 渲染）
- 增加功能（文件上传、历史对话列表）

### 2. 深化 Agent
- 加联网搜索工具（突破知识库限制）
- Agent + 流式输出（边思考边显示）
- 多 Agent 协作
- 接到前端网页（让用户体验 Agent）

### 3. 第 3 阶段准备
- Python + FastAPI（切换技术栈）
- LangChain Python 版（更成熟的生态）
- 向量数据库重战（Chroma/PGVector）

---

## 附录：常见问题

**Q1：为什么用 `fetch` 而不是 `EventSource`？**
- `EventSource` 只支持 GET，我们的接口是 POST（要带 body）

**Q2：为什么要独立线程池？**
- 流式调用是阻塞的（等 LLM 吐字），会占用 Web 线程
- Tomcat 默认只有 200 个 Web 线程，不能浪费在"等待"上

**Q3：问题重写会不会很慢？**
- 会多一次 LLM 调用（延迟+成本）
- 生产环境可以用小模型（Haiku）专门做重写，或缓存常见改写

**Q4：滑动窗口只保留 5 轮够吗？**
- 对大部分对话够用（超过 5 轮的连续上下文很少）
- 可以根据实际调整（配置化）

**Q5：刷新页面后对话丢失怎么办？**
- 当前：刷新 = 新会话（`conversationId` 重新生成）
- 改进：把 ID 存 localStorage，刷新后恢复历史

---

**文档版本**：v1.0  
**更新时间**：2026-06-24  
**作者**：Chloe（AI 全栈学习 · 第 2 阶段）
