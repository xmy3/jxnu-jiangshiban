package cn.jxnu.nvzhuanban.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 「女专红」品牌色 ─ 致敬江西师大女子专科学校历史。
 *
 * 三个候选值，可在 Theme.kt 顶部一行换：
 *   - Brand:        默认深酒红，沉稳
 *   - BrandBright:  更艳，年轻
 *   - BrandDark:    更暗沉，正式
 */
val NvzhuanRed = Color(0xFFA91D34)
val NvzhuanRedBright = Color(0xFFA50034)
val NvzhuanRedDark = Color(0xFF8B1B2B)

// ──────────────────────────────────────────────────────────
// 浅色配色（由 NvzhuanRed 作为 seed 派生自 Material Theme Builder）
// ──────────────────────────────────────────────────────────
val LightPrimary = Color(0xFFA91D34)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFFFDADA)
val LightOnPrimaryContainer = Color(0xFF410008)

val LightSecondary = Color(0xFF775656)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFFFDADA)
val LightOnSecondaryContainer = Color(0xFF2C1515)

val LightTertiary = Color(0xFF755A2F)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFDDAE)
val LightOnTertiaryContainer = Color(0xFF281800)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFFFF8F7)
val LightOnBackground = Color(0xFF221919)
val LightSurface = Color(0xFFFFF8F7)
val LightOnSurface = Color(0xFF221919)
val LightSurfaceVariant = Color(0xFFF4DDDD)
val LightOnSurfaceVariant = Color(0xFF524343)
val LightOutline = Color(0xFF857373)
val LightOutlineVariant = Color(0xFFD7C1C1)

val LightInverseSurface = Color(0xFF382E2E)
val LightInverseOnSurface = Color(0xFFFEEDEC)
val LightInversePrimary = Color(0xFFFFB3B6)
val LightScrim = Color(0xFF000000)

val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFFFF0F0)
val LightSurfaceContainer = Color(0xFFFCE9E9)
val LightSurfaceContainerHigh = Color(0xFFF6E3E3)
val LightSurfaceContainerHighest = Color(0xFFF1DEDE)

// ──────────────────────────────────────────────────────────
// 深色配色
// ──────────────────────────────────────────────────────────
val DarkPrimary = Color(0xFFFFB3B6)
val DarkOnPrimary = Color(0xFF67001B)
val DarkPrimaryContainer = Color(0xFF8B1129)
val DarkOnPrimaryContainer = Color(0xFFFFDADA)

val DarkSecondary = Color(0xFFE7BDBD)
val DarkOnSecondary = Color(0xFF44292A)
val DarkSecondaryContainer = Color(0xFF5D3F3F)
val DarkOnSecondaryContainer = Color(0xFFFFDADA)

val DarkTertiary = Color(0xFFE5C18D)
val DarkOnTertiary = Color(0xFF402D05)
val DarkTertiaryContainer = Color(0xFF5B431A)
val DarkOnTertiaryContainer = Color(0xFFFFDDAE)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF1A1111)
val DarkOnBackground = Color(0xFFF1DEDE)
val DarkSurface = Color(0xFF1A1111)
val DarkOnSurface = Color(0xFFF1DEDE)
val DarkSurfaceVariant = Color(0xFF524343)
val DarkOnSurfaceVariant = Color(0xFFD7C1C1)
val DarkOutline = Color(0xFFA08C8C)
val DarkOutlineVariant = Color(0xFF524343)

val DarkInverseSurface = Color(0xFFF1DEDE)
val DarkInverseOnSurface = Color(0xFF382E2E)
val DarkInversePrimary = Color(0xFFA91D34)
val DarkScrim = Color(0xFF000000)

val DarkSurfaceContainerLowest = Color(0xFF140B0B)
val DarkSurfaceContainerLow = Color(0xFF221919)
val DarkSurfaceContainer = Color(0xFF271D1D)
val DarkSurfaceContainerHigh = Color(0xFF322828)
val DarkSurfaceContainerHighest = Color(0xFF3D3232)
