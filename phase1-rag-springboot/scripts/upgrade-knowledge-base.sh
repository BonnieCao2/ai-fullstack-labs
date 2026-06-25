#!/bin/bash

# 项目产品化：知识库内容升级脚本
# 把学习笔记整理成 RAG 知识库

PROJECT_ROOT="/Users/chloe/code/ai-fullstack-labs/phase1-rag-springboot"
DOCS_DIR="$PROJECT_ROOT/data/documents"

echo "=== 准备知识库目录 ==="
# 备份旧文档
if [ -d "$DOCS_DIR" ]; then
    mv "$DOCS_DIR" "$DOCS_DIR.backup.$(date +%Y%m%d_%H%M%S)"
fi
mkdir -p "$DOCS_DIR"

echo "=== 复制学习文档到知识库 ==="
# 核心学习文档
cp "$PROJECT_ROOT/LEARNING_LOG.md" "$DOCS_DIR/01-学习日志-完整记录.md"
cp "$PROJECT_ROOT/PHASE2_SUMMARY.md" "$DOCS_DIR/02-第二阶段总结-流式多轮Agent.md"
cp "$PROJECT_ROOT/RAG_EXPLAINED.md" "$DOCS_DIR/03-RAG原理详解.md"
cp "$PROJECT_ROOT/CLAUDE.md" "$DOCS_DIR/04-项目说明文档.md"

# 如果有其他技术笔记，也可以加进来
# cp ~/Documents/my-tech-notes/*.md "$DOCS_DIR/"

echo "=== 知识库内容统计 ==="
echo "文档数量: $(ls -1 "$DOCS_DIR"/*.md | wc -l)"
echo "总字数: $(cat "$DOCS_DIR"/*.md | wc -w)"
echo ""
echo "文档列表："
ls -lh "$DOCS_DIR"/*.md

echo ""
echo "=== 完成！下一步 ==="
echo "1. 启动项目: mvn spring-boot:run -Dspring-boot.run.profiles=local"
echo "2. 摄入文档: curl -X POST http://localhost:8080/api/rag/documents/ingest-directory \\"
echo "              -H 'Content-Type: application/json' \\"
echo "              -d '{\"directoryPath\": \"./data/documents\"}'"
echo "3. 测试提问: curl -X POST http://localhost:8080/api/rag/query \\"
echo "              -H 'Content-Type: application/json' \\"
echo "              -d '{\"question\": \"什么是 RAG 的天花板？\"}'"
