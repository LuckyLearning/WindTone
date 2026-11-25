package com.luckylearning.windtone.data.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlinePlayHintTest {

    @Test
    fun missingScriptOnlyWhenRegistryAndReportAreBothEmpty() {
        assertTrue(OnlinePlayHint.missingScript(registryEmpty = true, loadedPlatforms = 0))
        assertFalse(OnlinePlayHint.missingScript(registryEmpty = true, loadedPlatforms = 6))
        assertFalse(OnlinePlayHint.missingScript(registryEmpty = false, loadedPlatforms = 0))
        assertFalse(OnlinePlayHint.missingScript(registryEmpty = false, loadedPlatforms = 6))
    }
}
