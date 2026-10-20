package cn.jxnu.nvzhuanban.ui.screens.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.AppRelease
import cn.jxnu.nvzhuanban.ui.theme.AppShape

/** 只用于服务清单的薄荷色强调；按实际应用的主题判亮暗，不影响学生卡和全局主题。 */
private val serviceAccent: Color
    @Composable get() = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        Color(0xFFA6D6BD)
    } else {
        Color(0xFF2C6E5A)
    }

@Composable
internal fun ProfileToolsSection(
    onOpenClassroom: () -> Unit,
    onOpenPeopleSearch: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenCourseOffering: () -> Unit,
    onOpenPlanSearch: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ProfileSectionHeading("学业工具")
        ProfileSectionSurface(filled = true) {
            ProfileServiceRow(
                icon = Icons.Outlined.CalendarMonth,
                title = stringResource(R.string.calendar_title),
                iconTint = serviceAccent,
                onClick = onOpenCalendar,
            )
            ProfileSectionDivider()
            ProfileServiceRow(
                icon = Icons.Outlined.PeopleOutline,
                title = stringResource(R.string.people_search_title),
                iconTint = serviceAccent,
                onClick = onOpenPeopleSearch,
            )
            ProfileSectionDivider()
            ProfileServiceRow(
                icon = Icons.AutoMirrored.Outlined.ListAlt,
                title = stringResource(R.string.course_offering_title),
                iconTint = serviceAccent,
                onClick = onOpenCourseOffering,
            )
            ProfileSectionDivider()
            // 全校方案查询沿用原入口；个人培养方案仍由上方毕业进度摘要进入。
            ProfileServiceRow(
                icon = Icons.Outlined.School,
                title = "培养方案查询",
                iconTint = serviceAccent,
                onClick = onOpenPlanSearch,
            )
            ProfileSectionDivider()
            ProfileServiceRow(
                icon = Icons.Outlined.MeetingRoom,
                title = stringResource(R.string.classroom_title),
                iconTint = serviceAccent,
                onClick = onOpenClassroom,
            )
        }
    }
}

@Composable
internal fun ProfileSettingsSection(
    versionName: String,
    latestRelease: AppRelease?,
    /** Android 12+ 且没给「闹钟与提醒」权限时为 true —— 只有这时才显示准点刷新入口。 */
    showExactAlarmEntry: Boolean,
    onOpenTheme: () -> Unit,
    onAddWidgetClick: () -> Unit,
    onExactAlarmClick: () -> Unit,
    onAboutClick: () -> Unit,
    onCheckUpdate: () -> Unit,
    onLogoutClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ProfileSectionHeading("偏好与应用")
        ProfileSectionSurface(filled = false) {
            ProfileServiceRow(
                icon = Icons.Outlined.ColorLens,
                title = stringResource(R.string.profile_theme),
                onClick = onOpenTheme,
                trailing = {
                    // 展示当前主题的强调色；点击仍进入原有主题模式/动态取色弹窗。
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    ProfileRowChevron()
                },
            )
            ProfileSectionDivider()
            ProfileServiceRow(
                icon = Icons.Outlined.Widgets,
                title = "添加桌面小组件",
                subtitle = "将今日课表放到手机桌面",
                onClick = onAddWidgetClick,
            )
            // 只在缺权限时出现：widget 靠精确闹钟在上下课那一刻重绘，没有权限时系统会合并推迟，
            // 表现为「都上课十分钟了还显示下一节」。授予后本行自动消失（进入页面时重新读权限态）。
            if (showExactAlarmEntry) {
                ProfileSectionDivider()
                ProfileServiceRow(
                    icon = Icons.Outlined.Bolt,
                    title = "小组件准点刷新",
                    subtitle = "未开启：上下课切换可能延迟几分钟",
                    iconTint = serviceAccent,
                    onClick = onExactAlarmClick,
                )
            }
            ProfileSectionDivider()
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                ProfileSupportAction(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.profile_about),
                    subtitle = "江师办 v$versionName",
                    modifier = Modifier.weight(1f),
                    onClick = onAboutClick,
                )
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                ProfileSupportAction(
                    icon = Icons.Outlined.SystemUpdate,
                    title = "检查更新",
                    subtitle = if (latestRelease != null) "新版本 v${latestRelease.versionName}"
                    else "当前 v$versionName",
                    highlighted = latestRelease != null,
                    modifier = Modifier.weight(1f),
                    onClick = onCheckUpdate,
                )
            }
        }
        TextButton(
            onClick = onLogoutClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = AppShape.listItem,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Logout,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.profile_logout),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ProfileSectionHeading(title: String) {
    Row(
        modifier = Modifier.padding(start = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(3.dp).height(12.dp).background(serviceAccent, AppShape.tag))
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProfileSectionSurface(filled: Boolean, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.card,
        color = if (filled) MaterialTheme.colorScheme.surfaceContainerLow
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(content = { content() })
    }
}

@Composable
private fun ProfileServiceRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: @Composable () -> Unit = { ProfileRowChevron() },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun ProfileSupportAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val tint = if (highlighted) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (highlighted) tint else MaterialTheme.colorScheme.onSurface,
            )
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = tint)
        }
    }
}

@Composable
private fun ProfileRowChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp),
    )
}

@Composable
private fun ProfileSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}
