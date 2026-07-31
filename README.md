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

江西师大教务系统的第三方 Android 客户端。

> 非官方项目。App 没有自己的服务器，所有数据都来自你本人登录教务系统后能看到的页面。

## 能做什么

- 课表：切学期、左右滑动切周、桌面小部件，没网时也能看最近一次加载的课表。教务网的课表没有周次信息，默认按 1–18 周显示，可以在课程详情里自己改
- 通知：教务处的通知、通告、教务风采、图文新闻合在一个列表里，支持搜索，正文可以调字号
- 成绩：学期成绩和考试出分。
- 考试安排：学期考试和补缓考放一起，带倒计时
- 开课查询：全校开课检索；课表里点老师名字或教室号，可以直接查这个老师的课、这间教室的占用
- 还有：培养方案、毕业学分审核、师生查询、看他人课表、校历、空闲教室

## 隐私

没有后端，没有统计 SDK，请求只发往学校域名（`*.jxnu.edu.cn`）。登录走学校 CAS 的标准流程，密码 RSA 加密后提交，和浏览器登录是同一个接口。勾选「下次自动登录」时密码存在本机（Android Keystore 加密），卸载即销毁，不进云备份。细节写在 [docs/privacy.md](docs/privacy.md)。

## 安装

Android 8.0 以上，去 [Releases](../../releases) 下载 APK。

## 自己编译

需要 JDK 17+ 和 Android SDK 36，Gradle Wrapper 仓库自带：

```bash
./gradlew :app:assembleDebug    # debug 包
./gradlew :app:test             # 单测（parser 回归，几秒跑完）
```

想打 release 包要自己配签名：`keytool` 生成一个 keystore，复制 `keystore.properties.example` 为 `keystore.properties` 填好；也可以放在仓库外，用环境变量 `NVZHUANBAN_KEYSTORE_PROPERTIES` 指过去。keystore 和密码别提交进仓库。

## 已知问题

- 教务网是老 ASP.NET，偶尔抽风返回 500，下拉刷新一般能自己好
- 考试的具体时间教务处经常不填或乱填，所以 App 只信日期，不显示几点开考
