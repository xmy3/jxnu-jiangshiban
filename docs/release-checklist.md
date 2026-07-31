# Release checklist

Use this checklist before producing or sharing a release build.

## Versioning

- Update `versionName` and `versionCode` in `app/build.gradle.kts`.
- Keep the current rule: `versionCode = major * 100000 + minor * 100 + patch`
  (e.g. 1.0.0 → 100000). versionCode must stay monotonically increasing — the
  encoding was widened once, so never narrow the formula or lower the value.
- Add a dated entry to `CHANGELOG.md` with user-facing changes and internal risk notes.

## Required checks

Run these from the repository root:

```powershell
.\gradlew.bat :app:test :app:compileDebugKotlin :app:lintDebug
```

If a device or emulator is available, run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Then complete the manual checklist in `docs/smoke-test.md`.

When upgrading AGP or Kotlin, re-check AndroidX, OkHttp, Compose BOM, and
coroutines in `gradle/libs.versions.toml`; Maven `latest` may point to preview
artifacts, so prefer stable releases for normal app builds.
Current tooling debt: AGP remains on 8.7.2 because AGP 9.x requires Gradle
9.4.1+, and the Gradle distribution must be downloadable before that migration.

For release candidates, also build the signed artifact locally:

```powershell
.\gradlew.bat :app:assembleRelease
```

Record the APK SHA-256 next to the release notes:

```powershell
Get-FileHash .\app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```

## Signing material

- `keystore.properties`, `*.jks`, and `*.keystore` must stay out of version control.
- Store local signing material outside the repository. The build defaults to
  `%USERPROFILE%\.android\nvzhuanban-signing\keystore.properties`, and can be
  overridden with `NVZHUANBAN_KEYSTORE_PROPERTIES` or
  `-PnvzhuanbanKeystoreProperties=...`.
- Keep HAR captures and real production HTML samples outside the repository
  unless they are fully anonymized.
- If a keystore or password is ever committed or shared, rotate the signing key before release.

## Security and privacy review

- Re-read `docs/privacy.md` and update it when auth, logging, WebView, local storage, widget snapshots, or avatar loading changes.
- Confirm `android:debuggable` is not enabled in release.
- Confirm OkHttp logging remains debug-only and does not log credentials, cookies, passwords, or student IDs.
- Confirm `network_security_config.xml` does not allow broad cleartext traffic.
- Re-check WebView screens after URL or rendering changes: JavaScript, file access, content access, and mixed content should stay disabled unless a specific feature requires them.
- Re-check credential handling after auth changes: auto-login must depend on `SecureCredentialStore.isAvailable()`.

## Manual smoke test

- Fresh login and logout.
- Profile > 隐私说明 opens and matches `docs/privacy.md`.
- Auto-login after app restart.
- Schedule load, semester switch, and refresh.
- Grades and test-grade pages.
- Exam list.
- Announcement list, pagination, and detail view.
- Profile screen with avatar toggle both off and on.
- Today schedule widget after opening the schedule screen.
