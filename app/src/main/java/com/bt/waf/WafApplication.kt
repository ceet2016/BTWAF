package com.bt.waf

import android.app.Application
import com.bt.waf.data.net.WafClient
import com.bt.waf.data.repo.WafRepository
import com.bt.waf.data.store.AuthStore

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
    }
}
