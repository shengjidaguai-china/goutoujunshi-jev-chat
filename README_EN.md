<!-- README_SYNC: source=working-tree; updated=2026-10-06 -->

<p align="center"><a href="./README.md">简体中文</a> · English</p>

# Goutoujunshi Jev Chat

**Goutoujunshi beside your chat window: screen reading, analysis, and reply drafts.** This standalone project builds on [Goutoujunshi](https://github.com/shengjidaguai-china/goutoujunshi). The public downloads are a Mac source preview, a Windows preview ZIP, and an Android debug APK. The Android build currently cannot capture WeChat chat screenshots, so WeChat is unsupported. Windows and Android still need device-level validation. You decide whether to send every draft.

If it helps you, [Star the project](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/stargazers) so you can find it again and help others discover it.

## Latest update: Android review, cancellation, and DeepSeek context

On October 6, 2026, the Android implementation was updated:

- Hiding review, disabling the assistant, or changing chats cancels old work and clears review state. Stale results cannot reopen the overlay; active HTTP connections are disconnected where possible.
- DeepSeek judgment, rotated-label checks, and candidate ranking receive the current contact's stage, goal, background, and selected history. Detailed analysis and reply explanations also receive the context.
- Only complete JSON string arrays become candidates. Explanations, error objects, and truncated outputs produce an error message.
- Identical messages in different contacts are handled separately. With OCR automatic analysis off, automatic recognition only lights the bubble. Manual screenshot recognition still opens review; analysis always requires confirmation.
- All seven Chinese strategies have specific next-step advice. Your own replies no longer clear the other person's stop-contact request; a new message from that person allows reassessment.
- The strategy connectivity test uses the reply address and key currently typed in the form, without requiring a save. Regression tests cover cancellation, candidate formats, context, chat identity, and boundaries.

On October 4, 2026, [PR #1](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/pull/1) was merged into `main`. It addresses transcript review disappearing or looping in apps such as Soul that use manual screenshot recognition:

- Accessibility window events are ignored while the review editor is open, preventing overlay focus changes from replacing the review with an idle panel.
- After “Confirm transcript and analyze,” the service waits up to about one second for focus to return to the original chat app before validating the window and starting analysis.
- Switching chat apps or failing to restore the window shows an error. Unit tests cover the related state decisions.

The [post-merge main run](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/actions/runs/37172091002) passed Android unit tests and APK assembly, plus the Mac and Windows builds. Android device validation was not performed in this round.

**Package version: `v0.1.7-preview`.** The Android APK includes these fixes. Windows and Mac packages are rebuilt alongside it; their capture and analysis logic was not changed in this round. Download the files from [GitHub Releases](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/releases/latest) and verify them against `SHA256SUMS.txt` on the release page. Version `v0.1.6-preview` includes PR #1 but not these fixes. See the [Android guide](integrations/jev_android/README.md) for installation, DeepSeek configuration, and manual screenshot recognition.

Android remains a preview, and WeChat remains unsupported. Automated tests cover the updated logic; overlay focus, device capture support, and actual model compatibility still require device-level validation. Requests already sent to a provider cannot be recalled; disabling cancels subsequent steps and discards stale results.

## Mac, Windows, and Android preview packages

Download the file for your platform from [GitHub Releases](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/releases/latest). Extract the full Windows ZIP before launching its executable. Build logs are available on the [GitHub Actions build page](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/actions/workflows/platform-build.yml).

| Platform | Artifact | Current status |
| --- | --- | --- |
| macOS | [`goutoujunshi-jev-chat-mac.zip`](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/releases/latest/download/goutoujunshi-jev-chat-mac.zip) | Source ZIP. Run `安装依赖.command`, then `离线演示.command` or `启动.command`. Requires Python 3.12 and uv; there is no signed `.app`. |
| Windows | [`goutoujunshi-jev-chat-windows-preview.zip`](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/releases/latest/download/goutoujunshi-jev-chat-windows-preview.zip) | Executable-directory ZIP. Automated build passes; Windows device validation is pending. |
| Android | [`goutoujunshi-jev-chat-android-debug.apk`](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/releases/latest/download/goutoujunshi-jev-chat-android-debug.apk) | Android 11+ debug preview. **It currently cannot capture WeChat chat screenshots, so WeChat is unsupported.** Other chat-app paths still need device validation. |

All three builds now include transcript review, Jev or DeepSeek strategy judgment, ranked drafts, detailed analysis, style rewrites, relationship stage and goal, and example/CSV candlestick charts. The screenshots below show the Mac interface; layouts and capture capabilities differ by platform. Android still cannot capture WeChat. See the [Windows guide](integrations/jev_windows/README.md) and [Android guide](integrations/jev_android/README.md).

### Android: install the debug APK

On Android 11 or later, download the APK and allow installation from that source when prompted. An older debug APK may have a different temporary signing certificate; if Android rejects an update, uninstall the old build first (which clears its local app settings). Select Jev or DeepSeek for strategy judgment, configure a reply model, and choose local ML Kit or an image model (DeepSeek Flash, OpenRouter, or another compatible endpoint) for screenshot recognition. Grant Accessibility and overlay permissions as guided. The assistant and automatic analysis start disabled. Check recognized text and speakers before confirming analysis. **This Android preview cannot currently capture WeChat chat screenshots, so it does not support WeChat.** QQ, X, and Feishu paths still require validation on actual devices. The app drafts replies; you decide whether to send them.

### Windows: extract the ZIP

Requires Windows 10 version 1903 or later, or Windows 11, with WeChat for Windows 4.x. Download the preview ZIP, **extract the entire archive**, open the `goutoujunshi-jev-chat-windows` folder, and run `goutoujunshi-jev-chat-windows.exe`. The packaged build does not require a separate Python installation.

In Settings, select **Jev or DeepSeek strategy judgment** and configure a reply provider. OCR can run locally with RapidOCR or send a cropped chat image to DeepSeek/OpenRouter. Open the intended WeChat conversation and keep the window visible. After capture, click “Review transcript and analyze” to correct text and speakers. The result also offers detailed analysis, a style rewrite, per-chat stage and goal settings, and a CSV chart window. You can copy a candidate or fill a draft. Filling depends on window coordinates; verify the conversation, recipient, and draft before sending it yourself. This build still needs Windows device validation. See the [Windows guide](integrations/jev_windows/README.md) for running from source.

### macOS: run the source preview

Install Python 3.12 and [`uv`](https://docs.astral.sh/uv/), then download and extract the Mac ZIP. Run `安装依赖.command`, followed by `离线演示.command` to check that the interface opens. Run `启动.command` for normal use. In “Settings → Interfaces and models,” choose a strategy provider (automatic, TypeSafe Jev, or DeepSeek) and a reply provider (DeepSeek or OpenRouter), then open “Configure interfaces” to save the corresponding keys. DeepSeek strategy judgment can be paired with OpenRouter replies. Image recognition can independently use Apple Vision, DeepSeek, or OpenRouter; the cloud options each use their own key. The new settings are in the current source; an older Release ZIP may lack them.

Grant the **terminal that launches the app** Screen Recording permission in macOS Privacy & Security. Filling a chat draft also requires Accessibility permission. Open the intended conversation, choose “Read conversation” from the floating bubble, verify the recognized transcript and speakers, and confirm analysis. This ZIP is a source preview, not a signed `.app`. More commands and OCR options are in [Install and run](#install-and-run) below.

## Screenshots

These screenshots show the Mac version. Synthetic demo data is labeled separately from live captures.

### Overlay beside WeChat

![The overlay beside WeChat shows possible intent, evidence, advice, and ranked reply drafts](documentation/screenshots/overlay-in-wechat.png)

*Desktop screenshot supplied by the author. The panel shows a possible intent, the model's confidence estimate, evidence from the visible chat, and ranked reply drafts. The 52% and 48% values are relative recommendation weights for this set of drafts. “Copy” puts a draft on the clipboard; “Fill” inserts it into the current chat draft. You choose whether to send it.*

### Detailed analysis

![Detailed analysis in an offline synthetic demo](documentation/screenshots/analysis-detail-demo.png)

*Offline synthetic demo. The detail view separates possible intent, advice, your own feelings, observed facts, and reasonable hypotheses. The transcript tab lets you check the recognized text before analysis. The 62% shown here is a model self-assessment for its intent hypothesis, not a validated probability.*

### Relationship candlestick window

![The relationship candlestick window with example patterns and a chat CSV import option](documentation/screenshots/kline-window.png)

*Click “K-line” at the top of the overlay to open the relationship trends window. The menu offers five patterns, and you can import a chat CSV to explore how the chart changes over time.*

### Five illustrative patterns

![Five relationship candlestick examples](documentation/screenshots/five-kline-patterns.png)

*Five relationship patterns: mutual warming, cooling after intense chat, repair after conflict, a busy but reliable partner, and drawing a line after a clear boundary. Follow the changes along the timeline, then compare each turning point with the chat event behind it.*

## One round of use

1. Open the target conversation in WeChat for Mac and click “Read conversation” in the overlay.
2. Check the recognized text, speakers, relationship stage, and your goal before confirming analysis.
3. Review the possible intent, confidence estimate, evidence, advice, and ranked reply drafts. Open “Detailed analysis” for facts, hypotheses, unknowns, next steps, and stop conditions.
4. Copy a draft or fill the verified chat input. You decide when and whether to send it.

**Apple Vision** performs local text recognition by default. You can opt into **DeepSeek or OpenRouter image recognition**; those modes send a cropped chat screenshot to the selected service and incur API usage. OpenRouter has a separate image-model field that requires an image-capable model. The Mac form can select DeepSeek or OpenRouter for reply drafts; other OpenAI-compatible endpoints remain available through environment variables. Strategy judgment can remain automatic (Jev when configured, otherwise the reply model), use Jev explicitly, or call DeepSeek independently before drafting. The DeepSeek path extracts evidence, then checks first-token `logprobs` across three rotated A–G label mappings; it shows seven relative strategy weights only when the choices agree and all labels are present. Otherwise it keeps the evidence-based DeepSeek judgment and marks weights unavailable. These extra requests add latency and usage; weights are not reply-success probabilities. Keys are stored in separate Mac Keychain entries. No real keys are included in this repository.

Intent confidence is the reply model's uncalibrated self-assessment. Candidate percentages are relative recommendation weights within the current set of drafts. Neither is a verified probability of intent, reply rate, or relationship outcome. The app shows an unknown state when the evidence is insufficient.

## What makes it different

- **Replies in your style:** It uses only verified messages attributed to you in the current conversation. “More like me” revises the current drafts without training a model or borrowing text from other conversations.
- **Reasons and trade-offs:** Expand each candidate to see why it fits and what it costs. Advice includes an observation window and a stop condition.
- **Opt-in relationship profiles:** With explicit consent, the app stores limited background details. You can inspect, pause, undo, or delete them. It does not store the full chat.
- **Charts with stated rules:** Five example patterns are included. For an imported CSV, the chart tracks daily message-direction balance. Neither chart measures love or relationship quality.
- **Human control:** OCR results are reviewed first, automatic analysis starts off, and the chat is checked again before filling. The app does not press Send.

## Install and run

Requires macOS, Python 3.12, and [`uv`](https://docs.astral.sh/uv/). From the repository root:

```bash
cd integrations/jev_mac
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python -r requirements.txt
./start.command --demo
```

`--demo` uses a synthetic conversation and stays offline: it neither reads WeChat nor calls a model. For real use, run `./start.command`. Run `./start.command --settings` to open settings directly, then use “Interfaces and models → Configure interfaces.” Save the OpenRouter key there. For replies, click “Use for replies”; for image recognition, save a separate image model ID, then select OpenRouter image recognition under “Screen reading and overlay.” These choices are independent. Grant the launching terminal macOS Screen Recording permission. Filling a draft also requires Accessibility permission.

![Offline preview of the provider configuration window](documentation/design/provider-config-preview.png)

*Earlier offline preview of the provider settings. The current form adds an OpenRouter card. Stored keys are never shown, and this image contains no real key.*

For models, OCR choices, Keychain and environment configuration, CSV format, and operating limits, see the [Mac usage guide](integrations/jev_mac/README.md) (Chinese).

## Verification and status

On September 28, 2026, Python tests and the Android debug APK build/unit tests passed after the cross-platform strategy and transcript-review updates. Windows device validation remains pending. To repeat the local checks:

```bash
python3 -B scripts/validate_skill.py
python3 -B -m unittest discover -s tests -q
```

These are **preview builds**. Live WeChat capture, model requests, and Accessibility filling on Mac were not repeated in this offline check. Windows still needs device-level checks of capture, overlay, and filling against actual chat app versions. Android's non-WeChat chat-app paths also need device validation; this build disables the WeChat capture entry point. An incomplete Jev judgment stops reply drafting. Windows filling uses window coordinates: it checks the current chat and foreground window, but cannot read back the input control. Copy and paste manually when the target is uncertain. Cloud image recognition and analysis send relevant content to the configured services. See the [data-use notice](PRIVACY.md).

The repository bundles Goutoujunshi's behavior rules and selected knowledge. Its original code uses the [MIT License](LICENSE). Mac window modules are adapted from [jev-chat-jarvis-mac](https://github.com/jev-chat/jev-chat-jarvis-mac), with its MIT notice in [vendor/LICENSE](integrations/jev_mac/vendor/LICENSE). Android sources come from [Jev Android](https://github.com/jev-chat/jev-chat-jarvis), and Windows sources from [Jev Windows](https://github.com/jev-chat/jev-chat-windows); both retain their LICENSE and NOTICE in their directories. See the [Windows NOTICE](integrations/jev_windows/NOTICE) for the PySide6-Fluent-Widgets distribution license.
