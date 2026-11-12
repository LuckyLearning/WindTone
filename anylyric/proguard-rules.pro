# AnyLyric 运行时不依赖任何第三方库，默认规则已足够。
# 保留 NotificationListenerService 子类名，避免 R8 混淆后系统按 Manifest 名绑定失败。
-keep class com.anylyric.service.MediaWatchService { *; }
