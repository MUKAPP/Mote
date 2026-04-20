package com.mukapp.mote.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import com.mukapp.mote.data.model.ChatRole
import org.json.JSONObject
import java.util.Locale

fun String.toChatRoleOrNull(): ChatRole? {
    return when (lowercase(Locale.ROOT)) {
        ChatRole.System.apiValue -> ChatRole.System
        ChatRole.User.apiValue -> ChatRole.User
        ChatRole.Assistant.apiValue -> ChatRole.Assistant
        ChatRole.Tool.apiValue -> ChatRole.Tool
        else -> null
    }
}

fun hasManageAllFilesPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

fun openManageAllFilesAccessSettings(context: Context) {
    val packageUri = "package:${context.packageName}".toUri()
    val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            context.startActivity(appDetailsIntent)
        }
    }.onFailure {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallbackIntent)
            } else {
                context.startActivity(appDetailsIntent)
            }
        }.onFailure { error ->
            Log.e("Mote", "打开文件管理权限设置失败", error)
        }
    }
}

fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return when (val value = opt(name)) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}
