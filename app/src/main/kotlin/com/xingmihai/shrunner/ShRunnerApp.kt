package com.xingmihai.shrunner

import android.app.Application
import com.xingmihai.shrunner.engine.ShellEnv

/** 应用入口：注入 Context 给 ShellEnv，用于定位可写暂存目录。 */
class ShRunnerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShellEnv.init(this)
    }
}
