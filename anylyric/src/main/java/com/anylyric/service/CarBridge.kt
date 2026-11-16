package com.anylyric.service

import android.content.Context

/**
 * 车型直连桥接（0.2.27）。
 *
 * 车厂各自提供的「媒体中心」Binder 服务接口完全不同（BYD 是
 * `com.byd.mediacontroller`，其它车厂各有一套），但对本应用来说对外只有四件事：
 * **探测、启动、报状态、停止**。SessionHub 只依赖这个接口 —— 新增车型时加一个
 * 实现类即可，不需要去改会话层（这就是把 [BydMediaController] 从硬编码里拔出来的原因）。
 *
 * 约束：
 *  · [probe] 必须无副作用、可在主线程调用（只做 PackageManager 查询与品牌判断）；
 *  · [start] 必须幂等，重复调用不产生第二次绑定；
 *  · 桥接拿到数据后统一调 `SessionHub.onBydMetadata(...)` 上报（该方法名沿用历史，
 *    实际已是所有车型通道的通用入口）。
 */
interface CarBridge {

    /** 稳定标识，只用于日志与诊断，不进用户配置。 */
    val id: String

    /** 这台设备上有没有可对接的服务。 */
    fun probe(context: Context): Boolean

    /** 启动（内部幂等）。[probe] 为 false 时应立刻返回。 */
    fun start(context: Context)

    fun stop()

    /** 诊断用的一行状态：`registered` / `binding` / `idle` / `unavailable` … */
    fun statusLabel(): String
}

/**
 * 车型桥接注册表。
 *
 * 按顺序取**第一个探测通过**的实现：同一台设备上一般只有一个车厂服务可用。
 * 顺序即优先级 —— 将来若出现「同一台机器两套服务都在」的车型，排前面的赢。
 */
object BridgeRegistry {

    private val bridges: List<CarBridge> = listOf(BydMediaController)

    /** 当前设备可用的桥；null 表示这台机器没有车机直连通道（普通手机）。 */
    fun active(context: Context): CarBridge? =
        bridges.firstOrNull { runCatching { it.probe(context) }.getOrDefault(false) }

    fun all(): List<CarBridge> = bridges
}
