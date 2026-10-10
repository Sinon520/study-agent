# 备考学习 Agent 系统（study-agent）

> 面向考研 / 考公人群的个性化学习 Agent 系统。
> Java 做业务工程化，Python 做 AI 能力，双栈分工明确。

---

## 一、项目简介

把"定计划 → 做任务 → 记错题 → 复盘 → 问答"这条备考闭环做成一个系统，其中三件事交给 AI：

1. **RAG 问答**：上传自己的资料（PDF / Word），基于资料库回答问题，**每个回答都带引用溯源**（文档名 + 页码），资料里没有的内容明确说不知道。
2. **规划 Agent**：输入"3 个月考研英语二、每天 2 小时"，LangGraph 编排的 Agent 自动拆成逐日任务并入库。
3. **异步 AI 处理**：错题自动打知识点标签、每周自动生成复盘报告，走 RocketMQ 异步链路，不阻塞主流程。

工程上的重点不是"能跑"，而是把几个真实工程问题讲清楚：**跨语言 traceId 全链路贯穿、RAG 引用溯源、消息消费幂等、慢查询优化**。

## 二、架构

![架构图](images/architecture.svg)

### 技术栈

| 层 | 技术 | 说明 |
|----|------|------|
| 业务服务 | Java 21 + Spring Boot 3 + MyBatis-Plus | 认证鉴权、CRUD、事务、消息生产/消费 |
| AI 服务 | Python 3.13 + FastAPI + LangChain / LangGraph | RAG 检索增强、Agent 编排、流式输出 |
| 向量库 | Milvus（Milvus Lite 本地文件模式） | 免 Docker，个人机器友好 |
| 嵌入 / 重排 | BGE-M3 + BGE-Reranker-v2-m3（本地 CPU） | 零 API 成本 |
| 生成模型 | 阿里云百炼 `qwen3.7-flash` | OpenAI 兼容协议，SSE 流式，含思考模式 |
| 存储 | MySQL 8 / Redis | 业务数据 + 会话上下文与 token 缓存 |
| 消息 | RocketMQ 5.5 | 错题归类 / 报告生成 / 微信推送三类异步任务 |
| PC 端 | Vue 3 + Vite + Element Plus + Pinia + ECharts | 计划 / 问答 / 错题 / 进度 / 报告 |
| 小程序 | UniApp（Vue3 语法） | 今日计划、错题列表、我的、简化问答 |

### 为什么是双栈

AI 生态（LangChain、LangGraph、FlagEmbedding、pymilvus）在 Python 上最完整，而业务侧的事务、鉴权、消息可靠性是 Java 的主场。所以**边界按能力划**：Java 是唯一写入口和权限入口，Python 只对内网暴露、不持有业务写权限（写库一律回调 Java 的内部接口），保证"单一写入口"和权限校验统一。

### 目录结构

```
study-agent/
├── server-java/          # Spring Boot 业务服务（8080）
├── server-ai/            # FastAPI + LangChain AI 服务（8000）
├── web-pc/               # Vue3 PC 端
├── miniapp/              # UniApp 小程序
├── docs/                 # 表设计、架构、时序图、踩坑记录
├── sql/                  # 建库建表脚本
├── scripts/              # 一键启停脚本
└── logs/                 # 运行日志（不入库）
```

## 三、核心链路

### 1. RAG 问答（SSE 流式 + 引用溯源）

```mermaid
sequenceDiagram
    participant U as 前端
    participant J as Java 8080
    participant P as Python 8000
    participant R as Redis
    participant M as Milvus
    participant L as qwen3.7-flash

    U->>J: POST /chat/ask (SSE)
    J->>J: 校验 JWT，取 userId，生成/透传 X-Trace-Id
    J->>P: 转发 POST /rag/chat（带 X-Trace-Id）
    P->>R: 读最近 6 轮会话历史
    P->>M: BGE-M3 向量检索 top 20
    P->>P: BGE-Reranker-v2-m3 重排取 top 5
    P->>L: 拼 prompt 流式生成
    L-->>P: token 流（content / reasoning_content）
    P-->>J: SSE 帧（token 逐段）
    J-->>U: SSE 帧（透传，带 traceId）
    P-->>J: event: citations（文档名+页码+片段）
    J-->>U: event: citations
    J->>J: 落库 chat_message（含 citations JSON）
```

### 2. 错题异步归类

```mermaid
sequenceDiagram
    participant U as 前端
    participant J as Java
    participant Q as RocketMQ
    participant P as Python
    participant D as MySQL

    U->>J: 新增错题
    J->>D: 写 wrong_question
    J-->>U: 立即返回（不阻塞）
    J->>Q: 发 wrong-classify {wrongQuestionId, content}
    Q->>J: 消费者收到消息
    J->>P: POST /agent/classify
    P->>P: LLM 返回 {科目, 知识点标签}
    P-->>J: 标签 JSON
    J->>D: 回写 wrong_question_tag(_rel)，唯一键幂等
```

## 四、快速开始

### 0. 环境要求

| 组件 | 版本 | 本机路径（示例） |
|------|------|-----------------|
| JDK | 21 | `D:\jdk-21\jdk-21.0.11` |
| Maven | 3.9+ | `D:\maven\apache-maven-3.9.9` |
| Python | 3.13 | 受管解释器（见 `docs/环境说明.md`） |
| MySQL | 8.0 | 服务名 `MySQL80`，root/123456 |
| Redis | 8.x | 6379，无密码 |
| RocketMQ | 5.5 | `D:\rocketmq-all-5.5.0-bin-release` |
| Node.js | 20+ | 前端与小程序构建 |

### 1. 初始化数据库

```bash
mysql -uroot -p123456 < sql/init.sql
# 校验：应看到 13 张表
mysql -uroot -p123456 -D study_agent -e "show tables;"
```

### 2. 配置环境变量

```bash
copy .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY（阿里云百炼控制台获取）
```

### 3. 一键启动

```bat
scripts\start-all.bat
```

或手动分别启动：

```bash
# Java
cd server-java && mvn -DskipTests package
java -jar target/server-java-1.0.0-SNAPSHOT.jar --server.port=8080

# Python
cd server-ai
.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

### 4. 验证

| 服务 | 地址 |
|------|------|
| Java 健康检查 | http://127.0.0.1:8080/health |
| Java 接口文档 | http://127.0.0.1:8080/swagger-ui.html |
| Python 健康检查 | http://127.0.0.1:8000/health |
| Python 接口文档 | http://127.0.0.1:8000/docs |
| PC 前端 | http://127.0.0.1:5173 |

演示账号：`demo` / `123456`

## 五、文档索引

| 文档 | 内容 |
|------|------|
| [表设计.md](docs/表设计.md) | 13 张表结构、ER 图、索引设计与依据 |
| [架构.md](docs/架构.md) | 双栈拆分理由、模块职责、可替换点 |
| [环境说明.md](docs/环境说明.md) | 本机中间件与工具链、端口、常见问题 |
| [踩坑记录.md](docs/踩坑记录.md) | 真实踩过的坑与解决方式 |

## 六、进度

| 周 | 主题 | 状态 |
|----|------|------|
| 第 0 周 | 环境确认与仓库初始化 | ✅ 完成 |
| 第 1 周 | 中间件 + 表设计 + 双工程骨架 + 登录 | ✅ 完成 |
| 第 2 周 | 业务 CRUD 闭环 + PC / 小程序主页面 | ⬜ 未开始 |
| 第 3 周 | AI 能力层（RAG + 规划 Agent + SSE） | ⬜ 未开始 |
| 第 4 周 | RocketMQ 异步 + 微信订阅消息 | ⬜ 未开始 |
| 第 5 周 | 可视化收尾 + 性能优化 | ⬜ 未开始 |
| 第 6 周 | 部署 + 文档 + 面试准备 | ⬜ 未开始 |

## 七、安全说明

- `.env` 已被 `.gitignore` 排除，**API Key 一律走环境变量，不硬编码、不提交**
- `sql/init.sql` 里的演示账号密码为 BCrypt 密文
- 微信 AppSecret、JWT Secret 同理由环境变量注入
