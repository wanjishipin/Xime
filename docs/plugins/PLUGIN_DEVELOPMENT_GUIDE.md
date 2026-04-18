# Kime 插件开发完整指南

## 插件系统架构

Kime 采用动态加载插件架构，支持 ContentProvider 代理、sharedUserId 数据共享。

```
┌─────────────────────────────────────────────┐
│          主应用 (Kime APK)                   │
│                                              │
│  ┌─────────────────────────────────────┐   │
│  │   PluginManager                      │   │
│  │   - PluginClassLoader 加载插件APK    │   │
│  │   - ProxyManager 管理Provider代理    │   │
│  │   - XmlManager 持久化插件信息        │   │
│  └─────────────────────────────────────┘   │
│                                              │
│  ┌─────────────────────────────────────┐   │
│  │   HostProvider                       │   │
│  │   - 代理插件的ContentProvider        │   │
│  │   - 路径重写: authority/path         │   │
│  └─────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
           │
           │ PluginClassLoader 加载
           ▼
┌─────────────────────────────────────────────┐
│       插件 APK (独立安装)                    │
│                                              │
│  ┌─────────────────────────────────────┐   │
│  │   IPluginEntryClass 实现             │   │
│  │   - onLoad(PluginContext)            │   │
│  │   - onUnload()                       │   │
│  │   - 返回具体的插件接口实例            │   │
│  └─────────────────────────────────────┘   │
│                                              │
│  ┌─────────────────────────────────────┐   │
│  │   ContentProvider (可选)             │   │
│  │   - 通过 sharedUserId 共享数据       │   │
│  └─────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

## 核心概念

### 插件类型

| 类型 | 接口 | 用途 |
|------|------|------|
| PREDICTION | PredictionPlugin | 联想词预测 |
| SPEECH | SpeechPlugin | 语音转文字 |
| EMOJI | EmojiPlugin | 表情输入 |

### sharedUserId 数据共享

**重要**：如果插件需要与主应用共享配置数据，必须配置 sharedUserId：

```xml
<!-- 主应用 AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    android:sharedUserId="com.kingzcheung.kime.shared">

<!-- 插件 AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    android:sharedUserId="com.kingzcheung.kime.shared">
```

## 开发插件步骤

### 1. 创建项目结构

```
my-kime-plugin/
├── build.gradle.kts
├── proguard-rules.pro
└── src/main/
    ├── AndroidManifest.xml
    ├── java/com/example/plugin/
    │   ├── PluginDeclaration.kt      # 空的 Activity
    │   ├── MyPlugin.kt               # 实现 IPluginEntryClass
    │   ├── PluginSettingsActivity.kt # 设置界面（可选）
    │   └── ConfigProvider.kt         # ContentProvider（可选）
    └── res/
```

### 2. 配置 build.gradle.kts

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.kime.plugin"
    compileSdk = 36
    
    defaultConfig {
        applicationId = "com.example.kime.plugin.myplugin"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    buildFeatures {
        compose = true
    }
}

dependencies {
    compileOnly(project(":plugin-core"))
    
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
}
```

### 3. 配置 AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    android:sharedUserId="com.kingzcheung.kime.shared">
    
    <!-- ContentProvider 数据共享需要的权限 -->
    <uses-permission android:name="android.permission.INTERACT_ACROSS_USERS_FULL" />
    
    <queries>
        <package android:name="com.kingzcheung.kime" />
    </queries>
    
    <application
        android:allowBackup="false"
        android:label="@string/app_name">
        
        <!-- 插件声明 Activity -->
        <activity
            android:name=".PluginDeclaration"
            android:exported="true">
            <intent-filter>
                <action android:name="com.kingzcheung.kime.plugin.EXTENSION" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
        
        <!-- 设置界面 -->
        <activity
            android:name=".PluginSettingsActivity"
            android:exported="true"
            android:theme="@android:style/Theme.Material.Light.NoActionBar">
        </activity>
        
        <!-- ContentProvider（用于配置共享） -->
        <provider
            android:name=".ConfigProvider"
            android:authorities="com.example.kime.plugin.config"
            android:exported="true"
            android:grantUriPermissions="true">
        </provider>
        
        <!-- 插件入口类 -->
        <meta-data
            android:name="plugin.entryClass"
            android:value="com.example.plugin.MyPlugin" />
        
        <meta-data
            android:name="plugin.description"
            android:value="插件描述" />
        
        <meta-data
            android:name="plugin.type"
            android:value="prediction" />
        
    </application>
</manifest>
```

### 4. 实现插件入口类

```kotlin
package com.example.plugin

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.kingzcheung.kime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.kime.plugin.core.api.PredictionPlugin
import com.kingzcheung.kime.plugin.core.model.PluginContext

class MyPlugin : IPluginEntryClass {
    
    private var pluginInstance: PredictionPlugin? = null
    private var appContext: Context? = null
    
    override fun onLoad(context: PluginContext) {
        appContext = context.application
        
        // 创建插件实例
        pluginInstance = MyPredictionImpl(appContext!!)
    }
    
    override fun onUnload() {
        pluginInstance?.release()
        pluginInstance = null
    }
    
    // IPluginEntryClass 要求返回具体的插件接口
    fun getPredictionPlugin(): PredictionPlugin? = pluginInstance
    
    override fun hasSettings(): Boolean = true
    
    override fun openSettings(context: Context) {
        try {
            val intent = Intent()
            intent.setClassName(
                "com.example.kime.plugin.myplugin",
                "com.example.plugin.PluginSettingsActivity"
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开设置: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
```

### 5. 实现具体插件接口

```kotlin
package com.example.plugin

import android.content.Context
import com.kingzcheung.kime.plugin.core.api.PredictionCandidate
import com.kingzcheung.kime.plugin.core.api.PredictionPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MyPredictionImpl(private val context: Context) : PredictionPlugin {
    
    private var initialized = false
    
    override suspend fun predict(inputText: String, topK: Int): List<PredictionCandidate> {
        if (!initialized || inputText.isEmpty()) return emptyList()
        
        return withContext(Dispatchers.IO) {
            // 实现预测逻辑
            listOf("候选1", "候选2").take(topK)
                .map { PredictionCandidate(it, 1.0f) }
        }
    }
    
    override fun learn(text: String) {
        // 学习用户输入
    }
    
    override suspend fun saveLearnedData() {
        // 保存学习数据
    }
    
    fun release() {
        initialized = false
    }
}
```

### 6. ContentProvider 数据共享（可选）

如果插件需要保存配置供主应用读取：

```kotlin
package com.example.plugin

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

class ConfigProvider : ContentProvider() {
    
    companion object {
        const val AUTHORITY = "com.example.kime.plugin.config"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/config")
        private const val HOST_PACKAGE = "com.kingzcheung.kime"
        private const val PREFS_NAME = "plugin_config"
    }
    
    // 关键：通过 sharedUserId 访问宿主的 SharedPreferences
    private fun getHostPrefs(): SharedPreferences? {
        val ctx = context ?: return null
        
        return if (ctx.packageName == HOST_PACKAGE) {
            // 在宿主进程
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } else {
            // 在插件进程，通过 sharedUserId 访问宿主
            try {
                val hostContext = ctx.createPackageContext(
                    HOST_PACKAGE, 
                    Context.CONTEXT_IGNORE_SECURITY
                )
                hostContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            } catch (e: Exception) {
                Log.e("ConfigProvider", "Failed to get host context", e)
                null
            }
        }
    }
    
    override fun query(uri: Uri, projection: Array<out String>?, ...): Cursor? {
        val value = getHostPrefs()?.getString("key", "") ?: ""
        val cursor = MatrixCursor(arrayOf("value"))
        cursor.addRow(arrayOf(value))
        return cursor
    }
    
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val value = values?.getAsString("value")
        getHostPrefs()?.edit()?.putString("key", value)?.apply()
        return uri
    }
    
    // ... 其他方法
}
```

### 7. ProGuard 规则

```proguard
# 禁用混淆
-dontobfuscate
-dontoptimize

# 保留插件类
-keep class com.example.plugin.** { *; }

# 保留 Kotlin
-keep class kotlin.** { *; }
```

## 主应用访问插件 Provider

主应用通过代理访问插件的 ContentProvider：

```kotlin
// 主应用代码
import com.kingzcheung.kime.plugin.core.utils.queryPlugin
import com.kingzcheung.kime.plugin.core.utils.insertPlugin

// 使用代理扩展函数
val cursor = contentResolver.queryPlugin(
    ConfigProvider.CONTENT_URI,
    null, null, null, null
)

val values = ContentValues().put("value", "config_data")
contentResolver.insertPlugin(ConfigProvider.CONTENT_URI, values)
```

## 安装和测试

### 构建

```bash
./gradlew assembleDebug
```

### 清除插件数据（调试用）

```bash
./gradlew clearPlugins      # 清除插件文件
./gradlew uninstallApp      # 完全卸载主应用
```

### 安装顺序

```bash
# 1. 卸载旧版本（sharedUserId 变化必须完全卸载）
adb uninstall com.kingzcheung.kime
adb uninstall com.example.kime.plugin.myplugin

# 2. 安装新版本
adb install app/build/outputs/apk/debug/Kime-xxx.apk
adb install my-plugin/build/outputs/apk/debug/my-plugin-xxx.apk
```

## 常见问题

### 1. ClassNotFoundException

- 原因：ProGuard 混淆了插件类
- 解决：添加 `-keep class com.example.plugin.** { *; }`

### 2. ContentProvider 数据读取为空

- 原因：sharedUserId 未配置或签名不一致
- 解决：确保两个应用配置相同的 sharedUserId，且签名相同

### 3. 插件无法发现

- 原因：AndroidManifest intent-filter 配置错误
- 解决：检查 `<action android:name="com.kingzcheung.kime.plugin.EXTENSION" />`

## 现有插件示例

| 插件 | 类型 | 特点 |
|------|------|------|
| funasr-speech | SPEECH | ContentProvider 配置共享、sharedUserId |
| prediction-onnx | PREDICTION | ONNX 模型加载 |
| kaomoji | EMOJI | 预定义颜文字数据 |
| emoji-sticker | EMOJI | 图片表情包 |

## 参考文档

- [plugin-core 源码](../../plugin-core/) - 核心实现
- [现有插件实现](../../plugins/) - 学习最佳实践