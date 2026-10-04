# 堡塔云 WAF 安卓管理端（BTWAF）

堡塔云WAF（[aaPanel/aawaf](https://github.com/aaPanel/aawaf)）的原生 Android 客户端，使用 Kotlin + Jetpack Compose 编写，
通过完整会话接口（后台 Cookie + JWT）而非有限的开放 API 接入，功能覆盖后台绝大部分模块。

## 一、技术栈

| 项 | 版本 / 选型 |
|---|---|
| 语言 | Kotlin 1.9.24 |
| UI | Jetpack Compose（Material3）+ Navigation Compose |
| 网络 | Retrofit 2.11 + OkHttp 4.12 + Gson Converter |
| 存储 | SharedPreferences（连接信息与 JWT） |
| AGP / Gradle | 8.5.2 / 8.7 |
| compileSdk / minSdk | 34 / 24 |

## 二、鉴权流程（重要）

后台并非标准 REST API，直接 POST `user/login` 会被安全入口中间件拦下。正确顺序是：

```
1) GET  https://<host>:<port>/<入口路径>          # 建立 SID 会话 Cookie
2) POST https://<host>:<port>/api/user/login      # {username, password} -> res.token(JWT)
3) POST https://<host>:<port>/api/<module>/<action>
   Header: Authorization: bt<token>               # 前缀 "bt"，无空格
   Cookie: 步骤 1 得到的会话 Cookie
```

> **注意**：`/api/*` 挂在**站点根路径**下，不在入口路径之下。`/<入口路径>/api/...` 会 404。

统一响应包：`{ "code": 0, "res": <data>, "nonce": <ts> }`，`code == 0` 表示成功；失败时 `res` 通常是错误描述字符串。

## 三、界面与模块对应关系

| 界面 | 主要接口 |
|---|---|
| 登录 | `user/login`、`user/logout` |
| 概览 | `overview/count_info`、`overview/infos`、`overview/attack_map`、`sys/get_system_info` |
| 防护网站 | `wafmastersite/get_site_list`、`create_site`、`modify_site`、`delete_site`、`get_site_log`、`get_all_ssl` |
| 拦截记录 | `report/attack_report_log`、`report/attack_report_log_info`、`report/cc_list` |
| IP / URL / UA 黑白名单 | `limit/get_ip`、`get_url`、`get_ua`、`set_*`、`del_data`、`off_*` |
| 操作日志 | `opt_log/list` |
| 自定义规则 | `customize/list`、`customize/remove` |
| 访问控制 | `speed/get_list`、`speed/open_rule_status` |
| 人机验证 | `manm/get_list`、`manm/del_rules`、`manm/off_authtype` |
| 区域拦截 | `wafrules/get_regional_restrictions_rules`、`del_*`、`set_*` |
| 防护规则 | `wafrules/get_global_rules`、`apply_all`、`set_cc_rules`、`set_site_run_mode`、`set_malicious_ip`、`recover_default_config`、`update_spider_rules` |
| 通知 | `notification/list`、`notification/update`、`notification/clear`（邮箱 / 钉钉 / 飞书 / 企业微信） |
| 系统设置 | `config/get_config` 及 `set_title` / `set_domain` / `set_ip` / `set_language` / `set_timeout` / `set_port` / `set_admin_path` / `set_basic_auth` / `set_open_api` / `set_pwd` |
| 账号与运维 | `user/profile`、`user/update_profile`、`user/restart`、`user/update` |

接口全量清单（315 个，由源码结构体方法自动提取）见 [`docs/API.md`](docs/API.md)。

## 四、构建

依赖 JDK 17 与 Android SDK 34。若本机已装在 `~/.workbuddy/binaries/android/`，可直接用 wrapper 构建：

```bash
export JAVA_HOME=~/.workbuddy/binaries/android/jdk17tmp/jdk-17.0.20.1+1
export ANDROID_HOME=~/.workbuddy/binaries/android/sdk
./gradlew assembleDebug
```

产物：

- 调试包 `app/build/outputs/apk/debug/app-debug.apk`
- 正式包 `app/build/outputs/apk/release/app-release.apk`

签名（可选）：复制 `keystore.properties.example` 为 `keystore.properties` 并填入自有证书信息；不配置时 release 会自动退回 debug 签名。

用 Android Studio 直接打开亦可，需在 `local.properties` 里写明 `sdk.dir`。

## 五、使用说明

1. 首次打开在登录页填入「服务器地址」，形如 `https://81.70.19.168:8379/d75e7a5401`（**含入口路径**）。
2. 若后台使用自签名证书，勾选「信任自签名证书」。
3. 登录成功后会话保存在本地，下次自动进入；退出登录会清除 token。

> 安全提示：「信任自签名证书」会跳过 TLS 校验，仅建议在测试环境开启。

## 六、已实现的非显然处

- **ListConfig 驱动**：IP/URL/UA 名单、操作日志、自定义规则、限速、人机验证、区域拦截共用一个屏幕和 ViewModel，
  由配置项描述列表路径、新增字段、删除参数形态（单值 / 数组）、开关动作（0/1 或 bool），避免重复代码。
- **开关字段兼容**：后端对开关状态混用布尔、数字、字符串三种写法，读取时统一判定。
- **站点 ID 容错**：不同版本列表返回里站点标识字段可能是 `site_id` / `id` / `name`，按顺序取值。
- **响应形态容错**：列表数据有的在 `res.list`、有的直接是顶层数组，均做兼容。
