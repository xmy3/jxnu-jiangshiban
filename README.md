<div align="center">
  <img src="docs/assets/brand/logo.svg" width="128" alt="江师办品牌标：即将闭合的环，缺口处一粒火花">
  <h1>江师办</h1>
  <p><strong>江西师范大学 · 掌上教务</strong></p>
  <p>
    <a href="../../releases/latest"><img src="https://img.shields.io/github/v/release/xmy3/jxnu-jiangshiban?label=%E6%9C%80%E6%96%B0%E7%89%88&color=A91D34" alt="最新版"></a>
    <a href="../../actions/workflows/android.yml"><img src="https://github.com/xmy3/jxnu-jiangshiban/actions/workflows/android.yml/badge.svg" alt="CI"></a>
    <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
    <img src="https://img.shields.io/badge/Kotlin%20%C2%B7%20Compose%20%C2%B7%20M3-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin + Compose + Material 3">
  </p>
</div>

[江西师范大学] 教务系统的非官方 Android 客户端，灵感来自「酱紫办」。Kotlin + Jetpack Compose + Material 3，单 Gradle 模块。品牌标识与配色规范见 [docs/brand.md](docs/brand.md)。

> ⚠️ **本项目与江西师范大学官方、教务处、信息办均无任何关联。** 所有数据均来自访问者本人在 `jwc.jxnu.edu.cn` 上能正常浏览的页面，App 仅做客户端渲染。

## 功能

- 课表（含本学期/历史学期切换、自定义周次覆盖、桌面小部件）
- 通知 / 通告 / 教务风采 / 图文新闻（教务处四路合并，本地搜索）
- 成绩（学期成绩 + 考试出分两个 sub-tab，江师大用「标准分」/「加权平均标准分」）
- 考试安排（学期考试 + 补缓考合并展示，含倒计时）
- 开课查询（按学期 / 学院 / 星期 / 节次 / 教室 / 课程 / 教师组合检索全校开课；课表详情可一键查某位老师的课或某间教室的占用）
- 培养方案与毕业学分审核
- 师生查询、他人课表、学生 / 教师详情
- 校历 PDF 索引
- 空闲教室（嵌入 [xmy3/jxnu-classroom](https://xmy3.github.io/jxnu-classroom/)）

## 安全与隐私

- **没有自建后端。** App 不收集、不上报任何遥测、不接入任何第三方分析 SDK。所有请求只发往 `*.jxnu.edu.cn`。
- **登录走 CAS 标准流程**：`uis.jxnu.edu.cn/cas/login`，密码用教务公钥 RSA 加密后提交，与浏览器登录走同一接口。
- **凭证仅本机存储**：勾选「下次自动登录」时，密码经 Android Keystore 硬件根密钥加密写入 `EncryptedSharedPreferences`，卸载即销毁，**不会**进入云备份 / 换机迁移（见 `app/src/main/res/xml/data_extraction_rules.xml`）。
- **全站强制 HTTPS**：见 `network_security_config.xml`。仅 debug 包接受用户安装的根证书（Charles 抓包调试用）。
- **公开页**（通知列表、校历）不带 cookie；**业务页**（课表、成绩等）才附本机会话 cookie。

## 系统要求

- Android 8.0 (API 26) 及以上
- 联网（**不需要校园网或 VPN**，`jwc.jxnu.edu.cn` 公网可达）

## 安装

去 [Releases](../../releases) 下最新一份 APK 安装即可。

## 自己编译

依赖 JDK 17+ 和 Android SDK 36。仓库自带 Gradle Wrapper（pinned 8.9）。

常用任务：

```bash
# 编译 debug APK
./gradlew :app:assembleDebug

# 仅编译验证（最快）
./gradlew :app:compileDebugKotlin

# 跑全部 JVM 单测（parser 回归 + 工具类）
./gradlew :app:test

# 打 release APK + AAB（需要签名配置，见下）
./gradlew :app:assembleRelease :app:bundleRelease
```

### 配 release 签名

1. 生成 keystore（先做一次就行，自己保管好）：
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias nvzhuanban \
           -keyalg RSA -keysize 2048 -validity 36500
   ```
2. 复制 `keystore.properties.example` 为 `keystore.properties` 并填上密码/别名；或者放到仓库外用环境变量 `NVZHUANBAN_KEYSTORE_PROPERTIES=/path/to/keystore.properties` 指过去（推荐，仓库里始终没有签名信息）。
3. `keystore.properties` 与 `release.jks` 已经在 `.gitignore` 里，**别提交**。

## 已知限制

- 服务端 ASP.NET WebForms 偶尔会因 ViewState 漂移返回 500；下拉刷新一般能自愈。
- 课表页本身不带「周次」字段，所有课程默认 1–18 周。学期实际周次可在课程详情里手动改，覆盖保存在本机。
- 教务处自己有时把考试时间录得不准（甚至有"上午"标 09:00 这种）；UI 只信「日期」，不显示「时-分」。

## 致谢

- 灵感来自「酱紫办」。
- 空闲教室页托管在 [xmy3/jxnu-classroom](https://github.com/xmy3/jxnu-classroom)。

## 反馈

GitHub Issues 直接提；不会出现在 App 内的"问题反馈"入口，因为 App 不收任何用户上行信息。
