# 🚀 人生智能编排体 (Life Planner AI) 版本发布日志

## [v1.1.0] - 2026-10-16 诚实化与加固版

本版本的主线是：让文档和行为与事实一致，并修复审核报告中指出的问题。

> **取代说明**：下方 v1.0.4 及更早版本中关于「双模透明化」「离线降级 / 本地规则演示模式」「无 Key 时 100% 本地运行」「APK 离线秒开」的说法，以及 v1.0.4 中「全量 11 项自动化测试 100% 通过」的结论，均已被本版本取代，保留原文仅作历史记录。当时的测试之所以能通过，正是因为存在本版本已移除的模板降级路径；并且旧测试会直接读写你的真实 `life_planner.db`。自 v1.1.0 起：AI 不再降级，测试改用临时数据库隔离，APK 也不能离线使用。

### AI 行为变化
1. **AI 不再降级为模板**：未配置 `AI_API_KEY`，或模型不可用（连不上、超时、鉴权失败、限流、返回内容无法解析）时，AI 接口返回 HTTP 错误和中文说明，不再生成替代内容，也不会写入任务或 AI 记录。已移除 `AI_MOCK_FALLBACK` 配置项。
2. **危机拦截覆盖更多入口**：除决策顾问外，每日/每周复盘和每日规划也会先做本地危机内容筛查，命中时不调用模型。
3. **周复盘的进度建议需用户确认**：AI 只给出建议值和理由，用户确认后才通过 `POST /api/goals/progress/apply` 写入，AI 不会自己修改目标进度。
4. **AI 记录标注来源**：新记录带 `model_name`；来源取值为 `ai_model`、`crisis_safety_guard`、`legacy_unverified`。
5. **默认模型配置不再偏向某一家**：默认 `AI_BASE_URL=https://api.openai.com/v1`、`AI_MODEL=gpt-4o-mini`，任何兼容 OpenAI 协议的端点均可使用。

### 数据
6. **目标软删除**：删除目标只标记 `deleted_at`（子目标一并标记），名下历史任务保留，复盘历史可追溯。
7. **数据库迁移**：`migrate_db()` 幂等地添加 `goals.deleted_at`、`ai_messages.model_name`，并把旧版本的 `ai_model` / `offline_mock` 记录标记为 `legacy_unverified`（来源未核实）。

### Android
8. **改用 `WebViewAssetLoader` 加载页面**（`https://appassets.androidplatform.net/assets/...`），并关闭 `allowFileAccess`、`allowContentAccess`、`allowFileAccessFromFileURLs`、`allowUniversalAccessFromFileURLs`。此前页面从 `file://` 加载，`fetch` 相对路径会变成 `file:///api/...` 而失败。
9. **后端地址与错误提示**：未设置地址、连不上服务器、后端返回错误时都会弹出明确提示（不再静默）；保存地址时校验 `http://` / `https://` 开头并去掉末尾斜杠，`http://` 仅允许局域网/本机地址。状态胶囊「本地离线」改为「未连接服务器」，App 没有后端不能使用。
10. **收紧权限**：`allowBackup` 改为 `false`。明文 HTTP 仍然允许（`network-security-config` 不支持按网段放行，局域网 IP 无法逐个列举），详见 README 的风险说明。
11. **构建**：`gradle-wrapper.properties` 的 Gradle 版本统一为 8.4（与 CI 一致）；`build_apk.bat` 先找 `gradlew.bat`，再找全局 `gradle`，都没有时给出下一步说明。仓库仍未提交 `gradle-wrapper.jar`。
12. 版本号：`versionCode 2`、`versionName 1.1.0`。

### 其他
13. **XSS 转义补全**：Android 内置页面对任务、目标、缺口等插值统一转义。
14. **测试隔离**：测试改用临时数据库，不再写入 `life_planner.db`；运行方式为 `py -m pytest -q`，`pytest` 移到 `requirements-dev.txt`。
15. **依赖加上界**：`requirements.txt` 为各依赖补充了大版本上限。
16. **启动与配置**：`start.py` 横幅在未配置 Key 时提示 AI 功能将报错，并提示服务无登录保护；`.env.example` 的 `DEBUG` 默认值改为 `False`。
17. **README**：如实写明配置 Key 后健康数据、档案、目标和近 7 天日志会发送给模型服务商；写明服务无登录保护。

### 尚未验证
- Android 代码（Kotlin / Gradle）**没有在本机编译**，本次开发环境没有 JDK、Android SDK 和 Gradle；编译结果以 CI 或你自己的构建为准。
- 未在真机或模拟器上运行过 APK。

---

## [v1.0.4] - 2026-10-16 权威审核全面达标版 (Audit Verification & Security Hardening)

本版本基于权威《审核报告》中的专项检测，对系统的安全机制、算法严谨度、双模透明度与原生移动端通信进行了全方位加固与重构，全量 11 项自动化测试 100% 通过（0 警告、0 错误）。

### 🛡️ 核心安全与算法修复 (P0 全部闭环)
1. **[P0-2] 极端安全危机熔断与心理援助直连**：
   - 增加**确定性前置危机筛查**（不依赖网络与云端模型），对自伤、轻生与严重绝望表述瞬间短路拦截；
   - 坚决杜绝"可以但有条件"等不当推演，立即启动红色关怀防线，直接返回结论"必须停止规划并寻求专业支持"；
   - 界面直显全国 24 小时免费心理救助热线（全国希望生命救助热线 `400-161-9995`、北京心理危机干预热线 `010-82951332`、青少年热线 `12355`、紧急报警 `110/120`），并支持移动端一键拨打。
2. **[P0-1] 双模透明化与降级来源醒目标记**：
   - AI 顾问、日计划、目标拆解与复盘全量注入 `source` 标记（`ai_model` / `offline_mock` / `crisis_safety_guard`）；
   - 未配置 API 密钥时，界面顶部醒目展示 `⚠️ 本地规则演示模式` 横幅，确保数据诚实，杜绝假冒大模型输出；
   - 离线降级模式下不再静默向 `tasks` 表写入虚拟任务，彻底避免真实待办数据污染。
3. **[P0-3] 睡眠时长缺失伪造修复与缺口算法升级**：
   - 修复未填睡眠被伪造为 7.5 小时的错误，无数据时准确返回 `None`，图表真实断线留空；
   - 缺口雷达新增"连续多天未记录作息睡眠"独立探测项；对真实记录的睡眠计算负债，杜绝预警失效。
4. **[P0-4] 目标停滞检测长标题语义词干匹配**：
   - 重构 D4 目标冷落检测算法，引入书名号分词与词干抽取（如「攻坚『雅思7分』...」精准提取「雅思」与「雅思7分」）；
   - 联动关联 `Task` 近 7 天打卡完成度，彻底消除对 AI 拆解长标题目标的误报。

### ⚡ 架构质量与移动端跨域加固 (P1 修复)
1. **[P1-1] 全局 CORS 跨域支持**：`main.py` 正式引入 `CORSMiddleware`，放行 `allow_origins=["*"]` 与预检请求；安卓原生 WebView 增加 `allowFileAccessFromFileURLs` 与 `allowUniversalAccessFromFileURLs`，彻底打通局域网真机联调。
2. **[P1-4] 隐私边界透明声明**：明确区分离线本地存储（100% 留存在本地 SQLite）与云端 API 调用数据出境边界。
3. **[P1-5] 全局 XSS 净化**：新增 `LifePlanner.escapeHtml`，对任务标题、目标、技能及问答历史进行无死角转义防注入；目标树卡片弹窗改用 ID 缓存映射，杜绝属性引号解析崩溃。
4. **[P1-7] 现代化规范重构与零告警**：
   - FastAPI 启动事件升级为最新异步 `lifespan`；
   - Pydantic 全面升级为 `ConfigDict` 与 `SettingsConfigDict`，消除 23 条废弃警告；
   - 全面弃用 `utcnow()` 改为 `datetime.now(timezone.utc)`；
   - `calculate_streak` 连续打卡算法单次 SQL 聚合搞定，消除 N+1 循环查询；
   - SQLite 自动启用 `PRAGMA foreign_keys=ON`，增量无损字段迁移 `migrate_db()` 自动补齐表结构。

---

# 🚀 人生智能编排体 (Life Planner AI) v1.0.0 正式版发布

> **懂你、判断、解释、推动你前进的贴身人生规划与决策随身智囊**  
> 专为智能手机与原生安卓设备深度打造 · OLED Pure Black & Liquid Glass 顶级视觉

---

## 📱 原生安卓与手机端重磅支持

- **🤖 原生安卓应用工程 (Native Android Project)**：
  - 内置完整的官方标准 Android Studio 原生工程（位于 `android/` 目录）；
  - 采用原生 Kotlin/Java 开发，支持无边框全屏沉浸、OLED 纯黑底色与状态栏自适应；
  - 集成原生触感振动反馈桥接 (`WebAppInterface.kt`)，点击与保存时提供舒适的触觉震动；
  - 配套 `build_apk.bat` 与 GitHub Actions CI/CD 流水线，自动云端构建生成可安装的 Android Debug/Release APK。
- **⚡ 手机端免安装秒变 App (PWA 规范)**：
  - 完美适配手机浏览器（iPhone Safari / Android Chrome / 微信），点击「添加到主屏幕」即可生成独立全屏图标；
  - 彻底去除浏览器地址栏与工具条，全屏沉浸运行，内置离线 Service Worker 缓存。
- **🌐 局域网真机联调支持**：
  - 启动脚本 `start.py` 自动探测并输出本机局域网 IP（如 `http://192.168.x.x:8000`），同 Wi-Fi 下拿起手机即可直接连、直接用。

---

## 🧠 AI 智能体核心四大模式 (D1 ~ D4)

1. **D1 每日规划引擎 (Daily Plan)**：
   - 综合历史记录、昨日复盘与进行中重点目标，自动生成今日 3-5 个关键行动项；
   - 自动推入今日待办清单，一键勾选即时完成，并附带针对性作息与健康警醒。
2. **D2 智能决策顾问 (Ask Advisor)**：
   - 面对熬夜、聚餐、辞职、投资等现实生活选择，严格输出标准 **6 段式深度结构化研判**：
     - **【结论】**：建议做 / 不建议做 / 可以但有条件（三选一醒目置顶）
     - **【理由】**：深度结合用户档案、身体状况与近期记录
     - **【好处】**：预期积极收益 (Pros)
     - **【坏处与风险】**：潜在短期消耗与长期代价 (Cons & Risks)
     - **【最佳折中方案】**：安全缓冲防线与执行细则 (Alternative)
     - **【与目标的关系】**：促进或阻碍了哪项目标 (Goal Impact)
3. **D3 战术与战略双轨复盘 (Review)**：
   - **每日复盘**：精准比对计划完成度，提炼高光交付与偏离归因，输出明日战术修正；
   - **周度复盘**：汇总近 7 天生活投入全景，分析各目标推进变化与全周最大摩擦点。
4. **D4 缺口检测雷达 (Gap Detection)**：
   - 自动扫描近 7-14 天生活数据，智能捕获「睡眠负债」、「营养失衡 (蔬菜/蛋白质不足)」、「目标停滞」等隐性危机，提前预警防患于未然。

---

## 🎯 目标系统与 30 天走势大盘

- **多层级目标树**：支持 Vision（愿景）➔ Long（长期 1-5 年）➔ Stage（阶段 1-3 个月）➔ Weekly（周）四级树形架构；
- **AI 智能拆解与一键采纳**：输入宏观目标，AI 自动拆解为阶段与周计划，支持一键批量生成并挂载到目标树；
- **近 30 天双轴趋势图表**：基于 Chart.js 响应式渲染专注学习时长与睡眠节律双曲线、心情与精力电量波动曲线。

---

## 🧪 质量与测试保障

- 核心业务与 API 接口均通过自动化测试验证：
  - `tests/test_stage1.py`：阶段 1 核心增删改查自测通过
  - `tests/test_stage2.py`：阶段 2 AI 智能体接入与 6 段式研判自测通过
  - `tests/test_stage3.py`：阶段 3 复盘与缺口雷达自测通过
  - `tests/test_stage4.py`：阶段 4 趋势大盘与全流程 E2E 集成测试全部通过

---

## 📥 安装与运行

### 电脑本地启动
```bash
# 1. 安装依赖
pip install -r requirements.txt

# 2. 启动服务 (同时输出局域网手机访问地址并自动打开浏览器)
python start.py
# 或 Windows 直接双击 run.bat
```

### 原生安卓 APK 构建
- 在 Android Studio 中打开 `android/` 目录，点击 **Build ➔ Build Bundle(s) / APK(s) ➔ Build APK(s)** 即可导出 APK；
- 或直接在 GitHub Releases / GitHub Actions Artifacts 页面下载由云端自动构建完成的最新 APK 安装包！
