# digital-human-rag · 数字分身口播稿 RAG 生成器

> 给「数字分身」装大脑的 Spring Boot 工程：输入一个主题，自动从你的私有知识库检索上下文，调用大模型生成一段**像你口吻的短视频口播稿**。
>
> 这是「10 年 Java 架构师从 0 造数字分身」系列的工程化部分。形象/声音克隆在即梦 APP 完成，本工程负责「大脑」（写稿），生成稿再贴回即梦出片。

## 它能做什么

```
你的私有素材（docs/*.md）
        ↓ 切片 + 向量化
   内存向量库（余弦相似召回 top-k）
        ↓ 主题 + 召回上下文 + 人设
   DeepSeek Chat 生成口播稿
        ↓
   贴进即梦 → 数字人出片
```

输入：`怎么用数字人做课程`
输出：一段口语化、反割韭菜工程师风格的长口播稿（人设写在 PromptBuilder，长度由规范控制，默认约 185 秒）。

## 环境要求

- **JDK 17+**（已验证 JDK 21 可用，推荐 21）
- **Maven 3.9.x**（Spring Boot 3.3.5 要求 ≥ 3.6.3，别用 Maven 4）

## 配置（重要 · 密钥安全）

项目需要两个 API Key，**都不要硬编码提交到仓库**：

| 变量 | 用途 | 平台 |
|---|---|---|
| `DASHSCOPE_API_KEY` | 向量化 embedding（把文本变数字） | 阿里云百炼 DashScope |
| `DEEPSEEK_API_KEY` | 生成口播稿（对话大模型） | DeepSeek 开放平台 |

### 步骤

1. 复制配置模板：
   ```bash
   cp src/main/resources/application.example.yml src/main/resources/application.yml
   ```
2. 编辑 `application.yml`，把两行默认值换成你的真实 key：
   ```yaml
   dashscope-api-key: sk-你的百炼key
   deepseek-api-key: sk-你的DeepSeekkey
   ```
3. 本地 `application.yml` 已被 `.gitignore` 排除，**不会上传**，放心填。

> 也可改用环境变量：`export DASHSCOPE_API_KEY=...` / `export DEEPSEEK_API_KEY=...`（PowerShell 用 `$env:变量名="..."`）。

## 运行

```bash
cd rag
mvn spring-boot:run                 # 默认人设=pan（老潘），默认主题
mvn spring-boot:run -Dspring-boot.run.arguments="怎么用数字人做课程"          # 自定义主题
mvn spring-boot:run -Dapp.persona=anuo -Dspring-boot.run.arguments="怎么做数字人分身"  # 切阿诺人设
```

`app.persona` 决定用哪套 prompt 与哪个知识库（加载 `docs/{persona}` 下的 `.md`）。可选值：`pan`（老潘，默认）/ `anuo`（阿诺）。

首次启动会自动加载对应人设 `docs/{persona}/` 下所有 `.md`，切片并向量化进内存，然后针对主题生成口播稿并打印到控制台。

## 项目结构

```
rag/
├─ pom.xml
├─ src/main/java/com/view163/digitalhuman/
│  ├─ DemoApplication.java        # 入口 + CommandLineRunner
│  ├─ config/AppProperties.java   # 读取 app.* 配置（key 走 env 或 yml）
│  ├─ ingest/                     # ① 文档加载 DocumentLoader + 切片 TextSplitter
│  ├─ embed/                      # ② 百炼 text-embedding-v3 向量化
│  ├─ store/                      # ③ 内存向量库（VectorStore 接口 + InMemory 实现）
│  ├─ retrieve/Retriever.java     # ④ 余弦相似 top-k 召回
│  ├─ llm/                        # ⑤ DeepSeek Chat 生成
│  ├─ prompt/PromptBuilder.java   # 人设 + 召回上下文拼装
│  └─ service/ScriptGenerator.java# 编排以上五步
└─ src/main/resources/
   ├─ docs/
   │  ├─ pan/                     # 老潘知识库（包子店稿 / Agent 落地稿 / 扣子种草 / 剪映吐槽）
   │  └─ anuo/                    # 阿诺知识库（数字人分身制作揭秘）
   ├─ application.example.yml     # 配置模板（入库）
   └─ application.yml             # 真实配置（不入库，需自己建）
```

## 关键设计点

- **DeepSeek 不提供 embedding**：向量化必须另接模型（这里用阿里云百炼 `text-embedding-v3`）。这是 RAG 最常见的坑，已在 `EmbeddingClient` 注释中标明。
- **MVP 零数据库**：向量库用内存 `Map`，启动即建好；后期把 `InMemoryVectorStore` 换成 PGVector 实现同一个 `VectorStore` 接口即可，其余代码不动。
- **人设可调**：风格写死在 `PromptBuilder`（`10 年 Java 架构师、口语化、自黑、反割韭菜`），改这一段就能调口吻。
- **召回旋钮**：`application.yml` 里的 `top-k` / `chunkSize` / `overlap` 控制召回质量与口播稿贴合度。

## 路线图（TODO）

- [ ] 接即梦 API，让分身自动出片（主题 → 稿子 → 视频 全自动，不用手动贴）
- [ ] 向量库持久化（PGVector / Milvus），支持大体量知识库
- [ ] 批量生成口播稿 + 内容日历
- [ ] 私有化降本（开源 embedding + 本地 LLM）

## License

个人项目，仅供学习交流。
