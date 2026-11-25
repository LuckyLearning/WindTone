package com.luckylearning.windtone.data.online

/**
 * 在线播放失败时该对用户说哪一句。
 *
 * 页面上的「已加载 N 个音源」数的是 [com.luckylearning.windtone.data.online.script.LxLoadReport.sources]
 * （成功注册的平台）。播放失败文案如果只看「这一瞬间注册表是不是空的」，
 * 会在启动加载还没跑完、或 reload 刚 clear 注册表时喊「还没加载音源脚本」，
 * 和页面上的「已加载 6 个音源」同时出现。
 *
 * 只有注册表空 **且** 最近一次加载报告也是 0 个平台，才说没脚本。
 * 报告里有平台、这次却没拿到地址，说的是地址问题，让用户去重载或换一首。
 */
internal object OnlinePlayHint {

    fun missingScript(registryEmpty: Boolean, loadedPlatforms: Int): Boolean =
        registryEmpty && loadedPlatforms <= 0
}
