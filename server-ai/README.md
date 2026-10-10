# study-agent AI 服务（server-ai）

FastAPI + LangChain 工程骨架，承载 **本地开源模型（BGE-M3 嵌入 / BGE-Reranker-v2-m3 重排）**
与 **阿里云百炼 qwen3.7-flash（思考模型）** 的接入层。

- 服务默认端口：**8000**
- 接口文档：<http://127.0.0.1:8000/docs>
- 健康检查：<http://127.0.0.1:8000/health>

---

## 一、目录结构

```
server-ai/
├── app/
│   ├── __init__.py
│   ├── main.py                 # FastAPI 入口 + lifespan（启动打印脱敏配置，不预加载模型）
│   ├── core/
│   │   ├── __init__.py
│   │   ├── config.py           # pydantic-settings 读环境变量 / .env
│   │   ├── logger.py           # loguru：控制台彩色 + 文件按天滚动，格式含 trace_id
│   │   ├── trace.py            # traceId ContextVar + 纯 ASGI 中间件（不缓冲 SSE）
│   │   ├── exceptions.py       # ModelNotReadyError / BizException + 全局异常处理器
│   │   └── llm.py              # ChatOpenAI 工厂 + extract_reasoning() + check_llm()
│   ├── routers/
│   │   ├── __init__.py
│   │   ├── health.py           # /health、/health/llm、/health/db
│   │   └── debug.py            # /debug/embed、/debug/rerank（模型自检探针）
│   ├── services/
│   │   ├── __init__.py         # 惰性导出，import 不加载模型
│   │   ├── embedding.py        # BGEM3Embedder：单例 + 懒加载 + 线程安全
│   │   └── reranker.py         # BGEReranker：单例 + 懒加载 + 线程安全
│   └── models/
│       ├── __init__.py
│       └── schemas.py          # 请求/响应模型
├── scripts/
│   ├── download_models.py      # 模型下载（断点续传，可重复执行）
│   └── check_models.py         # 模型自检（维度 / 重排打分）
├── logs/                       # 运行日志（server-ai.log、uvicorn.out.log）
├── data/                       # Milvus Lite 本地数据文件（milvus_study.db）
├── requirements.txt            # pip freeze 真实结果
├── .env.example                # 环境变量模板
└── README.md
```

---

## 二、环境准备

### 1. 使用受管解释器创建 venv

```powershell
C:\Users\asus\.workbuddy\binaries\python\versions\3.13.12\python.exe -m venv D:\code\wkbdy\study-agent\server-ai\.venv
```

### 2. 激活 venv（PowerShell）

```powershell
cd D:\code\wkbdy\study-agent\server-ai
.\.venv\Scripts\Activate.ps1
# 若提示脚本被禁止执行：
# Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

> 也可以不激活，直接全路径调用：`.\.venv\Scripts\python.exe`

### 3. 安装依赖（PyPI 直连不可用，必须走阿里云镜像）

```powershell
# 方式一：把镜像写进 venv 配置（一次即可）
.\.venv\Scripts\python.exe -m pip config set --site global.index-url https://mirrors.aliyun.com/pypi/simple/
.\.venv\Scripts\python.exe -m pip config set --site global.trusted-host mirrors.aliyun.com

# 方式二（更稳妥）：每条命令显式指定镜像
.\.venv\Scripts\python.exe -m pip install -i https://mirrors.aliyun.com/pypi/simple/ --trusted-host mirrors.aliyun.com -r requirements.txt
```

> **注意**：`FlagEmbedding` 会拉取 CPU 版 `torch`（约 200MB+），安装很慢，
> 建议放到后台任务或把超时调到 30 分钟以上。

---

## 三、模型下载与自检

**结论先说：国内请用 ModelScope（魔搭），不要去碰 hf-mirror。**
实测同一台机器同一个 2.2GB 权重：

| 来源 | 实测速率 | 2.2GB 耗时 | 备注 |
| --- | --- | --- | --- |
| hf-mirror.com | **0.167 MB/s** | 约 3.8 小时/个 | 在大文件上还会**挂死**（socket 不关也不给数据，超时都不触发） |
| **ModelScope 魔搭** | **28.6 MB/s** | **约 80 秒/个** | 两个模型的字节数与 HF **完全一致** |

两个模型全部下完**总共 181.9 秒**（bge-m3 82.7s + reranker 98.7s，共 4.4GB）。

```powershell
cd D:\code\wkbdy\study-agent\server-ai

# ★ 推荐：ModelScope（默认源），下载到 server-ai/models/（已在 .gitignore 中）
.\.venv\Scripts\python.exe scripts\download_models.py
.\.venv\Scripts\python.exe scripts\download_models.py --only bge-reranker-v2-m3

# 备选：hf-mirror（慢且会挂死，需要看门狗兜底）
.\.venv\Scripts\python.exe scripts\download_models.py --source hf --supervise --stall-timeout 180
```

**模型放哪里 / 要不要改 `.env`**：下载到 `server-ai/models/bge-m3/` 与
`server-ai/models/bge-reranker-v2-m3/`。`app/core/config.py` 的
`embedding_model_path` / `reranker_model_path` 会自动做「**本地目录优先**」解析：
只要 `models/<name>/config.json` 存在就优先用本地权重，
所以 `.env` 里保持 `EMBEDDING_MODEL=BAAI/bge-m3` **原样不用改**。

脚本行为：
- **逐文件**下载；**严格校验字节数**，与仓库上报大小完全一致才落地（见下方踩坑）；
- 每行打印「文件序号 / 总体百分比 / 已下载字节 / 文件名 / 速率」，日志可实时判断进度；
- 结束时打印**逐个文件的本地路径与大小**，并列出缺失清单作为验收依据。

自检（真实加载模型并出结果）：

```powershell
# 默认：模型未就绪时打印「未就绪，跳过」并 exit 0（下模型期间可安全挂流水线）
.\.venv\Scripts\python.exe scripts\check_models.py

# 严格模式：任一项不通过则 exit 1（发布门禁用）
.\.venv\Scripts\python.exe scripts\check_models.py --strict
```

实测输出（`--strict`，且 `HF_HUB_OFFLINE=1` 强制离线，证明本地即可加载、不依赖网络）：
```
[1/2] 嵌入模型 D:\...\models\bge-m3 (use_fp16=False)
      [OK] 本地缓存完整 | 权重 = pytorch_model.bin
      [OK] 向量维度 = 1024 (期望 1024)
[2/2] 重排模型 D:\...\models\bge-reranker-v2-m3 (use_fp16=False)
      加载 FlagReranker ... 实现类=cross
      passage[0] score=0.189282   Milvus 是一款开源的向量数据库，支持十亿级向量检索。
      passage[1] score=-11.035801 今天北京天气晴朗，气温 25 摄氏度。
      passage[2] score=-6.205856  BGE-M3 支持稠密、稀疏与多向量三种检索方式。
      分数列表 = [0.189282, -11.035801, -6.205856]
结果: 嵌入=通过  重排=通过   (exit 0)
```
排序语义正确：真正相关的「Milvus 是向量数据库」得分最高，无关的天气句最低。

---

## 四、启动服务

```powershell
cd D:\code\wkbdy\study-agent\server-ai

# 开发模式（热重载）
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

# 生产/验收模式（不用 --reload，避免子进程难回收）
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# 也可以直接跑入口
.\.venv\Scripts\python.exe -m app.main
```

启动后：

- 接口文档 <http://127.0.0.1:8000/docs>
- OpenAPI <http://127.0.0.1:8000/openapi.json>
- 健康检查 <http://127.0.0.1:8000/health>

**配置 API Key**（不要硬编码，只走环境变量 / `.env`）：

```powershell
# 方式一：环境变量
$env:DASHSCOPE_API_KEY = "sk-xxxxxxxx"

# 方式二：复制模板为 .env 并填写
Copy-Item .env.example .env
```

---

## 五、接口清单

| 方法 | 路径 | 说明 | 主要返回 |
| --- | --- | --- | --- |
| GET | `/` | 服务根路径 | 服务名、`/docs` 提示 |
| GET | `/health` | 服务健康检查（**不触发模型加载**） | `status/service/version/traceId/models/llm_configured` |
| GET | `/health/llm` | 百炼连通性检查（发「只回复两个字：在线」） | `content`、`reasoning_content` 是否为空 |
| GET | `/health/db` | MySQL 连通性（`select 1`） | `{"status":"UP"/"DOWN", "error": ...}`，失败不 500 |
| POST | `/debug/embed` | 嵌入探针，触发 BGE-M3 懒加载 | `dimension`（应为 1024）、`vectors`、`sample` |
| POST | `/debug/rerank` | 重排探针，触发重排模型懒加载 | `results: [{index, score, passage}]` |
| GET | `/debug/stream` | **SSE 流式探针**（思考链 / 正式回答分离） | `text/event-stream`：`start`/`reasoning`/`content`/`done` |

请求示例：

```powershell
# 嵌入探针
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8000/debug/embed `
  -ContentType 'application/json' -Body '{"texts":["你好"]}'

# 重排探针
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8000/debug/rerank `
  -ContentType 'application/json' `
  -Body '{"query":"什么是向量数据库？","passages":["Milvus 是开源向量数据库","今天天气不错"],"top_k":2}'

# SSE 流式探针（curl -N 关闭客户端缓冲，才能看到逐块下发）
curl.exe -N "http://127.0.0.1:8000/debug/stream?prompt=只回复两个字：在线"
```

### 统一响应约定

| 场景 | HTTP | 响应体 |
| --- | --- | --- |
| 本地模型未就绪（未下载/加载失败） | 503 | `{"code": 5031, "msg": "本地模型未就绪: ..."}` |
| 未配置 `DASHSCOPE_API_KEY` | 503 | `{"code": 5032, "msg": "未配置 DASHSCOPE_API_KEY..."}` |
| 业务异常 `BizException` | 200 | `{"code": <业务码>, "msg": "..."}` |
| 参数校验失败 | 422 | `{"code": 4220, ...}` |
| 未捕获异常 | 500 | `{"code": 5000, "msg": "服务器内部错误"}`（日志含完整堆栈 + traceId） |

---

## 六、关键设计说明

### 1. traceId 全链路（`app/core/trace.py`）

- 请求头 `X-Trace-Id` 优先复用（网关/Java 侧透传），缺失则生成 `uuid4().hex`；
- 写入 `ContextVar`，loguru 通过 `logger.configure(patcher=...)` 自动注入每条日志的 `extra`；
- 响应头回写 `X-Trace-Id`；
- **使用纯 ASGI 中间件而非 `BaseHTTPMiddleware`**：后者会缓冲响应体，破坏后续 SSE 流式输出。

日志格式（每行都带 traceId）：

```
2026-10-10 12:30:00.123 | INFO     | 3f2a...e19 | app.main:lifespan:62 - study-agent-ai v1.0.0 启动中 ...
```

### 2. 模型单例 + 懒加载（`app/services/`）

- 模型**只在首次调用时加载一次**并常驻内存，绝不每次请求重新加载；
- 双检锁（`threading.Lock`）保证并发首次调用只加载一次；
- `FlagEmbedding` / `torch` 在加载方法**内部**导入，因此依赖缺失不会导致服务启动崩溃，
  而是在调用时抛 `ModelNotReadyError` → HTTP 503；
- `/health` 只报告 `is_loaded` 状态，**不触发加载**，保证探活秒回。

### 3. 思考模型 `qwen3.7-flash` 的 `reasoning_content`（**实测结论，第 3 周 SSE 依据**）

`qwen3.7-flash` 是思考模型，服务端把思维链与正式回答分开返回：

- `content` → 正式回答；
- `reasoning_content` → 思维链（思考过程）。

实测环境：`langchain-openai 1.7.0` + `openai 3.26.1`。

| 观测点 | 结论 |
| --- | --- |
| 原生 openai SDK（非流式） | `choices[0].message.reasoning_content` **有值**（英文思维链，约 700~1000 字符） |
| 原生 openai SDK（流式） | `delta.reasoning_content` **先逐块下发**，`delta.content` 最后才出现（实测 77 chunk：第 1 个出 reasoning，第 74 个出 content） |
| `extra_body={"enable_thinking": True}` | 无变化，**思考默认就是开启的** |
| **LangChain 非流式** | `AIMessage.additional_kwargs` 只有 `{'refusal': None}`，**reasoning_content 被丢弃** |
| **LangChain 流式** | `AIMessageChunk.additional_kwargs` **全程为空**，reasoning 全部丢失 |
| 丢弃后的残留痕迹 | 只有 usage 里的 `completion_tokens_details.reasoning_tokens`（实测 190） |

**根因**：`langchain_openai.chat_models.base` 里的 `_convert_delta_to_message_chunk`（流式）
与 `_convert_dict_to_message`（非流式）只映射 OpenAI 标准字段，未映射 `reasoning_content`。

**本工程给出的两条可用路径**（均在 `app/core/llm.py`）：

1. **补丁方案（默认启用）**：`enable_reasoning_passthrough()`
   把上述两个**模块级函数**替换为包装版，将 `reasoning_content` 注入
   `additional_kwargs`。因为调用点在模块内按全局名解析，替换模块属性即可同时拦截
   流式与非流式两条路径。启动时由 `PRESERVE_REASONING=true`（默认）自动安装，
   幂等、失败不影响启动。装好之后 `extract_reasoning()` 对流式/非流式都有效。
2. **原生 SDK 方案（推荐用于 SSE）**：`get_openai_client()` / `get_async_openai_client()`
   直接拿官方 SDK 客户端，`delta.reasoning_content` 与 `delta.content` 天然分离。
   `/debug/stream` 就是用它实现的参考实现。

>`extract_reasoning(chunk)` 依次从 `additional_kwargs["reasoning_content"]`、
>`response_metadata`、对象属性、dict 键中取思维链，兼容 LangChain 与原生 SDK 两种对象。
>`extract_reasoning_tokens(message)` 可在文本被丢弃时，至少读出 `reasoning_tokens`。

**SSE 实测（`/debug/stream`，见 `logs/sse_probe.py`）**：90 个事件、总时长 3.70s，
首个 `reasoning` 事件在 +0.42s、首个 `content` 事件在 +3.66s，
首末事件跨度 3.60s —— 证明**逐块下发未被缓冲**，
即「纯 ASGI traceId 中间件 + SSE」的组合是成立的。

### 4. Milvus Lite

`pymilvus` + `milvus-lite` 支持本地文件模式（`MILVUS_URI=./data/milvus_study.db`），无需 Docker。
`MilvusClient(uri="./data/milvus_study.db")` 即可建库建表。

> Windows 平台上的 `milvus-lite` 兼容性以实际安装到的 wheel 为准；若运行时报平台不支持，
> 可改用 Docker 版 Milvus Standalone 并设置 `MILVUS_URI=http://127.0.0.1:19530`。

---

## 七、常见问题（踩坑记录）

| 问题 | 现象 | 解决 |
| --- | --- | --- |
| **hf-mirror 太慢+挂死** | 仅 0.167 MB/s（2.2GB 要 ~3.8h），且大文件会永久卡住 | **改用 ModelScope（28.6 MB/s，~80s）**，见第三节 |
| **下载文件被追加损坏** | `SafetensorError: Error while deserializing header: incomplete metadata, file not fully covered`；`model.safetensors` 变成 2,382,220,908 B（比正确的多 106 MB） | 早先版本用 HTTP Range 做 append 续传，redirect/CDN 在重试时语义不一致导致重复追加。改为**每次从零写 `.part` + 校验字节数完全一致才原子改名**，不一致就重下 |
| **重排模型架构加载错** | `FlagLLMReranker` 把 bge-reranker-v2-m3 按 `XLMRobertaForCausalLM` 加载：`classifier.dense/out_proj` 被标 UNEXPECTED 丢弃，凭空新建 `lm_head.*`；随后 `compute_score() takes 2 positional arguments but 3 were given` | bge-reranker-v2-m3 是 `XLMRobertaForSequenceClassification` cross-encoder，**必须用 `FlagReranker`**；`FlagLLMReranker` 只适用于 bge-reranker-v2-gemma/minicpm 等生成式重排模型。已改为 FlagReranker 优先、FlagLLMReranker 兜底 |
| PyPI 直连不通 | `pip install` 超时/SSL 错误 | 加 `-i https://mirrors.aliyun.com/pypi/simple/ --trusted-host mirrors.aliyun.com`，并 `pip config set --site` 写进 venv |
| HuggingFace 下载慢 | `snapshot_download` 卡住 | 先 `$env:HF_ENDPOINT="https://hf-mirror.com"`，再运行 `scripts/download_models.py` |
| **hf-xet 401** | `File reconstruction error: CAS Client Error: HTTP status client error (401 Unauthorized), domain: https://cas-server.xethub.hf.co/...` | `hf-xet` 会**绕过镜像直连** HF 的 CAS 服务；设 `HF_HUB_DISABLE_XET=1` 强制走经典 HTTP 下载（脚本与 `config.apply_runtime_env()` 已内置） |
| **镜像 403** | `403 Forbidden ... imgs%2F.DS_Store` 导致整个 snapshot 失败 | bge-m3 仓库里的 `imgs/.DS_Store` 镜像不提供；用 `ignore_patterns=["*.DS_Store"]` 排除，脚本还内置 `allow_patterns` 降级重试 |
| **GBK 控制台崩脚本** | `UnicodeEncodeError: 'gbk' codec can't encode character '\u274c'`，重试循环被杀 | Windows 控制台默认 GBK，print emoji 会抛异常；脚本开头 `sys.stdout.reconfigure(encoding="utf-8", errors="replace")`，并只用 ASCII 状态标记 |
| Windows 符号链接告警 | `cache-system uses symlinks by default ... does not support them` | 无开发者模式时无法建软链，功能不受影响，只是缓存**多占一份磁盘**；`HF_HUB_DISABLE_SYMLINKS_WARNING=1` 关闭噪音 |
| **LangChain 丢弃思维链** | `additional_kwargs` 为空，`reasoning_content` 取不到 | 见上文「关键设计说明 3」：启用透传补丁或改用原生 openai SDK |
| **大文件挂死** | 日志永久停在 `pytorch_model.bin`，缓存字节数长时间不变，也不报错 | hf-mirror 传大文件会挂死且不触发超时；用 `--supervise` 看门狗（实测 30s 零增长即重启续传） |
| **签名 URL 过期** | 大文件下到一半连接断 | 镜像 302 地址 `X-Amz-Expires=3600`（1 小时），0.1~0.2 MB/s 下 2.2GB 必然超时；只能反复续传 |
| torch 安装慢/大 | `FlagEmbedding` 拉 CPU torch（实测 124MB wheel） | 后台任务 + 30 分钟以上超时；失败记「待解决」，其余功能不受影响（模型懒加载） |
| SSE 被缓冲 | 流式接口一次性吐出 | 用纯 ASGI 中间件实现 traceId（本工程已如此），不要用 `BaseHTTPMiddleware` |
| PowerShell 中文乱码 | `Invoke-WebRequest` 拿到 `æå¡å·²å¯å¨` | Windows PowerShell 5.1 对无 `charset` 的 JSON 响应按 latin-1 解码；服务端字节本身是正确的 UTF-8，改用 `curl.exe` 或 `[System.Text.Encoding]::UTF8.GetString()` 验证 |
| 启动即失败 | 模型没下载完服务起不来 | 模型全部懒加载，`/health` 只报状态；未就绪时相关接口返回 503 |
| 端口占用 | `WinError 10048` | `Get-NetTCPConnection -LocalPort 8000` 找 PID 后 `Stop-Process -Id <pid> -Force` |
| PowerShell 激活脚本被拦 | `Activate.ps1 无法加载` | `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`，或直接用 `.venv\Scripts\python.exe` |
