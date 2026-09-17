package com.mukapp.mote.tools

import java.util.ArrayDeque
import java.util.Locale

internal object ShellRiskDetector {
    private const val MaxNestedDetectionDepth = 4

    private val shellInterpreters = setOf("sh", "bash", "mksh", "ash", "dash", "zsh", "ksh")
    private val packageManagers = setOf("apt", "apt-get", "yum", "dnf", "pacman", "apk")
    private val sudoOptionsWithValue = setOf(
        "--auth-type",
        "--close-from",
        "--chdir",
        "--group",
        "--host",
        "--login-class",
        "--prompt",
        "--role",
        "--type",
        "--command-timeout",
        "--other-user",
        "--user"
    )
    private val sudoShortOptionsWithValue = setOf('a', 'C', 'c', 'D', 'g', 'h', 'p', 'R', 'r', 'T', 't', 'U', 'u')
    private val doasOptionsWithValue = setOf("-C")
    private val doasShortOptionsWithValue = setOf('C', 'u')
    private val sensitivePathPrefixes = setOf(
        "/sdcard",
        "/storage",
        "/data",
        "/system",
        "/vendor",
        "/product",
        "/odm",
        "/mnt",
        "/apex",
        "/dev/block",
        "/sys/fs/selinux"
    )
    private val shellControlPrefixes = setOf(
        "!",
        "{",
        "}",
        "if",
        "then",
        "else",
        "elif",
        "fi",
        "while",
        "until",
        "for",
        "do",
        "done",
        "case",
        "esac",
        "in"
    )
    private val dangerousXargsCommands = setOf(
        "rm",
        "rmdir",
        "unlink",
        "mv",
        "cp",
        "install",
        "ln",
        "dd",
        "truncate",
        "chmod",
        "chown",
        "pm",
        "cmd",
        "settings",
        "content",
        "appops",
        "setprop",
        "mount",
        "find",
        "git",
        "sed",
        "perl",
        "rsync",
        "tee",
        "curl",
        "wget"
    )

    /** curl/wget 常见布尔短选项，用于在短选项串（如 -sSo、-qO）中定位输出选项，避免把 -d 等取值选项的附着值误判为输出。 */
    private val curlBooleanShortOptions = setOf('s', 'S', 'L', 'f', 'v', 'k', 'g', 'i', '4', '6')
    private val wgetBooleanShortOptions = setOf('q', 'v', 'c', 'b', 'N', 'S', '4', '6')

    fun detect(command: String): String? {
        return detectCommandString(command, nestedDepth = 0)
    }

    private fun detectCommandString(command: String, nestedDepth: Int): String? {
        if (nestedDepth > MaxNestedDetectionDepth) {
            return null
        }
        val commandWithoutHereDocuments = stripHereDocuments(command)
        extractCommandSubstitutions(commandWithoutHereDocuments).forEach { nestedCommand ->
            detectCommandString(nestedCommand, nestedDepth + 1)?.let { risk ->
                return risk
            }
        }
        return detectTokens(tokenize(commandWithoutHereDocuments), nestedDepth)
    }

    private fun detectTokens(tokens: List<Token>, nestedDepth: Int): String? {
        var index = 0
        var hasPipeBeforeSegment = false
        while (index < tokens.size) {
            while (index < tokens.size && tokens[index].type == TokenType.SEPARATOR) {
                val separator = (tokens[index] as Token.Separator).text
                hasPipeBeforeSegment = hasPipeBeforeSegment || separator == "|" || separator == "|&"
                index += 1
            }
            if (index >= tokens.size) {
                break
            }

            val segmentStart = index
            while (index < tokens.size && tokens[index].type != TokenType.SEPARATOR) {
                index += 1
            }

            detectSegment(tokens.subList(segmentStart, index), nestedDepth, hasPipeBeforeSegment)?.let { risk ->
                return risk
            }
            hasPipeBeforeSegment = false
        }
        return null
    }

    private fun detectSegment(tokens: List<Token>, nestedDepth: Int, hasPipeInput: Boolean): String? {
        val words = mutableListOf<String>()
        var redirectionRisk: String? = null
        var hasInputRedirect = false
        var index = 0

        while (index < tokens.size) {
            when (val token = tokens[index]) {
                is Token.Word -> {
                    words += token.text
                    index += 1
                }
                is Token.Redirect -> {
                    val target = tokens.getOrNull(index + 1) as? Token.Word
                    if (isInputRedirect(token.text)) {
                        hasInputRedirect = true
                    }
                    if (isOutputRedirect(token.text) && target != null && isFileRedirectTarget(target.text)) {
                        redirectionRisk = redirectionRisk ?: "重定向覆盖或追加文件"
                    }
                    index += if (target == null) 1 else 2
                }
                is Token.Separator -> index += 1
            }
        }

        return detectWords(words, nestedDepth, hasExternalStdin = hasPipeInput || hasInputRedirect) ?: redirectionRisk
    }

    private fun detectWords(rawWords: List<String>, nestedDepth: Int, hasExternalStdin: Boolean = false): String? {
        if (nestedDepth > MaxNestedDetectionDepth) {
            return null
        }

        val words = rawWords
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        var commandIndex = 0
        while (commandIndex < words.size && isVariableAssignment(words[commandIndex])) {
            commandIndex += 1
        }
        if (commandIndex >= words.size) {
            return null
        }

        val command = normalizeCommandName(words[commandIndex])
        if (command.isEmpty()) {
            return null
        }

        // 命令名经变量或命令替换动态决定时无法静态审计；su -c 字符串内已有同类判定，这里覆盖顶层与包装器路径。
        if (command.startsWith('$') || command.startsWith('`')) {
            return "执行动态生成的 Shell 命令"
        }

        val args = words.drop(commandIndex + 1)
        if (isHelpOrVersionOnly(args)) {
            return null
        }

        return when {
            command in shellControlPrefixes -> detectWords(args, nestedDepth, hasExternalStdin)
            command == "env" -> detectEnv(args, nestedDepth, hasExternalStdin)
            command == "command" -> detectCommandBuiltin(args, nestedDepth, hasExternalStdin)
            command == "exec" -> detectExec(args, nestedDepth, hasExternalStdin)
            command == "nohup" || command == "builtin" -> detectWords(args, nestedDepth + 1, hasExternalStdin)
            command == "sudo" -> detectSudo(args, nestedDepth, hasExternalStdin)
            command == "doas" -> detectDoas(args, nestedDepth, hasExternalStdin)
            command == "timeout" -> detectTimeout(args, nestedDepth, hasExternalStdin)
            command == "nice" -> detectNice(args, nestedDepth, hasExternalStdin)
            command == "ionice" -> detectIonice(args, nestedDepth, hasExternalStdin)
            command == "setsid" -> detectSetsid(args, nestedDepth, hasExternalStdin)
            command == "time" -> detectWords(args.dropWhile { it.startsWith("-") }, nestedDepth + 1, hasExternalStdin)
            command == "toybox" || command == "busybox" -> detectWords(args, nestedDepth + 1, hasExternalStdin)
            command == "eval" -> detectCommandString(args.joinToString(" "), nestedDepth + 1)
            command in shellInterpreters -> detectShellScriptArgument(args, nestedDepth)
            command == "su" -> detectSu(args, nestedDepth, hasExternalStdin)
            command == "run-as" -> detectRunAs(args, nestedDepth)
            command == "xargs" -> detectXargs(args, nestedDepth)
            command == "find" -> detectFind(args, nestedDepth)
            command == "rm" -> detectRm(args)
            command == "rmdir" -> detectCommandWithTargets(args, "删除目录")
            command == "unlink" -> detectSingleDelete(args)
            command == "mv" -> detectMove(args)
            command == "cp" -> detectCopyOverwrite(args, "复制并覆盖文件")
            command == "install" -> detectCopyOverwrite(args, "安装并覆盖文件")
            command == "ln" -> detectLink(args)
            command == "curl" -> detectCurl(args)
            command == "wget" -> detectWget(args)
            command == "dd" -> detectDd(args)
            command == "truncate" -> detectCommandWithTargets(args, "截断文件")
            command == "chmod" -> detectPermissionChange(args, "递归修改文件权限", "修改敏感路径权限")
            command == "chown" -> detectPermissionChange(args, "递归修改文件所有者", "修改敏感路径所有者")
            command == "pm" -> detectPm(args)
            command == "cmd" -> detectCmd(args)
            command == "settings" -> detectSettings(args)
            command == "content" -> detectContent(args)
            command == "appops" -> detectAppOps(args)
            command == "setprop" -> detectCommandWithTargets(args, "修改 Android 系统属性")
            command == "mount" -> detectMount(args)
            command == "git" -> detectGit(args)
            command == "sed" -> detectSed(args)
            command == "perl" -> detectPerl(args)
            command == "rsync" -> detectRsync(args)
            command == "tee" -> detectTee(args)
            command in packageManagers -> detectPackageManager(args)
            command == "mkfs" || command.startsWith("mkfs.") -> detectCommandWithTargets(args, "格式化文件系统")
            else -> null
        }
    }

    private fun detectEnv(args: List<String>, nestedDepth: Int, hasExternalStdin: Boolean): String? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            index += when {
                isVariableAssignment(arg) -> 1
                arg == "--" -> return detectWords(args.drop(index + 1), nestedDepth + 1, hasExternalStdin)
                arg == "-S" && index + 1 < args.size -> {
                    return detectCommandString(args.drop(index + 1).joinToString(" "), nestedDepth + 1)
                }

                arg == "-u" || arg == "--unset" || arg == "-C" || arg == "--chdir" -> 2
                arg == "-i" || arg == "-" || arg == "--ignore-environment" -> 1
                arg.startsWith("--unset=") || arg.startsWith("--chdir=") -> 1
                arg.startsWith("-") -> 1
                else -> return detectWords(args.drop(index), nestedDepth + 1, hasExternalStdin)
            }
        }
        return null
    }

    private fun detectCommandBuiltin(args: List<String>, nestedDepth: Int, hasExternalStdin: Boolean): String? {
        if (args.any { it == "-v" || it == "-V" }) {
            return null
        }
        return detectWords(args.filterNot { it == "-p" || it == "--" }, nestedDepth + 1, hasExternalStdin)
    }

    private fun detectExec(args: List<String>, nestedDepth: Int, hasExternalStdin: Boolean): String? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            index += when {
                arg == "--" -> return detectWords(args.drop(index + 1), nestedDepth + 1, hasExternalStdin)
                arg == "-a" -> 2
                arg.startsWith("-") -> 1
                else -> return detectWords(args.drop(index), nestedDepth + 1, hasExternalStdin)
            }
        }
        return null
    }

    private fun detectSudo(args: List<String>, nestedDepth: Int, hasExternalStdin: Boolean): String? {
        return detectWords(skipWrapperOptions(args, sudoOptionsWithValue, sudoShortOptionsWithValue), nestedDepth + 1, hasExternalStdin)
    }

    private fun detectDoas(args: List<String>, nestedDepth: Int, hasExternalStdin: Boolean): String? {
        return detectWords(skipWrapperOptions(args, doasOptionsWithValue, doasShortOptionsWithValue), nestedDepth + 1, hasExternalStdin)
    }

    private fun detectTimeout(args: List<String>, nestedDepth: Int, hasExternalStdin: Boolean): String? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when {
                arg == "--" -> {
                    index += 1
                    break
                }
                arg == "-s" || arg == "--signal" || arg == "-k" || arg == "--kill-after" -> index += 2
                arg.startsWith("--signal=") || arg.startsWith("--kill-after=") -> index += 1
                arg == "-v" || arg == "--verbose" || arg == "--foreground" || arg == "--preserve-status" -> index += 1
                arg.startsWith("-") && !isLikelyDuration(arg) -> index += 1
                else -> break
            }
        }

        if (index < args.size && isLikelyDuration(args[index])) {
            index += 1
        }
        return detectWords(args.drop(index), nestedDepth + 1, hasExternalStdin)
    }

    private fun detectNice(args: List<String>, nestedDepth: Int, hasExternalStdin: Boolean): String? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            index += when {
                arg == "--" -> return detectWords(args.drop(index + 1), nestedDepth + 1, hasExternalStdin)
                arg == "-n" || arg == "--adjustment" -> 2
                arg.startsWith("--adjustment=") -> 1
                isNicePriorityShortcut(arg) -> 1
                arg.startsWith("-") -> 1
                else -> return detectWords(args.drop(index), nestedDepth + 1, hasExternalStdin)
            }
        }
        return null
    }

    private fun detectIonice(args: List<String>, nestedDepth: Int, hasExternalStdin: Boolean): String? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            index += when {
                arg == "--" -> return detectWords(args.drop(index + 1), nestedDepth + 1, hasExternalStdin)
                arg == "-c" || arg == "--class" || arg == "-n" || arg == "--classdata" || arg == "-p" || arg == "--pid" -> 2
                arg.startsWith("--class=") || arg.startsWith("--classdata=") || arg.startsWith("--pid=") -> 1
                isIoniceAttachedOption(arg) -> 1
                arg.startsWith("-") -> 1
                else -> return detectWords(args.drop(index), nestedDepth + 1, hasExternalStdin)
            }
        }
        return null
    }

    private fun detectSetsid(args: List<String>, nestedDepth: Int, hasExternalStdin: Boolean): String? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when {
                arg == "--" -> return detectWords(args.drop(index + 1), nestedDepth + 1, hasExternalStdin)
                arg.startsWith("-") -> index += 1
                else -> return detectWords(args.drop(index), nestedDepth + 1, hasExternalStdin)
            }
        }
        return null
    }

    private fun detectShellScriptArgument(args: List<String>, nestedDepth: Int): String? {
        args.forEachIndexed { index, arg ->
            val isCommandOption = isSuCommandOption(arg)
            if (isCommandOption && index + 1 < args.size) {
                return detectCommandString(args[index + 1], nestedDepth + 1)
            }
        }
        return null
    }

    private fun suCommandStringArgument(args: List<String>): String? {
        args.forEachIndexed { index, arg ->
            val isCommandOption = isSuCommandOption(arg)
            if (isCommandOption && index + 1 < args.size) {
                return args[index + 1]
            }
        }
        return null
    }

    private fun isSuCommandOption(arg: String): Boolean {
        return arg == "-c" || (arg.startsWith("-") && !arg.startsWith("--") && arg.drop(1).contains('c'))
    }

    private fun detectSu(args: List<String>, nestedDepth: Int, hasExternalStdin: Boolean): String? {
        val commandString = suCommandStringArgument(args)
        // 动态命令先于嵌套解析判定，保持 su 专属提示语，不退化为通用的动态命令提示。
        if (commandString?.let { hasDynamicCommandPosition(it) } == true) {
            return "通过 su 执行动态 Shell 命令"
        }
        commandString?.let { detectCommandString(it, nestedDepth + 1) }?.let { risk -> return risk }

        if (commandString != null) {
            return null
        }

        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when {
                arg == "--" -> {
                    index += 1
                    break
                }
                isSuCommandOption(arg) || arg == "-s" || arg == "--shell" || arg == "-g" || arg == "-G" -> index += 2
                arg.startsWith("--shell=") -> index += 1
                arg.startsWith("-") -> index += 1
                else -> break
            }
        }

        if (index >= args.size) {
            return if (hasExternalStdin) "通过 su 从输入流执行命令" else null
        }

        val commandArgs = args.drop(index)
        if (hasExternalStdin && commandArgs.size == 1 && isLikelySuUser(commandArgs.first())) {
            return "通过 su 从输入流执行命令"
        }
        val executableArgs = if (commandArgs.size >= 2 && isLikelySuUser(commandArgs.first())) {
            commandArgs.drop(1)
        } else {
            commandArgs
        }
        return detectWords(executableArgs, nestedDepth + 1, hasExternalStdin) ?: if (hasExternalStdin) {
            "通过 su 从输入流执行命令"
        } else {
            null
        }
    }

    private fun detectRunAs(args: List<String>, nestedDepth: Int): String? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            index += when {
                arg == "--user" -> 2
                arg.startsWith("--user=") -> 1
                arg.startsWith("-") -> 1
                else -> break
            }
        }
        return detectWords(args.drop(index + 1), nestedDepth + 1)
    }

    private fun detectXargs(args: List<String>, nestedDepth: Int): String? {
        val commandArgs = parseXargsCommandArgs(args) ?: return null
        val command = normalizeCommandName(commandArgs.firstOrNull().orEmpty())
        if (command in dangerousXargsCommands || command in shellInterpreters || command == "mkfs" || command.startsWith("mkfs.")) {
            detectWords(commandArgs, nestedDepth + 1)?.let { risk -> return risk }
            return detectXargsImplicitTargetRisk(command, commandArgs.drop(1))
        }
        return null
    }

    private fun detectFind(args: List<String>, nestedDepth: Int): String? {
        if (args.any { it == "-delete" }) {
            return "批量删除查找结果"
        }

        args.forEachIndexed { index, arg ->
            if ((arg == "-exec" || arg == "-execdir") && index + 1 < args.size) {
                val endIndex = args.indexOfFirstFrom(index + 1) { it == ";" || it == "+" }
                    .takeIf { it >= 0 }
                    ?: args.size
                val execWords = args.subList(index + 1, endIndex)
                detectWords(execWords, nestedDepth + 1)?.let {
                    return "通过 find 执行高风险命令"
                }
            }
        }
        return null
    }

    private fun detectRm(args: List<String>): String? {
        val targets = commandTargets(args)
        if (targets.isEmpty()) {
            return null
        }

        val recursive = args.any { isRecursiveOption(it) || isShortOptionEnabled(it, 'r') || isShortOptionEnabled(it, 'R') }
        val sensitiveOrWildcard = targets.any { isSensitiveOrWildcardTarget(it) }
        return when {
            recursive && sensitiveOrWildcard -> "递归删除敏感路径或通配文件"
            recursive -> "递归删除文件或目录"
            sensitiveOrWildcard -> "删除敏感路径或通配文件"
            else -> "删除文件或目录"
        }
    }

    private fun detectSingleDelete(args: List<String>): String? {
        val targets = commandTargets(args)
        if (targets.isEmpty()) {
            return null
        }
        return if (targets.any { isSensitiveOrWildcardTarget(it) }) "删除敏感路径或通配文件" else "删除文件或目录"
    }

    private fun detectMove(args: List<String>): String? {
        val targets = commandTargets(args)
        return if (targets.size >= 2) "移动或覆盖文件" else null
    }

    private fun detectCopyOverwrite(args: List<String>, risk: String): String? {
        return if (commandTargets(args).size >= 2) risk else null
    }

    private fun detectLink(args: List<String>): String? {
        val force = args.any { it == "--force" || isShortOptionEnabled(it, 'f') }
        return if (force && commandTargets(args).isNotEmpty()) "强制创建链接并覆盖已有文件" else null
    }

    private fun detectCurl(args: List<String>): String? {
        args.forEachIndexed { index, arg ->
            when {
                arg == "--remote-name" || arg == "--remote-name-all" -> return "下载内容写入文件"
                arg.startsWith("--output=") -> {
                    if (isFileRedirectTarget(arg.substringAfter('='))) {
                        return "下载内容写入文件"
                    }
                }
                arg == "--output" -> {
                    val target = args.getOrNull(index + 1)
                    if (target != null && isFileRedirectTarget(target)) {
                        return "下载内容写入文件"
                    }
                }
                else -> {
                    if (shortOptionAttachedValue(arg, 'O', curlBooleanShortOptions) != null) {
                        return "下载内容写入文件"
                    }
                    val attachedOutput = shortOptionAttachedValue(arg, 'o', curlBooleanShortOptions)
                    if (attachedOutput != null) {
                        val target = attachedOutput.ifEmpty { args.getOrNull(index + 1).orEmpty() }
                        if (isFileRedirectTarget(target)) {
                            return "下载内容写入文件"
                        }
                    }
                }
            }
        }
        return null
    }

    private fun detectWget(args: List<String>): String? {
        args.forEachIndexed { index, arg ->
            when {
                arg.startsWith("--output-document=") -> {
                    if (isFileRedirectTarget(arg.substringAfter('='))) {
                        return "下载内容写入文件"
                    }
                }
                arg == "--output-document" -> {
                    val target = args.getOrNull(index + 1)
                    if (target != null && isFileRedirectTarget(target)) {
                        return "下载内容写入文件"
                    }
                }
                else -> {
                    val attachedOutput = shortOptionAttachedValue(arg, 'O', wgetBooleanShortOptions)
                    if (attachedOutput != null) {
                        val target = attachedOutput.ifEmpty { args.getOrNull(index + 1).orEmpty() }
                        if (isFileRedirectTarget(target)) {
                            return "下载内容写入文件"
                        }
                    }
                }
            }
        }
        return null
    }

    /** 在「布尔短选项前缀 + 取值选项」形式的短选项串中定位 [option]，返回其附着值；空串表示值在下一个参数。 */
    private fun shortOptionAttachedValue(arg: String, option: Char, booleanPrefixOptions: Set<Char>): String? {
        if (arg.length < 2 || arg[0] != '-' || arg.startsWith("--")) {
            return null
        }
        var cursor = 1
        while (cursor < arg.length && arg[cursor] in booleanPrefixOptions) {
            cursor += 1
        }
        if (cursor >= arg.length || arg[cursor] != option) {
            return null
        }
        return arg.substring(cursor + 1)
    }

    private fun detectDd(args: List<String>): String? {
        val outputTarget = args.firstNotNullOfOrNull { arg ->
            val normalized = arg.lowercase(Locale.ROOT)
            if (normalized.startsWith("of=")) arg.substringAfter('=') else null
        }
        return when {
            outputTarget != null && isCriticalSystemPath(outputTarget) -> "低级块设备或敏感路径写入"
            outputTarget != null && isFileRedirectTarget(outputTarget) -> "低级块设备或文件写入"
            else -> null
        }
    }

    private fun detectPermissionChange(args: List<String>, recursiveRisk: String, sensitiveRisk: String): String? {
        if (args.any { isRecursiveOption(it) || isShortOptionEnabled(it, 'r') || isShortOptionEnabled(it, 'R') }) {
            return recursiveRisk
        }

        val operands = commandTargets(args)
        val targets = operands.drop(1)
        return if (targets.any { isSensitivePath(it) }) sensitiveRisk else null
    }

    private fun detectPm(args: List<String>): String? {
        return when (commandArgumentsSkippingOptionValues(args, setOf("--user")).firstOrNull()?.lowercase(Locale.ROOT)) {
            "install", "install-existing", "install-create", "install-write", "install-commit" -> "安装或替换应用包"
            "uninstall-system-updates" -> "卸载系统应用更新"
            "uninstall" -> "卸载应用包"
            "clear" -> "清除应用数据"
            "disable", "disable-user", "hide", "suspend" -> "禁用或隐藏应用包"
            "enable", "unhide", "unsuspend", "default-state" -> "修改应用启用状态"
            "grant", "revoke", "reset-permissions" -> "修改应用权限"
            else -> null
        }
    }

    private fun detectCmd(args: List<String>): String? {
        val commandArgs = commandArgumentsSkippingOptionValues(args, setOf("--user"))
        return when (commandArgs.firstOrNull()?.lowercase(Locale.ROOT)) {
            "package" -> detectPm(commandArgs.drop(1))
            "appops" -> detectAppOps(commandArgs.drop(1))
            "settings" -> detectSettings(commandArgs.drop(1))
            "content" -> detectContent(commandArgs.drop(1))
            else -> null
        }
    }

    private fun detectSettings(args: List<String>): String? {
        return when (commandArgumentsSkippingOptionValues(args, setOf("--user")).firstOrNull()?.lowercase(Locale.ROOT)) {
            "put", "delete", "reset" -> "修改 Android 系统设置"
            else -> null
        }
    }

    private fun detectContent(args: List<String>): String? {
        return when (commandArgumentsSkippingOptionValues(args, setOf("--user")).firstOrNull()?.lowercase(Locale.ROOT)) {
            "delete", "update", "insert" -> "修改 Android 内容提供者数据"
            else -> null
        }
    }

    private fun detectAppOps(args: List<String>): String? {
        return when (commandArgumentsSkippingOptionValues(args, setOf("--user")).firstOrNull()?.lowercase(Locale.ROOT)) {
            "set", "reset" -> "修改应用权限策略"
            else -> null
        }
    }

    private fun detectMount(args: List<String>): String? {
        val mountOptions = extractMountOptions(args)
        val hasRw = mountOptions.any { it == "rw" }
        val remountsWritable = hasRw || (mountOptions.any { it == "remount" } && !mountOptions.any { it == "ro" })
        return if (remountsWritable) "重新挂载文件系统为可写" else null
    }

    private fun detectGit(args: List<String>): String? {
        val commandArgs = parseGitCommandArgs(args)
        return when (commandArgs.firstOrNull()) {
            "clean" -> {
                val cleanArgs = commandArgs.drop(1)
                val dryRun = cleanArgs.any { it == "-n" || it == "--dry-run" }
                val forced = cleanArgs.any { isShortOptionEnabled(it, 'f') || it == "--force" }
                if (!dryRun && forced) "清理未跟踪文件" else null
            }
            "reset" -> if (commandArgs.any { it == "--hard" }) "重置工作区并丢弃更改" else null
            else -> null
        }
    }

    private fun detectSed(args: List<String>): String? {
        return if (args.any { it == "-i" || it.startsWith("-i.") || it.startsWith("-i") || it == "--in-place" || it.startsWith("--in-place=") || isShortOptionEnabled(it, 'i') }) {
            "原地修改文件内容"
        } else {
            null
        }
    }

    private fun detectPerl(args: List<String>): String? {
        return if (args.any { it == "-i" || it.startsWith("-i") || isShortOptionEnabled(it, 'i') }) {
            "原地修改文件内容"
        } else {
            null
        }
    }

    private fun detectRsync(args: List<String>): String? {
        return if (args.any { it == "--delete" || it.startsWith("--delete-") || it == "--remove-source-files" }) {
            "同步时删除或移动源文件"
        } else {
            null
        }
    }

    private fun detectTee(args: List<String>): String? {
        val targets = commandTargets(args).filter { isFileRedirectTarget(it) }
        return if (targets.isNotEmpty()) "通过 tee 写入文件" else null
    }

    private fun detectPackageManager(args: List<String>): String? {
        val actions = commandTargets(args).map { it.lowercase(Locale.ROOT) }
        return if (actions.any { it == "remove" || it == "purge" || it == "erase" || it == "del" }) {
            "卸载系统软件包"
        } else {
            null
        }
    }

    private fun detectCommandWithTargets(args: List<String>, risk: String): String? {
        return if (commandTargets(args).isNotEmpty()) risk else null
    }

    private fun commandTargets(args: List<String>): List<String> {
        val targets = mutableListOf<String>()
        var afterDoubleDash = false
        args.forEach { arg ->
            when {
                afterDoubleDash -> targets += arg
                arg == "--" -> afterDoubleDash = true
                arg.startsWith("-") -> Unit
                else -> targets += arg
            }
        }
        return targets
    }

    private fun commandArgumentsSkippingOptionValues(args: List<String>, optionsWithValue: Set<String>): List<String> {
        val result = mutableListOf<String>()
        var afterDoubleDash = false
        var skipNext = false
        args.forEach { arg ->
            when {
                skipNext -> skipNext = false
                afterDoubleDash -> result += arg
                arg == "--" -> afterDoubleDash = true
                arg in optionsWithValue -> skipNext = true
                optionsWithValue.any { option -> arg.startsWith("$option=") } -> Unit
                arg.startsWith("-") -> Unit
                else -> result += arg
            }
        }
        return result
    }

    private fun skipWrapperOptions(
        args: List<String>,
        longOptionsWithValue: Set<String>,
        shortOptionsWithValue: Set<Char>
    ): List<String> {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            index += when {
                arg == "--" -> return args.drop(index + 1)
                arg in longOptionsWithValue -> 2
                longOptionsWithValue.any { option -> arg.startsWith("$option=") } -> 1
                isShortOptionWithSeparatedValue(arg, shortOptionsWithValue) -> 2
                isShortOptionWithAttachedValue(arg, shortOptionsWithValue) -> 1
                arg.startsWith("-") -> 1
                else -> return args.drop(index)
            }
        }
        return emptyList()
    }

    private fun isShortOptionWithSeparatedValue(arg: String, optionsWithValue: Set<Char>): Boolean {
        return arg.length == 2 && arg[0] == '-' && arg[1] in optionsWithValue
    }

    private fun isShortOptionWithAttachedValue(arg: String, optionsWithValue: Set<Char>): Boolean {
        return arg.length > 2 && arg[0] == '-' && !arg.startsWith("--") && arg[1] in optionsWithValue
    }

    private fun extractMountOptions(args: List<String>): Set<String> {
        val options = mutableSetOf<String>()
        var index = 0
        while (index < args.size) {
            val arg = args[index].lowercase(Locale.ROOT)
            when {
                arg == "-o" || arg == "--options" -> {
                    addMountOptions(args.getOrNull(index + 1), options)
                    index += 2
                }
                arg.startsWith("-o") && arg.length > 2 -> {
                    addMountOptions(arg.drop(2), options)
                    index += 1
                }
                arg.startsWith("--options=") -> {
                    addMountOptions(arg.substringAfter('='), options)
                    index += 1
                }
                else -> index += 1
            }
        }
        return options
    }

    private fun addMountOptions(raw: String?, options: MutableSet<String>) {
        raw.orEmpty()
            .lowercase(Locale.ROOT)
            .split(',')
            .map { it.substringBefore('=').trim() }
            .filterTo(options) { it.isNotEmpty() }
    }

    private fun parseGitCommandArgs(args: List<String>): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        var afterDoubleDash = false
        while (index < args.size) {
            val arg = args[index]
            when {
                afterDoubleDash -> {
                    result += arg.lowercase(Locale.ROOT)
                    index += 1
                }
                arg == "--" -> {
                    afterDoubleDash = true
                    index += 1
                }
                arg == "-C" || arg == "-c" || arg == "--git-dir" || arg == "--work-tree" -> index += 2
                arg.startsWith("--git-dir=") || arg.startsWith("--work-tree=") -> index += 1
                arg.startsWith("-") -> index += 1
                else -> {
                    result += args.drop(index).map { it.lowercase(Locale.ROOT) }
                    break
                }
            }
        }
        return result
    }

    private fun parseXargsCommandArgs(args: List<String>): List<String>? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            index += when {
                arg == "--" -> return args.drop(index + 1).takeIf { it.isNotEmpty() }
                !arg.startsWith("-") || arg == "-" -> return args.drop(index)
                xargsOptionConsumesNextValue(arg) -> 2
                xargsOptionHasAttachedValue(arg) -> 1
                else -> 1
            }
        }
        return null
    }

    private fun xargsOptionConsumesNextValue(arg: String): Boolean {
        return arg in setOf(
            "-a",
            "-E",
            "-I",
            "-L",
            "-n",
            "-P",
            "-s",
            "-d",
            "--arg-file",
            "--delimiter",
            "--eof",
            "--max-args",
            "--max-lines",
            "--max-procs",
            "--max-chars",
            "--replace"
        )
    }

    private fun xargsOptionHasAttachedValue(arg: String): Boolean {
        if (arg.startsWith("--")) {
            return arg.startsWith("--arg-file=") ||
                    arg.startsWith("--delimiter=") ||
                    arg.startsWith("--eof=") ||
                    arg.startsWith("--max-args=") ||
                    arg.startsWith("--max-lines=") ||
                    arg.startsWith("--max-procs=") ||
                    arg.startsWith("--max-chars=") ||
                    arg.startsWith("--replace=")
        }
        return arg.length > 2 && arg[0] == '-' && arg[1] in setOf('a', 'E', 'I', 'L', 'n', 'P', 's', 'd', 'e', 'i', 'l')
    }

    private fun detectXargsImplicitTargetRisk(command: String, args: List<String>): String? {
        return when (command) {
            "rm" -> {
                val recursive = args.any { isRecursiveOption(it) || isShortOptionEnabled(it, 'r') || isShortOptionEnabled(it, 'R') }
                if (recursive) "递归删除文件或目录" else "删除文件或目录"
            }
            "rmdir" -> "删除目录"
            "mv" -> "移动或覆盖文件"
            "cp" -> "复制并覆盖文件"
            "install" -> "安装并覆盖文件"
            "ln" -> if (args.any { it == "--force" || isShortOptionEnabled(it, 'f') }) {
                "强制创建链接并覆盖已有文件"
            } else {
                null
            }
            "truncate" -> "截断文件"
            "chmod" -> if (args.any { isRecursiveOption(it) || isShortOptionEnabled(it, 'r') || isShortOptionEnabled(it, 'R') }) {
                "递归修改文件权限"
            } else {
                null
            }
            "chown" -> if (args.any { isRecursiveOption(it) || isShortOptionEnabled(it, 'r') || isShortOptionEnabled(it, 'R') }) {
                "递归修改文件所有者"
            } else {
                null
            }
            else -> if (command.startsWith("mkfs.")) "格式化文件系统" else null
        }
    }

    private fun firstCommandArgument(args: List<String>): String? {
        return commandTargets(args).firstOrNull()?.lowercase(Locale.ROOT)
    }

    private fun normalizeCommandName(raw: String): String {
        val normalizedPath = raw.trim().replace('\\', '/')
        return normalizedPath.substringAfterLast('/').lowercase(Locale.ROOT)
    }

    private fun isVariableAssignment(word: String): Boolean {
        return Regex("[A-Za-z_][A-Za-z0-9_]*(\\+)?=.*").matches(word)
    }

    private fun isLikelySuUser(word: String): Boolean {
        val normalized = word.lowercase(Locale.ROOT)
        return normalized == "root" ||
                normalized == "shell" ||
                normalized == "system" ||
                normalized == "nobody" ||
                normalized.all { it.isDigit() } ||
                Regex("u\\d+_a\\d+").matches(normalized)
    }

    private fun hasDynamicCommandPosition(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return false
        }

        val tokens = tokenize(trimmed)
        var index = 0
        while (index < tokens.size) {
            while (index < tokens.size && tokens[index].type == TokenType.SEPARATOR) {
                index += 1
            }
            if (index >= tokens.size) {
                break
            }

            val words = mutableListOf<String>()
            while (index < tokens.size && tokens[index].type != TokenType.SEPARATOR) {
                val token = tokens[index]
                if (token is Token.Word) {
                    words += token.text
                }
                index += 1
            }

            var commandIndex = 0
            while (commandIndex < words.size && isVariableAssignment(words[commandIndex])) {
                commandIndex += 1
            }
            if (commandIndex >= words.size) {
                continue
            }

            while (commandIndex < words.size && normalizeCommandName(words[commandIndex]) in shellControlPrefixes) {
                commandIndex += 1
            }
            if (commandIndex >= words.size) {
                continue
            }
            val firstWord = words[commandIndex]
            if (firstWord.startsWith('$') || firstWord.startsWith('`')) {
                return true
            }
        }
        return false
    }

    private fun isHelpOrVersionOnly(args: List<String>): Boolean {
        val normalizedArgs = args
            .filter { it.isNotBlank() }
            .map { it.lowercase(Locale.ROOT) }
        return normalizedArgs.isNotEmpty() && normalizedArgs.all { arg ->
            arg == "-h" || arg == "-?" || arg == "--help" || arg == "help" ||
                    arg == "--version" || arg == "version"
        }
    }

    private fun isLikelyDuration(arg: String): Boolean {
        return Regex("[0-9]+(\\.[0-9]+)?[smhd]?").matches(arg.lowercase(Locale.ROOT))
    }

    private fun isNicePriorityShortcut(arg: String): Boolean {
        return Regex("-[0-9]+").matches(arg) || Regex("--?[+]?[0-9]+").matches(arg)
    }

    private fun isIoniceAttachedOption(arg: String): Boolean {
        return arg.length > 2 && arg[0] == '-' && !arg.startsWith("--") && arg[1] in setOf('c', 'n', 'p')
    }

    private fun isRecursiveOption(arg: String): Boolean {
        return arg == "--recursive" || arg.startsWith("--recursive=")
    }

    private fun isShortOptionEnabled(arg: String, option: Char): Boolean {
        return arg.startsWith("-") && !arg.startsWith("--") && arg.drop(1).contains(option)
    }

    private fun isSensitiveOrWildcardTarget(target: String): Boolean {
        return isSensitivePath(target) || target.any { it == '*' || it == '?' || it == '[' }
    }

    private fun isSensitivePath(target: String): Boolean {
        val value = target.lowercase(Locale.ROOT)
        return value == "/" || value == "/proc/sysrq-trigger" ||
                value.startsWithAnyPathPrefix(sensitivePathPrefixes)
    }

    private fun String.startsWithAnyPathPrefix(prefixes: Set<String>): Boolean {
        return prefixes.any { prefix -> this == prefix || startsWith("$prefix/") }
    }

    private fun isCriticalSystemPath(target: String): Boolean {
        val value = target.lowercase(Locale.ROOT)
        return value == "/" || value == "/proc/sysrq-trigger" ||
                value.startsWithAnyPathPrefix(setOf("/dev/block", "/sys/fs/selinux"))
    }

    private fun isInputRedirect(redirect: String): Boolean {
        return redirect.contains('<')
    }

    private fun isOutputRedirect(redirect: String): Boolean {
        return redirect.contains('>') && !redirect.startsWith("<<")
    }

    private fun isFileRedirectTarget(target: String): Boolean {
        val normalized = target.trim().lowercase(Locale.ROOT)
        return normalized.isNotEmpty() &&
                !normalized.startsWith('&') &&
                normalized != "/dev/null" &&
                normalized != "-"
    }

    private fun stripHereDocuments(command: String): String {
        val normalizedCommand = command.replace("\r\n", "\n").replace('\r', '\n')
        val pendingDelimiters = ArrayDeque<String>()
        val stripped = StringBuilder()

        normalizedCommand.split('\n').forEach { line ->
            if (pendingDelimiters.isNotEmpty()) {
                val delimiter = pendingDelimiters.peekFirst()
                if (line.trim() == delimiter) {
                    pendingDelimiters.removeFirst()
                }
                stripped.append('\n')
                return@forEach
            }

            stripped.append(line).append('\n')
            collectHereDocumentDelimiters(line).forEach { delimiter ->
                pendingDelimiters.addLast(delimiter)
            }
        }

        return stripped.toString()
    }

    private fun extractCommandSubstitutions(command: String): List<String> {
        val substitutions = mutableListOf<String>()
        var index = 0
        var inSingleQuote = false
        var inDoubleQuote = false

        while (index < command.length) {
            val char = command[index]
            when {
                char == '\\' -> index += 2
                char == '\'' && !inDoubleQuote -> {
                    inSingleQuote = !inSingleQuote
                    index += 1
                }
                char == '"' && !inSingleQuote -> {
                    inDoubleQuote = !inDoubleQuote
                    index += 1
                }
                !inSingleQuote && char == '$' && command.getOrNull(index + 1) == '(' -> {
                    if (command.getOrNull(index + 2) == '(') {
                        index = skipArithmeticExpansion(command, index + 3)
                    } else {
                        val endIndex = findMatchingCommandSubstitutionEnd(command, index + 2)
                        if (endIndex < 0) {
                            index += 2
                        } else {
                            substitutions += command.substring(index + 2, endIndex)
                            index = endIndex + 1
                        }
                    }
                }
                !inSingleQuote && char == '`' -> {
                    val endIndex = findBacktickSubstitutionEnd(command, index + 1)
                    if (endIndex < 0) {
                        index += 1
                    } else {
                        substitutions += command.substring(index + 1, endIndex)
                        index = endIndex + 1
                    }
                }
                else -> index += 1
            }
        }

        return substitutions
    }

    private fun findMatchingCommandSubstitutionEnd(command: String, startIndex: Int): Int {
        var index = startIndex
        var depth = 1
        var inSingleQuote = false
        var inDoubleQuote = false

        while (index < command.length) {
            val char = command[index]
            when {
                char == '\\' -> index += 2
                char == '\'' && !inDoubleQuote -> {
                    inSingleQuote = !inSingleQuote
                    index += 1
                }
                char == '"' && !inSingleQuote -> {
                    inDoubleQuote = !inDoubleQuote
                    index += 1
                }
                !inSingleQuote && char == '$' && command.getOrNull(index + 1) == '(' -> {
                    depth += 1
                    index += 2
                }
                !inSingleQuote && !inDoubleQuote && char == ')' -> {
                    depth -= 1
                    if (depth == 0) {
                        return index
                    }
                    index += 1
                }
                else -> index += 1
            }
        }
        return -1
    }

    private fun findBacktickSubstitutionEnd(command: String, startIndex: Int): Int {
        var index = startIndex
        while (index < command.length) {
            index += when (command[index]) {
                '\\' -> 2
                '`' -> return index
                else -> 1
            }
        }
        return -1
    }

    private fun skipArithmeticExpansion(command: String, startIndex: Int): Int {
        var index = startIndex
        while (index < command.length - 1) {
            if (command[index] == ')' && command[index + 1] == ')') {
                return index + 2
            }
            index += if (command[index] == '\\') 2 else 1
        }
        return command.length
    }

    private fun collectHereDocumentDelimiters(line: String): List<String> {
        val tokens = tokenize(line)
        val delimiters = mutableListOf<String>()
        tokens.forEachIndexed { index, token ->
            if (token is Token.Redirect && (token.text == "<<" || token.text == "<<-")) {
                val delimiter = (tokens.getOrNull(index + 1) as? Token.Word)?.text?.trim()
                if (!delimiter.isNullOrEmpty()) {
                    delimiters += delimiter
                }
            }
        }
        return delimiters
    }

    private fun tokenize(command: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val current = StringBuilder()
        var index = 0
        var canStartComment = true

        fun flushWord() {
            if (current.isNotEmpty()) {
                tokens += Token.Word(current.toString())
                current.clear()
            }
        }

        while (index < command.length) {
            val char = command[index]
            if (current.isEmpty() && canStartComment && char == '#') {
                while (index < command.length && command[index] != '\n') {
                    index += 1
                }
                if (index < command.length && command[index] == '\n') {
                    tokens += Token.Separator("\n")
                    index += 1
                }
                canStartComment = true
                continue
            }

            if (char.isWhitespace()) {
                flushWord()
                if (char == '\n') {
                    tokens += Token.Separator("\n")
                }
                canStartComment = true
                index += 1
                continue
            }

            if (char == '\'') {
                index += 1
                while (index < command.length && command[index] != '\'') {
                    current.append(command[index])
                    index += 1
                }
                if (index < command.length) {
                    index += 1
                }
                canStartComment = false
                continue
            }

            if (char == '$' && command.getOrNull(index + 1) == '\'') {
                val quoted = readAnsiCString(command, index + 2)
                current.append(quoted.text)
                index = quoted.endIndex
                canStartComment = false
                continue
            }

            if (char == '"') {
                index += 1
                while (index < command.length && command[index] != '"') {
                    if (command[index] == '\\' && index + 1 < command.length) {
                        val next = command[index + 1]
                        if (next == '\n') {
                            index += 2
                        } else {
                            current.append(next)
                            index += 2
                        }
                    } else {
                        current.append(command[index])
                        index += 1
                    }
                }
                if (index < command.length) {
                    index += 1
                }
                canStartComment = false
                continue
            }

            if (char == '\\') {
                if (index + 1 < command.length) {
                    val next = command[index + 1]
                    if (next == '\n') {
                        index += 2
                    } else {
                        current.append(next)
                        index += 2
                        canStartComment = false
                    }
                } else {
                    current.append(char)
                    index += 1
                    canStartComment = false
                }
                continue
            }

            readRedirect(command, index)?.takeIf { current.isEmpty() || char == '<' || char == '>' || char == '&' }?.let { redirect ->
                flushWord()
                tokens += Token.Redirect(redirect.text)
                index = redirect.endIndex
                canStartComment = true
                continue
            }

            if (char == '&' && index + 1 < command.length && (command[index + 1].isDigit() || command[index + 1] == '-')) {
                current.append(char)
                index += 1
                canStartComment = false
                continue
            }

            if (isSeparator(char)) {
                flushWord()
                val next = command.getOrNull(index + 1)
                val separator = when (char) {
                    '&' if next == '&' -> "&&"
                    '|' if next == '|' -> "||"
                    '|' if next == '&' -> "|&"
                    else -> char.toString()
                }
                index += separator.length
                tokens += Token.Separator(separator)
                canStartComment = true
                continue
            }

            current.append(char)
            canStartComment = false
            index += 1
        }

        flushWord()
        return tokens
    }

    private fun readAnsiCString(command: String, startIndex: Int): QuotedString {
        val text = StringBuilder()
        var index = startIndex
        while (index < command.length && command[index] != '\'') {
            val char = command[index]
            if (char == '\\' && index + 1 < command.length) {
                val escaped = readAnsiEscape(command, index + 1)
                text.append(escaped.text)
                index = escaped.endIndex
            } else {
                text.append(char)
                index += 1
            }
        }
        if (index < command.length && command[index] == '\'') {
            index += 1
        }
        return QuotedString(text.toString(), index)
    }

    private fun readAnsiEscape(command: String, escapedIndex: Int): QuotedString {
        return when (val escaped = command[escapedIndex]) {
            'a' -> QuotedString("\u0007", escapedIndex + 1)
            'b' -> QuotedString("\b", escapedIndex + 1)
            'e', 'E' -> QuotedString("\u001B", escapedIndex + 1)
            'f' -> QuotedString("\u000C", escapedIndex + 1)
            'n' -> QuotedString("\n", escapedIndex + 1)
            'r' -> QuotedString("\r", escapedIndex + 1)
            't' -> QuotedString("\t", escapedIndex + 1)
            'u' -> readUnicodeEscape(command, escapedIndex + 1, maxDigits = 4, fallback = "u")
            'U' -> readUnicodeEscape(command, escapedIndex + 1, maxDigits = 8, fallback = "U")
            'v' -> QuotedString("\u000B", escapedIndex + 1)
            '\\', '\'', '"' -> QuotedString(escaped.toString(), escapedIndex + 1)
            'x' -> readHexEscape(command, escapedIndex + 1, maxDigits = 2)
            in '0'..'7' -> readOctalEscape(command, escapedIndex)
            else -> QuotedString(escaped.toString(), escapedIndex + 1)
        }
    }

    private fun readHexEscape(command: String, startIndex: Int, maxDigits: Int): QuotedString {
        var index = startIndex
        var value = 0
        var digits = 0
        while (index < command.length && digits < maxDigits) {
            val digit = hexDigitValue(command[index]) ?: break
            value = value * 16 + digit
            digits += 1
            index += 1
        }
        return if (digits == 0) QuotedString("x", startIndex) else QuotedString(value.toChar().toString(), index)
    }

    private fun readUnicodeEscape(command: String, startIndex: Int, maxDigits: Int, fallback: String): QuotedString {
        var index = startIndex
        var value = 0
        var digits = 0
        while (index < command.length && digits < maxDigits) {
            val digit = hexDigitValue(command[index]) ?: break
            value = value * 16 + digit
            digits += 1
            index += 1
        }
        return if (digits == 0 || !Character.isValidCodePoint(value)) {
            QuotedString(fallback, startIndex)
        } else {
            QuotedString(String(Character.toChars(value)), index)
        }
    }

    private fun readOctalEscape(command: String, startIndex: Int): QuotedString {
        var index = startIndex
        var value = 0
        var digits = 0
        while (index < command.length && digits < 3 && command[index] in '0'..'7') {
            value = value * 8 + (command[index] - '0')
            digits += 1
            index += 1
        }
        return QuotedString(value.toChar().toString(), index)
    }

    private fun hexDigitValue(char: Char): Int? {
        return when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> null
        }
    }

    private fun readRedirect(command: String, startIndex: Int): RedirectToken? {
        var index = startIndex
        val redirect = StringBuilder()

        if (command[index] == '&') {
            if (index + 1 >= command.length || command[index + 1] != '>') {
                return null
            }
            redirect.append("&>")
            index += 2
            if (index < command.length && command[index] == '>') {
                redirect.append('>')
                index += 1
            }
            return RedirectToken(redirect.toString(), index)
        }

        if (command[index].isDigit()) {
            val fdStart = index
            while (index < command.length && command[index].isDigit()) {
                index += 1
            }
            if (index >= command.length || (command[index] != '>' && command[index] != '<')) {
                return null
            }
            redirect.append(command.substring(fdStart, index))
        }

        when (command.getOrNull(index)) {
            '>' -> {
                redirect.append('>')
                index += 1
                if (command.getOrNull(index) == '>' || command.getOrNull(index) == '|') {
                    redirect.append(command[index])
                    index += 1
                }
            }
            '<' -> {
                redirect.append('<')
                index += 1
                if (command.getOrNull(index) == '<') {
                    redirect.append('<')
                    index += 1
                    when (command.getOrNull(index)) {
                        '<', '-' -> {
                            redirect.append(command[index])
                            index += 1
                        }
                    }
                } else if (command.getOrNull(index) == '>') {
                    redirect.append('>')
                    index += 1
                }
            }
            else -> return null
        }

        return RedirectToken(redirect.toString(), index)
    }

    private fun isSeparator(char: Char): Boolean {
        return char == ';' || char == '|' || char == '&' || char == '(' || char == ')'
    }

    private inline fun <T> List<T>.indexOfFirstFrom(startIndex: Int, predicate: (T) -> Boolean): Int {
        for (index in startIndex until size) {
            if (predicate(this[index])) {
                return index
            }
        }
        return -1
    }

    private data class RedirectToken(val text: String, val endIndex: Int)

    private data class QuotedString(val text: String, val endIndex: Int)

    private sealed class Token {
        data class Word(val text: String) : Token() {
            override val type: TokenType = TokenType.WORD
        }

        data class Redirect(val text: String) : Token() {
            override val type: TokenType = TokenType.REDIRECT
        }

        data class Separator(val text: String) : Token() {
            override val type: TokenType = TokenType.SEPARATOR
        }

        abstract val type: TokenType
    }

    private enum class TokenType {
        WORD,
        SEPARATOR,
        REDIRECT
    }
}
