# Superpowers 使用指南

## 概述

Superpowers 是一套完整的 AI 辅助开发方法论,通过 14 个可组合的 skills 自动引导开发流程。它会在合适的时机自动触发相应的技能,无需手动调用。

## 核心工作流程

```
需求提出 → 头脑风暴 → 编写规格 → 制定计划 → 子 agent 并行开发 → 代码审查 → 验证完成
```

### 典型开发流程

1. **你提出需求**  
   例如:"实现一个基于 FAISS 的向量检索模块"

2. **自动触发 brainstorming**  
   Superpowers 不会立即写代码,而是先问你:
   - 这个模块的核心职责是什么?
   - 需要支持哪些检索策略?
   - 性能指标是什么?

3. **生成规格说明**  
   把需求拆解成易读的小块展示给你确认

4. **制定实现计划**  
   生成清晰的步骤列表,强调:
   - **TDD**(测试驱动开发):先写测试,再写实现
   - **YAGNI**(You Aren't Gonna Need It):只实现当前需要的功能
   - **DRY**(Don't Repeat Yourself):避免重复代码

5. **启动子 agent 并行开发**  
   Superpowers 会启动多个 subagent:
   - Agent A 写单元测试
   - Agent B 实现核心逻辑
   - Agent C 编写集成测试
   - 主 agent 负责检查和审查他们的产出

6. **代码审查与验证**  
   自动触发审查流程,确保代码质量后才标记完成

---

## 14 个内置 Skills

### 🧠 规划与设计类

| Skill | 作用 | 自动触发时机 |
|-------|------|-------------|
| **brainstorming** | 需求分析和头脑风暴 | 当你提出新功能或项目需求时 |
| **writing-plans** | 编写实现计划 | 规格确认后,准备实施前 |
| **executing-plans** | 执行计划中的步骤 | 计划被批准后 |

### 🔨 开发类

| Skill | 作用 | 自动触发时机 |
|-------|------|-------------|
| **test-driven-development** | TDD 测试驱动开发 | 开发新功能时 |
| **systematic-debugging** | 系统化调试方法 | 遇到 bug 或测试失败时 |
| **verification-before-completion** | 完成前验证 | 即将标记任务完成前 |

### 👥 多 Agent 协作类

| Skill | 作用 | 自动触发时机 |
|-------|------|-------------|
| **dispatching-parallel-agents** | 并行调度多个 agent | 任务可拆分为独立子任务时 |
| **subagent-driven-development** | 子 agent 驱动开发 | 执行计划中的并行任务时 |
| **requesting-code-review** | 请求代码审查 | 代码实现完成后 |
| **receiving-code-review** | 接收并处理审查反馈 | 收到审查意见时 |

### 🛠️ 工具与流程类

| Skill | 作用 | 自动触发时机 |
|-------|------|-------------|
| **using-git-worktrees** | 使用 git worktree 隔离开发 | 需要并行开发多个特性时 |
| **finishing-a-development-branch** | 完成开发分支 | 特性开发完成,准备合并时 |

### 📚 元技能类

| Skill | 作用 | 何时使用 |
|-------|------|----------|
| **using-superpowers** | 引导如何使用 Superpowers | Session 启动时自动加载 |
| **writing-skills** | 编写自定义 skill | 需要扩展 Superpowers 能力时 |

---

## 针对 RAG 项目的使用场景

### 场景 1:实现新的 RAG 组件

**你的输入:**
```
实现一个文档分块器(DocumentChunker),支持固定长度和语义分块两种策略
```

**Superpowers 自动工作流:**
1. 触发 `brainstorming`:
   - 询问分块粒度(字符数/token数)
   - 确认是否需要重叠(overlap)
   - 语义分块的相似度阈值
   
2. 触发 `writing-plans`:生成实现计划
   ```
   1. 定义 DocumentChunker 接口
   2. 实现 FixedLengthChunker(TDD)
   3. 实现 SemanticChunker(TDD)
   4. 编写集成测试
   5. 性能基准测试
   ```

3. 触发 `subagent-driven-development`:
   - Subagent A:写 FixedLengthChunker 的测试和实现
   - Subagent B:写 SemanticChunker 的测试和实现
   - 主 agent 审查代码并运行测试

### 场景 2:调试 RAG 检索精度问题

**你的输入:**
```
检索结果不准确,top-5 里只有 2 个相关文档
```

**Superpowers 自动工作流:**
1. 触发 `systematic-debugging`:
   - 隔离问题:是 embedding 问题还是检索算法问题?
   - 写验证测试:用已知相关文档测试检索
   - 逐层排查:embedding 质量 → 向量相似度计算 → top-k 选择
   
2. 触发 `verification-before-completion`:
   - 运行完整的检索测试集
   - 确认 precision@5 和 recall@5 指标达标

### 场景 3:并行开发多个 RAG 模块

**你的输入:**
```
我需要同时开发:
1. PDF 文档解析器
2. Embedding 生成器
3. 向量检索服务
```

**Superpowers 自动工作流:**
1. 触发 `using-git-worktrees`:
   - 为每个模块创建独立的 git worktree
   - 避免并行开发时的代码冲突
   
2. 触发 `dispatching-parallel-agents`:
   - Worktree A:subagent 开发 PDF 解析器
   - Worktree B:subagent 开发 Embedding 生成器
   - Worktree C:subagent 开发向量检索服务
   
3. 触发 `finishing-a-development-branch`:
   - 各模块完成后,逐个合并到主分支
   - 运行集成测试确保兼容

---

## 最佳实践

### ✅ 推荐做法

1. **信任自动触发**  
   不要手动调用 skills,让 Superpowers 自动判断时机

2. **配合 TDD 流程**  
   当 Superpowers 提议"先写测试",配合它的节奏:
   ```
   红(测试失败) → 绿(通过测试) → 重构
   ```

3. **及时反馈**  
   当 brainstorming 阶段询问需求细节时,提供具体的:
   - ✅ "支持 512 token 的 overlap 分块"
   - ❌ "合理的分块就行"

4. **审查计划再批准**  
   计划生成后,花 1-2 分钟审查步骤是否合理再说"go"

5. **利用并行能力**  
   对于独立的模块(如 RAG 的三大组件),明确说"并行开发"

### ❌ 避免做法

1. **不要跳过 brainstorming**  
   即使你觉得需求很清楚,让 Superpowers 走完流程能发现盲点

2. **不要催促子 agent**  
   subagent-driven-development 可能持续数小时,这是正常的

3. **不要手动合并 worktree**  
   使用 `finishing-a-development-branch` skill 处理合并

4. **不要忽略验证步骤**  
   `verification-before-completion` 会要求运行测试,不要跳过

---

## 与 Phase 1 学习计划的结合

### Week 1:RAG 基础原型

**任务:**实现 embedding、检索、生成三大模块

**使用 Superpowers 的建议输入:**
```
使用 Superpowers 并行开发 RAG 基础原型:
1. Embedding 模块:对接讯飞 Spark API
2. 检索模块:基于 FAISS 实现向量检索
3. 生成模块:调用 LLM 生成回答

要求:
- 每个模块独立可测试
- 使用 TDD 开发
- 准备好集成测试
```

**预期 Superpowers 行为:**
- 自动创建 3 个 git worktree
- 并行启动 3 个 subagent 开发
- 主 agent 负责审查和集成测试

### Week 2-3:检索优化实验

**任务:**对比 dense retrieval、hybrid retrieval、rerank

**使用 Superpowers 的建议输入:**
```
实现 RAG 检索策略对比实验:
1. 实现 DenseRetriever(纯向量检索)
2. 实现 HybridRetriever(向量+BM25)
3. 实现 RerankRetriever(检索后重排)
4. 编写评测框架,对比 precision@k 和 recall@k
```

**预期 Superpowers 行为:**
- brainstorming 阶段会问:评测数据集准备好了吗?
- 生成 TDD 计划:先写评测框架测试,再实现各策略
- 并行开发 3 个检索器
- 验证阶段自动运行完整评测

---

## 常见问题

### Q1:如何知道 Superpowers 是否已激活?

**A:** 当你提出开发需求时,如果 Claude 不是直接写代码,而是先问你"让我先理解一下需求...",说明 `brainstorming` skill 已自动触发。

### Q2:可以手动调用某个 skill 吗?

**A:** 可以,但不推荐。Superpowers 设计为自动触发,手动调用可能打乱流程。如果确实需要:
```
/superpowers:brainstorming
```

### Q3:subagent 开发时我能看到进度吗?

**A:** 能,HUD(已安装的 claude-hud 插件)会在状态栏显示:
```
[claude-hud] Agents: 3 running | Tasks: ✓ 5 completed, ◐ 2 in progress
```

### Q4:如果不满意生成的计划怎么办?

**A:** 在计划展示后,明确说"修改计划",然后提出具体调整:
```
修改计划:第 3 步应该先实现缓存层,再做检索
```

### Q5:Superpowers 会自动 commit 代码吗?

**A:** 不会。代码提交由你控制,或者让 Claude 在你确认后执行 git 操作。

### Q6:能关闭某个 skill 吗?

**A:** 可以在对话中说明:
```
这次任务不需要 TDD,直接实现就行
```
Superpowers 会尊重你的偏好,但可能会提醒风险。

---

## 进阶技巧

### 自定义 Skill

如果你的 RAG 项目有特定的重复流程,可以编写自定义 skill。使用:
```
/superpowers:writing-skills
```

例如,为"RAG 评测实验"创建标准化流程的 skill:
```markdown
# rag-evaluation-experiment

当用户提出 RAG 评测需求时:
1. 确认评测数据集(query, ground_truth, corpus)
2. 定义评测指标(precision@k, recall@k, MRR, NDCG)
3. 实现策略接口
4. 运行对比实验
5. 生成可视化报告
```

### 与 Git Worktree 配合

当需要并行开发多个特性时,Superpowers 会自动使用 worktree:
```bash
# Superpowers 自动创建的结构
.claude/worktrees/
├── feature-embedding/    # Subagent A 工作目录
├── feature-retrieval/    # Subagent B 工作目录
└── feature-generation/   # Subagent C 工作目录
```

完成后自动合并,无需手动操作。

### 结合 CLAUDE.md 项目指令

在项目根目录创建 `CLAUDE.md`,定义 RAG 项目的开发规范:
```markdown
# RAG 项目开发规范

## 测试要求
- 单元测试覆盖率 > 80%
- 每个检索策略必须有基准测试
- 使用 JUnit 5 + Mockito

## 代码规范
- 使用 Spring Boot 依赖注入
- 向量操作统一使用 VectorService
- 所有 API 返回统一的 Result<T> 包装

## 评测标准
- precision@5 > 0.6
- recall@10 > 0.8
```

Superpowers 会读取这些规范,确保生成的代码符合项目约定。

---

## 总结

Superpowers 的核心价值:
1. **自动化流程管理** — 你专注需求,它负责流程
2. **TDD 强制** — 减少返工,提高代码质量
3. **并行开发能力** — 多个 subagent 同时工作,提升效率
4. **验证闭环** — 不允许跳过测试直接标记完成

对于 RAG 学习项目,这意味着:
- 更快的原型迭代(Week 1 可以并行完成三大模块)
- 更可靠的实验对比(强制 TDD 确保评测准确)
- 更系统的调试流程(systematic-debugging 有章法)

---

## 参考资源

- **Superpowers 官方仓库:** https://github.com/obra/superpowers
- **本地安装路径:** `~/.claude/plugins/cache/superpowers-marketplace/superpowers/`
- **官方文档:** `~/.claude/plugins/cache/superpowers-marketplace/superpowers/6.0.3/docs/`
- **已启用插件:** 在 Claude Code 中运行 `/help` 查看可用命令

---

## 快速上手检查清单

- [ ] 确认 Superpowers 已安装(`/plugin list`)
- [ ] 确认 HUD 正常显示(输入框下方有状态栏)
- [ ] 测试自动触发:提出一个开发需求,观察是否先触发 brainstorming
- [ ] 在项目根目录创建 `CLAUDE.md` 定义开发规范
- [ ] 准备好第一个任务:"实现 RAG 基础原型的三大模块"

**开始使用:** 直接向 Claude 提出你的开发需求,Superpowers 会自动接管流程! 🚀
