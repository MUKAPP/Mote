package com.mukapp.mote.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShellRiskDetectorTest {

    @Test
    fun detectsDeletionThroughExecutablePath() {
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("/system/bin/rm -rf /sdcard/tmp"))
        assertEquals("删除文件或目录", ShellRiskDetector.detect("./rm old.log"))
    }

    @Test
    fun ignoresDangerousTextInQuotesCommentsAndHereDocuments() {
        assertNull(ShellRiskDetector.detect("echo \"rm -rf /sdcard/tmp\""))
        assertNull(ShellRiskDetector.detect("printf 'mv a b'"))
        assertNull(ShellRiskDetector.detect("ls # rm -rf /sdcard/tmp"))
        assertNull(ShellRiskDetector.detect("cat <<EOF\nrm -rf /sdcard/tmp\nEOF"))
    }

    @Test
    fun detectsAndroidDataAndSettingsMutations() {
        assertEquals("清除应用数据", ShellRiskDetector.detect("pm clear com.example.app"))
        assertEquals("禁用或隐藏应用包", ShellRiskDetector.detect("pm disable-user com.example.app"))
        assertEquals("卸载应用包", ShellRiskDetector.detect("cmd package uninstall com.example.app"))
        assertEquals("清除应用数据", ShellRiskDetector.detect("cmd package clear --user 0 com.example.app"))
        assertEquals("修改 Android 系统设置", ShellRiskDetector.detect("settings put global http_proxy host:port"))
        assertEquals("修改 Android 系统设置", ShellRiskDetector.detect("settings --user 0 delete global http_proxy"))
        assertEquals("修改 Android 内容提供者数据", ShellRiskDetector.detect("content delete --uri content://example/items"))
    }

    @Test
    fun detectsCommonFileMutationUtilities() {
        assertEquals("批量删除查找结果", ShellRiskDetector.detect("find . -delete"))
        assertEquals("通过 find 执行高风险命令", ShellRiskDetector.detect("find . -exec rm -rf {} ;"))
        assertEquals("清理未跟踪文件", ShellRiskDetector.detect("git clean -fdx"))
        assertEquals("清理未跟踪文件", ShellRiskDetector.detect("git -C repo clean -fdx"))
        assertEquals("重置工作区并丢弃更改", ShellRiskDetector.detect("git reset --hard"))
        assertEquals("原地修改文件内容", ShellRiskDetector.detect("sed -i 's/a/b/g' file.txt"))
        assertEquals("原地修改文件内容", ShellRiskDetector.detect("sed -Ei 's/a/b/g' file.txt"))
        assertEquals("原地修改文件内容", ShellRiskDetector.detect("perl -pi -e 's/a/b/g' file.txt"))
        assertEquals("同步时删除或移动源文件", ShellRiskDetector.detect("rsync --delete source/ target/"))
    }

    @Test
    fun detectsPermissionAndFilesystemMutations() {
        assertEquals("递归修改文件权限", ShellRiskDetector.detect("chmod --recursive 777 /sdcard/tmp"))
        assertEquals("修改敏感路径所有者", ShellRiskDetector.detect("chown shell:shell /data/local/tmp/file"))
        assertEquals("格式化文件系统", ShellRiskDetector.detect("mkfs.ext4 /dev/block/example"))
        assertEquals("低级块设备或文件写入", ShellRiskDetector.detect("dd if=/dev/zero of=/sdcard/file.img"))
        assertEquals("重新挂载文件系统为可写", ShellRiskDetector.detect("mount -o rw,remount /system"))
    }

    @Test
    fun detectsFileWritesButIgnoresDiscardedOutput() {
        assertEquals("重定向覆盖或追加文件", ShellRiskDetector.detect("echo hello > result.txt"))
        assertEquals("通过 tee 写入文件", ShellRiskDetector.detect("printf hello | tee -a result.txt"))
        assertNull(ShellRiskDetector.detect("echo hello > /dev/null"))
        assertNull(ShellRiskDetector.detect("tee --help"))
    }

    @Test
    fun detectsNestedShellCommands() {
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("sh -c \"rm -rf /sdcard/tmp\""))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("env PATH=/system/bin rm -rf /sdcard/tmp"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("r\\m -rf /sdcard/tmp"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("echo $(rm -rf /sdcard/tmp)"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("echo `rm -rf /sdcard/tmp`"))
    }

    @Test
    fun returnsNullForReadOnlyOrInspectionCommands() {
        assertNull(ShellRiskDetector.detect("ls -la /sdcard"))
        assertNull(ShellRiskDetector.detect("cat /sdcard/file.txt"))
        assertNull(ShellRiskDetector.detect("rm --help"))
        assertNotNull(ShellRiskDetector.detect("rm -rf /sdcard/tmp --help"))
    }
}
