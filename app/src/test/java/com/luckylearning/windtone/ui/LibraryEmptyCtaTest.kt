package com.luckylearning.windtone.ui

import com.luckylearning.windtone.data.ScanScope
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryEmptyCtaTest {

    @Test
    fun foldersScope_primaryIsImportFolder_regardlessOfGrant() {
        assertEquals(
            LibraryEmptyPrimaryAction.IMPORT_FOLDER,
            resolveLibraryEmptyCta(ScanScope.FOLDERS, granted = true),
        )
        assertEquals(
            LibraryEmptyPrimaryAction.IMPORT_FOLDER,
            resolveLibraryEmptyCta(ScanScope.FOLDERS, granted = false),
        )
    }

    @Test
    fun fullScope_withoutGrant_asksPermission() {
        assertEquals(
            LibraryEmptyPrimaryAction.GRANT_AND_SCAN,
            resolveLibraryEmptyCta(ScanScope.FULL, granted = false),
        )
    }

    @Test
    fun fullScope_withGrant_scansMusic() {
        assertEquals(
            LibraryEmptyPrimaryAction.FULL_SCAN,
            resolveLibraryEmptyCta(ScanScope.FULL, granted = true),
        )
    }

    @Test
    fun unsetScope_followsGrantFlag() {
        assertEquals(
            LibraryEmptyPrimaryAction.GRANT_AND_SCAN,
            resolveLibraryEmptyCta(ScanScope.UNSET, granted = false),
        )
        assertEquals(
            LibraryEmptyPrimaryAction.FULL_SCAN,
            resolveLibraryEmptyCta(ScanScope.UNSET, granted = true),
        )
    }
}
