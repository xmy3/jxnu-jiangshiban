package cn.jxnu.nvzhuanban.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.jxnu.nvzhuanban.R

/**
 * TopAppBar 通用刷新按钮。
 *
 * - 静止时：显示 Refresh 图标，点击调 [onClick]
 * - 刷新中（[isRefreshing] = true）：显示 CircularProgressIndicator，按钮 disabled
 *
 * 与 PullToRefreshBox 的下拉转圈互补：下拉时这里也会转，统一一个"刷新中"信号。
 */
@Composable
fun RefreshIconButton(
    isRefreshing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = !isRefreshing, modifier = modifier) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.cd_refresh),
            )
        }
    }
}
