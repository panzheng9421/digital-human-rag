# digital-human-rag · 数字分身全自动出片管线

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?logo=springboot&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.9+-C71A36?logo=apachemaven&logoColor=white)
![LLM](https://img.shields.io/badge/LLM-DeepSeek-4D6BFF?logo=deepseek&logoColor=white)
![Embedding](https://img.shields.io/badge/Embedding-DashScope--v3-FF6A00?logo=alibabacloud&logoColor=white)
![Video](https://img.shields.io/badge/Video-Seedance%202.5-0B84FF)
![Image](https://img.shields.io/badge/Image-Seedream%204.0-7C3AED)
![license](https://img.shields.io/badge/license-Private-lightgray)

> 给「数字分身」装大脑、再让它开口说话的 Spring Boot 工程：输入一个主题（或一段裸口播稿），自动完成 **RAG 写稿 → 表演富化 → 调视频模型出片 → 自动下载**，产出可直接发布的数字人口播视频。
>
> 这是「10 年 Java 架构师从 0 造数字分身」系列的工程化部分。当前出片引擎为**火山方舟 Seedance 2.5 直连**，真人形象走「已授权真人素材」`asset://` 通道。

## 两种用法

| 用法 | 适合 | 入口 |
|---|---|---|
| **Web 工作台**（推荐） | 边做边看、手动控制每一步 | 启动后端 → 浏览器打开 `docs/index.html` |
| **命令行一键出片** | 批量 / 脚本化 / 调试 | `mvn spring-boot:run --args="主题"` |

Web 工作台把出片拆成 **5 个模块**，每一步的主动权都在你手里：

```
① 文生图   提示词 → Seedream 出图（可选 16:9 / 9:16，2K / 4K）
② 文案     一句话主题 → RAG 生成裸口播稿；或直接粘贴已有的稿子
③ 切片     按 5–30s 动态切片，每段可改字、改时长
④ 导演富化 逐段富化（动作/表情/台词节奏/音色），一键富化全部 / 单段重做
⑤ 生成视频 点哪段出哪段，单段独立成片，不自动拼接
```

## 它能做什么

```
你的私有素材（docs/{persona}/*.md）
        ↓ ① 切片 + 百炼向量化 + 余弦召回 top-k
   DeepSeek 生成裸口播稿                    ←── 或 直接在页面粘贴裸稿（跳过写稿）
        ↓ ①.5 按句边界切片（每段字数随 video.seconds 联动）
        ↓ ② 表演富化（EnrichPrompt 模板：动作/表情/台词节奏/音色锚定）
   Seedance 2.5 结构化视频提示词
        ↓ ③ 提交火山方舟 → 轮询 → 自动下载 mp4
   output/{taskId}/seg_XX.mp4
```

一次运行 = 从主题到成片全自动，无需人工贴稿。

## 关键设计点

- **DeepSeek 不提供 embedding**：向量化另接百炼 `text-embedding-v3`（RAG 最常见的坑）。
- **MVP 零数据库**：内存向量库，启动即建；换 PGVector 只需实现同一个 `VectorStore` 接口。
- **向量库每次重建先清空**：`ScriptGenerator.buildKnowledgeBase()` 开头 `store.clear()`，改了知识库文档后重新生成即生效，不会新旧切片混叠。
- **音色锚定是固定值**：`EnrichPrompt.voiceAnchorFor(persona)` 按 persona 给死（阿诺=青年女声标准普通话；老潘=中年男声京腔），禁止 LLM 编造，保证全片音色一致。
- **时间戳硬兜底**：`capTimestamps()` 把超出 `video.seconds` 的时间戳裁剪/删行，防 LLM 超时。
- **断点续跑**：分段命名 `seg_XX.mp4` 确定性落盘，失败片段下轮重试，已成功的不重复烧钱。
- **`ratio` 必须加引号**：`"16:9"`，否则 YAML 1.1 六十进制解析成 969 → 方舟 400。
- **向导式不出自动拼接**：每段是独立成片，主动权交给用户（后续可加手动拼接接口）。
- **成片仍需人工过眼**：视频生成有随机性，批量不等于消除随机，坏片重生成。

## 出片引擎（`app.video.provider` 三选一）

| provider | 说明 | 现状 |
|---|---|---|
| `volcengine` | **火山方舟直连**，模型 `doubao-seedance-2-5-260628`，单条 ≤30s、1080p、支持 `generate_audio` 配音 | ✅ 当前默认，端到端已跑通 |
| `grok` | Grok 视频（中转站或官方 API），支持 `reference_audios` 预设音色 | 可用 |
| `seedance` | Seedance 中转站（new.xlcsh.top） | 备选 |

### 真人形象：已授权素材通道（重要）

方舟**不允许**直接传含真人人脸的外部 URL 参考图（会报 `InputImageSensitiveContentDetected.PrivacyInformation`）。正确做法：

1. 方舟控制台 →「体验中心 → 我的 → 真人人像 → 管理素材」录入真人素材，本人扫码完成真人认证授权；
2. 拿到 `asset_id`，在 `application.yml` 参考图里写 `asset://asset-xxxx`；
3. 参考图支持「人物 + 场景一图全含」——把人物和背景合成一张图，1 张即可锚定全部。

同理，要锁真人**声音**：把录音在方舟注册成 `asset://` 音频 id，填进 `reference-audio-urls`（代码已支持，传入后自动强制 `generate_audio=true`）。

### 文生图（工作台模块 ①）

走火山方舟 **Seedream** 图片生成，与视频引擎共用同一把 `ARK_API_KEY`：

- 接口 `POST /api/image/generate {prompt, size, ratio}`
- `ratio`：`16:9`（横版，默认）/ `9:16`（竖版）
- `size`：`2K` / `4K`，与画幅组合换算为方舟自定义像素（16:9 的 2K=2560x1440、4K=3840x2160；9:16 的 2K=1440x2560、4K=2160x3840）
- 默认模型 `doubao-seedream-4-0-250828`，可用 `app.image.model-id` 覆盖
- 同步返回 `url`（24 小时有效），页面直接预览 / 下载

## 环境要求

- **JDK 17+**（已验证 JDK 21 可用，推荐 21）
- **Maven 3.9.x**（Spring Boot 3.3.5 要求 ≥ 3.6.3，别用 Maven 4）
- **ffmpeg**（仅多段拼接时需要；Windows 填 `video.ffmpeg-path` 绝对路径）

## 配置（重要 · 密钥安全）

项目需要三个 API Key，**都不要硬编码提交到仓库**：

| 变量 | 用途 | 平台 |
|---|---|---|
| `DASHSCOPE_API_KEY` | 向量化 embedding（百炼 `text-embedding-v3`） | 阿里云百炼 DashScope |
| `DEEPSEEK_API_KEY` | 写稿 + 表演富化（对话大模型） | DeepSeek 开放平台 |
| `ARK_API_KEY` | 火山方舟视频 / 图片生成（或填 yml 的 `volcengine.api-key`） | 火山方舟 |

### 步骤

1. 复制配置模板：
   ```bash
   cp src/main/resources/application.example.yml src/main/resources/application.yml
   ```
2. 编辑 `application.yml`，把默认值换成你的真实 key，并按需调整：
   ```yaml
   app:
     persona: anuo          # 人设：pan（老潘）/ anuo（阿诺）
     video:
       provider: volcengine
       seconds: 30          # 单段时长；2.5 支持 ≤30s，2.0-mini 硬上限 15s
       size: 720            # 480 / 720 / 1080（越贵越清晰，调试用低档）
       ratio: "16:9"        # ⚠️ 必须加引号！否则 YAML 把 16:9 当六十进制解析成 969
       generate-audio: true # 模型合成配音；false=静音仅环境音
       reference-image-urls:
         - asset://asset-xxxx   # 已授权真人素材（人物+背景一图全含）
     image:
       model-id: doubao-seedream-4-0-250828   # 文生图模型，可覆盖
   ```
3. 本地 `application.yml` 已被 `.gitignore` 排除，**不会上传**，放心填。

> 💰 省钱提示：调试管线用 `size: 480` + 短 `seconds`（几块钱一次），正式出片再上 720/1080。1080p/30s 单条约 45 元。

## 运行

### A. Web 工作台（推荐）

```bash
# 后端：默认 8080
mvn spring-boot:run

# 前端：直接用浏览器打开（GitHub Pages 同款页面）
#   docs/index.html  ← 工作台主页
# 或本地起个静态服务：
python -m http.server 5500 -d docs
# 然后浏览器访问 http://localhost:5500/index.html
```

工作台模块对应的后端接口（均 `CrossOrigin("*")`，GitHub Pages 跨域可直连；挂 ECS 后收紧域名）：

| 模块 | 方法 & 路径 | 说明 |
|---|---|---|
| 文案 | `POST /api/wizard/draft` `{topic, persona}` | RAG 生成裸口播稿，返回 `{taskId, script}` |
| 文案 | `POST /api/wizard/slice` `{taskId, script, seconds}` | 动态切片（手动输入文案时后端自动建会话） |
| 富化 | `POST /api/wizard/enrich` `{taskId, index, segmentText}` | 逐段富化，返回 `{visualPrompt, enriched}` |
| 出片 | `POST /api/wizard/video` `{taskId, index, segmentText, visualPrompt}` | 异步出片单段 |
| 出片 | `GET /api/wizard/video/{taskId}/{index}` | 轮询单段状态 / 视频地址 |
| 文生图 | `POST /api/image/generate` `{prompt, size, ratio}` | Seedream 文生图，返回 `{url, size}` |
| 一键出片 | `POST /api/generate` `{topic, persona}` → `GET /api/task/{id}` | 主题直达成片（在线体验区用） |

成片通过 `WebConfig` 映射的 `/output/**` 静态目录直接播放 / 下载，无需额外文件服务。

### B. 命令行一键出片

```bash
cd rag

# ① 主题 → 全自动出片（RAG 写稿起）
mvn spring-boot:run -Dspring-boot.run.arguments="怎么做数字人分身"

# ② 切人设（决定 prompt 与知识库 docs/{persona}/）
mvn spring-boot:run -Dapp.persona=anuo -Dspring-boot.run.arguments="做一下自我介绍"

# ③ 已有裸口播稿，跳过 Stage1 直接富化+出片
mvn spring-boot:run "-Dspring-boot.run.arguments=--raw=哈喽，我是老潘……"
mvn spring-boot:run "-Dspring-boot.run.arguments=--raw-file=D:/scripts/script.txt"
```

产物落在 `output/`（gitignored）：分段 `seg_XX.mp4` + 最终 `final.mp4`。

## 项目结构

```
rag/
├─ pom.xml
├─ docs/index.html                 # Web 工作台（主页 = 原 workbench.html）
├─ src/main/java/com/view163/digitalhuman/
│  ├─ DemoApplication.java          # 入口 + CommandLineRunner，串联 Stage1→4；--raw/--raw-file 解析
│  ├─ config/AppProperties.java     # app.* 配置（video 下拆 seedance/grok/volcengine 引擎块 + image 块）
│  ├─ ingest/                       # ① 文档加载 DocumentLoader + 切片 TextSplitter
│  ├─ embed/                        # ① 百炼 text-embedding-v3 向量化
│  ├─ store/                        # ① 内存向量库（VectorStore 接口 + InMemory 实现 + clear()）
│  ├─ retrieve/Retriever.java       # ① 余弦相似 top-k 召回
│  ├─ llm/DeepSeekClient.java       # ①⑤ DeepSeek Chat（写稿/富化共用）
│  ├─ prompt/PromptBuilder.java     # ① 人设 system prompt + 召回上下文拼装
│  ├─ prompt/EnrichPrompt.java      # ② 富化模板（八段结构 + 音色锚定固定值 + 时间戳硬兜底）
│  ├─ service/ScriptGenerator.java  # ① 编排 RAG 五步（buildKnowledgeBase 含 clear）
│  ├─ service/ScriptSlicer.java     # ①.5 按句边界切片（支持动态 seconds）
│  ├─ service/PerformanceEnricher.java # ② 裸稿 → 视频提示词
│  ├─ service/VideoGenerator.java   # ③ 出片接口
│  ├─ service/VolcengineVideoGenService.java # ③ 方舟直连（提交/轮询/下载/asset:// 真人通道）
│  ├─ service/GrokVideoGenService.java       # ③ Grok 引擎
│  ├─ service/VideoGenService.java           # ③ Seedance 中转站引擎
│  ├─ service/VideoConcatenator.java         # ④ ffmpeg 拼接
│  └─ web/                          # Web 工作台后端（新增）
│     ├─ WizardController.java      # 向导式 4 步接口（draft/slice/enrich/video）
│     ├─ ImageController.java       # 文生图接口（/api/image/generate）
│     ├─ GenerateController.java    # 一键出片接口（/api/generate + /api/task/{id}）
│     └─ WebConfig.java             # /output/** 静态映射
├─ reference/                       # 接入参考文档（Seedance 提示词工程 / 方舟接入清单）
└─ src/main/resources/
   ├─ docs/
   │  ├─ pan/                       # 老潘知识库
   │  └─ anuo/                      # 阿诺知识库（分身制作揭秘/SOP/不聊技术/做网站/抱怨没流量）
   ├─ application.example.yml       # 配置模板（入库）
   └─ application.yml               # 真实配置（不入库，需自己建）
```

## 路线图（TODO）

- [x] 接视频生成 API，主题 → 稿子 → 视频 全自动（火山方舟 Seedance 2.5 直连已跑通）
- [x] 真人形象 `asset://` 已授权素材通道
- [x] 参考音频 `reference_audio` 管线（待实测：需录音 asset id）
- [x] Web 工作台：文案/切片/富化/出片向导 + 文生图模块
- [ ] 向量库持久化（PGVector / Milvus），支持大体量知识库
- [ ] 批量生成 + 内容日历 + 人工审片环节
- [ ] 私有化降本（开源 embedding + 本地 LLM）
- [ ] 单段成片手动拼接接口

## License

个人项目，仅供学习交流。
