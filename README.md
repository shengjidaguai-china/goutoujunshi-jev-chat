<!-- README_SYNC: source=working-tree; updated=2026-10-04 -->

<p align="center">简体中文 · <a href="./README_EN.md">English</a></p>

# 狗头军师 Jev Chat

**聊天窗口旁的狗头军师：读屏、分析、生成回复草稿。** 这是从[狗头军师](https://github.com/shengjidaguai-china/goutoujunshi)延伸出来的独立项目。目前公开提供 Mac 源码预览包、Windows 预览 ZIP 和 Android 调试 APK。Android 版目前无法截取微信聊天画面，暂不支持微信；Windows 和 Android 仍需实机测试。发送始终由用户决定。

如果这套聊天副驾对你有用，可以给[项目点一个 Star](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/stargazers)，方便以后找到，也让更多有相同需求的人看到它。

## 最近更新：Android 原文核对流程

2026 年 10 月 4 日，[PR #1](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/pull/1) 已合并到 `main`。本次修复针对 Soul 等通过手动截图识别的应用中，原文核对页消失或循环返回的问题：

- 核对页编辑文字时，忽略核对期间的无障碍窗口事件，避免焦点变化把页面替换成待机状态。
- 点「确认原文并分析」后，最多等待约一秒，让焦点回到原聊天应用，再验证窗口并开始分析。
- 如果已切换聊天应用，或窗口未恢复，显示提示；新增对应的状态判断单元测试。

[合并后的 main 构建](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/actions/runs/37172091002)已通过 Android 单元测试与 APK 构建，以及 Mac、Windows 构建；本轮未做 Android 真机验收。

**本次打包版本：`v0.1.6-preview`。** Android APK、Windows 预览 ZIP 与 Mac 源码 ZIP 均从包含本次合并的 `main` 重新构建。请从 [GitHub Releases 下载页](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/releases/latest)下载对应文件，按同页 `SHA256SUMS.txt` 校验。`v0.1.5-preview` 及更早版本尚未包含 PR #1。Android 的安装、DeepSeek 配置与通用截屏入口见 [Android 使用说明](integrations/jev_android/README.md)。

Android 仍处于预览阶段，微信仍不支持。核对中隐藏／停用助手、DeepSeek 判断的关系上下文、候选格式校验等问题仍待修复，详情见 Android 使用说明。本次合并覆盖原文核对页与焦点恢复的修复。

## Mac、Windows 与 Android 预览包

直接从 [GitHub Releases 下载页](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/releases/latest)下载对应平台的文件。Windows 的 ZIP 需完整解压后运行其中的程序。构建记录可在 [GitHub Actions 构建页](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/actions/workflows/platform-build.yml)查看。

| 平台 | 构建产物 | 当前状态 |
| --- | --- | --- |
| macOS | [`goutoujunshi-jev-chat-mac.zip`](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/releases/latest/download/goutoujunshi-jev-chat-mac.zip) | 源码 ZIP；解压后运行 `安装依赖.command`，再运行 `离线演示.command` 或 `启动.command`。需要 Python 3.12 和 uv，尚无签名 `.app`。 |
| Windows | [`goutoujunshi-jev-chat-windows-preview.zip`](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/releases/latest/download/goutoujunshi-jev-chat-windows-preview.zip) | 可执行目录 ZIP；自动构建通过 |
| Android | [`goutoujunshi-jev-chat-android-debug.apk`](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/releases/latest/download/goutoujunshi-jev-chat-android-debug.apk) | Android 11+ 调试预览包。**目前无法截取微信聊天画面，暂不支持微信。**  |

三端现已接入核对原文、Jev／DeepSeek 策略判断、候选排序、详细分析、口吻改写、关系阶段与目标，以及 K 线示例和聊天 CSV 导入。截图展示的是 Mac 界面，Windows 和 Android 的布局及采集能力仍有差异；Android 微信截图目前不可用。各端的操作与已知限制见 [Windows 使用说明](integrations/jev_windows/README.md)和 [Android 使用说明](integrations/jev_android/README.md)。

### Android：安装调试 APK

在 Android 11 或更新版本上下载 APK，允许系统安装此来源的应用后安装。旧版调试 APK 若因签名不同无法覆盖安装，需先卸载旧包；卸载会清除本机应用设置。打开应用，选择 Jev 或 DeepSeek 策略判断，配置回复模型；截图识图可选本地 ML Kit 或视觉模型（DeepSeek Flash、OpenRouter 等）。按界面提示授予无障碍、悬浮窗权限；首次安装时助手和自动分析默认关闭，需要主动开启。识别后先核对原文与双方身份，再确认分析。**当前 Android 预览版无法截取微信聊天画面，所以微信暂不支持。** 请勿把 Mac 版微信旁的截图理解为 Android 效果；QQ、X、飞书等路径也仍需在对应设备上验证。应用只生成草稿，发送由你决定。

### Windows：解压 ZIP

适用于 Windows 10 1903 及以上或 Windows 11，目标聊天应用为微信 Windows 4.x。下载预览 ZIP，**完整解压**后进入 `goutoujunshi-jev-chat-windows` 文件夹，运行 `goutoujunshi-jev-chat-windows.exe`；这个打包版本无需另装 Python。

首次打开设置，选择 **Jev 或 DeepSeek 策略判断**，配置回复生成接口；识图可选 RapidOCR 本地、DeepSeek 或 OpenRouter。打开要处理的微信会话，保持聊天窗口可见；新消息出现后先点「核对原文并分析」，修正文字和说话人，再看候选。可打开详细分析、改写口吻、设置当前会话的阶段与目标，或在 K 线窗口导入聊天 CSV。候选可以复制或填入草稿；填入依赖当前窗口位置，使用前请确认会话、收件人和草稿内容，最后由你自己发送。此包尚待 Windows 实机验收。[Windows 使用说明](integrations/jev_windows/README.md)还列出了源码运行方式。

### macOS：运行源码预览包

先安装 Python 3.12 和 [`uv`](https://docs.astral.sh/uv/)，再下载并解压 Mac ZIP。依次运行包内的 `安装依赖.command`、`离线演示.command`；确认界面能打开后，运行 `启动.command`。首次使用在「设置 → 接口与模型」分别选择策略判断（自动／TypeSafe Jev／DeepSeek）和回复生成（DeepSeek／OpenRouter），再点「配置接口」填写对应 Key。DeepSeek 策略判断可与 OpenRouter 回复组合。识图可以独立选 Apple Vision、DeepSeek 或 OpenRouter；云端识图各用对应的 Key。
OpenRouter 图形配置已进入当前源码；旧版 GitHub Release ZIP 若没有 OpenRouter 卡片，请使用更新后的源码包。

读取微信需在 macOS「隐私与安全性」中给**启动程序的终端**开启「屏幕录制」权限；要将候选填入草稿，再开启「辅助功能」权限。打开目标会话后，点悬浮球「读取对话」，先核对识别原文与双方身份，再确认分析。这个 ZIP 是源码预览包，尚无签名 `.app`；更详细的命令和识图选项见下方[Mac 安装与启动](#mac-安装与启动)。

## 界面预览

以下截图展示 Mac 版；演示图中的数据与真实会话分开标注。

### 微信旁的悬浮窗

![微信旁的狗头军师悬浮窗，展示意图、依据、建议和候选回复](documentation/screenshots/overlay-in-wechat.png)

*图：作者提供的桌面截图。悬浮窗显示对方可能的意图、模型估计的判断把握、原文依据和候选回复排序。图中的 52%／48% 是这轮候选的相对推荐权重。点击「复制」可取出文字；「填入」只写入当前聊天草稿，发送仍由用户决定。*

### 详细分析

![狗头军师详细分析的离线合成演示](documentation/screenshots/analysis-detail-demo.png)

*图：离线合成演示。详细页把可能意图、军师建议、自己的感受、已知事实与合理推测分开；「核对原文」页供分析前检查识别结果。图中的 62% 是模型对意图推测的自评，并非经过验证的概率。*

### 关系趋势 K 线入口

![关系趋势 K 线窗口，可选择走势案例或导入聊天 CSV](documentation/screenshots/kline-window.png)

*图：点击悬浮窗右上角的「K线」，进入关系趋势窗口。下拉菜单包含五种走势，也可以导入聊天 CSV 查看随时间变化的曲线。*

### 五种走势怎样读

![五种关系走势的 K 线读图示例](documentation/screenshots/five-kline-patterns.png)

*图：五种关系走势——双向升温、热聊后降温、冲突后修复、忙但仍兑现、明确边界后收线。沿着时间轴看每次升降，再对照相应的聊天事件，就能看到关系节奏在哪些节点发生变化。*

## 一轮怎么用

1. 打开 Mac 微信中的目标会话，点悬浮球的「读取对话」。
2. 核对识别出的原文、说话人、关系阶段和目标，再确认分析。
3. 查看对方**可能的意图**、判断把握、依据、军师建议和「候选回复排序」。打开「详细分析」可看事实、推测、未知、下一步与停止条件。
4. 选择候选并复制，或在确认当前会话和输入控件后填入**草稿**。发送由你决定。

默认使用 **Apple Vision 本地文字识别**；设置中可改为 **DeepSeek 或 OpenRouter 图片识别**，此时聊天区域截图会发送至所选服务并产生接口用量。OpenRouter 的识图模型 ID 与回复模型 ID 分开配置，须选择支持图像输入的模型。回复生成可在界面中选择 DeepSeek 或 OpenRouter；其他 OpenAI 兼容接口仍可用环境变量。策略判断可选择自动（Jev 优先，无 Jev 时由回复模型判断）、指定 TypeSafe Jev，或指定 DeepSeek 独立判断。DeepSeek 选项先整理证据，再用三次标签轮换检查七种策略的首 token 权重；结果不稳定时标明权重不可用，继续使用已得到的 DeepSeek 证据判断。随后由回复模型生成候选。此过程会增加接口请求及用量，权重不是回复成功率。各 Key 分开配置，界面输入后存入本项目专用的 Mac 钥匙串条目；仓库不包含任何真实 Key。

「判断把握」是模型对意图推测的自评；候选百分比是本轮回复的相对推荐权重。它们都不是对方的真实意图概率、回复率或关系成功率。没有可靠依据时，界面会提示无法判断。

## 项目特色

- **像自己说话**：只参考当前会话中经过核对、确认为「我」的原话；「更像我一点」可重新调整候选口吻。不训练模型，也不读取其他会话来模仿。
- **把理由讲清楚**：每条候选可展开适用理由和代价；建议同时给观察窗口与停止条件，避免只产出一句话术。
- **关系档案可选择**：首次明确同意后才保存有限的对象背景，可查看、暂停、撤销和删除；不存整份聊天。
- **K 线有计算口径**：内置五组走势案例，也能导入核对过双方身份的 CSV，按每日消息方向绘图。图线不代表关系质量或爱意分数。
- **控制留给用户**：OCR 后先核对，自动分析默认关闭，填入前重新检查会话；程序不会替你点发送。

## Mac 安装与启动

需要 macOS、Python 3.12 和 [`uv`](https://docs.astral.sh/uv/)。在仓库根目录运行：

```bash
cd integrations/jev_mac
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python -r requirements.txt
./start.command --demo
```

`--demo` 使用合成对话，离线展示界面，不读取微信、不调用模型。真实使用时运行 `./start.command`；`./start.command --settings` 可直接打开设置。点「配置接口」填写 OpenRouter Key；选择 OpenRouter 回复时点「用作回复」，选择 OpenRouter 识图时填写识图模型 ID、点「保存识图」，再到「读屏与悬浮窗」选 OpenRouter 图片识别并保存设置。两种用途可以独立选择。Jev Key 按需配置。打开微信后，需要给**启动程序的终端**授予 macOS「屏幕录制」权限；使用「填入」还需要「辅助功能」权限。

![狗头军师 Jev Chat 的接口配置窗口](documentation/design/provider-config-preview.png)

*图：早期接口配置页的离线预览。新版新增 OpenRouter 卡片；已有 Key 不会回显，图片里没有真实密钥。*

模型、OCR、钥匙串、可选环境变量、K 线 CSV 格式和操作限制见 [Mac 使用说明](integrations/jev_mac/README.md)。

## 自检与使用边界

2026 年 9 月 28 日加入跨端策略、核对和关系功能后，Python 测试、Android 调试包构建与单元测试通过；Windows 实机运行仍待验收。可以在本地复查：

```bash
python3 -B scripts/validate_skill.py
python3 -B -m unittest discover -s tests -q
```

这些是预览包。Mac 的真实微信读屏、模型接口与辅助功能填入没有在本轮离线自检中重复验证；Windows 的聊天识别、悬浮窗及填入仍需在对应设备与微信版本上验收。Android 的非微信聊天应用路径也仍需实机验收；当前版本明确关闭微信读取入口。判断结果缺失时会停止生成回复。Windows 的填入依赖窗口坐标，虽会检查当前会话和前台窗口，仍无法读回验证输入控件；不确定时可复制候选后手动粘贴。云端识图与分析会将相关内容发送给所配置的服务，详见[数据使用说明](PRIVACY.md)。

仓库主体采用 [MIT 许可证](LICENSE)。Mac 窗口模块参考 [jev-chat-jarvis-mac](https://github.com/jev-chat/jev-chat-jarvis-mac)，保留其 [MIT 说明](integrations/jev_mac/vendor/LICENSE)；Android 与 Windows 分别参考 [Jev Android](https://github.com/jev-chat/jev-chat-jarvis) 和 [Jev Windows](https://github.com/jev-chat/jev-chat-windows)，第三方分发注意事项见 [Windows NOTICE](integrations/jev_windows/NOTICE)。

这里非常感谢 jev-chat-jarvis项目，<br>
从该项目得到启发，结合goutoujunshi而来。

联系我加入升级打怪开源群：
Email：247133278@qq.com<br>
WeChat：loonges<br>
QQ：247133278<br>
