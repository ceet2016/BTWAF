package com.bt.waf

import android.app.Application
import com.bt.waf.data.net.WafClient
import com.bt.waf.data.repo.WafRepository
import com.bt.waf.data.store.AuthStore
import com.bt.waf.data.update.AppUpdater

class WafApplication : Application() {

    lateinit var authStore: AuthStore
        private set
    lateinit var wafClient: WafClient
        private set
    lateinit var repository: WafRepository
        private set

    override fun onCreate() {
        super.onCreate()
        authStore = AuthStore(this)
        wafClient = WafClient(authStore)
        repository = WafRepository(authStore, wafClient)
        // 在线更新需要读包信息拿当前版本号，必须尽早拿到 Context。
        AppUpdater.attach(this)
        // 上次下载多半是中途断链留下的半截包，先清掉再重新开始。
        // 不清的话下次续传会在半截文件上继续，浪费流量还可能拼出坏包。
        AppUpdater.cleanup()
    }
}
