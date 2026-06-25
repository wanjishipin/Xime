package com.kingzcheung.xime.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SetupStep {
    EnableIme,
    SelectSchemas,
    SwitchToIme
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    visible: Boolean = true,
    onNavigateToSchemaSettings: () -> Unit,
    onCompleted: () -> Unit
) {
    var currentStep by remember { mutableStateOf(SetupStep.EnableIme) }
    var hasBeenToSettings by remember { mutableStateOf(false) }
    var deployReminder by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    // 从设置页返回后检查已启用的方案（初始为空，用户必须主动选择）
    val enabledSchemas = remember { mutableStateOf(if (hasBeenToSettings) SchemaManager.getEnabledSchemas(context) else emptyList()) }

    // 每次向导重新可见时（从设置页返回），刷新方案列表
    LaunchedEffect(visible) {
        if (visible) {
            enabledSchemas.value = SchemaManager.getEnabledSchemas(context)
        }
    }

    if (visible) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("设置向导") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Step indicator
                StepIndicator(currentStep = currentStep)
                Spacer(Modifier.height(32.dp))

                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "step_content"
                ) { step ->
                    when (step) {
                        SetupStep.EnableIme -> EnableImeStep(
                            onNext = { currentStep = SetupStep.SelectSchemas }
                        )
                        SetupStep.SelectSchemas -> SelectSchemasStep(
                        enabledSchemas = enabledSchemas.value,
                        deployReminder = deployReminder,
                        onNavigateToSchemaSettings = {
                            hasBeenToSettings = true
                            deployReminder = null
                            onNavigateToSchemaSettings()
                        },
                        onNext = {
                            enabledSchemas.value = SchemaManager.getEnabledSchemas(context)
                            if (enabledSchemas.value.isNotEmpty() && !RimeConfigHelper.isDeploymentComplete(context)) {
                                deployReminder = "方案已选择，但尚未部署。请前往设置点击「部署」按钮编译词库"
                            } else {
                                deployReminder = null
                                currentStep = SetupStep.SwitchToIme
                            }
                        }
                    )
                    SetupStep.SwitchToIme -> SwitchToImeStep(
                        onCompleted = {
                            // 将当前方案设为第一个已启用的方案
                            // 否则 currentSchema 保持默认值 "wubi86"，
                            // 用户可能根本没启用 wubi86，导致键盘无法输入中文
                            val enabledSchemas = SchemaManager.getEnabledSchemas(context)
                            if (enabledSchemas.isNotEmpty()) {
                                SettingsPreferences.setCurrentSchema(context, enabledSchemas.first())
                            }
                            SettingsPreferences.setSetupCompleted(context, true)
                            SettingsPreferences.setDeploymentDone(context, true)
                            RimeConfigHelper.storeDeploymentHash(context)
                            onCompleted()
                        }
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun StepIndicator(currentStep: SetupStep) {
    val steps = SetupStep.entries
    val currentIndex = steps.indexOf(currentStep)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, step ->
            val isCompleted = index < currentIndex
            val isCurrent = index == currentIndex

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = when {
                        isCompleted -> MaterialTheme.colorScheme.primary
                        isCurrent -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "${index + 1}",
                            color = if (isCompleted || isCurrent)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (step) {
                        SetupStep.EnableIme -> "启用"
                        SetupStep.SelectSchemas -> "选方案"
                        SetupStep.SwitchToIme -> "切换"
                    },
                    fontSize = 11.sp,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (index < steps.size - 1) {
                HorizontalDivider(
                    modifier = Modifier
                        .width(48.dp)
                        .padding(bottom = 20.dp),
                    color = if (index < currentIndex) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EnableImeStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(checkImeEnabled(context)) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            text = "曦码输入法",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "基于 Rime 引擎的 Android 输入法",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(48.dp))

        Text(
            text = "步骤 1：启用输入法",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "请在系统设置中启用「曦码输入法」",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
        ) {
            Text("去系统设置启用")
        }

        if (isEnabled) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "✓ 曦码输入法已启用",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            ) {
                Text("下一步")
            }
        } else {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "请先在系统设置中启用曦码输入法",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    scope.launch { isEnabled = checkImeEnabled(context) }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            ) {
                Text("检查状态")
            }
        }

        Spacer(Modifier.weight(1f))
    }
    LaunchedEffect(Unit) {
        while (!isEnabled) {
            delay(2000)
            val enabled = checkImeEnabled(context)
            if (enabled) {
                isEnabled = true
                break
            }
        }
    }
}

private fun checkImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return false
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

@Composable
private fun SelectSchemasStep(
    enabledSchemas: List<String>,
    deployReminder: String? = null,
    onNavigateToSchemaSettings: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            text = "步骤 2：选择输入方案",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "点击下方按钮前往设置，选择您需要的输入方案并点击「部署」",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "请至少选择一个方案后才能继续",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        Button(
            onClick = { onNavigateToSchemaSettings() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
        ) {
            Text("去设置选择方案")
        }

        if (enabledSchemas.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "✓ 已选择 ${enabledSchemas.size} 个方案",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            if (deployReminder != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = deployReminder,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            ) {
                Text("下一步")
            }
        }

        Spacer(Modifier.weight(1f))
    }
}

private suspend fun doCompile(
    context: Context,
    enabledSchemas: List<String>,
    onProgress: (String) -> Unit,
    onDone: () -> Unit,
    onError: () -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            onProgress("正在初始化输入法引擎...")

            // 1. 初始化 Rime 引擎
            val (userDataDir, sharedDataDir) =
                RimeConfigHelper.initializeRimeDataAsync(context)
            val engine = RimeEngine.getInstance()
            engine.initialize(userDataDir, sharedDataDir)

            // 2. 部署 = 编译词库 + 创建 session（一步完成）
            onProgress("正在编译词库...")
            engine.deploy()

            onDone()
        } catch (e: Exception) {
            Log.e("SetupWizard", "Compile failed", e)
            onProgress("错误：${e.message}")
            onError()
        }
    }
}

@Composable
private fun SwitchToImeStep(onCompleted: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            text = "步骤 4：切换输入法",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "一切准备就绪！请切换到曦码输入法开始使用",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showInputMethodPicker()
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
        ) {
            Text("弹出输入法选择器")
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onCompleted,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
        ) {
            Text("完成设置")
        }
    }
}
