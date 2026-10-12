package cn.jxnu.nvzhuanban

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.jxnu.nvzhuanban.ui.screens.profile.ProfileLightweightListPreview

/** 只在 debug 构建中提供示例界面入口，正式应用仍从 MainActivity 登录。 */
class ProfilePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ProfileLightweightListPreview() }
    }
}
