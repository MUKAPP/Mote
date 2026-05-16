package com.mukapp.mote

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.mukapp.mote.tools.BusyBoxManager
import com.mukapp.mote.util.MoteLog

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MoteLog.i("App", "应用启动")
        DynamicColors.applyToActivitiesIfAvailable(this)
        MoteLog.d("App", "已请求应用动态主题色")
        Thread(
            {
                MoteLog.i("App", "开始后台初始化 BusyBox")
                runCatching { BusyBoxManager.initialize(this) }
                    .onSuccess { MoteLog.i("App", "BusyBox 后台初始化结束") }
                    .onFailure { error -> MoteLog.w("App", "BusyBox 后台初始化异常", error) }
            },
            "BusyBoxInit"
        ).start()
    }
}
