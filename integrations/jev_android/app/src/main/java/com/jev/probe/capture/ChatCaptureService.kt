package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.CaptureInputActivity
import com.jev.probe.capture.ocr.CaptureHandoff
import com.jev.probe.capture.ocr.CaptureReadiness
import com.jev.probe.capture.ocr.captureReadiness
import com.jev.probe.capture.ocr.MlKitOcr
import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.capture.ocr.ScreenCapture
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.GoutouGuidance
import com.jev.probe.core.WorkScope
import com.jev.probe.core.WorkToken
import com.jev.probe.core.kb.ChatContext
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.JevClient
import com.jev.probe.jev.ExplanationFormat
import com.jev.probe.jev.VisionClient
import com.jev.probe.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.CancellationException
import java.util.concurrent.FutureTask

internal fun shouldIgnoreAccessibilityEvents(reviewPending: Boolean): Boolean = reviewPending

internal fun shouldReviewOcr(manual: Boolean, autoAnalyze: Boolean, latestFrom: String?): Boolean =
    manual || (autoAnalyze && latestFrom == "other")

internal enum class ReviewConfirmationState {
    WAIT_FOR_CHAT_WINDOW,
    CHAT_CURRENT,
    CHAT_CHANGED
}

internal fun reviewConfirmationState(
    livePackage: String?,
    ownPackage: String,
    expectedChatPackage: String
): ReviewConfirmationState = when (livePackage) {
    null, ownPackage -> ReviewConfirmationState.WAIT_FOR_CHAT_WINDOW
    expectedChatPackage -> ReviewConfirmationState.CHAT_CURRENT
    else -> ReviewConfirmationState.CHAT_CHANGED
}

/**
 * The live capture service (registered under a disguised class name so WeChat
 * exposes its node tree — see the disguised subclass). It reads whichever
 * adapted chat app is in the foreground, detects a new incoming message from the
 * other person, runs Jev analysis off the main thread, and drives the floating
 * overlay.
 *
 * Per-app node rules live in [ChatAppAdapter] implementations; everything here
 * is app-agnostic.
 *
 * It never sends a message. The only write action is ACTION_SET_TEXT (or a
 * clipboard PASTE fallback) to fill the chat input box when the user taps
 * "填入"; the user still presses send.
 */
open class ChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)

    /** Adapted chat apps, keyed by package name. */
    private val adapters = listOf(QQAdapter(), XAdapter(), FeishuAdapter()).associateBy { it.pkg }

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun submit(token: WorkToken = session.current, task: () -> Unit) {
        if (!session.isCurrent(token)) return
        val future = FutureTask<Unit> {
            try { WorkScope.run(token, task) }
            catch (_: CancellationException) { }
        }
        val detach = token.onCancel { future.cancel(true) }
        try { worker.execute { try { future.run() } finally { detach() } } }
        catch (_: RejectedExecutionException) { detach(); future.cancel(true) }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null
    private val session = CaptureSession { ::prefs.isInitialized && prefs.enabled }
    private var detachPrefs: (() -> Unit)? = null

    private var lastSignature: String = ""
    private var activePkg: String? = null

    /** Last known-good (non-transient) title per package. See [isTransientTitle]:
     *  a page like X's DM thread briefly shows "连接中…" as `snapshot.title`
     *  right after opening, which must never overwrite a real conversation
     *  title or get saved as a contact name. Never cleared on app switch — the
     *  next real title for that package simply replaces it. */
    private val lastGoodTitle: MutableMap<String, String> = HashMap()
    private val debounce = Runnable {
        val snapshot = pendingSnapshot
        if (snapshot != null) reviewSnapshot(snapshot, activePkg ?: foregroundPkg ?: "")
    }
    private var pendingSnapshot: ChatSnapshot? = null
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    private var foregroundPkg: String? = null

    // ---- OCR path (B stage). Everything here runs on the main thread: the
    // screenshot callback and the ML Kit callback are both posted back to it.
    private val screenCapture by lazy {
        ScreenCapture(this,
            hideOverlay = { overlay?.setHiddenForShot(true) },
            restoreOverlay = { overlay?.setHiddenForShot(false) })
    }
    private val ocr = MlKitOcr()
    private var ocrBusy = false
    private var externalCaptureId: String? = null
    private var manualContentFingerprint: String? = null
    private var lastAnalysis: com.jev.probe.core.Analysis? = null
    private var lastAnalyzedSnapshot: ChatSnapshot? = null
    private var lastContext: ChatContext? = null
    private var explanationGeneration = 0

    /** What the screen looked like the last time we fired an automatic shot.
     *  See [ocrSignature]: this is the brake on the OCR path. */
    private var lastOcrSignature: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        session.setEnabled(prefs.enabled)
        overlay = OverlayController(this)
        overlay?.onHidden = { cancelWork() }
        detachPrefs = prefs.observeEnabled { enabled ->
            session.setEnabled(enabled)
            cancelWork()
            if (!enabled) overlay?.hide() else maybeCapture()
        }
        overlay?.onManualAnalyze = {
            if (!session.reviewPending) {
                val pkg = rootInActiveWindow?.packageName?.toString() ?: foregroundPkg ?: ""
                val snapshot = currentSnapshot
                // Generic OCR must read the current screen again on every manual tap.
                if (pkg !in adapters || snapshot == null) ocrCaptureManual()
                else reviewSnapshot(snapshot, pkg)
            }
        }
        overlay?.onDetails = { requestExplanation() }
        overlay?.onExplain = { candidate -> requestExplanation(candidate) }
        overlay?.onRewrite = {
            val snapshot = lastAnalyzedSnapshot
            val analysis = lastAnalysis
            val ctx = lastContext
            val token = session.current
            val pkg = activePkg ?: foregroundPkg ?: ""
            if (snapshot == null || analysis == null || analysis.rankedReplies.isEmpty() ||
                !snapshotIsCurrent(snapshot, pkg)) {
                overlay?.toast("请先生成当前会话的候选")
            } else {
                overlay?.toast("正在按你的原话调整口吻…")
                submit(token) {
                    val client = JevClient(prefs)
                    try {
                        val candidates = client.rewrite(snapshot, analysis,
                            analysis.rankedReplies.map { it.text })
                        val ranked = client.rerank(snapshot, prefs.relationship, analysis, candidates, ctx)
                        main.post {
                            if (session.isCurrent(token) && snapshotIsCurrent(snapshot, pkg)) {
                                lastAnalysis = analysis.copy(rankedReplies = ranked)
                                overlay?.showReplies(ranked) { text -> fillInput(text, snapshot, pkg) }
                            }
                        }
                    } catch (e: Exception) {
                        main.post { if (session.isCurrent(token)) overlay?.toast(e.message ?: "口吻调整失败；原候选已保留") }
                    }
                }
            }
        }
        // Bubble menu: file the open conversation as a knowledge-base contact.
        // Contacts are never created automatically — this is the one-tap way in.
        overlay?.onSaveContact = {
            val title = currentSnapshot?.title
            val pkg = activePkg ?: foregroundPkg ?: ""
            when {
                title.isNullOrBlank() -> overlay?.toast("当前会话没有标题，存不了")
                isTransientTitle(title) -> overlay?.toast("当前会话标题还没加载出来，稍后再试")
                else -> submit {
                    val msg = try {
                        KbStore.get(this).saveOrMergeContact(title, pkg)
                    } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}" }
                    main.post { overlay?.toast(msg) }
                }
            }
        }
        // Bubble menu: one manual screenshot + OCR, for any app at all.
        overlay?.onOcrCapture = { ocrCaptureManual() }
        // Keep the process at foreground importance so MIUI does not freeze us.
        runCatching { KeepAliveService.start(this) }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { MlKitOcr.warmUp() }
        // HyperOS may kill and restart us. On (re)connect, proactively re-show the
        // bubble for whatever chat is already open, so it comes back on its own
        // instead of waiting for the user to scroll.
        main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.enabled) { session.setEnabled(false); overlay?.hide(); return }
        session.setEnabled(true)

        // Permission-window focus are expected during an explicit
        // capture. The handoff checks the original app AND content before use.
        if (externalCaptureId != null) return
        val type = event.eventType
        // A manual OCR round also becomes stale while its review editor owns
        // focus. Changes from the captured app must cancel it; edits in our
        // overlay have our own package and must keep the review intact.
        val capturedPkg = activePkg
        if (capturedPkg != null && capturedPkg !in adapters &&
            event.packageName?.toString() == capturedPkg &&
            (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                type == AccessibilityEvent.TYPE_VIEW_SCROLLED) &&
            (currentSnapshot != null || ocrBusy)) {
            // Returning from consent can redraw/recreate an unchanged chat.
            // Cancel on a real content change, not every accessibility event.
            val live = rootInActiveWindow?.takeIf { it.packageName?.toString() == capturedPkg }
                ?: windows.asSequence().mapNotNull { it.root }
                    .firstOrNull { it.packageName?.toString() == capturedPkg }
            if (live != null && manualContentFingerprint != null &&
                contentFingerprint(live) == manualContentFingerprint) return
            cancelWork()
            foregroundPkg = capturedPkg
            overlay?.showIdle(null)
            return
        }
        // showReview() makes the overlay focusable so its EditText can be
        // corrected. That focus transition emits accessibility window events,
        // while rootInActiveWindow may report either our overlay or the chat app
        // underneath. Neither is a real navigation, so review/cancel must remain
        // the sole owners of this panel until the user chooses one.
        if (shouldIgnoreAccessibilityEvents(session.reviewPending)) return

        // Decide "did we leave the chat app" from the REAL active window, not the
        // event's package. The event package can be an IME (e.g. com.tencent.wetype)
        // or the status bar while the chat app is still foreground — keying off it
        // made the bubble flicker (hide → re-show → hide…). rootInActiveWindow stays
        // on the chat app while the keyboard is up, so this is stable.
        //
        // An app with no adapter is NOT a reason to take the bubble away: the only
        // way into DingTalk / Telegram / anything else is the bubble menu's
        // "截屏识别一次", and a bubble that is gone cannot be tapped. So we park
        // the idle bubble there instead — still no automatic capture, no analysis.
        // The bubble does come off for places where it would only be in the way:
        // our own settings screens, the launcher, and the system UI.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = rootInActiveWindow?.packageName?.toString()
            if (fg != null && fg !in adapters) {
                val changed = fg != activePkg
                if (changed) cancelWork()
                foregroundPkg = fg
                val drop = fg == packageName ||
                    fg == WECHAT_PACKAGE ||
                    fg.contains("launcher", ignoreCase = true) ||
                    fg == "com.miui.home" ||
                    fg == "com.android.systemui"
                if (drop) overlay?.hide()
                else if (changed || overlay?.isShowing() != true) overlay?.showIdle(null)
                return
            }
        }

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> maybeCapture()
        }
    }

    private fun maybeCapture() {
        if (!session.current.isActive() || session.reviewPending || externalCaptureId != null) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        // Apps with no adapter are never handled automatically (v1.3 revision):
        // the only way in for them is the bubble menu's "截屏识别一次".
        val adapter = adapters[pkg]
        if (adapter == null) {
            // Rebinding the service can miss the chat's WINDOW_STATE_CHANGED.
            // Keep the explicit screenshot menu available without capturing.
            if (pkg != null && pkg != packageName && pkg != WECHAT_PACKAGE &&
                pkg != "com.android.systemui" && pkg != "com.miui.home" &&
                !pkg.contains("launcher", ignoreCase = true)) {
                foregroundPkg = pkg
                if (overlay?.isShowing() != true) overlay?.showIdle(null)
            }
            return
        }
        // Only act inside a chat window (the adapter returns null elsewhere).
        val rawSnapshot = adapter.extract(root, resources) ?: return
        // Stabilize the title BEFORE anything below reads it: some apps (X) show
        // a transient "连接中…" title for a moment right after opening a thread.
        val snapshot = stabilizeTitle(pkg ?: "", rawSnapshot)
        if (!prefs.isAllowed(snapshot.title)) { overlay?.hide(); return }
        if (pkg != activePkg || currentSnapshot?.title != snapshot.title) {
            cancelWork()
            activePkg = pkg
            currentSnapshot = snapshot
            overlay?.resetForNewConversation()
        }
        // In a chat window but the tree holds no text (Feishu draws its bodies,
        // WeChat hides them when the disguise fails) → screenshot + OCR, subject
        // to ScreenCapture's own >=1s throttle and failure backoff.
        if (snapshot.messages.isEmpty()) {
            if (prefs.ocrFallback) {
                // Gate BEFORE the shot, not after the OCR. Feishu's tree is empty
                // on every content-changed event, and a successful shot resets the
                // failure backoff — so without this the caret blinking or an
                // "online" badge flipping keeps a screenshot going out every
                // second forever. The picture can only differ if the bubbles moved
                // or the conversation changed, and that is exactly what the
                // signature measures.
                val sig = ocrSignature(pkg ?: "", snapshot.title, snapshot.bubbleRects)
                if (sig == lastOcrSignature && overlay?.isShowing() == true) return
                lastOcrSignature = sig
                ocrCapture(snapshot.title, snapshot.bubbleRects, pkg ?: "", manual = false)
            }
            return
        }

        // Switching to another adapted app resets the dedupe signature, so two apps
        // whose last few messages happen to match cannot swallow each other.
        if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

        val sig = snapshot.signature()
        val showing = overlay?.isShowing() == true
        // Same content and the bubble is already up → nothing to do.
        if (sig == lastSignature && showing) return
        // Same content but the bubble is gone (killed by MIUI, or we left and came
        // back) → just put the bubble back, do NOT re-analyze (saves tokens/time).
        if (sig == lastSignature && !showing) { overlay?.showIdle(snapshot.title); return }
        cancelWork()
        activePkg = pkg
        currentSnapshot = snapshot
        // Anything else reaching here is a genuinely different conversation (new
        // app, or new content in this one) — a leftover judgment/candidates from
        // whatever was shown before must not leak into it.
        overlay?.resetForNewConversation()
        lastSignature = sig
        Log.d(TAG, "snapshot[$pkg] title=${snapshot.title} n=${snapshot.messages.size} " +
            snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" }) // sides + lengths only, never content

        // Trigger only when the newest message is from the other person, and only
        // if auto-analyze is on. Otherwise show the idle bubble (tap to analyze).
        if (snapshot.latestFrom != "other" || !prefs.autoAnalyze) {
            overlay?.showIdle(snapshot.title); return
        }

        pendingSnapshot = snapshot
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, 800) // debounce bursts of content-changed events
    }

    /** A placeholder title an app shows only for a moment (e.g. X's "连接中…"
     *  right after opening a DM thread) — never a real conversation title.
     *  Blank/null counts too, so a caller can always fall back the same way. */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    /** Replace a transient title with the last known-good one for this package
     *  (if any), and otherwise remember the current title as the new good one. */
    private fun stabilizeTitle(pkg: String, snapshot: ChatSnapshot): ChatSnapshot {
        if (isTransientTitle(snapshot.title)) {
            val good = lastGoodTitle[pkg] ?: return snapshot
            return snapshot.copy(title = good)
        }
        snapshot.title?.let { lastGoodTitle[pkg] = it }
        return snapshot
    }

    /** Invalidate every callback before clearing UI state or starting another round. */
    private fun cancelWork() {
        explanationGeneration++
        session.reset()
        main.removeCallbacksAndMessages(null)
        pendingSnapshot = null
        currentSnapshot = null
        activePkg = null
        lastSignature = ""
        lastOcrSignature = ""
        ocrBusy = false
        manualContentFingerprint = null
        lastAnalysis = null
        lastAnalyzedSnapshot = null
        lastContext = null
        overlay?.resetForNewConversation()
    }

    private fun requestExplanation(candidate: String? = null) {
        val snapshot = lastAnalyzedSnapshot
        val analysis = lastAnalysis
        val ctx = lastContext
        val token = session.current
        val pkg = activePkg ?: foregroundPkg ?: ""
        if (snapshot == null || analysis == null || !snapshotIsCurrent(snapshot, pkg) ||
            (candidate != null && analysis.rankedReplies.none { it.text == candidate })) {
            overlay?.toast("请先核对并分析当前会话")
            return
        }
        val heading = if (candidate == null) "详细分析" else "回复理由与代价"
        val generation = ++explanationGeneration
        val requestToken = WorkToken { session.isCurrent(token) }
        val detach = token.onCancel { requestToken.cancel() }
        val back = {
            explanationGeneration++
            requestToken.cancel()
            if (session.isCurrent(token) && snapshotIsCurrent(snapshot, pkg)) overlay?.showCandidateReplies()
            Unit
        }
        overlay?.showExplanationLoading(heading, back)
        submit(token) {
            try {
                val result = WorkScope.run(requestToken) {
                    val client = JevClient(prefs)
                    if (candidate == null) client.details(snapshot, prefs.relationship, analysis, ctx)
                    else client.explain(snapshot, prefs.relationship, analysis, candidate, ctx)
                }
                main.post {
                    if (generation == explanationGeneration && session.isCurrent(token) && snapshotIsCurrent(snapshot, pkg))
                        overlay?.showDetails(result, heading)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = ExplanationFormat.failureMessage(heading, e)
                main.post {
                    if (generation == explanationGeneration && session.isCurrent(token) && snapshotIsCurrent(snapshot, pkg))
                        overlay?.showExplanationError(heading, message, { requestExplanation(candidate) }, back)
                }
            } finally {
                detach()
            }
        }
    }

    private fun runAnalysis(token: WorkToken = session.current) {
        if (!session.isCurrent(token) || session.analyzing) return
        val snapshot = pendingSnapshot ?: return
        pendingSnapshot = null
        val pkg = activePkg ?: foregroundPkg ?: ""
        if (!snapshotIsCurrent(snapshot, pkg)) return
        if (!prefs.hasKey() && !GoutouGuidance.explicitBoundary(snapshot)) {
            overlay?.showError("未设置判断接口密钥，去设置里填"); return
        }
        if (!session.beginAnalysis(token)) return
        overlay?.showLoading(); overlay?.setNote(snapshot.note); overlay?.setSnapshot(snapshot)
        val client = JevClient(prefs)
        val rel = prefs.relationship
        submit(token) {
            val ctx = try {
                ContextBuilder.build(this, snapshot, pkg, prefs)
            } catch (e: Exception) {
                Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
            }
            main.post {
                if (session.isCurrent(token) && snapshotIsCurrent(snapshot, pkg))
                    overlay?.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0)
            }

            // Judgment, draft and rank share one cancellation scope.
            token.checkActive()
            val judgment = client.judge(snapshot, rel, ctx)
            if (judgment.error != null) {
                finishAnalysis(token, snapshot, pkg) { overlay?.showError(judgment.error) }
            } else {
                main.post { if (session.isCurrent(token) && snapshotIsCurrent(snapshot, pkg)) overlay?.showJudgment(judgment) }
                token.checkActive()
                var replyError: String? = null
                val ranked = try { client.draftAndRank(snapshot, rel, ctx, judgment) } catch (e: Exception) {
                    replyError = e.message ?: e.javaClass.simpleName
                    emptyList()
                }
                finishAnalysis(token, snapshot, pkg) {
                    lastAnalysis = judgment.copy(rankedReplies = ranked)
                    lastAnalyzedSnapshot = snapshot
                    lastContext = ctx
                    overlay?.showReplies(ranked, replyError) { text -> fillInput(text, snapshot, pkg) }
                }
            }
        }
    }

    private fun snapshotIsCurrent(snapshot: ChatSnapshot, pkg: String): Boolean {
        if (!session.current.isActive()) return false
        val current = currentSnapshot ?: return false
        val root = rootInActiveWindow ?: return false
        if (pkg.isBlank() || root.packageName?.toString() != pkg ||
            current.title != snapshot.title || current.signature() != snapshot.signature()) return false
        // A new chat can be visible before OCR finishes and updates currentSnapshot.
        // Check the live title too, so the previous chat's result cannot flash over it.
        val adapter = adapters[pkg] ?: return true // manual OCR in an unadapted app
        val liveTitle = adapter.extract(root, resources)?.title ?: return false
        return isTransientTitle(liveTitle) || liveTitle == snapshot.title
    }

    private fun finishAnalysis(token: WorkToken, snapshot: ChatSnapshot, pkg: String, display: () -> Unit) {
        main.post {
            if (!session.finishAnalysis(token)) return@post
            if (snapshotIsCurrent(snapshot, pkg)) display()
        }
    }

    // ------------------------------------------------------------------ OCR

    /**
     * Bubble menu → "截屏识别一次". Works on ANY app, adapted or not: one whole
     * screen shot, every line OCR'd, lines grouped into pseudo-bubbles by line
     * spacing. Nobody can tell who said what this way, so everything is filed as
     * the other person and the panel says so.
     */
    private fun ocrCaptureManual(mode: String = prefs.captureMethod) {
        if (!session.current.isActive()) return
        val root = rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }
            ?: windows.asSequence().filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }.firstOrNull { it.packageName?.toString() != packageName }
        val pkg = root?.packageName?.toString() ?: foregroundPkg ?: activePkg ?: ""
        if (pkg.isBlank() || pkg == packageName) {
            overlay?.toast("请先切到要识别的聊天应用"); return
        }
        if (pkg == WECHAT_PACKAGE) {
            overlay?.toast("当前 Android 版无法截取微信聊天画面，暂不支持微信")
            return
        }
        // Top bar text, if this app has one we can read; else the first OCR line.
        val title = root?.let {
            findTitleInActionBar(it, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.15, 0.85)
        }
        cancelWork()
        activePkg = pkg
        foregroundPkg = pkg
        manualContentFingerprint = root?.let { contentFingerprint(it) }
        if (mode == Prefs.CAPTURE_SYSTEM) externalCapture(title, pkg, root)
        else ocrCapture(title, emptyList(), pkg, manual = true)
    }

    private fun contentFingerprint(root: AccessibilityNodeInfo): String {
        // The consent activity can recreate the chat window after an orientation change,
        // and an overlay can change node visibility without changing the chat.
        // Compare semantic content, not those transient window properties.
        val text = StringBuilder()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var count = 0
        while (stack.isNotEmpty() && count++ < 4000) {
            val node = stack.removeLast()
            if (!node.isPassword) {
                text.append(node.viewIdResourceName).append(':').append(node.text)
                    .append(':').append(node.contentDescription).append('\n')
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun externalCapture(title: String?, pkg: String, root: AccessibilityNodeInfo?) {
        val token = session.current
        if (!token.isActive() || root == null) return
        var fingerprint = ""
        ocrBusy = true
        var id = ""
        var detach: () -> Unit = {}
        val request = CaptureHandoff.Request(
            isActive = { session.isCurrent(token) },
            readiness = {
                val live = rootInActiveWindow
                val livePkg = live?.packageName?.toString()
                captureReadiness(livePkg, packageName, pkg,
                    live != null && livePkg == pkg && contentFingerprint(live) == fingerprint)
            },
            setHidden = { overlay?.setHiddenForShot(it) },
            onResult = { result ->
                detach()
                if (externalCaptureId == id) externalCaptureId = null
                if (!session.isCurrent(token)) {
                    if (result is ScreenCapture.Result.Ok) result.bitmap.recycle()
                } else when (result) {
                    is ScreenCapture.Result.Failed -> {
                        ocrBusy = false
                        showCaptureFallback(result.humanMessage)
                    }
                    is ScreenCapture.Result.Ok -> acceptCapturedBitmap(result, title, emptyList(), pkg,
                        manual = true, token = token)
                }
            })
        id = CaptureHandoff.register(request)
        externalCaptureId = id
        detach = token.onCancel { CaptureHandoff.cancel(id) }
        overlay?.setHiddenForShot(true)
        // Let the original application become active before opening the consent activity.
        main.postDelayed({
            if (CaptureHandoff.get(id) == null) return@postDelayed
            val live = rootInActiveWindow
            if (live?.packageName?.toString() != pkg) {
                CaptureHandoff.complete(id, ScreenCapture.Result.Failed(-10, "请返回目标聊天后重试"))
                return@postDelayed
            }
            fingerprint = contentFingerprint(live)
            manualContentFingerprint = fingerprint
            runCatching {
                startActivity(Intent(this, CaptureInputActivity::class.java)
                    .putExtra(CaptureInputActivity.EXTRA_REQUEST, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            }.onFailure { CaptureHandoff.complete(id, ScreenCapture.Result.Failed(-10, "无法打开截屏入口，请重试")) }
        }, 220)
    }

    private fun showCaptureFallback(message: String) {
        overlay?.showCaptureFallback(message, onRetry = { ocrCaptureManual() })
    }

    /**
     * What the screen would look like to a camera, as far as the tree can tell.
     *
     * Feishu: the conversation title plus every bubble rectangle and its side —
     * the bubbles move whenever the list scrolls or a message arrives, and stay
     * put when only chrome (caret, presence dot, timestamp) redraws. Apps that
     * give us no rectangles fall back to package + title, which at least stops a
     * burst of events on one screen from becoming a burst of screenshots.
     */
    private fun ocrSignature(pkg: String, title: String?, rects: List<BubbleRect>): String {
        if (rects.isEmpty()) return pkg + "|" + (title ?: "")
        return (title ?: "") + "|" + rects.joinToString(";") { br ->
            val r = br.rect
            "${r.left},${r.top},${r.right},${r.bottom},${br.side}"
        }
    }

    /**
     * Screenshot, then either OCR each known bubble rect (Feishu: the tree knows
     * where the bubbles are and who sent them, just not what they say) or OCR
     * the whole screen (everything else).
     */
    private fun ocrCapture(treeTitle: String?, rects: List<BubbleRect>, pkg: String, manual: Boolean) {
        val token = session.current
        if (ocrBusy || !token.isActive()) return
        ocrBusy = true
        screenCapture.capture { res ->
            if (!session.isCurrent(token)) {
                if (res is ScreenCapture.Result.Ok) runCatching { res.bitmap.recycle() }
                return@capture
            }
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    Log.i(TAG, "ocr: screenshot failed code=${res.code}")
                    // Nothing was read, so the signature must not claim this screen
                    // is done — the next event may retry, still held back by
                    // ScreenCapture's own throttle and failure backoff.
                    if (!manual) lastOcrSignature = ""
                    // Throttle/interval codes are transient timing, not something
                    // the user can act on — nagging about them would be constant.
                    val transient = res.code == ScreenCapture.CODE_THROTTLED || res.code == 3
                    if (manual && res.code != 6 && !transient) showCaptureFallback(res.humanMessage)
                    else if (manual || !transient) overlay?.showError(res.humanMessage)
                }
                is ScreenCapture.Result.Ok -> acceptCapturedBitmap(res, treeTitle, rects, pkg, manual, token)
            }
        }
    }

    private fun acceptCapturedBitmap(res: ScreenCapture.Result.Ok, title: String?, rects: List<BubbleRect>,
                                     pkg: String, manual: Boolean, token: WorkToken, cropChatArea: Boolean = true) {
        if (prefs.ocrEngine == Prefs.OCR_VISION) {
            ocrCloud(res.bitmap, title, pkg, manual, token, cropChatArea)
            return
        }
        ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
        ocr.originX = res.originX; ocr.originY = res.originY
        if (rects.isNotEmpty() && !manual) {
            val fresh = rootInActiveWindow?.let { collectFeishuBubbleRects(it, resources) }
            ocrByRects(res.bitmap, if (fresh.isNullOrEmpty()) rects else fresh, title, pkg, token)
        } else ocrWholeScreen(res.bitmap, title, pkg, manual, token, cropChatArea)
    }

    /** One cropped image per round, rather than a paid request for every bubble. */
    private fun ocrCloud(bmp: Bitmap, title: String?, pkg: String, manual: Boolean, token: WorkToken, cropChatArea: Boolean = true) {
        val top = if (cropChatArea) (bmp.height * TOP_CROP).toInt().coerceIn(0, bmp.height - 1) else 0
        val bottom = if (cropChatArea) (bmp.height * BOTTOM_CROP).toInt().coerceIn(top + 1, bmp.height) else bmp.height
        val crop = Bitmap.createBitmap(bmp, 0, top, bmp.width, bottom - top)
        if (crop !== bmp) runCatching { bmp.recycle() }
        val detachCrop = token.onCancel { runCatching { crop.recycle() } }
        if (!VisionClient.supportsVision(prefs.visionBaseUrl) || prefs.effectiveVisionKey().isBlank()) {
            runCatching { crop.recycle() }
            detachCrop()
            ocrBusy = false
            overlay?.showError("请配置支持图片输入的视觉接口与密钥")
            return
        }
        submit(token) {
            val result = try {
                val encoded = VisionClient.encodeJpeg(crop)
                VisionClient(prefs).extractDialog(encoded)
            } catch (_: Exception) { "" }
            finally { detachCrop(); runCatching { crop.recycle() } }
            main.post {
                if (!session.isCurrent(token)) return@post
                if (result.isBlank()) {
                    ocrBusy = false
                    overlay?.showError("视觉模型未识别出聊天文字，请检查模型或改用本地 OCR")
                    return@post
                }
                val messages = result.lineSequence().mapNotNull { line ->
                    val clean = line.trim().trimStart('-', '•', ' ')
                    when {
                        clean.startsWith("我：") -> Msg("me", clean.removePrefix("我：").trim())
                        clean.startsWith("对方：") -> Msg("other", clean.removePrefix("对方：").trim())
                        else -> null
                    }
                }.filter { it.text.isNotBlank() }.take(60).toList()
                finishOcrSnapshot(ChatSnapshot(title, messages,
                    note = "视觉模型识图；原文和双方身份必须核对，截图已发送至所选服务"), pkg, manual, token)
            }
        }
    }

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(bmp: Bitmap, rects: List<BubbleRect>, title: String?, pkg: String, token: WorkToken) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        // Screen -> bitmap: drop the window origin first. A window shot does not
        // start at (0,0) in split screen or when it excludes the status bar.
        val ox = ocr.originX; val oy = ocr.originY
        val out = arrayOfNulls<Msg>(rects.size)
        var remaining = rects.size
        rects.forEachIndexed { i, br ->
            val region = Rect(
                ((br.rect.left - ox) * sx).toInt(), ((br.rect.top - oy) * sy).toInt(),
                ((br.rect.right - ox) * sx).toInt(), ((br.rect.bottom - oy) * sy).toInt())
            ocr.recognize(bmp, region) { lines ->
                val text = cleanBubbleText(lines.joinToString(" ") { it.text })
                if (text.isNotEmpty()) out[i] = Msg(br.side, text)
                remaining--
                if (remaining == 0) {
                    runCatching { bmp.recycle() }
                    finishOcrSnapshot(ChatSnapshot(title, out.filterNotNull()), pkg, manual = false, token = token)
                }
            }
        }
    }

    /** Whole screen minus the top bar and the input area, grouped by line gaps. */
    private fun ocrWholeScreen(bmp: Bitmap, treeTitle: String?, pkg: String, manual: Boolean, token: WorkToken, cropChatArea: Boolean = true) {
        val region = if (cropChatArea) Rect(0, (bmp.height * TOP_CROP).toInt(), bmp.width, (bmp.height * BOTTOM_CROP).toInt())
            else Rect(0, 0, bmp.width, bmp.height)
        ocr.recognize(bmp, region) { lines ->
            runCatching { bmp.recycle() }
            val msgs = groupOcrLines(lines)
            val title = treeTitle?.takeIf { it.isNotBlank() }
                ?: lines.firstOrNull()?.text?.trim()?.take(24)
            finishOcrSnapshot(ChatSnapshot(title, msgs, note = OCR_NOTE), pkg, manual, token)
        }
    }

    /**
     * OCR lines → "bubbles": a gap larger than 1.2x the previous line's height
     * starts a new one. Side is unknowable from a flat screen read, so every
     * group is filed as the other person (and [OCR_NOTE] says so on the panel).
     */
    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> {
        val usable = lines
            .filter { it.text.isNotBlank() && !PURE_TIME.matches(it.text.trim()) }
            .sortedBy { it.bounds.top }
        val out = ArrayList<Msg>()
        val buf = StringBuilder()
        var prev: OcrLine? = null
        for (l in usable) {
            val p = prev
            if (p != null) {
                val gap = l.bounds.top - p.bounds.bottom
                val lineHeight = maxOf(p.bounds.height(), 1)
                if (gap > lineHeight * 1.2f) {
                    if (buf.isNotEmpty()) { out.add(Msg("other", buf.toString())); buf.setLength(0) }
                }
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l.text.trim())
            prev = l
        }
        if (buf.isNotEmpty()) out.add(Msg("other", buf.toString()))
        return out
    }

    /** Strip the read receipt and the timestamp Feishu glues onto a bubble. */
    private fun cleanBubbleText(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (tail in arrayOf("已读", "未读")) {
                if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true }
            }
            TAIL_TIME.find(t)?.let { t = t.substring(0, it.range.first).trim(); changed = true }
        }
        return t
    }

    /** Shared tail of both OCR paths: dedupe, then analyze or park the bubble. */
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean, token: WorkToken) {
        if (!session.isCurrent(token) || rootInActiveWindow?.packageName?.toString() != pkg) return
        ocrBusy = false
        // Counts only — OCR'd chat text never goes to logcat.
        Log.i(TAG, "ocr[$pkg] msgs=${snapshot.messages.size} manual=$manual")
        if (snapshot.messages.isEmpty()) {
            if (manual) overlay?.showError("这一屏没认出文字")
            return
        }
        if (!prefs.isAllowed(snapshot.title)) { overlay?.hide(); return }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot
        val sig = snapshot.signature()
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!manual && sig == lastSignature) {
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        // Same rule as the tree path: past this point the conversation is either
        // new or being force-refreshed, so drop whatever was shown before.
        overlay?.resetForNewConversation()
        lastSignature = sig

        if (shouldReviewOcr(manual, prefs.ocrAutoAnalyze, snapshot.latestFrom)) reviewSnapshot(snapshot, pkg)
        else overlay?.showIdle(snapshot.title)
    }

    /** Both tree extraction and OCR require a reviewed transcript before model calls. */
    private fun reviewSnapshot(snapshot: ChatSnapshot, pkg: String) {
        if (!snapshotIsCurrent(snapshot, pkg)) return
        val token = session.beginReview() ?: return
        overlay?.showReview(snapshot, onConfirm = { confirmed ->
            if (!session.isCurrent(token) || !session.reviewPending) return@showReview
            overlay?.showLoading()
            completeReviewConfirmation(confirmed, pkg, token)
        }, onCancel = {
            if (session.isCurrent(token)) {
                cancelWork()
                currentSnapshot = snapshot
                activePkg = pkg
                overlay?.showIdle(snapshot.title)
            }
        })
        if (overlay?.isShowing() != true) cancelWork()
    }

    /**
     * Dropping focus from the editable review overlay updates
     * rootInActiveWindow asynchronously. Wait for the original chat window
     * before validating it and starting analysis.
     */
    private fun completeReviewConfirmation(
        confirmed: ChatSnapshot,
        pkg: String,
        token: WorkToken,
        attempt: Int = 0
    ) {
        if (!session.isCurrent(token) || !session.reviewPending) return
        val livePkg = rootInActiveWindow?.packageName?.toString()
        when (reviewConfirmationState(livePkg, packageName, pkg)) {
            ReviewConfirmationState.WAIT_FOR_CHAT_WINDOW -> {
                if (attempt < REVIEW_FOCUS_RETRIES) {
                    main.postDelayed({
                        completeReviewConfirmation(confirmed, pkg, token, attempt + 1)
                    }, REVIEW_FOCUS_RETRY_MS)
                } else {
                    cancelWork()
                    overlay?.showError("无法重新确认聊天窗口，请关闭核对页后重试")
                }
            }
            ReviewConfirmationState.CHAT_CHANGED -> {
                cancelWork()
                overlay?.showError("聊天应用已切换，请重新识别后再分析")
            }
            ReviewConfirmationState.CHAT_CURRENT -> {
                if (!session.confirmReview(token)) return
                overlay?.finishReview()
                currentSnapshot = confirmed
                pendingSnapshot = confirmed
                main.removeCallbacks(debounce)
                runAnalysis(token)
            }
        }
    }

    /** Fill only a verified, still-current chat input box (never sends). */
    private fun fillInput(text: String, snapshot: ChatSnapshot, pkg: String) {
        val token = session.current
        submit(token) {
            val edit = verifiedInput(snapshot, pkg)
            val before = edit?.text?.toString()
            // Existing drafts belong to the user. Never clear or overwrite them.
            if (edit == null || before == null || before.isNotEmpty()) {
                token.checkActive()
                copyToClipboard(text)
                main.post { if (session.isCurrent(token)) overlay?.toast("会话或输入框未确认，或已有草稿；已复制，请手动粘贴") }
                return@submit
            }
            token.checkActive()
            var ok = setTextRaw(edit, text)
            Thread.sleep(150)
            var after = verifiedInput(snapshot, pkg)?.text?.toString()
            ok = ok && after == text
            // A failed SET_TEXT may still have landed. Paste only when a fresh read
            // proves the box is empty; never clear a box to make the fallback work.
            if (!ok && after == "") {
                val focused = verifiedInput(snapshot, pkg)
                if (focused != null && focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Thread.sleep(150)
                    if (verifiedInput(snapshot, pkg)?.text?.toString() == "") {
                        token.checkActive()
                        copyToClipboard(text)
                        focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        Thread.sleep(150)
                        after = verifiedInput(snapshot, pkg)?.text?.toString()
                        ok = after == text
                    }
                }
            }
            Log.i(TAG, "fill: verified=$ok readback=${after?.length ?: -1}")
            main.post {
                if (!session.isCurrent(token)) return@post
                if (ok) overlay?.toast("已填入，确认后自己发送")
                else {
                    copyToClipboard(text)
                    overlay?.toast("无法确认是否填入；请检查输入框，勿重复粘贴")
                }
            }
        }
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Never choose a search box or an editable field in another app. */
    private fun verifiedInput(snapshot: ChatSnapshot, pkg: String): AccessibilityNodeInfo? {
        if (!snapshotIsCurrent(snapshot, pkg)) return null
        val root = rootInActiveWindow ?: return null
        val fresh = adapters[pkg]?.extract(root, resources) ?: return null
        if (fresh.messages.isEmpty() || fresh.title != snapshot.title ||
            fresh.signature() != snapshot.signature()) return null
        return findEditable(root)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        var best: AccessibilityNodeInfo? = null
        var bestBottom = -1
        val bounds = Rect()
        val minY = resources.displayMetrics.heightPixels * 0.35f
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable && node.isVisibleToUser) {
                node.getBoundsInScreen(bounds)
                if (bounds.width() >= 80 && bounds.height() >= 24 &&
                    bounds.centerY() >= minY && bounds.bottom > bestBottom) {
                    best = node
                    bestBottom = bounds.bottom
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return best
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        detachPrefs?.invoke()
        detachPrefs = null
        session.destroy()
        cancelWork()
        // Tear the overlay down and cut its callback so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.onSaveContact = null
        overlay?.onOcrCapture = null
        overlay?.onHidden = null
        overlay?.hide()
        overlay = null
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "JEVASSIST"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val REVIEW_FOCUS_RETRIES = 20
        private const val REVIEW_FOCUS_RETRY_MS = 50L

        /** Whole-screen OCR keeps the middle: no action bar, no input area. */
        private const val TOP_CROP = 0.12f
        private const val BOTTOM_CROP = 0.84f

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "OCR 未分边，把全部消息当作对方所说"

        private val PURE_TIME = Regex("""\d{1,2}[:：]\d{2}""")
        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )
    }
}
