package com.mukapp.mote.tools

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class BusyBoxManagerTest {

    @Test
    fun buildEnvironmentVariablesPrependsBusyBoxDirectories() {
        val environment = BusyBoxManager.BusyBoxEnvironment(
            shellDir = File("/data/user/0/com.mukapp.mote/files/shell"),
            binDir = File("/data/user/0/com.mukapp.mote/files/shell/bin"),
            tmpDir = File("/data/user/0/com.mukapp.mote/files/shell/tmp"),
            busyBox = File("/data/user/0/com.mukapp.mote/files/shell/busybox"),
            abi = "arm64-v8a",
            sourceId = "asset:busybox/arm64-v8a/busybox:1"
        )

        val variables = BusyBoxManager.buildEnvironmentVariables(
            environment = environment,
            inheritedPath = "/system/bin:/vendor/bin"
        )

        assertEquals(
            "/data/user/0/com.mukapp.mote/files/shell/bin:/data/user/0/com.mukapp.mote/files/shell:/system/bin:/vendor/bin",
            variables["PATH"]?.normalizedSeparators()
        )
        assertEquals(environment.busyBox.path.normalizedSeparators(), variables["BUSYBOX"]?.normalizedSeparators())
        assertEquals(environment.shellDir.path.normalizedSeparators(), variables["MOTE_SHELL_DIR"]?.normalizedSeparators())
        assertEquals(environment.tmpDir.path.normalizedSeparators(), variables["TMPDIR"]?.normalizedSeparators())
    }

    @Test
    fun buildEnvironmentVariablesDoesNotDuplicatePathSegments() {
        val environment = BusyBoxManager.BusyBoxEnvironment(
            shellDir = File("/data/user/0/com.mukapp.mote/files/shell"),
            binDir = File("/data/user/0/com.mukapp.mote/files/shell/bin"),
            tmpDir = File("/data/user/0/com.mukapp.mote/files/shell/tmp"),
            busyBox = File("/data/user/0/com.mukapp.mote/files/shell/busybox"),
            abi = "x86_64",
            sourceId = "native:/data/app/lib/x86_64/libbusybox.so"
        )

        val variables = BusyBoxManager.buildEnvironmentVariables(
            environment = environment,
            inheritedPath = "/data/user/0/com.mukapp.mote/files/shell/bin:/system/bin"
        )

        assertEquals(
            "/data/user/0/com.mukapp.mote/files/shell/bin:/data/user/0/com.mukapp.mote/files/shell:/system/bin",
            variables["PATH"]?.normalizedSeparators()
        )
    }

    @Test
    fun assetCandidatesMatchAndroidAbiNames() {
        assertEquals(
            listOf("busybox/arm64-v8a/busybox", "busybox/aarch64/busybox"),
            BusyBoxManager.assetCandidatesForAbi("arm64-v8a")
        )
        assertEquals(
            listOf("busybox/armeabi-v7a/busybox", "busybox/armv7/busybox", "busybox/arm/busybox"),
            BusyBoxManager.assetCandidatesForAbi("armeabi-v7a")
        )
    }

    private fun String.normalizedSeparators(): String = replace('\\', '/')
}
