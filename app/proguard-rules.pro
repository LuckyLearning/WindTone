# ─────────────────────────────────────────────────────────────
#  本地音乐播放器 · R8 混淆规则
#  原则：只补各库消费者规则覆盖不到的地方，不做过度 keep（否则体积压不下来）
# ─────────────────────────────────────────────────────────────

# 保留行号，崩溃栈可读（配合设置页的崩溃日志导出）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 注解 / 泛型签名 / 内部类：Room 生成的代码、Compose、Kotlin 元数据都依赖
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ── 应用自身代码：**参与混淆**（v1.1.97 起）
#
# 在此之前这里是整包 `-keep ...** { *; }`，等于自家 90+ 个类一个都没混淆，
# 真正被混淆的只有第三方库 —— 既没减到体积，也没抬高改包成本。
#
# 但有几类东西**必须按名字保留**，否则升级会直接把用户已有状态弄坏：
#
#  1. 无障碍服务：组件名会被系统写进 `ENABLED_ACCESSIBILITY_SERVICES`。
#     类名一变，用户升级后系统记的还是旧名 → 服务等于被关掉（而且用户无从理解）。
#  2. 桌面小组件 / 磁贴：组件名同样被系统登记在 AppWidgetManager 与 TileService 里。
#  3. Application / Activity / 广播 / 前台服务：由 manifest 与系统实例化。
-keep class com.luckylearning.windtone.overlay.ForegroundAppService { *; }
-keepnames class com.luckylearning.windtone.MusicApp
-keepnames class com.luckylearning.windtone.MainActivity
-keepnames class com.luckylearning.windtone.CarBootReceiver
-keepnames class com.luckylearning.windtone.playback.PlaybackService
-keepnames class com.luckylearning.windtone.overlay.LyricOverlayService
-keepnames class com.luckylearning.windtone.widget.**
-keepnames class com.luckylearning.windtone.tile.**

# ViewModel 由 ViewModelProvider **反射调用构造函数**（类对象是 Class 字面量传进去的，
# 但构造函数必须存在且签名可见）。只保构造函数，成员照常混淆。
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# BuildConfig：字段多为编译期常量（会被内联），保留类本身以防按名读取
-keep class com.luckylearning.windtone.BuildConfig { *; }

# ── 枚举：SongSource / SortOrder / LyricSource / LibraryView 通过 valueOf 反序列化
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── pinyin4j：运行期用类加载器读取 classpath 下的 pinyin4j.txt 数据表
-keep class net.sourceforge.pinyin4j.** { *; }
-keep class com.belerweb.pinyin4j.** { *; }
-dontwarn net.sourceforge.pinyin4j.**
-dontwarn com.belerweb.pinyin4j.**

# ── Room：数据库实现类由注解处理器生成，保留入口
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ── Media3 / ExoPlayer：内部有按名称加载解码器扩展的逻辑，交由消费者规则 + 关闭告警
-dontwarn androidx.media3.**

# ── 其它常见噪声（可选依赖）
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-dontwarn com.google.common.**
-dontwarn javax.annotation.**
-dontwarn kotlinx.coroutines.**
