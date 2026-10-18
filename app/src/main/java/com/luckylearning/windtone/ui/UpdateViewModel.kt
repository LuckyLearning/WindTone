package com.luckylearning.windtone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.luckylearning.windtone.MusicApp
import com.luckylearning.windtone.data.UpdateActions
import com.luckylearning.windtone.state.UpdateUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * 更新域 ViewModel：界面与 [com.luckylearning.windtone.data.UpdateRepository] 之间的一层。
 *
 * 与播放域同样的做法——状态与流程都在仓库里，动作面用 `by` 整块委托。
 */
class UpdateViewModel(app: Application) :
    AndroidViewModel(app),
    UpdateActions by (app as MusicApp).container.updates {

    private val repo = (app as MusicApp).container.updates
    val update: StateFlow<UpdateUiState> = repo.update

    /** 回到前台时续上「授权后装包」那一步（v1.1.23，见 UpdateRepository.onAppForeground） */
    fun onAppForeground() = repo.onAppForeground()
}
