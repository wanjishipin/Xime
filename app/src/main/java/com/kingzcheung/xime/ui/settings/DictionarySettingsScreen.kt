package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.DataObject
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.viewmodel.DictionaryHubViewModel

/**
 * 「词库管理」入口页。
 *
 * 以前这里是三个页签（自定义短语 / 用户词库 / 方案词库），按"数据落在哪个文件里"分类
 * —— 用户得先认识 `custom_phrase.txt`、`*.userdb`、`*.dict.yaml` 才知道点哪个。现在改成
 * 按"词库内容"下钻：
 *  - 用户词库：输入时自动积累、存在 `<词典名>.userdb` 里的词条；
 *  - 快捷短语：手写的 `custom_phrase`，随输入方案生效；
 *  - 方案自带的静态词表属于方案本身 ⇒ 移到「输入方案 → 方案词表」，这里只留一句指路。
 *
 * 每个目的地都是独立导航路由，因此系统返回键逐层正确，页面内不再需要自绘返回箭头。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryHubContent(
    onBack: () -> Unit,
    onNavigateToUserDict: () -> Unit,
    onNavigateToCustomPhrase: () -> Unit,
) {
    val viewModel: DictionaryHubViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 从子页面返回时重新统计：导入过码表、新增过短语后数量会变
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("词库管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(title = "词库内容", content = {
                SettingsItem(
                    icon = Icons.TwoTone.Storage,
                    title = "用户词库",
                    subtitle = "输入时自动积累的词条" +
                        (uiState.userDictCount?.let { " · $it 本" } ?: ""),
                    onClick = onNavigateToUserDict,
                    showArrow = true
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                SettingsItem(
                    icon = Icons.TwoTone.DataObject,
                    title = "快捷短语",
                    subtitle = "补充方案码表之外的常用短语，随输入方案生效" +
                        (uiState.customPhraseCount?.let { " · $it 条" } ?: ""),
                    onClick = onNavigateToCustomPhrase,
                    showArrow = true
                )
            })

            Text(
                "方案自带的静态词表（.dict.yaml）属于方案本身，请到「输入方案 → 方案词表」查看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "跨设备搬运：单本词库用「用户词库 → 导出/导入文本码表」，与电脑端小狼毫、鼠须管同格式；" +
                    "多设备按时间戳合并请用「同步与备份 → 词库同步」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}