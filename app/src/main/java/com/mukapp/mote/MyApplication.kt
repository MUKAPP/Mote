package com.mukapp.mote

import android.app.Application

import com.mukapp.mote.tools.BusyBoxManager
import com.mukapp.mote.util.MoteLog
import io.ratex.RaTeXFontLoader

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MoteLog.i("App", "应用启动")

        Thread(
            {
                MoteLog.i("App", "开始后台初始化 BusyBox")
                runCatching { BusyBoxManager.initialize(this) }
                    .onSuccess { MoteLog.i("App", "BusyBox 后台初始化结束") }
                    .onFailure { error -> MoteLog.w("App", "BusyBox 后台初始化异常", error) }
            },
            "BusyBoxInit"
        ).start()

        Thread(
            {
                MoteLog.i("App", "开始后台初始化 RaTeX 字体")
                runCatching { RaTeXFontLoader.ensureLoaded(this) }
                    .onSuccess { count -> MoteLog.i("App", "RaTeX 字体后台初始化结束：$count 个字体") }
                    .onFailure { error -> MoteLog.w("App", "RaTeX 字体后台初始化异常", error) }
            },
            "RaTeXFontInit"
        ).start()
    }
}
