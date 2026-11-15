package com.anylyric.lyrics.online

/**
 * 「获取更多版本」的推进规则（0.2.27 追加）。
 *
 * 用户原话：「换更多歌词的更多上限不限制，点一次获取更多就没个源查2个，再点再查」。
 *
 * 要做到「再点再查」而不是「再点还是那几条」，必须记住**每个源已经取到几版**：
 * 每次都从搜索结果第一条开始试，去重挡掉旧的那几条之后就一定没有新增。
 * 这里把「游标」与「配额」算清楚，让五个源共用同一套语义：
 *
 *  · 游标 = 这个源已经贡献的版本数，既当作「跳过前 N 条候选」，
 *    也换算成搜索结果页码（每页 [PAGE_SIZE] 条）；
 *  · 配额 = 本次这个源还能再收几版，固定 [PER_SOURCE]（用户要的「每源 2 个」）。
 *
 * 为什么不设产品上限：同名翻唱/现场/DJ/伴奏确实可能有十几版，而这是用户主动点出来的，
 * 不点不会发生。只留一个 [SAFETY_MAX] 防止程序跑飞（内存里每版几十行，200 版约几十 KB）。
 */
object OnlineScan {

    /** 每个源**每轮**最多贡献几版（= 用户说的「每个源查 2 个」）。 */
    const val PER_SOURCE = 2

    /** 搜索结果分页大小。游标越过一页就翻到下一页，避免反复读同一批候选。 */
    const val PAGE_SIZE = 20

    /**
     * 版本总数上限。
     *
     * **不是产品限制**，只是防御：每点一次多 2 版/源，正常人点不到 20 次；
     * 真跑飞了也得有个天花板，否则内存里的版本表会无限涨。
     */
    const val SAFETY_MAX = 200

    /**
     * 本次这个源还能再收几版。
     *
     * @param want      本次总共想要几版（[SAFETY_MAX] 表示「不设限，能取多少取多少」）
     * @param collected 本次已经收到的版本数（跨源累计）
     */
    fun quota(want: Int, collected: Int): Int {
        val room = want - collected
        if (room <= 0) return 0
        return minOf(PER_SOURCE, room)
    }

    /** 游标 → 搜索结果页码（1 起）。已取 0~19 版都在第 1 页，20 版起翻第 2 页。 */
    fun pageFor(skip: Int): Int = 1 + skip.coerceAtLeast(0) / PAGE_SIZE

    /**
     * 分页接口里，页内还需要再跳过几条。
     *
     * 页码（或 offset）已经把整页跳过了，若在页内再按完整游标跳一次，
     * 第 2 页的 20 条会被整页丢光 —— 表现就是「版本到 20 多个之后，再点更多永远没有新增」。
     */
    fun inPageSkip(skip: Int): Int = skip.coerceAtLeast(0) % PAGE_SIZE
}
