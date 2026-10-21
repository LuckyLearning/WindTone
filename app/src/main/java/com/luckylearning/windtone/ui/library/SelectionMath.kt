package com.luckylearning.windtone.ui.library

import com.luckylearning.windtone.data.model.Song

/**
 * 多选态的集合运算（v1.1.37 抽出）。
 *
 * 多选态本身住在 `UiState`（`selecting` + `selection`），这里只负责「勾选集合怎么变」。
 * 抽成纯函数是为了把几条**用户能察觉、坏了却不报错**的规则钉在一个地方：
 *
 *  1. **全选 / 反选只作用于「当前可见列表」**（搜索过滤后看到的那批），不是整库。
 *     不在当前列表里的旧勾选会被**整份丢弃** —— 用户搜出一个子集后反选，
 *     不该把子集之外的歌也一起翻过来。
 *  2. **勾选集合 → 歌曲列表按曲库顺序**，不是按勾选先后。批量操作（加入歌单 /
 *     收藏 / 入队）的结果因此可预期，重进界面也不会变样。
 *  3. 歌曲被移出曲库时，勾选集合同步摘掉对应键，否则底部操作栏会对着一堆
 *     已不存在的歌报数。
 *
 * 全部是 `Set<String>`（stableKey）上的运算，零框架依赖 —— 单测跑普通 JVM 就够。
 */
object SelectionMath {

    /** 长按进多选态：**保留**已有勾选，把这一首加进去（不是清空重选） */
    fun enter(selection: Set<String>, key: String): Set<String> = selection + key

    /** 勾选 / 取消勾选：在集合里就摘掉，不在就加回来 */
    fun toggle(selection: Set<String>, key: String): Set<String> =
        if (key in selection) selection - key else selection + key

    /**
     * 全选当前列表。
     *
     * **整份替换**而不是并集：用户先勾了两首、改了搜索条件、再点全选，
     * 期望的就是「当前看到的这些」，不该把条件外的旧勾选留下来。
     */
    fun selectAll(visibleKeys: Collection<String>): Set<String> = visibleKeys.toSet()

    /** 反选当前列表：在这批里取补集；不在这批里的旧勾选同样被丢弃 */
    fun invert(selection: Set<String>, visibleKeys: Collection<String>): Set<String> =
        visibleKeys.toSet() - selection

    /** 摘掉一批键（歌曲被移出曲库 / 删除时用） */
    fun drop(selection: Set<String>, keys: Collection<String>): Set<String> = selection - keys

    /**
     * 勾选集合对应的歌曲，**按曲库顺序**（[songs] 的顺序）而不是勾选顺序。
     *
     * 顺序稳定是硬需求：批量加入歌单后，歌单里的先后应当可预期；
     * 若按勾选先后，用户每次看到的队形都不一样，也没法对照原列表核对。
     */
    fun selectedSongs(songs: List<Song>, selection: Set<String>): List<Song> {
        if (selection.isEmpty()) return emptyList()
        return songs.filter { it.stableKey in selection }
    }
}
