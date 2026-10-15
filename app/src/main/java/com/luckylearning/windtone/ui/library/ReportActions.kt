package com.luckylearning.windtone.ui.library

/**
 * 收听报告域对外的**动作面**。
 *
 * 抽成接口的目的见 [LibraryActions]。注意 `loadReport` **不在**这里：
 * 它的默认值 `_library.value.report?.days ?: DEFAULT_REPORT_DAYS` 依赖实例状态，
 * 接口默认值无法表达，因此改由 `LibraryViewModel` 直接声明（详见该类里的说明）。
 */
interface ReportActions {
    fun setReportDays(days: Int)
}
