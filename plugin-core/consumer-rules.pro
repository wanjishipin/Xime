# Plugin Core Consumer Rules
#
# 插件系统大量依赖“约定而非静态引用”：QuickJS 桥接（函数/对象绑定）、
# kotlinx.serialization（manifest/registry/错误落盘）、suspend 调用、枚举名落盘回读等。
# 一旦被 R8 裁剪/优化，会出现 release 与 debug 行为不一致
# （插件加载失败、事件丢失、错误恢复失败等）。
#
# 因此本库整体保持（不裁剪、不优化、不改名），确保任意宿主直接依赖均可用。
# 各包职责：api（插件契约）/ model（数据模型）/ runtime（加载与安装）/
#          js（QuickJS 运行时与适配器）/ security（错误与分类）/ util（版本等）。

-keep class com.kingzcheung.xime.plugin.core.** { *; }

# 保留 Kotlin 元数据（kotlinx.serialization / 反射场景需要）
-keep class kotlin.Metadata { *; }

# 保留 suspend 函数签名（Continuation 桥接/反射场景）
-keepclassmembers class * {
    public *** *(kotlin.coroutines.Continuation);
}
