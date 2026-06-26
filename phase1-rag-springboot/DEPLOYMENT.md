# 部署总结文档

## 项目信息

- **项目名称**：AI 知识问答助手（RAG + Agent）
- **技术栈**：Spring Boot 3.2.5 + LangChain4j + Claude API
- **部署平台**：Railway (PaaS)
- **在线地址**：https://ai-fullstack-labs-production.up.railway.app/
- **部署时间**：2026-06-25

---

## 部署架构

```
GitHub Repository
      ↓
Railway 自动检测推送
      ↓
Nixpacks 构建 (Java 17 + Maven)
      ↓
Docker 容器运行 (Linux x86_64)
      ↓
公开域名访问
```

**关键配置文件**：
- `railway.json` - 指定子目录构建和启动命令
- `nixpacks.toml` - 指定 Java 版本（已移除，让 Nixpacks 自动检测）
- `application.yml` - 动态端口 `${PORT:8080}`

---

## 部署前准备

### 1. 动态端口配置

Railway 会注入 `PORT` 环境变量，应用必须绑定到这个端口。

**修改 `application.yml`**：
```yaml
server:
  port: ${PORT:8080}  # 生产用 PORT 环境变量，本地默认 8080
```

### 2. 子目录构建配置

项目在 `phase1-rag-springboot/` 子目录，需要告诉 Railway 从这里构建。

**创建 `railway.json`**（项目根目录）：
```json
{
  "$schema": "https://railway.app/railway.schema.json",
  "build": {
    "builder": "NIXPACKS",
    "buildCommand": "cd phase1-rag-springboot && mvn clean package -DskipTests"
  },
  "deploy": {
    "startCommand": "cd phase1-rag-springboot && java -jar target/rag-doc-assistant-1.0.0.jar",
    "restartPolicyType": "ON_FAILURE",
    "restartPolicyMaxRetries": 10
  }
}
```

### 3. 环境变量配置

在 Railway 服务的 **Variables** 标签添加：

```
ANTHROPIC_AUTH_TOKEN=你的讯飞 token
ANTHROPIC_BASE_URL=https://one.iflytek.com/api/llm/console/chat
```

⚠️ **重要**：环境变量改完要手动点 "Apply" 或 "Redeploy" 才会生效。

### 4. Java 版本配置

最初创建了 `nixpacks.toml` 指定 Java 版本，但遇到包名错误（`jdk21` 不存在）。最终删除该文件，让 Nixpacks 从 `pom.xml` 自动检测 Java 版本，反而更稳定。

---

## 部署过程中的三个核心问题

### 🔥 问题 1：jar 包资源加载失败

#### 现象

本地运行正常，Railway 部署后日志显示：
```
WARN ... 知识库目录不存在：./data/documents，跳过自动摄入
```

#### 根因分析

1. **文档不在标准资源路径**：
   - 文档原本在 `data/documents/`（项目根目录）
   - Maven 默认只打包 `src/main/resources/` 下的资源
   - 即使手动配置打包，jar 包内也无法用文件系统路径（`./data/`）访问

2. **jar 包 vs 文件系统**：
   - 开发环境：项目是文件夹，`new File("./data/documents")` 能找到
   - 生产环境：jar 是单文件，内部资源必须用 classpath 方式读取

#### 解决方案

**步骤 1：移动文档到标准资源路径**
```bash
mkdir -p src/main/resources/documents
mv data/documents/*.md src/main/resources/documents/
```

**步骤 2：改用 classpath 资源加载**

原代码（文件系统路径，jar 包内不可用）：
```java
File directory = new File("./data/documents");
Document document = FileSystemDocumentLoader.loadDocument(path, new TextDocumentParser());
```

新代码（classpath 资源，jar 包内可用）：
```java
PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
Resource resource = resolver.getResource("classpath:documents/learning-log.md");
try (InputStream inputStream = resource.getInputStream()) {
    String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    Document document = Document.from(content);
}
```

**步骤 3：验证打包结果**
```bash
mvn clean package -DskipTests
jar tf target/rag-doc-assistant-1.0.0.jar | grep documents
# 应该看到：
# BOOT-INF/classes/documents/learning-log.md
# BOOT-INF/classes/documents/...
```

#### 关键要点

✅ **资源文件放 `src/main/resources/`**  
✅ **用 `classpath:` 前缀读取**  
✅ **通过 `InputStream` 读取内容**  
❌ **不要用 `File`/`Path` 访问 jar 包内资源**

---

### 🔥 问题 2：中文文件名编码问题

#### 现象

改用 classpath 后，本地测试成功，但 Railway 部署后日志显示：
```
WARN ... 文档不存在: documents/01-学习日志-完整记录.md
WARN ... 文档不存在: documents/02-第二阶段总结-流式多轮Agent.md
ERROR ... 自动摄入失败：没有成功摄入任何文档
```

#### 根因分析

**跨平台文件系统编码差异**：
- macOS：UTF-8 编码，中文文件名正常
- Railway Linux 容器：可能用不同的 locale，导致中文文件名无法正确识别
- 即使文件在 jar 包里，Resource 路径解析时也受系统编码影响

#### 解决方案

**重命名所有文档为英文**：
```bash
cd src/main/resources/documents
mv 01-学习日志-完整记录.md learning-log.md
mv 02-第二阶段总结-流式多轮Agent.md phase2-agent-summary.md
mv 03-RAG原理详解.md rag-explained.md
mv 04-项目说明文档.md project-readme.md
```

**更新代码中的文件名列表**：
```java
String[] documentFiles = {
    "documents/learning-log.md",
    "documents/phase2-agent-summary.md",
    "documents/rag-explained.md",
    "documents/project-readme.md"
};
```

#### 关键要点

✅ **服务器资源文件名永远用英文**（不只文件名，URL、API 路径也一样）  
✅ **避免所有非 ASCII 字符**（中文、emoji、特殊符号）  
✅ **这是 Linux 服务器部署的标准做法**

#### 教训

这是个典型的"我电脑上能跑"问题：
- 本地 macOS 开发环境：中文文件名正常 ✅
- 生产 Linux 环境：中文文件名无法识别 ❌
- **本地测试要用 Docker 模拟 Linux 环境**，避免这类跨平台兼容性问题

---

### 🔥🔥🔥 问题 3：Railway 启动超时（最难排查）

#### 现象

部署后访问域名，看到 Railway 错误页：
```
Application failed to respond
This error appears to be caused by the application.
```

查看 Deploy Logs，发现应用在摄入文档时被杀掉：
```
10:44:52 INFO  应用启动：开始自动摄入知识库文档
10:44:52 INFO  摄入文档: learning-log.md
10:45:57 INFO  文档摄入成功: learning-log.md  ← 用了 65 秒
10:45:57 INFO  摄入文档: phase2-agent-summary.md
10:46:42 INFO  文档摄入成功: phase2-agent-summary.md  ← 又用了 45 秒
10:46:42 INFO  摄入文档: rag-explained.md
[日志中断，应用被杀]
```

#### 根因分析

**Railway 健康检查超时机制**：
1. Railway 部署应用后，等待应用响应健康检查（通常是 HTTP 200）
2. 如果 **2 分钟内**应用没有响应，Railway 认为启动失败，杀掉进程
3. 我们的应用在 `@PostConstruct` 同步摄入文档：
   - 摄入 4 个文档（7万字）
   - 文档分块 + 向量化（每个 chunk 调一次 Embedding API）
   - 总耗时 **2+ 分钟**
4. 摄入期间，Spring Boot 的 Tomcat 已启动，但 `@PostConstruct` 还没执行完，应用逻辑没初始化好
5. Railway 健康检查超时 → 杀进程 → "Application failed to respond"

**为什么本地不超时？**
- 本地开发没有健康检查超时限制
- 即使慢也能等到摄入完成

#### 解决方案

**改用异步摄入，不阻塞应用启动**：

**步骤 1：主类启用异步支持**
```java
@SpringBootApplication
@EnableAsync  // 启用 Spring 异步支持
public class RagDocAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagDocAssistantApplication.class, args);
    }
}
```

**步骤 2：摄入方法改为异步**
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    // @PostConstruct 启动时调用，但立刻返回（不阻塞）
    @PostConstruct
    public void scheduleAutoIngest() {
        log.info("应用启动完成，准备异步摄入知识库文档");
        asyncIngestDocuments();  // 异步调用，不等待
    }

    // 实际摄入在后台线程执行
    @Async
    public void asyncIngestDocuments() {
        log.info("后台开始摄入知识库文档");
        
        // ... 原来的摄入逻辑 ...
        
        log.info("知识库摄入完成，成功处理 {} 个文档", successCount);
    }
}
```

**执行流程对比**：

| 同步摄入（超时） | 异步摄入（成功） |
|-----------------|-----------------|
| 1. Spring Boot 启动 | 1. Spring Boot 启动 |
| 2. 执行 @PostConstruct | 2. 执行 @PostConstruct（立刻返回） |
| 3. 摄入文档 2+ 分钟 ⏳ | 3. 应用启动完成 ✅ (10 秒) |
| 4. 应用启动完成 | 4. Railway 健康检查通过 ✅ |
| 5. ❌ 超时被杀 | 5. 后台继续摄入 1-2 分钟 |
|  | 6. 摄入完成 ✅ |

#### 关键要点

✅ **重量级初始化放后台**（数据加载、缓存预热、文件扫描）  
✅ **应用启动要快**（10 秒内响应健康检查）  
✅ **用 `@Async` 实现异步初始化**  
⚠️ **启动后短时间内服务降级**（摄入期间知识库为空）

#### 权衡

**优点**：
- 应用快速启动，不超时 ✅
- Railway 认为部署成功 ✅

**缺点**：
- 启动后 1-2 分钟内知识库为空
- 用户提问会看到"没有找到相关信息"
- 1-2 分钟后自动恢复正常

**改进方向**（未来优化）：
- 持久化向量库（Qdrant/Chroma），摄入一次永久保存
- 启动时显示"知识库加载中"提示
- 健康检查接口返回加载进度

---

## 最终配置清单

### 项目根目录 `railway.json`
```json
{
  "$schema": "https://railway.app/railway.schema.json",
  "build": {
    "builder": "NIXPACKS",
    "buildCommand": "cd phase1-rag-springboot && mvn clean package -DskipTests"
  },
  "deploy": {
    "startCommand": "cd phase1-rag-springboot && java -jar target/rag-doc-assistant-1.0.0.jar",
    "restartPolicyType": "ON_FAILURE",
    "restartPolicyMaxRetries": 10
  }
}
```

### `application.yml`
```yaml
server:
  port: ${PORT:8080}  # Railway 动态端口
```

### Railway 环境变量
```
ANTHROPIC_AUTH_TOKEN=你的讯飞 token
ANTHROPIC_BASE_URL=https://one.iflytek.com/api/llm/console/chat
```

### 文档资源结构
```
src/main/resources/
  └── documents/
      ├── learning-log.md
      ├── phase2-agent-summary.md
      ├── rag-explained.md
      └── project-readme.md
```

### DocumentService 异步摄入
```java
@PostConstruct
public void scheduleAutoIngest() {
    asyncIngestDocuments();
}

@Async
public void asyncIngestDocuments() {
    // 后台摄入逻辑
}
```

---

## 部署步骤（完整流程）

### 1. 准备代码
```bash
# 1. 动态端口配置
vim application.yml  # server.port: ${PORT:8080}

# 2. 创建 Railway 配置
vim railway.json  # 见上文

# 3. 文档移到 resources
mkdir -p src/main/resources/documents
mv data/documents/*.md src/main/resources/documents/

# 4. 文件名改英文
cd src/main/resources/documents
rename 's/[\x{4e00}-\x{9fff}]/_/g' *.md  # 或手动重命名

# 5. 代码改用 classpath + 异步摄入（见上文）

# 6. 本地验证
mvn clean package -DskipTests
java -jar target/rag-doc-assistant-1.0.0.jar
# 看日志确认摄入成功

# 7. 提交推送
git add .
git commit -m "chore: 部署配置"
git push
```

### 2. Railway 操作

**① 创建项目**：
1. 登录 https://railway.app
2. New Project → Deploy from GitHub repo
3. 选择 `ai-fullstack-labs` 仓库
4. Railway 自动开始构建

**② 配置环境变量**：
1. 进入服务 → Variables 标签
2. 添加 `ANTHROPIC_AUTH_TOKEN` 和 `ANTHROPIC_BASE_URL`
3. 保存后会自动重新部署

**③ 生成公开域名**：
1. Settings 标签 → Networking
2. Generate Domain
3. 得到 `xxx.up.railway.app` 域名

**④ 验证部署**：
1. 等 Deployments 状态变绿（Active）
2. 访问域名，看到欢迎界面 ✅
3. 等 1-2 分钟（后台摄入完成）
4. 提问测试，正常回答 ✅

### 3. 查看日志确认

Deploy Logs 搜索关键词：
- `"Started RagDocAssistantApplication"` → 应用启动成功
- `"知识库摄入完成"` → 摄入成功
- 没有 `ERROR` → 一切正常

---

## 生产环境最佳实践

### 1. 资源文件管理
- ✅ 放 `src/main/resources/`
- ✅ 用 `classpath:` 读取
- ✅ 文件名用英文
- ❌ 不要放项目根目录
- ❌ 不要用文件系统路径

### 2. 环境配置
- ✅ 端口、密钥从环境变量读
- ✅ 本地有默认值（`${PORT:8080}`）
- ✅ 敏感信息不提交代码
- ❌ 不要硬编码配置

### 3. 启动优化
- ✅ 重量级初始化异步执行
- ✅ 10 秒内响应健康检查
- ✅ 启动日志清晰可读
- ❌ 不要在启动时做耗时操作

### 4. 日志观察
- ✅ 日志是定位问题的关键
- ✅ 先看 ERROR，再看启动是否完成
- ✅ 搜索关键词快速定位
- ❌ 不要只看最后几行

### 5. 本地验证
- ✅ `mvn clean package && java -jar` 模拟生产
- ✅ 验证 jar 包包含所有资源
- ✅ 测试异步初始化是否正常
- ❌ 不能只用 IDE 运行

---

## 部署成功标志

✅ Railway Deployment 状态：**Active**（绿色）  
✅ Deploy Logs 最后一行：`Started RagDocAssistantApplication in X.XXX seconds`  
✅ 访问域名能看到欢迎界面  
✅ 等 1-2 分钟后提问能正常回答  
✅ Deploy Logs 有 `"知识库摄入完成，成功处理 4 个文档"`  

---

## 故障排查清单

### 应用无法访问（502/503）

**检查项**：
1. Railway Deployment 状态是否 Active？
2. Deploy Logs 有没有 ERROR？
3. 应用是否启动完成？（搜索 "Started"）
4. 端口配置是否正确？（`${PORT:8080}`）

### 知识库为空（回答"没找到"）

**检查项**：
1. 是否等够 1-2 分钟？（异步摄入需要时间）
2. Deploy Logs 搜索 "摄入完成"，确认摄入成功
3. 文档是否在 jar 包里？（`jar tf target/*.jar | grep documents`）
4. 文件名是否正确？（英文，没有拼写错误）

### 应用启动超时

**检查项**：
1. 是否用了 `@Async` 异步摄入？
2. 主类是否加了 `@EnableAsync`？
3. `@PostConstruct` 方法是否立刻返回？
4. 启动耗时是否少于 2 分钟？

### 构建失败

**检查项**：
1. `railway.json` 的 buildCommand 是否正确？
2. pom.xml 是否在正确的目录？
3. Maven 依赖是否都能下载？
4. Java 版本是否兼容？（17+）

---

## 后续优化方向

### 1. 持久化向量库 ⭐
- **现状**：InMemoryEmbeddingStore，重启后数据丢失，需要重新摄入
- **目标**：Qdrant/Chroma 持久化存储，摄入一次永久保存
- **收益**：启动秒开，不需要异步摄入的权衡

### 2. 启动加载提示
- **现状**：摄入期间用户看到"没找到"，体验不佳
- **目标**：前端显示"知识库加载中，请稍候..."
- **实现**：后端暴露加载状态接口，前端轮询

### 3. 性能优化
- **批量向量化**：一次调用 Embedding API 处理多个 chunk，减少网络开销
- **向量缓存**：重启时从缓存恢复，避免重新计算
- **检索优化**：Rerank 二次排序提升召回精度

### 4. 监控告警
- **健康检查接口**：`/actuator/health` 暴露应用状态
- **日志聚合**：接入 Railway 日志分析服务
- **错误追踪**：Sentry/Rollbar 监控生产异常

---

## 总结

这次部署从零开始，遇到了 **3 个经典的生产环境问题**，全部是本地正常、部署失败的类型：

1. **jar 包资源加载** - 开发 vs 生产环境差异
2. **中文文件名编码** - 跨平台兼容性问题
3. **启动超时** - 健康检查机制的约束

这些问题在书本上学不到，只有真正部署才会遇到。**排查过程比结果更重要**：
- 通过日志定位问题
- 理解问题的根本原因
- 找到适合当前场景的解决方案
- 权衡利弊做出决策

现在应用已上线，可以分享给任何人使用。虽然还有优化空间（持久化存储、性能优化），但核心功能完整，部署流程已跑通，后续迭代会更顺畅。

**在线体验**：https://ai-fullstack-labs-production.up.railway.app/ 🦉
