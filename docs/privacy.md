# Privacy notes

江师办是一个本地 Android 客户端，没有自有后端。课表、成绩、考试、通知、头像等数据都直接来自学校教务系统或公开通知页。

## 本地保存的数据

- 登录 cookie：保存在应用私有目录，用于维持教务系统会话。
- 自动登录凭据：仅在用户勾选“下次自动登录”时保存，使用 Android Keystore + EncryptedSharedPreferences 加密。
- 安装标识：随机生成的 installation UUID，用于生成 CAS 需要的 `fpVisitorId`，不读取 `ANDROID_ID` 等硬件标识。
- 课表周次修正、主题设置、头像开关、通知已读锚点、widget 快照：保存在本机。

## 不会做的事

- 不上传账号、密码、cookie、学号、成绩或课表到第三方服务器。
- 不默认请求学生头像；只有用户打开“显示学生头像”后才会访问头像接口。
- 不采集设备硬件标识。

## 清除和备份

- 在应用内退出登录会清除当前登录会话、已保存的自动登录凭据、派生缓存、通知已读锚点和 widget 快照。
- Android 系统设置中清除应用数据会删除所有本地配置、cookie、widget 快照和加密凭据。
- Android cloud backup / device transfer 会排除登录状态、加密凭据、cookie、widget 快照、主题设置、头像偏好、课表周次修正和通知已读锚点。

## 发布前检查

每次改动认证、网络、日志、WebView 或本地存储时，都需要重新确认：

- release 包不会输出账号、密码、cookie、学生头像 URL 或学号。
- WebView 仍禁用文件访问、content 访问和 mixed content；通知详情页禁用 JavaScript，空闲教室页仅允许加载固定 HTTPS 页面。
- 自动登录只在加密存储可用时启用。
