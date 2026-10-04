package com.bt.waf.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.DataRow
import com.bt.waf.ui.components.ErrorText
import com.bt.waf.ui.components.SectionTitle
import com.bt.waf.util.asMap
import com.bt.waf.util.asList
import com.bt.waf.util.l
import com.bt.waf.util.s
import com.bt.waf.util.asString
import com.bt.waf.viewmodel.DashboardViewModel
import com.bt.waf.viewmodel.WafViewModelFactory

private fun fmtBytes(s: String): String {
    val b = s.toLongOrNull() ?: return s
    val mb = b / 1024 / 1024.0
    return if (mb >= 1) "%.2f MB".format(mb) else "$b B"
}

@Composable
fun DashboardScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: DashboardViewModel = viewModel(factory = WafViewModelFactory(repo))
    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorText(vm.error)

        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                SectionTitle("实时统计")
                val count = vm.countInfo["count"].asMap()
                DataRow("总请求", count.s("request_total"))
                DataRow("QPS", count.s("qps"))
                DataRow("接收流量", fmtBytes(count.s("recv_bytes")))
                DataRow("发送流量", fmtBytes(count.s("send_bytes")))
                DataRow("恶意请求", count.s("malicious_total"))
                DataRow("代理时长", count.s("proxy_time"))
            }
        }

        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                SectionTitle("系统信息")
                val host = vm.sysInfo["host"].asMap()
                val cpu = vm.sysInfo["cpu"].asMap()
                val mem = vm.sysInfo["mem"].asMap()
                DataRow("主机名", host.s("host_name"))
                DataRow("系统", "${host.s("platform")} ${host.s("platform_version")}")
                DataRow("CPU", "${cpu.s("model_name")} (${cpu.s("percent")}%)")
                DataRow("内存", "${fmtBytes(mem.s("free"))} / ${fmtBytes(mem.s("total"))}")
                DataRow("运行", "${(host.l("up_time")).toInt().let { "${it / 3600}h${(it % 3600) / 60}m" }}")
            }
        }

        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                SectionTitle("攻击态势")
                val map = vm.attackMap["map"].asList()
                val top = vm.attackMap["top"].asList()
                DataRow("攻击来源(条)", map.size.toString())
                DataRow("Top 排名(条)", top.size.toString())
                vm.infos["intercept"].asList().let {
                    if (it.isNotEmpty()) DataRow("拦截记录(条)", it.size.toString())
                }
                vm.infos["request"].asList().let {
                    if (it.isNotEmpty()) DataRow("请求记录(条)", it.size.toString())
                }
            }
        }
    }
}
