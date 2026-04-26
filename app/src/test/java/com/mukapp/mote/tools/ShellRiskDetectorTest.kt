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
        assertEquals("修改应用权限策略", ShellRiskDetector.detect("cmd appops set com.example.app CAMERA deny"))
        assertEquals("修改 Android 系统设置", ShellRiskDetector.detect("cmd settings put global http_proxy host:port"))
        assertEquals("修改 Android 内容提供者数据", ShellRiskDetector.detect("cmd content delete --uri content://example/items"))
        assertEquals("卸载系统应用更新", ShellRiskDetector.detect("pm uninstall-system-updates com.android.webview"))
        assertEquals("修改应用启用状态", ShellRiskDetector.detect("pm enable com.example.app"))
        assertEquals("卸载系统应用更新", ShellRiskDetector.detect("cmd package uninstall-system-updates com.android.webview"))
        assertEquals("修改应用启用状态", ShellRiskDetector.detect("cmd package enable com.example.app"))
    }

    @Test
    fun detectsCommonFileMutationUtilities() {
        assertEquals("批量删除查找结果", ShellRiskDetector.detect("find . -delete"))
        assertEquals("通过 find 执行高风险命令", ShellRiskDetector.detect("find . -exec rm -rf {} ;"))
        assertEquals("删除文件或目录", ShellRiskDetector.detect("unlink old.log"))
        assertEquals("删除敏感路径或通配文件", ShellRiskDetector.detect("/system/bin/unlink /sdcard/important.txt"))
        assertEquals("递归删除文件或目录", ShellRiskDetector.detect("printf '%s\\n' /sdcard/tmp | xargs rm -rf"))
        assertEquals("递归删除文件或目录", ShellRiskDetector.detect("printf '%s\\n' /sdcard/tmp | xargs -- rm -rf"))
        assertEquals("移动或覆盖文件", ShellRiskDetector.detect("printf '%s\\n' old.log | xargs mv target.log"))
        assertEquals("格式化文件系统", ShellRiskDetector.detect("printf '%s\\n' /dev/block/example | xargs mkfs.ext4"))
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
        assertEquals("低级块设备或敏感路径写入", ShellRiskDetector.detect("dd if=/dev/zero of=/dev/block/by-name/boot"))
        assertNull(ShellRiskDetector.detect("dd if=/dev/zero of=/dev/null bs=1 count=1"))
        assertNull(ShellRiskDetector.detect("dd if=input.bin conv=noerror | wc -c"))
        assertEquals("重定向覆盖或追加文件", ShellRiskDetector.detect("dd if=input.bin conv=noerror > output.bin"))
        assertEquals("删除敏感路径或通配文件", ShellRiskDetector.detect("rm /proc/sysrq-trigger"))
        assertEquals("删除敏感路径或通配文件", ShellRiskDetector.detect("rm /sys/fs/selinux/enforce"))
        assertEquals("修改敏感路径权限", ShellRiskDetector.detect("chmod 644 /dev/block/by-name/boot"))
        assertEquals("重新挂载文件系统为可写", ShellRiskDetector.detect("mount -o rw,remount /system"))
        assertEquals("重新挂载文件系统为可写", ShellRiskDetector.detect("mount -o remount,rw /system"))
        assertNull(ShellRiskDetector.detect("mount -o remount,ro /system"))
        assertNull(ShellRiskDetector.detect("mount --options ro,remount /vendor"))
    }

    @Test
    fun detectsFileWritesButIgnoresDiscardedOutput() {
        assertEquals("重定向覆盖或追加文件", ShellRiskDetector.detect("echo hello > result.txt"))
        assertEquals("通过 tee 写入文件", ShellRiskDetector.detect("printf hello | tee -a result.txt"))
        assertNull(ShellRiskDetector.detect("echo hello > /dev/null"))
        assertNull(ShellRiskDetector.detect("printf hello | tee /dev/null"))
        assertNull(ShellRiskDetector.detect("printf hello | tee -a /dev/null"))
        assertNull(ShellRiskDetector.detect("printf hello | tee -- -"))
        assertNull(ShellRiskDetector.detect("tee --help"))
    }

    @Test
    fun detectsNestedShellCommands() {
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("sh -c \"rm -rf /sdcard/tmp\""))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("sh -c ${'$'}'rm -rf /sdcard/tmp'"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("eval ${'$'}'r\\155 -rf /sdcard/tmp'"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("eval ${'$'}'\\u0072m -rf /sdcard/tmp'"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("env PATH=/system/bin rm -rf /sdcard/tmp"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("r\\m -rf /sdcard/tmp"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("echo $(rm -rf /sdcard/tmp)"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("echo `rm -rf /sdcard/tmp`"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("sudo -u root rm -rf /sdcard/tmp"))
        assertEquals("修改 Android 系统设置", ShellRiskDetector.detect("doas -u root settings put global http_proxy host:port"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("timeout -k 1s 5s rm -rf /sdcard/tmp"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("nice -n 10 rm -rf /sdcard/tmp"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("ionice -c3 rm -rf /sdcard/tmp"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("setsid rm -rf /sdcard/tmp"))
    }

    @Test
    fun detectsDangerousCommandsBehindShellControlSyntax() {
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("{ rm -rf /sdcard/tmp; }"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("if true; then rm -rf /sdcard/tmp; fi"))
        assertEquals("修改 Android 系统设置", ShellRiskDetector.detect("while false; do settings put global http_proxy host:port; done"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("! rm -rf /sdcard/tmp"))
    }

    @Test
    fun detectsAndroidCommandWrappers() {
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("su 0 rm -rf /sdcard/tmp"))
        assertEquals("清除应用数据", ShellRiskDetector.detect("su root pm clear com.example.app"))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("su 0 sh -c \"rm -rf /sdcard/tmp\""))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("su -c \"su -c 'rm -rf /sdcard/tmp'\""))
        assertEquals("递归删除敏感路径或通配文件", ShellRiskDetector.detect("su -mmc \"rm -rf /sdcard/tmp\""))
        assertEquals("通过 su 从输入流执行命令", ShellRiskDetector.detect("printf 'rm -rf /sdcard/tmp' | su"))
        assertEquals("通过 su 从输入流执行命令", ShellRiskDetector.detect("su < script.sh"))
        assertEquals("通过 su 从输入流执行命令", ShellRiskDetector.detect("su <<EOF\nid\nEOF"))
        assertEquals("通过 su 从输入流执行命令", ShellRiskDetector.detect("printf 'id' | env PATH=/system/bin su root"))
        assertEquals("通过 su 执行动态 Shell 命令", ShellRiskDetector.detect("export x=rm; su -c \"${'$'}x -rf /sdcard/tmp\""))
        assertEquals("通过 su 执行动态 Shell 命令", ShellRiskDetector.detect("su -c \"! ${'$'}x -rf /sdcard/tmp\""))
        assertNull(ShellRiskDetector.detect("su -c \"echo ${'$'}PATH\""))
        assertEquals("递归删除文件或目录", ShellRiskDetector.detect("run-as com.example.app rm -rf files"))
        assertEquals("重定向覆盖或追加文件", ShellRiskDetector.detect("run-as com.example.app sh -c \"echo test > files/result.txt\""))
        assertNull(ShellRiskDetector.detect("su"))
        assertNull(ShellRiskDetector.detect("su root"))
        assertNull(ShellRiskDetector.detect("printf 'id' | su -c id"))
        assertNull(ShellRiskDetector.detect("run-as com.example.app ls files"))
    }

    @Test
    fun ignoresXargsOptionValuesThatLookDangerous() {
        assertNull(ShellRiskDetector.detect("printf '%s\\n' a | xargs -I rm echo rm"))
        assertNull(ShellRiskDetector.detect("xargs -a rm echo {}"))
        assertNull(ShellRiskDetector.detect("xargs --arg-file=rm echo {}"))
    }

    @Test
    fun returnsNullForReadOnlyOrInspectionCommands() {
        assertNull(ShellRiskDetector.detect("ls -la /sdcard"))
        assertNull(ShellRiskDetector.detect("cat /sdcard/file.txt"))
        assertNull(ShellRiskDetector.detect("rm --help"))
        assertNotNull(ShellRiskDetector.detect("rm -rf /sdcard/tmp --help"))
    }
}
