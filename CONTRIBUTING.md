# 贡献指南

感谢愿意花时间改进 WordBook。这是一个**个人自用向**的小项目，所以流程刻意保持轻量，但有几条底线。

## 开发环境

| 需要 | 版本 |
|---|---|
| JDK | 17+ |
| Android SDK | compileSdk 34 / minSdk 26 |
| Gradle | 用仓库自带 Wrapper（8.9），不要用系统 Gradle |

```bash
git clone https://github.com/yeyouluo/wordbook-android.git
cd wordbook-android
./gradlew assembleDebug        # 产物会出现在 dist/
```

用 Android Studio 打开也可以，首次会自动装好 SDK。

### 受限网络（可选）

若 Gradle 报 `SSLHandshakeException`（Java 流量被防火墙拦），仓库自带本地反代脚本：

```bash
python3 tools/maven-proxy.py                                  # 保持终端开着
echo "mavenProxy=http://127.0.0.1:18080" >> local.properties  # 该文件不入库
./gradlew assembleDebug
```

## 代码约定

1. **分层不能破**：`UI → ViewModel → DAO / AiClient / util`。界面不直接访问数据库与网络。
2. **数据库改动必须写 Migration**：`AppDatabase` 里追加 `Migration(n, n+1)`，禁止 `fallbackToDestructiveMigration`
   （用户词库不能因为升级消失）。
3. **AI 调用只从 `WordBookViewModel.newClient()` 一处构造**：新增厂商只需改 `AiProvider` 预设表。
4. **规则类逻辑放 `util/`**：复习调度在 `ReviewSchedule`（首页统计、每日通知、桌面小组件共用），
   CSV 在 `CsvUtil`，文本解析在 `WordTextUtils`（纯函数，可单测）。不要复制第二份。
5. **防御式解析模型输出**：只在 `{...}` 区间解码、字段缺失填空、解析失败要能降级而不是崩溃。
6. **功能取舍**：新功能要能回答"用户每天会用吗"。本项目宁可少而精，不做设置项堆砌。

## 提交前自检

```bash
./gradlew assembleDebug        # 必须通过
./gradlew lintDebug            # 关注新增的 error / 有意义的 warning
```

顺手看一眼 `git status`：不要提交 `local.properties`、`keystore/`、`.gradle/`、`.kotlin/`、`app/build/`。

## 提交信息

用约定式前缀 + 中文描述，例如：

```
feat: 批量查词支持失败项重试
fix: 旋转屏幕后复习弹窗重复弹出
ci: 去掉 setup-android，改用 runner 自带 SDK
docs: 补充多厂商 AI 配置说明
```

## Pull Request

- 一个 PR 只做一件事，说明**为什么**（用户场景）比说明改了什么更重要；
- 涉及 UI 的改动请附截图（仓库目前还没有截图，欢迎顺手补）；
- 涉及数据库/数据格式的改动，请在描述里写清迁移与兼容策略。

## 报告问题

用仓库的 Issue 模板（Bug 报告 / 功能建议）。Bug 请附上：机型与系统版本、App 版本（设置页底部可见）、
复现步骤、以及相关日志（如 `adb logcat | grep -i wordbook`）。

安全相关问题请走 [SECURITY.md](SECURITY.md)，不要开公开 Issue。
