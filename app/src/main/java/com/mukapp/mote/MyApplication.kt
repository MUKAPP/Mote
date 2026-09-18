package com.mukapp.mote

import android.app.Application

import com.mukapp.mote.data.ApiSettingsStore
import com.mukapp.mote.tools.BusyBoxManager
import com.mukapp.mote.util.MoteLog
import io.ratex.RaTeXFontLoader

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MoteLog.i("App", "应用启动")

        // ChatViewModel 在构造函数里同步读取设置；预热线程提前完成首次磁盘 I/O + JSON 解析 +
        // Keystore 解密并填充 ApiSettingsStore 内存缓存，之后主线程读取即为缓存命中。
        // SharedPreferences 内部自带同步，即使 ViewModel 抢先构造也只是等锁，不会读到半份数据。
        Thread(
            {
                runCatching { ApiSettingsStore.load(this) }
                    .onSuccess { MoteLog.i("App", "API 设置预热完成") }
                    .onFailure { error -> MoteLog.w("App", "API 设置预热异常", error) }
            },
            "ApiSettingsWarmup"
        ).start()

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
