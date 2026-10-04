package com.bt.waf.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bt.waf.data.repo.WafRepository
import com.bt.waf.ui.model.ListConfig

class WafViewModelFactory(
    private val repository: WafRepository,
    private val config: ListConfig? = null
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            config != null && modelClass.isAssignableFrom(ManagedListViewModel::class.java) ->
                ManagedListViewModel(repository, config) as T
            modelClass.isAssignableFrom(AuthViewModel::class.java) ->
                AuthViewModel(repository) as T
            modelClass.isAssignableFrom(DashboardViewModel::class.java) ->
                DashboardViewModel(repository) as T
            modelClass.isAssignableFrom(SitesViewModel::class.java) ->
                SitesViewModel(repository) as T
            modelClass.isAssignableFrom(AttackLogsViewModel::class.java) ->
                AttackLogsViewModel(repository) as T
            modelClass.isAssignableFrom(RulesViewModel::class.java) ->
                RulesViewModel(repository) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(repository) as T
            modelClass.isAssignableFrom(ProfileViewModel::class.java) ->
                ProfileViewModel(repository) as T
            modelClass.isAssignableFrom(NotificationsViewModel::class.java) ->
                NotificationsViewModel(repository) as T
            modelClass.isAssignableFrom(ReportViewModel::class.java) ->
                ReportViewModel(repository) as T
            modelClass.isAssignableFrom(CertViewModel::class.java) ->
                CertViewModel(repository) as T
            modelClass.isAssignableFrom(SysInfoViewModel::class.java) ->
                SysInfoViewModel(repository) as T
            modelClass.isAssignableFrom(IpGroupViewModel::class.java) ->
                IpGroupViewModel(repository) as T
            modelClass.isAssignableFrom(CustomizeViewModel::class.java) ->
                CustomizeViewModel(repository) as T
            modelClass.isAssignableFrom(ReplaceViewModel::class.java) ->
                ReplaceViewModel(repository) as T
            modelClass.isAssignableFrom(WafMasterViewModel::class.java) ->
                WafMasterViewModel(repository) as T
            modelClass.isAssignableFrom(NginxViewModel::class.java) ->
                NginxViewModel(repository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
        }
    }
}
