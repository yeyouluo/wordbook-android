<div align="center">

# WordBook · 单词本

**本地优先的安卓单词本**：多厂商 AI 查词与批量/对话式录入，艾宾浩斯复习计划与战绩，桌面小组件；数据全部保存在手机本地。

[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.09-4285F4.svg?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Release](https://img.shields.io/badge/Release-v0.3.2-success.svg)](../../releases)
[![CI](https://github.com/yeyouluo/wordbook-android/actions/workflows/android.yml/badge.svg)](../../actions/workflows/android.yml)

[功能](#-功能) · [快速开始](#-快速开始) · [AI 配置](#-ai-配置支持多厂商) · [工作原理](#-它是怎么工作的) · [项目结构](#-项目结构) · [Roadmap](#-roadmap) · [使用手册](使用手册.md) · [贡献](CONTRIBUTING.md)

</div>

---

## 为什么又做一个单词本？

市面的词典 App 功能很全，但要么把词库锁在云端，要么塞满广告和会员墙。这个项目的目标很窄：

> **把自己在阅读中遇到的生词，用最少的操作变成"真的背下来"的词，而且数据始终在自己手里。**

所以它的取舍是：**本地优先（Room，无账号无同步）+ 一个可换的 AI 大脑（批量查词/对话收词）+ 艾宾浩斯复习闭环**。
它是自用项目，功能不追求全，追求"每天真的会用"。

## ✨ 功能

<table>
<tr><td width="50%" valign="top">

**📥 录入**

- 🤖 **AI 查词**：一次生成音标、释义、例句、记忆法、同根词（带中文释义）
- 🎵 **批量 AI 查词**：一次粘贴 ≤100 个词，逐个查完即入库，带进度/取消/失败重试
- 💬 **AI 会话窗口**：贴一批词或直接提问，一次调用返回文字回复 + 可勾选入库的词卡
- 📤 **分享接收**：微信/浏览器里选词 → 分享 → 自动预填
- 📋 **剪贴板捕捉**：复制生词后打开 App 会提示入库（同词只提醒一次）
- ⬆️ **CSV 导入**：按表头名匹配列，Excel 整理好一键搬家

</td><td width="50%" valign="top">

**🧠 复习**

- 📅 **艾宾浩斯调度**：第 1/2/4/7/15/30 天复习，连对 5 次自动"已掌握"
- 🎯 **随机抽测**：隐藏释义、听音猜义，一键记 ✓/✗
- 📊 **复习战绩**：累计正确率 + 连续复习天数 + 本周复习天数
- 🔔 **每日提醒**：点通知直达复习弹窗
- 🧩 **桌面小组件**：不打开 App 就能看「今日待复习 N」，点一下开始复习

**🔧 其它**

- 🔊 **发音**：系统 TTS（离线），例句可整句朗读
- 🎨 **深色模式**：跟随系统
- ⬇️ **CSV 导出 / 每日自动备份**
- 🔍 搜索、筛选（学习中/已掌握）、字母或时间排序

</td></tr>
</table>

## 📱 界面一览

> 项目目前没有放截图（欢迎 PR 补充）。下面是主要界面的结构示意：

```
主界面                                  AI 会话窗口
┌──────────────────────────────┐        ┌──────────────────────────────┐
│ 单词本       🎵 💬 ⬆️ ⬇️ ⚙️ │        │ AI 助手             清空  ✕ │
├──────────────────────────────┤        ├──────────────────────────────┤
│ 🔍 搜索单词或释义        ✕    │        │ 你：resign compel retain     │
├──────────────────────────────┤        │ AI：已备好 3 个词条 ↓        │
│ [全部] [学习中] [已掌握] ⇅   │        │ ┌──────────────────────────┐ │
├──────────────────────────────┤        │ │ ☑ resign /rɪˈzaɪn/       │ │
│ 📚 已掌握 12 / 共 40 · 学习中 28│       │ │   v. 辞职；放弃          │ │
│ 🔔 今日待复习 5 个            │        │ │   🔗 resignation(辞职)   │ │
│ 📈 正确率 82% · 🔥 连续 4 天  │        │ └──────────────────────────┘ │
│ [开始复习]  [随机抽测]        │        │      [入库选中 (3)]          │
├──────────────────────────────┤        ├──────────────────────────────┤
│ 🔊 resign /rɪˈzaɪn/      ✓ 🗑│        │ 贴一批单词，或问点什么…  ➤   │
│    v. 辞职；放弃              │        └──────────────────────────────┘
│    🔗 resignation(辞职)      │
└────────── ＋ ────────────────┘        桌面小组件
                                        ┌──────────────────┐
                                         │ 单词本            │
                                         │ 今日待复习 5 个    │
                                         │ 已掌握 12 / 共 40  │
                                         └──────────────────┘
```

## 🚀 快速开始

### 方式一：直接装 APK（推荐给只想用的人）

1. 到 [Releases](../../releases)（或自己构建，见下）下载 `WordBook-v0.3.2-release.apk`；
2. 传到手机安装（首次需允许"未知来源应用"）；
3. 打开 App → 右上角 ⚙️ → 选 AI 厂商并填自己的 API Key（见下一节）。

| 包 | 体积 | 用途 |
|---|---|---|
| `WordBook-v0.3.2-release.apk` | ~11 MB | 日常使用 |
| `WordBook-v0.3.2-debug.apk` | ~17 MB | 需要看日志/调试时 |

两者**同签名**，可以互相覆盖安装且不丢数据。

### 方式二：从源码构建

```bash
git clone https://github.com/yeyouluo/wordbook-android.git
cd wordbook-android

# 用 Android Studio 打开即可（推荐），或命令行：
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # → app/build/outputs/apk/release/app-release.apk
```

构建完成后，带项目名的产物会自动复制到 `dist/`：

```
dist/WordBook-v0.3.2-debug.apk
dist/WordBook-v0.3.2-release.apk
```

**环境要求**：JDK 17+、Android SDK（compileSdk 34 / minSdk 26）。首次构建建议直接用 Android Studio 打开，它会自动装好 SDK。

<details>
<summary>受限网络（Java 流量被防火墙拦截）怎么构建</summary>

有些公司网络会按 TLS 指纹拦截 Java 的握手（`curl` 正常但 Gradle 报 `SSLHandshakeException`）。仓库自带一个本地反向代理脚本：

```bash
# 1. 起代理（保持终端开着）
python3 tools/maven-proxy.py

# 2. 在 local.properties 里加一行（该文件不入库）
echo "mavenProxy=http://127.0.0.1:18080" >> local.properties

# 3. 正常构建；不带这一行时，Gradle 只用标准仓库，对普通网络零影响
./gradlew assembleDebug
```

</details>

<details>
<summary>签名说明（为什么我没有 keystore）</summary>

个人签名密钥 `app/keystore/wordbook.keystore` **被 .gitignore 排除**，不会进仓库。构建行为：

- 本机存在该文件 → debug 与 release 共用它，两种包可互相覆盖升级；
- 不存在（clone 别人的仓库）→ debug 使用 Android Studio 默认调试签名，release 为未签名包。

想自己出一把可长期升级的正式包：

```bash
keytool -genkeypair -v -keystore app/keystore/wordbook.keystore \
  -alias androiddebugkey -storepass android -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000
```

⚠️ 生成后请立刻备份 keystore 与口令——丢了就无法再给已安装用户升级。
</details>

## 🔑 AI 配置（支持多厂商）

App 不绑定某一家厂商：内置 8 个预设，全部走 **OpenAI 兼容的 `/chat/completions`** 协议，差别只有 `baseUrl / model / apiKey` 三个变量。设置页可切换厂商、改模型、填 Key，并有**「测试连接」**按钮直接显示成功或具体报错。

| 厂商 | 默认模型 | 申请地址 |
|---|---|---|
| DeepSeek | `deepseek-chat` | platform.deepseek.com |
| Kimi（月之暗面） | `moonshot-v1-8k` | platform.moonshot.cn |
| 通义千问（阿里云百炼） | `qwen-plus` | bailian.console.aliyun.com |
| 智谱 GLM | `glm-4-flash` | open.bigmodel.cn |
| 硅基流动 SiliconFlow | `Qwen/Qwen2.5-7B-Instruct` | cloud.siliconflow.cn |
| OpenRouter | `openai/gpt-4o-mini` | openrouter.ai/keys |
| OpenAI | `gpt-4o-mini` | platform.openai.com |
| 自定义 | 手填 | 任何 OpenAI 兼容服务（中转站 / Ollama / vLLM） |

- 每个厂商的 Key **分开保存**，切换厂商不用重填；
- 支持 `response_format: json_object` 的厂商会用结构化输出；不支持的会自动去掉该参数重试一次；
- 不填 Key 也能用：手动添加、分享接收、剪贴板、CSV 导入、复习计划全部可用，只是没有 AI。

> **费用**：API 由你自己的账号计费。查一个词通常几厘钱，批量 100 词约几分钱。

## 🧠 它是怎么工作的

**1. 复习调度（艾宾浩斯）**：每个词只存三个字段——复习次数、连续答对次数、上次复习时间；间隔表 `[1,2,4,7,15,30]` 天写在代码里，`dueAt = base + intervals[min(连对次数, 5)]`。
答对 → 间隔右移一档；答错 → 回到第 1 天；连对 5 次自动标记「已掌握」，退出复习队列。
**首页统计、每日通知、桌面小组件共用同一份实现**（`util/ReviewSchedule`），不会出现"三处规则不一致"。

**2. AI 协议**：查词用 `response_format: json_object` 约束模型输出结构化词条；会话窗口用同样的机制让模型一次返回
`{"reply": "...", "words": [...]}`——**一条消息只花一次调用**，贴 8 个词也会一次返回 8 张词卡。
解析层对模型输出做防御式处理：只在 `{...}` 区间解码、字段缺失填空、同根词给字符串数组或对象数组都能吃下。

**3. 数据**：全部在 Room（SQLite）里，无账号、无埋点、无网络上报；CSV 导出/导入是唯一的"离开本机"通道，
并且每天自动备份一份到 App 私有目录（保留最近 7 份）。

## 🏗 项目结构

```
app/src/main/java/com/example/wordbook/
├── MainActivity.kt              # Activity：TTS 生命周期、Intent 入口（分享/快捷方式/通知/小组件）
├── WordBookApplication.kt       # 注册每日周期任务（备份 + 通知）
├── ShareReceiverActivity.kt     # 接收其他 App 分享的文本
├── WordBookWidgetProvider.kt    # 桌面小组件（RemoteViews）
├── WordBookViewModel.kt         # 全部状态与业务：词库、复习调度、批量任务、会话状态机
├── data/
│   ├── Word.kt                  # Room 实体
│   ├── WordDao.kt / AppDatabase.kt   # DAO + 版本迁移（v1→v2→v3，都是追加式）
│   ├── AiProvider.kt            # 厂商预设表（baseUrl / 默认模型 / 能力）
│   └── AiClient.kt              # 唯一的 AI 客户端（查词 / 问答 / 会话 / 测试连接）
├── ui/
│   ├── ListScreen.kt            # 主界面 + 单词卡片 + 统计卡片
│   ├── Dialogs.kt               # 添加 / 复习 / 抽测 / 详情 / 批量 / 设置
│   ├── ChatScreen.kt            # AI 会话窗口（全屏）
│   └── Theme.kt                 # 深色模式与配色
└── util/
    ├── ReviewSchedule.kt        # 复习调度 + 战绩统计（唯一实现点）
    ├── CsvUtil.kt               # 导出/导入/备份共用（按表头名匹配列）
    ├── WordTextUtils.kt         # 文本→单词候选的纯函数（可单测）
    └── DailyJobs.kt             # WorkManager：每日备份 + 每日通知
```

**架构约定**：`UI 只调用 ViewModel；ViewModel 只碰 DAO / AiClient / util`。所有 AI 调用都从 `newClient()` 一处构造，
所以 v1.5 加多厂商支持只动了 4 个文件。

## 🛠 技术栈

| 组件 | 版本 |
|---|---|
| Kotlin / AGP / Gradle | 2.0.20 / 8.5.2 / 8.9 (Wrapper) |
| Jetpack Compose | BOM 2024.09.03 + Material 3 |
| Room | 2.6.1（KSP） |
| WorkManager | 2.9.1 |
| OkHttp / kotlinx.serialization | 4.12.0 / 1.7.3 |
| compileSdk / minSdk / targetSdk | 34 / 26 / 34 |

## 🔒 隐私

- 词库、API Key、复习记录**全部存在手机本地**，App 无账号体系、无统计上报；
- 只有你主动使用 AI 功能时，相关文本才会发给你自己配置的 AI 厂商；
- 对话历史仅在内存中，退出即清；
- App 申请的权限只有「联网」和「通知」。

## 🗺 Roadmap

- [x] 多厂商 AI、批量查词、AI 会话、桌面小组件、复习战绩
- [ ] 分用途模型（查词用便宜模型 / 聊天用强模型）
- [ ] 复习曲线可视化与导出
- [ ] 单词分组 / 标签
- [ ] 单元测试（`ReviewSchedule`、`CsvUtil`、`WordTextUtils` 三处纯函数最值得覆盖）
- [x] **WebDAV 云同步**（0.3 起：Seafile 预填、三态触发、锚点决策、LWW 冲突备份）
- [ ] 局域网直连同步（免网盘）

## ⚠️ 已知限制

- 批量/分享录入**不支持带空格的词组**（`give up` 会被当两个词），只识别单个英文单词；
- AI 会话单条消息最多 20 个词，超过建议用批量入口；
- **正确率从 0.1 版开始累计**（更早的自用版本只存"连对次数"，无法反推历史正确率，界面会提示）；
- **云同步只同步词库**（含学习进度），**不含 AI Key、界面偏好与同步凭据**——换机后需要重新填 AI Key；
- 冲突策略是**最后写入优先（LWW）**：两台设备同时离线各改一份时，后同步的一方会赢，输的一方会自动
  导出 CSV 备份到应用目录（保留最近 7 份），不会静默丢数据；
- release 未开启混淆与资源裁剪，体积换"行为与 debug 完全一致"。

## 🤝 贡献

欢迎 Issue 与 PR，完整约定见 **[CONTRIBUTING.md](CONTRIBUTING.md)**。三条底线：

- 新功能要能回答"用户每天会用吗"——本项目宁可少而精，不做设置项堆砌；
- UI 不直接访问数据库与网络，统一走 ViewModel；规则类逻辑放 `util/` 复用，不复制第二份；
- 数据库改动**必须写 Migration**，不允许 `fallbackToDestructiveMigration`；提交前 `./gradlew assembleDebug` 通过。

| 入口 | 用途 |
|---|---|
| [Bug 报告](../../issues/new?template=bug_report.yml) | 闪退、行为不符预期、数据异常 |
| [功能建议](../../issues/new?template=feature_request.yml) | 新功能或改进想法 |
| [使用手册](使用手册.md) | 安装、AI 配置、复习体系、导入导出、常见问题 |
| [SECURITY.md](SECURITY.md) | 安全问题请走私密渠道，不要开公开 Issue |
| [CHANGELOG.md](CHANGELOG.md) | 版本变更记录 |

## 📄 License

[Apache License 2.0](LICENSE) © 2026 yeyouluo

> 本项目与 DeepSeek、OpenAI、月之暗面等任何 AI 厂商均无关联；使用各厂商 API 时请遵守其服务条款。
> 项目按"现状"提供，作者不对数据丢失或 API 费用负责——重要数据请定期导出 CSV。
