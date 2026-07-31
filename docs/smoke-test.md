# Release smoke test

Run this checklist before sharing a release APK. The Gradle checks prove the code builds; this checklist proves the app still behaves on Android.

## Automated checks

Always run:

```powershell
.\gradlew.bat :app:test :app:compileDebugKotlin :app:lintDebug
```

When an emulator or device is connected, also run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

If connected tests are skipped because no device is available, record that in the release note.

## Install smoke

1. Install the release APK.
2. Launch the app.
3. Confirm it shows either the login page or the main tab shell without crashing.
4. Confirm the displayed version in Profile > About matches `versionName`.
5. Open Profile > 隐私说明 and confirm it describes local storage, encrypted credentials, avatar behavior, and cleanup.

## Auth

1. Log in with a valid account.
2. Close and reopen the app; confirm auto-login works if "下次自动登录" was enabled.
3. Toggle "下次自动登录" off, log out, and confirm no password is retained.
4. Let the session expire or clear cookies, then open a protected page; confirm the app returns to login with a clear expired-session message.

## Main features

1. Schedule: load current semester, switch semester, return to today.
2. Schedule detail: edit course weeks and confirm the change persists after app restart.
3. Grades: load semester grades and test-grade tab.
4. Exams: open from Profile tools and verify the list renders.
5. Announcements: load first page, open detail, open external browser, paginate.
6. Profile: toggle theme mode, dynamic color where available, and avatar on/off.

## WebView and privacy

1. Open 空闲教室 and confirm only the allowed classroom site loads inside the app.
2. Open announcement detail and confirm the article renders through the hardened WebView.
3. Confirm avatar is not requested while "显示学生头像" is off.
4. Re-read `docs/privacy.md` if auth, cookie, avatar, WebView, logging, or widget behavior changed.

## Widget

1. Add 今日课表 widget to the launcher.
2. Open the app and load schedule successfully.
3. Return to launcher and confirm today's courses or the empty state render.
4. Change system time across a course boundary if practical; confirm widget refreshes.
