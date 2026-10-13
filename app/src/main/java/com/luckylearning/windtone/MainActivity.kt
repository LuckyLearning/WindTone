package com.luckylearning.windtone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luckylearning.windtone.ui.MainScreen
import com.luckylearning.windtone.ui.MusicViewModel
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MusicViewModel = viewModel()
            val seed by vm.themeSeed.collectAsStateWithLifecycle()
            MusicPlayerTheme(
                seedColor = if (vm.dynamicThemeEnabled()) seed else null,
            ) {
                MainScreen(vm)
            }
        }
    }
}
