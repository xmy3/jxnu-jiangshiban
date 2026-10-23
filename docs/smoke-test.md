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

To explicitly check the live university PNG/JPEG image service on Android (public images only):

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.jxnu.nvzhuanban.data.network.JwcImageLiveDiagnosticTest -Pandroid.testInstrumentationRunnerArguments.liveJwcImages=true
```

The live image checks are skipped by default so normal tests do not depend on the university service.

## Install smoke

1. Install the release APK.
2. Launch the app.
3. Confirm it shows either the login page or the main tab shell without crashing.
4. Confirm the displayed version in Profile > About matches `versionName`.
5. Open Profile > 关于 > 查看完整隐私说明 and confirm it describes local storage, encrypted credentials, and avatar behavior.

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

## Alert and credit regressions

1. Start with no saved login, sign in manually, then tap a grade or exam notification while the same Activity is alive. Confirm it opens the corresponding page.
2. Without exact-alarm permission, prepare a tomorrow-exam reminder before 20:00. If delivery is delayed, run the background check after 20:00 and confirm the pending reminder still arrives with the latest room/seat. A cancelled or rescheduled exam must not leave its old reminder behind.
3. After a reminder has been delivered, run another check and confirm it is not recreated. Enabling reminders for the first time after 20:00 must not create a late reminder for tomorrow.
4. Check fractional-credit courses and totals in grades and the training plan: 0.5 stays 0.5, 171.5 stays 171.5, and integer credits have no trailing .0.
5. Publish a makeup score while the original score is unchanged. Confirm the notification labels both scores correctly. Inserting or reordering exchange-course rows must not announce existing courses again.
6. Load a makeup/deferred exam with an explicit full date, then change its date or room. Confirm it participates in change alerts and the combined evening reminder without colliding with a regular exam of the same course. Entries without a year or with conflicting/ranged dates must not get a guessed reminder; an incomplete exam response must preserve pending reminders.
7. Sign out while a grade/exam request is pending, then sign in again. Confirm the old response cannot repopulate the cleared cache or update the new account's alert baseline.

## WebView and privacy

1. Open 我的 → 空闲教室 and confirm the classroom site and its internal links (including new-window links) stay inside the app. Confirm external links do not launch a browser, refresh works, and system Back navigates WebView history before leaving the screen.
2. Open announcement detail and confirm the article renders through the native reader, including text, tables, and images.
3. Confirm avatar is not requested while "显示学生头像" is off.
4. Re-read `docs/privacy.md` if auth, cookie, avatar, WebView, logging, or widget behavior changed.
5. With a network failure, confirm image loading stops after bounded retries. Restore connectivity and tap the failed image, or leave and return to the foreground: the image should recover; a successfully loaded image should not download again on resume.
6. Open an article with images inside a table or a text link and confirm all images appear; table images follow the text table in document order.

## Widget

1. Add 今日课表 widget to the launcher.
2. Open the app and load schedule successfully.
3. Return to launcher and confirm today's courses or the empty state render.
4. On Android 12+, open 我的 and confirm「小组件准点刷新」appears while「闹钟与提醒」is off; tap it, grant the permission, come back — the row must disappear and the widget repaint immediately.
5. Change system time across a course boundary if practical; confirm the widget flips「进行中 / 下一节」within a minute (with the exact-alarm grant) instead of several minutes later.
6. Reboot the phone and confirm the widget repaints without opening the app (boot receiver re-arms the alarm chain).
