<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png?v=2" alt="Pawchive" width="120" />
</p>

<h1 align="center">Pawchive</h1>

<p align="center">
  <a href="README.en.md">English</a> | <a href="README.ja.md">日本語</a>
</p>

<p align="center">
  一款精致、流畅的第三方 Android 客户端，为你带来 <a href="https://pawchive.pw">Pawchive</a> 平台的完整体验。<br/>
  聚合 Patreon、Fanbox、Discord 等平台的创作者内容，支持浏览、搜索、收藏与沉浸式媒体播放。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/API-30%2B-34A853?style=for-the-badge&logo=android&logoColor=white" alt="Min API" />
  <img src="https://img.shields.io/badge/Target_API-36-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Target API" />
  <img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge" alt="License" />
  <a href="https://t.me/Pawchive_Archive"><img src="https://img.shields.io/badge/Telegram-Channel-26A5E4?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram Channel" /></a>
  <a href="https://github.com/Aerunox/Archive"><img src="https://img.shields.io/badge/Next_Gen-Archive-181717?style=for-the-badge&logo=github&logoColor=white" alt="Next Generation Repository" /></a>
</p>

> [!IMPORTANT]
> 🚀 **新一代项目**：Pawchive 1.9.0 为本仓库最终版本，后续开发已迁移至新一代仓库 **[Aerunox/Archive](https://github.com/Aerunox/Archive)**，欢迎前往关注与 Star。
>
> 📢 **Telegram 频道**：加入 [Pawchive Archive](https://t.me/Pawchive_Archive)，第一时间获取版本发布与项目动态。

---

## 功能亮点

### 内容浏览
- **首页信息流**：分页加载最新内容，支持关键词筛选；可选「同作者仅显示一条」与「隐藏已收藏创作者的帖子」
- **创作者主页**：帖子 / 公告 / 关联账号 / 相似作者分页浏览（左右滑动切换），标签筛选、粉丝卡一览
- **帖子详情**：完整正文（白名单 HTML 渲染）、评论、修订历史、附件下载
- **多平台聚合**：Patreon / Fanbox / Discord 等来源，平台标签按品牌色区分

### 精准搜索
- **关键词搜索**：同时检索帖子与创作者，Tab 切换查看
- **文件哈希反查**：搜索框粘贴文件哈希（MD5 / SHA-256）追踪素材出处，支持 Discord 结果
- **离线全文搜索**：基于 Room FTS4 的中文分词索引，断网也能搜收藏内容
- **搜索历史**：本地持久化，保留条数可配置（5–50），支持单条删除与一键清空

### 沉浸式媒体
- **大图查看**：双指缩放、双击缩放、边界约束拖拽
- **动图播放**：GIF / 动画 WebP / 动画 HEIF，列表带「GIF」角标
- **视频播放**：Media3 ExoPlayer，Bilibili 风格控制器，倍速播放、断点续播、画中画；全屏与内嵌共用播放器实例，进出全屏零重新缓冲，画面等比缩放
- **多级回退**：原图优先，缺失时自动回退缩略图

### 收藏与账号
- **多账号切换**：账号间收藏 / 历史 / 下载数据隔离
- **云端收藏**：登录后同步收藏的帖子与创作者，写入后即时失效缓存；离线时操作自动排队，联网后后台补写
- **本地收藏**：无需登录，收藏即建立离线归档索引
- **离线阅读**：收藏帖全文归档（Room FTS4），断网可检索与阅读

### 内容订阅与通知
- **订阅创作者**：关注喜欢的创作者，新帖自动周期检测（间隔可调，最小 15 分钟）；可选「收藏即订阅」
- **系统通知**：检测到新帖即时推送，可随时关闭
- **应用内通知中心**：首页铃铛直达，未读数角标实时刷新；支持单条/全部已读，订阅管理页可随时退订

### 下载中心
- **HTTP Range 断点续传**：暂停保留临时文件，断点落库，应用重启后继续仍从断点续传；服务端不支持 Range 时自动降级为全量下载
- **独立任务通知**：每条任务一个通知，实时显示百分比 / 已下载大小 / 速度（EMA 平滑）/ 剩余时间，通知内可暂停、继续、取消
- **后台持续下载**：`dataSync` 前台服务保活，切后台不中断
- **下载规则**：按创作者 / 服务 / 文件类型自动入队
- **合规约束**：并发上限钳制 5、同文件重试间隔 ≥1 秒、自定义可识别 User-Agent

### 个性化设置
- **多语言**：中文 / English / 日本語，实时切换
- **外观**：日间 / 夜间 / 跟随系统，6 套主题强调色，Material Design 3；Android 12+ 支持动态取色（Material You）；沉浸式状态栏
- **显示与缩放**：整体 UI 缩放与全局文字大小滑杆，实时预览、即时生效
- **省流量**：列表默认加载缩略图，可切换为原图
- **下载参数**：自定义下载目录（SAF）、最大并发数（1–5）、重试次数（1–3）、仅 Wi-Fi 下载、文件命名格式
- **自动备份**：每日导出备份到所选目录
- **应用内更新**：1.9.0 为最终版本，此后停止更新，已移除 GitHub 更新检查功能；后续发展请关注新一代仓库 [Aerunox/Archive](https://github.com/Aerunox/Archive)

---

## 技术架构

| 类别 | 技术选型 | 说明 |
|------|---------|------|
| **语言** | Kotlin 2.3.20 | 由 AGP 9.3.2 内置，无需单独应用 Kotlin 插件 |
| **最低 SDK** | API 30 (Android 11) | 覆盖绝大多数活跃设备 |
| **目标 / 编译 SDK** | API 36 (minorApiLevel 1) | 最新 Android 版本 |
| **UI 框架** | XML + ViewBinding | 声明式布局，类型安全访问 |
| **设计语言** | Material Design 3 | 卡片分组、分段按钮、品牌色标签 |
| **模块化** | 10 个 Gradle 模块 | `app` / 7×`feature-*` / `data` / `core` |
| **依赖注入** | Hilt 2.59.2 + KSP 2.3.6 | `@HiltAndroidApp` / `@AndroidEntryPoint` |
| **本地存储** | Room 2.8.4 + DataStore 1.1.1 | 下载历史 / 离线归档 FTS4 用 Room，设置用 DataStore |
| **下载引擎** | 自研 OkHttp 流式下载 | 1.7.0 起替换 okdownload，单连接单请求 + Range 断点续传 |
| **网络层** | Retrofit 2.9.0 + OkHttp 4.12.0 | 类型安全 HTTP 客户端 |
| **图片加载** | Coil 2.6.0 + coil-gif | 协程原生，注册 `ImageDecoderDecoder` 支持动图 |
| **视频播放** | AndroidX Media3 1.4.1 | ExoPlayer + OkHttp 数据源 |
| **后台任务** | WorkManager 2.9.0 + Hilt | 周期订阅同步、自动备份、缓存清理 |
| **构建工具** | Gradle 9.5.0 + AGP 9.3.2 | 版本目录（`libs.versions.toml`）统一管理 |
| **质量门禁** | Kover 0.9.9 | 核心层（core + data）行覆盖率 ≥ 45% |

### 架构总览

模块协作与数据流向图（点击节点可跳转对应源文件）：

```mermaid
flowchart TD

subgraph group_ui["App and features"]
  node_app["Main app<br/>[MainActivity.kt]"]
  node_home["Home feed<br/>[HomeFragment.kt]"]
  node_search["Search UI<br/>[SearchFragment.kt]"]
  node_post["Post details"]
  node_account["Account and creator<br/>[AccountFragment.kt]"]
  node_settings["Settings"]
  node_downloads_ui["Download center UI"]
end

subgraph group_content["Content access"]
  node_api["Platform API<br/>[PawchiveApi.kt]"]
  node_clearance["Challenge clearance"]
  node_offline[("Offline search")]
end

subgraph group_library["Saved library"]
  node_archive["Offline archive"]
  node_bookmarks["Bookmarks<br/>[BookmarkManager.kt]"]
  node_session[("Account sessions<br/>[SessionManager.kt]")]
  node_subscriptions["Creator subscriptions"]
end

subgraph group_operations["Background operations"]
  node_rules["Download rules"]
  node_enqueue["Download enqueue"]
  node_download["Download execution<br/>[DownloadCenter.kt]"]
  node_http_download["HTTP transfer"]
  node_notifications["Download notifications"]
  node_download_service["Foreground service"]
  node_workers["Scheduled work"]
end

subgraph group_platform["Core platform"]
  node_settings_store[("Preferences<br/>[SettingsManager.kt]")]
  node_database[("Room database")]
end

node_user(("App user"))
node_creator_service{{"Pawchive platform"}}
node_android{{"Android system"}}

node_user -->|"opens"| node_app
node_app -->|"shows tab"| node_home
node_app -->|"shows tab"| node_search
node_app -->|"shows tab"| node_downloads_ui
node_user -->|"opens content"| node_post
node_user -->|"manages account"| node_account
node_user -->|"configures"| node_settings
node_home -->|"loads feed"| node_api
node_search -->|"searches online"| node_api
node_api -->|"requests content"| node_creator_service
node_api -->|"obtains clearance"| node_clearance
node_search -->|"searches archive"| node_offline
node_post -->|"saves content"| node_bookmarks
node_bookmarks -->|"indexes saved posts"| node_archive
node_archive -->|"stores archive"| node_database
node_offline -->|"queries index"| node_database
node_account -->|"manages sessions"| node_session
node_settings -->|"manages subscriptions"| node_subscriptions
node_subscriptions -->|"persists subscriptions"| node_database
node_post -->|"applies rules"| node_rules
node_rules -->|"enqueues matches"| node_enqueue
node_enqueue -->|"adds tasks"| node_download
node_download -->|"transfers files"| node_http_download
node_http_download -->|"writes files"| node_android
node_download -->|"updates progress"| node_notifications
node_notifications -->|"starts service"| node_download_service
node_download_service -->|"forwards actions"| node_download
node_downloads_ui -->|"manages tasks"| node_download
node_workers -->|"checks creators"| node_subscriptions
node_workers -->|"schedules work"| node_android
node_settings -->|"reads and writes"| node_settings_store

classDef toneNeutral fill:#f8fafc,stroke:#334155,stroke-width:1.5px,color:#0f172a
classDef toneBlue fill:#dbeafe,stroke:#2563eb,stroke-width:1.5px,color:#172554
classDef toneAmber fill:#fef3c7,stroke:#d97706,stroke-width:1.5px,color:#78350f
classDef toneMint fill:#dcfce7,stroke:#16a34a,stroke-width:1.5px,color:#14532d
classDef toneRose fill:#ffe4e6,stroke:#e11d48,stroke-width:1.5px,color:#881337
classDef toneIndigo fill:#e0e7ff,stroke:#4f46e5,stroke-width:1.5px,color:#312e81
classDef toneTeal fill:#ccfbf1,stroke:#0f766e,stroke-w
