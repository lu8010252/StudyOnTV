package com.studyontv.tv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class WatchAccessibilityService : AccessibilityService() {
    private var gate: GateOverlay? = null
    private val navigationKeys = NavigationKeyFilter { LearningNavigation.shouldBlockHome() }
    /** 当前的按键过滤状态；只在需要拦 Home / 任务键时打开（#162） */
    private var keyFilterOn = false
    private var keyFilterApplied = false

    /**
     * 打开 / 关闭系统级按键过滤。
     *
     * 常驻 `FLAG_REQUEST_FILTER_KEY_EVENTS` 会让所有按键（包括音量键）改走无障碍通路：
     * 海信 VIDAA 上厂商音量面板因此失效，SystemUI 退回 AOSP 白卡片（#162）。
     * 这里仅在「学习页正在拦 Home」时申请，其它时候保持关闭。
     */
    fun setKeyFilter(enabled: Boolean) {
        if (keyFilterApplied && enabled == keyFilterOn) return
        val info = try { serviceInfo } catch (_: Exception) { null } ?: return
        val flag = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.flags = if (enabled) info.flags or flag else info.flags and flag.inv()
        try {
            setServiceInfo(info)
            keyFilterOn = enabled
            keyFilterApplied = true
            Diag.info("A11y", if (enabled) "按键过滤已打开（学习页需要拦 Home / 任务键）" else "按键过滤已关闭")
        } catch (e: Exception) {
            Diag.warn("A11y", "切换按键过滤失败 ${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
        }
    }

    public override fun onKeyEvent(event: KeyEvent): Boolean {
        val consumed = navigationKeys.handle(event)
        if (consumed && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_HOME -> blockedHomePresses++
                KeyEvent.KEYCODE_APP_SWITCH -> blockedAppSwitchPresses++
            }
            LearningNavigation.notifyBlocked(DeviceRuntime.appContext)
        }
        return consumed
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && (pkg.contains("inputmethod") || pkg.contains("keyboard") || Foreground.isInputMethod(this, pkg))) return
            // 系统栏、音量条、投影自带的悬浮菜单都会弹出临时窗口，真正的前台应用并没有变，不能当成切换
            if (pkg == "com.android.systemui") return
            if (pkg != null && pkg.contains("packageinstaller", ignoreCase = true)) {
                val cls = event.className?.toString().orEmpty()
                val eventTexts = event.text?.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }.orEmpty()
                val labels = selfLabels()
                val matched = isSelfUninstallPrompt(cls, eventTexts, labels) ||
                    checkUninstallWithNode(cls, eventTexts, labels, try { event.source } catch (_: Exception) { null }) ||
                    checkUninstallWithNode(cls, eventTexts, labels, try { rootInActiveWindow } catch (_: Exception) { null })
                if (matched && SystemSettingsGuard.onPotentialTamper(this)) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return
                }
            }
            val rawCls = event.className?.toString()
            if (shouldIgnoreOverlayWindow(pkg, rawCls, SystemSettingsGuard.isVendorSecurityCenter(pkg))) return
            // countdown / gate 悬浮窗不代表应用 Activity 在前台（#96）。
            val ownOverlay = pkg == packageName && !Foreground.hasResumedActivity()
            if (!ownOverlay && (!GateService.hasGate() || pkg != packageName)) {
                val cls = rawCls?.takeIf { it.contains("Activity") }
                Foreground.update(pkg, cls)
                SystemSettingsGuard.onExternalWindowChanged(this, pkg)
            }
            // 无障碍服务由系统常驻，顺便充当看门狗：管理服务被系统清理后在下一次切换应用时拉起
            if (!GateService.running) GateService.ensureRunning(this, "accessibility")
            if (!ownOverlay) GateService.onForegroundChanged(this, pkg)
        }
    }
    override fun onInterrupt() = Unit

    /**
     * 被解绑（用户/系统关掉无障碍、或服务自身出问题被系统摘掉）时先走这里，
     * 留下一条记录，#120 这类「无障碍自己关闭」才能分清是解绑还是进程被杀（进程被杀不会走到任何回调）。
     */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        navigationKeys.reset()
        Diag.warn("A11y", "service unbound action=${intent?.action}")
        return super.onUnbind(intent)
    }
    override fun onServiceConnected() {
        instance = this
        connected = true
        GateService.note("无障碍服务已连接")
        Diag.info("A11y", "service connected")
        val overlayType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        gate = GateOverlay(this, overlayType) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        GateService.ensureRunning(this, "accessibility")
        // 连接时按当前状态定一次：升级前 XML 里写死的按键过滤要在这里清掉（#162）
        keyFilterApplied = false
        setKeyFilter(LearningNavigation.shouldBlockHome())
    }
    override fun onDestroy() {
        navigationKeys.reset()
        Diag.info("A11y", "service destroyed connected=$connected")
        gate?.destroy(); gate = null
        instance = null
        connected = false
        GateService.note("无障碍服务已断开")
        Foreground.clearExternal()
        super.onDestroy()
    }

    private var cachedSelfLabels: Set<String>? = null

    private fun selfLabels(): Set<String> {
        cachedSelfLabels?.let { return it }
        val appLabel = try { applicationInfo.loadLabel(packageManager)?.toString()?.trim() } catch (_: Exception) { null }
        val labels = buildSet {
            addAll(DEFAULT_SELF_LABELS)
            if (!appLabel.isNullOrEmpty()) add(appLabel)
        }
        cachedSelfLabels = labels
        return labels
    }

    private fun checkUninstallWithNode(
        cls: String,
        baseTexts: List<String>,
        labels: Set<String>,
        root: android.view.accessibility.AccessibilityNodeInfo?
    ): Boolean {
        if (root == null) return false
        val merged = ArrayList<String>(baseTexts.size + 16)
        merged.addAll(baseTexts)
        collectNodeTexts(root, merged)
        return isSelfUninstallPrompt(cls, merged, labels)
    }

    /** 有界广度优先收集窗口节点文本与内容描述，防止深层树阻塞回调并兼容 API < 34 节点回收 */
    @Suppress("DEPRECATION")
    private fun collectNodeTexts(
        root: android.view.accessibility.AccessibilityNodeInfo,
        out: MutableList<String>,
        maxNodes: Int = 32,
        maxDepth: Int = 6
    ) {
        val queue = ArrayDeque<Pair<android.view.accessibility.AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = queue.removeFirst()
            visited++
            try {
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                if (depth < maxDepth && visited < maxNodes) {
                    val count = try { node.childCount } catch (_: Exception) { 0 }
                    for (i in 0 until count) {
                        if (visited + queue.size >= maxNodes) break
                        val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                        queue.addLast(child to (depth + 1))
                    }
                }
            } finally {
                if (android.os.Build.VERSION.SDK_INT < 34) {
                    try { node.recycle() } catch (_: Exception) { }
                }
            }
        }
        while (queue.isNotEmpty()) {
            val (node, _) = queue.removeFirst()
            if (android.os.Build.VERSION.SDK_INT < 34) {
                try { node.recycle() } catch (_: Exception) { }
            }
        }
    }

    companion object {
        private val DEFAULT_SELF_LABELS = setOf("题了个题", "StudyOnTV")

        /**
         * 过滤非前台应用的浮层/吐司窗口（ST-001 / GitHub #187）：
         * - Toast / PopupWindow / 输入法窗口不代表前台应用切换；
         * - 国产安全中心（如 com.miui.securitycenter）常在微信等正常应用上方弹出隐私提示条、
         *   剪贴板横幅、游戏加速等非 Activity 浮层，若不拦截会污染前台并触发 reason=settings 循环锁屏；
         *   真正进入安全中心/应用管理/权限设置时其 className 含 Activity 或 Dialog。
         */
        internal fun shouldIgnoreOverlayWindow(
            pkg: String?,
            className: String?,
            isVendorSecurityCenter: Boolean
        ): Boolean {
            if (pkg.isNullOrEmpty()) return true
            val cls = className.orEmpty()
            if (cls.startsWith("android.inputmethodservice.") ||
                cls.endsWith("Toast", ignoreCase = true) ||
                cls.contains("PopupWindow", ignoreCase = true) ||
                cls.contains("PopupDecorView", ignoreCase = true)
            ) {
                return true
            }
            if (isVendorSecurityCenter) {
                if (cls.isBlank()) return true
                if (cls.startsWith("android.widget.") || cls.startsWith("android.view.")) return true
                val isRealPage = cls.contains("Activity", ignoreCase = true) ||
                    cls.contains("Dialog", ignoreCase = true)
                if (!isRealPage) return true
            }
            return false
        }

        /**
         * 判断 packageinstaller 窗口是否正在卸载本应用（ST-106）：
         * 同时兼容中文/英文卸载提示、通用 AlertDialog 类名以及本应用本地化名称。
         */
        internal fun isSelfUninstallPrompt(
            className: String,
            texts: Collection<String>,
            selfLabels: Set<String> = DEFAULT_SELF_LABELS
        ): Boolean {
            val effectiveLabels = buildSet {
                addAll(DEFAULT_SELF_LABELS)
                selfLabels.forEach { if (it.isNotBlank()) add(it.trim()) }
            }
            val mentionsSelf = texts.any { t ->
                effectiveLabels.any { label -> t.contains(label, ignoreCase = true) }
            }
            if (!mentionsSelf) return false
            return className.contains("Uninstall", ignoreCase = true) ||
                className.contains("Delete", ignoreCase = true) ||
                texts.any { it.contains("卸载") || it.contains("uninstall", ignoreCase = true) }
        }
        @Volatile var blockedHomePresses = 0L
            private set
        @Volatile var blockedAppSwitchPresses = 0L
            private set
        @Volatile private var instance: WatchAccessibilityService? = null
        @Volatile var connected = false
            private set
        fun enabled(context: Context): Boolean = try {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        } catch (_: Exception) { false }
        fun hasGate(): Boolean = instance?.gate?.isShowing == true
        fun presentedGate(now: Long = android.os.SystemClock.elapsedRealtime()): Boolean = instance?.gate?.presenting(now) == true
        fun lastGateError(): String = instance?.gate?.let { g ->
            g.lastError.ifEmpty { "accessibility gate attached but not visible kind=${g.lastErrorKind}" }
        } ?: ""
        fun showNavigationBlocked(): Boolean = instance?.gate?.showNavigationBlocked() == true
        /** 只在需要拦 Home / 任务键时申请按键过滤（#162），由 GateService 每个 tick 同步 */
        fun setHomeFilter(enabled: Boolean) { instance?.setKeyFilter(enabled) }
        fun keyFilterRequested(): Boolean = connected && ((instance?.serviceInfo?.flags ?: 0) and
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
        fun showGate(reason: String): Boolean = instance?.gate?.show(reason) == true
        fun hideGate() { instance?.gate?.hide() }
        fun suspendGate() { instance?.gate?.suspend() }
        internal const val WINDOW_TYPE_APPLICATION = 1

        /**
         * 校验 rootInActiveWindow 读到的窗口是否可作为前台应用（ST-109）：
         * - 排除自身、临时窗口、输入法及非 TYPE_APPLICATION 的系统浮层窗口；
         * - 国产安全中心必须明确为 TYPE_APPLICATION，防止隐私横幅/安全浮层穿透污染前台。
         */
        internal fun isAcceptableActiveWindow(
            pkg: String?,
            selfPackage: String,
            isTransient: Boolean,
            isInputMethod: Boolean,
            isVendorSecurityCenter: Boolean,
            windowType: Int?
        ): Boolean {
            if (pkg.isNullOrEmpty() || pkg == selfPackage || isTransient || isInputMethod) return false
            if (windowType != null && windowType != WINDOW_TYPE_APPLICATION) return false
            if (isVendorSecurityCenter && windowType != WINDOW_TYPE_APPLICATION) return false
            return true
        }

        /** 实时读取当前活动窗口包名：同一应用停留超过 30 分钟无切窗事件时用于续命，避免白名单应用误掉回 pkg=null */
        @Suppress("DEPRECATION")
        fun activeWindowPackage(context: Context): String? {
            val svc = instance ?: return null
            val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return null
            var pkg: String? = null
            var windowType: Int? = null
            try {
                pkg = root.packageName?.toString()
                windowType = try {
                    val win = root.window
                    if (win != null) {
                        val type = win.type
                        if (android.os.Build.VERSION.SDK_INT < 34) {
                            try { win.recycle() } catch (_: Exception) { }
                        }
                        type
                    } else null
                } catch (_: Exception) { null }
            } finally {
                if (android.os.Build.VERSION.SDK_INT < 34) {
                    try { root.recycle() } catch (_: Exception) { }
                }
            }
            if (!isAcceptableActiveWindow(
                    pkg = pkg,
                    selfPackage = context.packageName,
                    isTransient = Foreground.isTransient(pkg),
                    isInputMethod = Foreground.isInputMethod(context, pkg),
                    isVendorSecurityCenter = SystemSettingsGuard.isVendorSecurityCenter(pkg),
                    windowType = windowType
                )
            ) return null
            return pkg
        }
    }
}
