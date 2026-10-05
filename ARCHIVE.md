# BTWAF 项目归档

> 归档时间：2026-10-05 23:15 (GMT+8)
> 状态：**已完成并交付**，最新版本 **v1.2.1 (versionCode 4)**
> 仓库：<https://cnb.cool/unicom/BTWAF>

---

## 一、交付物清单

### 可安装包

| 渠道 | 地址 | 说明 |
|---|---|---|
| **Release 页** | <https://cnb.cool/unicom/BTWAF/-/releases> | 人工下载，最新 v1.2.1，标记「最新版本」 |
| Release 直链 | `https://cnb.cool/unicom/BTWAF/-/releases/download/v1.2.1/btwaf-latest.apk` | 匿名可下，12,420,917 字节 |
| 历史版本 | 同上，替换 tag 名（`v1.2.0` 等） | 长期存档 |
| App 内更新 | 关于作者页 →「下载安装包」 | 跳 Release 页 |
| App 在线更新 | 关于作者页 →「重新检查」 | 查 commit 附件，按需下载 |

> ⚠️ **每次发版必须同时确认 Release 的 tag 名与最新版本号一致**。
> 归档时发现过Release 停在 `v1.2.0`（versionCode 3）而 commit 附件已是
> `v1.2.1`（versionCode 4）—— 人工下载渠道会拿到旧版。已补打 `v1.2.1` tag 修正。

**签名指纹（SHA256）**
```
88:6C:B6:47:49:E4:2D:70:6B:61:8F:0F:DD:BF:8E:EB:EC:15:DA:3A:26:05:B9:B7:91:EC:33:F5:E2:81:22:0B
```
仓库内置 `keystore/release.jks`，alias `btwaf`。**debug 与 release 用同一把密钥**——
否则开发者平时装的 debug 包装不上 CI 产出的更新包，在线更新在本地根本无法验证。

### 代码规模

| 项 | 数量 |
|---|---|
| 提交 | 20（+ 本归档提交） |
| Kotlin 文件 | 53（27,215 行） |
| 资源文件 | 26 |
| 辅助脚本 | 9（`tools/`） |
| tag | `v1.2.0`、`v1.2.1` |

---

## 二、技术栈与架构

| 项 | 版本 / 选型 |
|---|---|
| 语言 | Kotlin 1.9.24 |
| UI | Jetpack Compose（Material3）+ Navigation Compose |
| 网络 | Retrofit 2.11 + OkHttp 4.12 + Gson |
| 存储 | SharedPreferences |
| AGP / Gradle | 8.5.2 / 8.7 |
| compileSdk / minSdk | 34 / 24 |

### 模块结构

```
app/src/main/java/com/bt/waf/
├── data/
│   ├── model/WafResponse.kt        统一响应解析
│   ├── net/WafApi.kt接口定义
│   ├── net/WafClient.kt            OkHttp/Retrofit 装配
│   ├── repo/WafRepository.kt       业务分发
│   ├── store/AuthStore.kt          连接信息 + JWT 持久化
│   └── update/AppUpdater.kt        在线更新（查版本/断点续传/SHA256）
├── ui/
│   ├── components/                 WafCard / PrimaryButton / StatusChip…
│   ├── screens/                    20 个 Screen 文件
│   ├── theme/                      设计系统
│   └── model/
├── viewmodel/
└── util/
```

---

## 三、功能覆盖

### 已实现模块（对应 aawaf 后台）

| 模块 | 接口数| 页面数 |
|---|---|---|
| 概览（attack overview） | 实时代理 | 1 |
| 攻击日志 | 全部子页实时刷新 | 8 |
| 防护网站 | 站点开关真实生效 | 多 |
| 防护规则（wafrules） | 41 | 5 |
| 自定义规则编辑器 | 全部 | 1 |
| 黑白名单 | 全部 | 2 |
| 区域拦截 | 全部 | 1 |
| 防护规则组 | 全部 | 1 |
| 内容替换 | 全部 | 1 |
| HTTP 流量录制 | 全部 | 1 |
| 请求头规则 | 全部 | 2 |
| 报头序列表 | 全部 | 1 |
| IP 分组 / 白名单 / 黑名单 | 全部 | 3 |
| URL 黑白名单 / UA黑白名单 | 全部 | 2 |
| 访问 IP 池 | 全部 | 1 |
| 人机验证 | 全部 | 1 |
| SSL 证书 / 证书代理 | 全部 | 2 |
| DNS集群 / 负载均衡 | 全部 | 2 |
| 集群节点 | 全部 | 1 |
| 用户管理 / 角色权限 / 通知设置 | 全部 | 3 |
| 系统设置 / 系统信息 / 账号信息 | 全部 | 3 |
| 关于作者（含在线更新） | — | 1 |

全量遍历自检（`tools/walk_pages.sh`）：**23 张截图 0 失败 0 重复**，
顶部空白 20~61px 全部通过，0 处不对称。

> 口径说明：源码是 **20 个 Screen 文件**；23 是遍历脚本实际产出的截图张数
> （部分 Screen 含多个 Tab，算上后共23 个可达页面）。两个数字含义不同，别混用。

---

## 四、鉴权流程（关键）

aawaf 不是标准 REST，三步走：

```
1. GET  <后台入口>              建立 SID 会话 Cookie
2. POST /api/user/login         → {code:0, res:{prefix:"bt", token, ttl:7200}}
3. 后续所有 /api/* 带Authorization: bt<token>
```

- 路由约定：`/api/<module>/<action>`，module 由 Go 结构体名 SnakeCase 化，action 由导出方法名 SnakeCase 化
- **无 SID Cookie 时 aawaf 返回 404 而非 401** —— 所以「登录失败」常表现为 404
- Cookie 被 WebView `reconfigure` 清空会导致静默掉登录（隐蔽）
- 支持图形验证码

---

## 五、CI/CD

### 两条流水线

| 触发 | 产物 | 用途 | 构建耗时 |
|---|---|---|---|
| push `main` | commit 附件 | App 在线更新（匿名查版本） | 127~441 秒 |
| push tag | Release 附件 | 人工下载 | 194~230 秒 |

镜像：`docker.cnb.cool/jacknic/docker/android-ci-builder:jdk17`
附件插件：`cnbcool/attachments:latest`

### 发新版流程

```bash
# 1. 改 app/build.gradle 的 versionCode / versionName（单一来源）
# 2. 提交推送，等 main 流水线产出 commit 附件（App 可提示更新）
git push origin main
# 3. 打 tag 产出 Release（人工下载渠道）
git tag -a v1.3.0 -m "v1.3.0"
git push origin v1.3.0
```

> **必须等流水线结束再对外说「已产出」**。push 后的 2~8 分钟内附件链接
> 一律404，此时状态是 `pending` 而非 `error` —— 极易误判为构建失败。

### 查构建状态

```bash
cnb build get-build-logs  --repo unicom/BTWAF --page-size 5
cnb build get-build-status --repo unicom/BTWAF --sn <构建号>
cnb build get-build-stage --repo unicom/BTWAF --sn <构建号> \
  --pipelineId <构建号>-001 --stageId stage-0
```

`api.cnb.cool` 的构建接口用仓库 git PAT 读不到（返 `errcode:5`），必须用 CLI。

---

## 六、安全与脱敏

已完成（提交 `7e1497e`）：

- `AuthViewModel.webUrl` 默认值**不再硬编码真实地址**（入口路径+端口实质等于半个凭据）
- 仅回填用户自己填过的值
- `HttpLoggingInterceptor` **只在 `BuildConfig.DEBUG` 安装**
  （BASIC 级别也会打印完整请求行 `--> POST https://1.2.3.4:8379/...`）
- `tools/verify_cookie.sh` 改为从环境变量 `BTWAF_PW` 读取，不再明文密码
- 注释内真实地址替换为 RFC 5737 / RFC 2606 示例
- README 加入登录页截图作为佐证

---

## 七、本机构建环境

```bash
export JAVA_HOME="$HOME/.workbuddy/binaries/android/jdk17tmp/jdk-17.0.20.1+1"
export ANDROID_HOME="$HOME/.workbuddy/binaries/android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

cd /c/Users/Administrator/Desktop/test/BTWAF
"$HOME/.workbuddy/binaries/android/gradle-8.7/bin/gradle" --offline --no-daemon :app:assembleRelease
```

- `--offline --no-daemon` **必须加**（本机无稳定网络访问 Gradle 分发源）
- adb：`export PATH="$HOME/.workbuddy/binaries/android/sdk/platform-tools:$PATH"`
- 带 PIL 的解释器（内置 3.13 **没有** PIL）：
  `C:/Users/Administrator/AppData/Local/Programs/Python/Python311/python.exe`
- ⚠️ `which convert` 返回 `/c/Windows/system32/convert`，那是Windows 磁盘转换工具，**不是 ImageMagick**

### ⚠️ 入库文件里绝不能有本机绝对路径

`gradle.properties` 曾写死 `org.gradle.java.home=C:/Users/...`，导致 CI 容器
里Gradle 在**配置阶段**就退出（22 秒挂），而 App 侧只表现为「在线更新永远提示
已是最新」—— 症状与病因完全不在一处。

---

## 八、已知事项与后续

### 已知限制

| 事项 | 状态 |
|---|---|
| ColorOS 上「安装更新」报「未找到可用的系统安装器」 | 设备禁用第三方安装器所致，非App bug。已用 `adb install -r` 等效路径验证覆盖安装成功 |
| Release 与 commit 附件 sha256 不同 | **正常**。两条流水线各独立构建一次，APK 内嵌构建时间戳，字节不同但签名一致、都能覆盖安装 |
| `/-/raw/<ref>/<file>` 在文件不存在时返回 200 + HTML | CNB 侧行为。判断文件是否存在不能只看状态码，要看 `Content-Type` |

### 可继续的方向

- [ ] 增加单元测试（WafRepository 的响应解析层目前只靠真机遍历验证）
- [ ] 证书代理的证书链导入向导
- [ ] 多实例切换（当前 AuthStore 只存一份连接信息）
- [ ] 深色主题跟随系统（当前固定浅色）

---

## 九、经验沉淀（已写入 skill）

`~/.workbuddy/skills/compose-backend-screen-fill/SKILL.md` 共1015 行，本项目沉淀：

- aawaf 路由约定与鉴权三步走、Cookie 被清空的隐蔽症状
- 视觉一致性排查（**用像素量不对称，不要靠肉眼**；五条噪声过滤规则）
- 截图坐标缩放陷阱（x/y 比例不同，须用 uiautomator dump 的 bounds）
- 品牌图标「一处绘制、多处引用」（mipmap PNG 与 Compose Icon 是两套独立来源）
- 自适应图标 ink/carve 独立传参、monochrome 语义相反
- 数字处理链路（Gson Double 饱和、窄化转换、越界守卫边界、`== true`）
- 在线更新四个硬前提（版本单一来源 / 签名统一 / 匿名可达性实测 / 查与下载分离）
- CI 十个坑：`grep -oP` 非 POSIX、`stat -c%s` 非 POSIX、本机绝对路径泄漏、
  commit 附件必须用 SHA、pending 被误判为失败、每条pipeline 都要改、
  `/-/raw/` 的 200+HTML、`/-/releases/<tag>/download/` 的 200+HTML、
  同版本两次构建 sha256 不同属正常、写了配置≠验证过配置
- 代码脱敏完整清单（含 `HttpLoggingInterceptor` BASIC 泄漏）
- 用户给截图放进 md 文档的三个坑（扩展名不可信、必须压缩、长图必须限高）

---

## 十、Git 历史

```
953612e docs: README 加入登录页与侧边抽屉截图
0f72ec2 feat: 打 tag 生成 Release，App 内增加「下载安装包」入口，v1.2.1
d9c30e2 fix: tag_push 分支补修 stat -c%s；明确 CI 构建需 6~8 分钟
fb56f71 docs: README 修正附件下载地址（分支名不可用，改为 commit SHA）
8c52bb9 feat: 在线更新改用 commit SHA 取附件，v1.2.0
38328ba fix: 移除 gradle.properties 里入库的本机 JDK 绝对路径（CI 构建失败的真凶）
fbbb75f fix: 修 CI 流水线的四处会导致「构建成功但产物不存在」的问题
7e1497e feat: 源码仓库地址、CNB 自动构建、在线更新、清除真实信息、新图标
f91f027 fix: 数字处理链路全面加固
17ceaf0 fix: 修复站点开关无效与子页面顶部大空白
ee6da10 feat: 实时刷新、App 图标、关于作者页
b78e1fa fix(ui): 修掉 Tab 栏不对称、token 过期处理与错误中文化
251e31e fix(ui): 收敛全站卡片内边距
0eb6a2d feat: 补齐 wafrules 防护规则模块 41 个接口
3a95171 feat: 补齐自定义规则编辑器、反向代理、DNS 集群
fd48b48 重做设计系统
86bb786 fix(ui): 真机验证发现的三类问题修复
447e3c1 feat: 重做信息架构与视觉，补齐 aawaf 缺失模块
a4873c7 fix: 修复登录 404 并支持图形验证码
54bff61 初始化堡塔云 WAF 安卓管理端
```