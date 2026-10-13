# ─────────────────────────────────────────────────────────────
#  本地音乐播放器 · R8 混淆规则
#  原则：只补各库消费者规则覆盖不到的地方，不做过度 keep（否则体积压不下来）
# ─────────────────────────────────────────────────────────────

# 保留行号，崩溃栈可读（配合设置页的崩溃日志导出）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 注解 / 泛型签名 / 内部类：Room 生成的代码、Compose、Kotlin 元数据都依赖
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ── 应用自身代码：不混淆（体积占比极小，换来崩溃栈可读 + 规避反射风险）
-keep class com.luckylearning.windtone.** { *; }

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
