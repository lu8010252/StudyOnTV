package com.studyontv.tv

import android.content.Context

object DeviceRuntime {
    private var server: LanServer? = null
    lateinit var store: LocalStore
        private set
    lateinit var appContext: Context
        private set
    lateinit var learning: LearningEngine
        private set

    @Synchronized
    fun ensure(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        appContext = app
        Diag.installCrashHandler()
        CrashDetector.install(app)   // 在 Diag handler 之后安装，确保我们的 handler 在最外层
        BuglyInit.init(app)          // 用户已同意过则立即初始化，否则 no-op
        Diag.info("App", "start version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) sdk=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MODEL}")
        (app as android.app.Application).registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                Foreground.resumeOwn(activity)
                GateService.onOwnActivityResumed()
            }
            override fun onActivityPaused(activity: android.app.Activity) { Foreground.pauseOwn(activity) }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        store = LocalStore(app)
        SeedAssets.ensureQuestions(app, store)
        learning = LearningEngine(store)
        server = LanServer(app, store, app.assets).also {
            it.start()
            Prefs.setLocalPort(app, it.boundPort)
        }
        WebShell.PrefsHolder.baseUrl = Prefs.localBase(app)
        // 积分同步：断网期间攒下的账本定时补传，同时刷新星星屋里的最新余额（家长加扣分、兑换后这里也会变）
        Thread {
            while (true) {
                try { Thread.sleep(120_000) } catch (e: InterruptedException) { break }
                try { PointsSync.syncNow(store) } catch (e: Exception) { }
            }
        }.apply { isDaemon = true; name = "points-sync" }.start()
        if (GradingService.settings(store).optString("mode") == "ai") {
            val pending = store.reviews("pending")
            for (i in 0 until pending.length()) GradingService.enqueue(store, learning, pending.getJSONObject(i).getString("id"))
        }
    }

    fun connection(context: Context): org.json.JSONObject {
        ensure(context)
        return org.json.JSONObject().put("ip", server?.localIp() ?: "")
            .put("port", Prefs.localPort(context)).put("token", store.adminToken())
    }
}
