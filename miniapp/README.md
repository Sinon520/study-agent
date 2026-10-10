# 备考学习 Agent · 微信小程序（UniApp + Vue3）

## 这是什么

PC 端负责"重"操作（建计划、录错题、传资料、看报告），小程序负责"随手记"：
**今天要做什么、错一道题马上记下来、排队时问一句 AI**。所以这里只做只读 + 轻写。

| 页面 | 路径 | 能力 |
|------|------|------|
| 登录 | `pages/login/login` | 账号密码登录、微信一键登录（`uni.login` → 后端换 openid） |
| 今日计划 | `pages/plan/plan` | 今日任务勾选完成、计划列表 + 任务抽屉 |
| 错题本 | `pages/wrong/wrong` | 按科目/掌握度/标签筛选、展开看答案、批量标记掌握度 |
| AI 问答 | `pages/chat/chat` | 流式问答（SSE 分块接收）、引用来源展示 |
| 学习进度 | `pages/progress/progress` | 4 个概览指标 + 时长柱状（纯 CSS）+ 科目/掌握度分布 |
| 我的 | `pages/my/my` | 用户信息、数据概览、未读通知、退出登录 |

## 为什么是 CLI 版 uni-app

本项目用 **CLI 版 uni-app**（`@dcloudio/vite-plugin-uni`）而不是 HBuilderX 图形工程，
原因：

1. **可 CI / 可命令行验证**：`pnpm build:mp-weixin` 能直接产出 `dist/build/mp-weixin`，
   不依赖图形界面的"发行"按钮；
2. **依赖与锁文件入库**：`pnpm-lock.yaml` 保证任何人装出来的版本一致；
3. **同一份源码也能被 HBuilderX 打开**：HBuilderX 识别 `src/manifest.json` + `src/pages.json`，
   习惯图形化的同学可以直接导入。

## 快速开始

```bash
# 1. 安装依赖
pnpm install

# 2. 构建微信小程序（产出 dist/build/mp-weixin）
pnpm build:mp-weixin

# 开发模式（watch，改代码自动重编）
pnpm dev:mp-weixin
```

然后用**微信开发者工具**导入 `dist/build/mp-weixin` 目录：

- AppID 选「测试号」即可（工程的 `project.config.json` 里是 `touristappid`）；
- 必须勾选 **详情 → 本地设置 → 不校验合法域名**，因为开发期请求的是
  `http://127.0.0.1:8080`（微信只允许 https 且需在小程序后台配白名单）；
- 真机预览需要把后端暴露到公网（内网穿透）或部署到有 https 的服务器。

> H5 预览：`pnpm dev:h5`，浏览器打开输出里的地址，适合快速看样式。

## 目录结构

```
miniapp/
├── src/
│   ├── App.vue             全局样式（含 .sa-* 通用类）
│   ├── main.js             createSSRApp 入口（小程序必须用 SSR 形态）
│   ├── manifest.json       AppID / 平台配置（appid 留空，导入时用测试号）
│   ├── pages.json          页面注册 + tabBar
│   ├── utils/request.js    请求封装（token、traceId、401 跳登录、业务码解包）
│   └── pages/
│       ├── login/login.vue
│       ├── plan/plan.vue
│       ├── wrong/wrong.vue
│       ├── chat/chat.vue
│       ├── progress/progress.vue
│       └── my/my.vue
├── pnpm-workspace.yaml     pnpm 11 的 allowBuilds（见下）
├── vite.config.js
└── package.json
```

## 两个必须知道的坑

### 1. pnpm 11 的构建脚本放行

pnpm 10+ 默认**不执行**依赖的 postinstall。`esbuild` 与 `core-js` 的 postinstall 被拦下时：

- `pnpm install` 以 `ERR_PNPM_IGNORED_BUILDS` + exit 1 结束；
- 更麻烦的是 `pnpm run` 前的 `depsStatusCheck` 会因此判定依赖未就绪、自动重跑 install，
  于是 `pnpm run build:mp-weixin` **还没执行到 uni 就失败**，报错还伪装成
  `Command failed ... pnpm install`，极难定位。

解决：`pnpm approve-builds --all` 生成 `pnpm-workspace.yaml` 里的 `allowBuilds`。
**注意 `package.json` 里的 `"pnpm"` 字段在 pnpm 11 起已不再被读取**（会在日志里警告并忽略）。

### 2. 小程序端拿不到 SSE 流

`uni.request` 默认不支持流式读取，所以 `pages/chat/chat.vue` 里用的是
`enableChunked: true` + `onChunkReceived` 手动解析 SSE 帧。这条路径**要等第 3 周
后端 `/chat/ask` 就绪后才能真正验证**；如果验证不通过，按降级预案把小程序问答页
改为"一次性返回"（`enableChunked` 置 false，等完整响应后一次渲染）。

## 与后端的接口对应

全部走 `utils/request.js` 里的 `api` 对象，与 PC 端调用同一套 HTTP 接口：

| 小程序能力 | 接口 |
|-----------|------|
| 登录 / 注册 / 微信登录 | `POST /auth/login`、`/auth/register`、`/auth/wx-login` |
| 今日任务 / 勾选完成 | `GET /plan/task/today`、`PUT /plan/task/status` |
| 计划列表 / 任务列表 | `GET /plan/page`、`GET /plan/task/list` |
| 错题筛选 / 批量掌握度 | `GET /wrong-question/page`、`PUT /wrong-question/batch/mastery` |
| 进度 | `GET /progress/recent`、`GET /progress/overview` |
| 通知 | `GET /notice/page`、`GET /notice/unread-count`、`PUT /notice/read/all` |
