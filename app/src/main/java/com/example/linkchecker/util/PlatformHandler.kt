package com.example.linkchecker.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.linkchecker.model.LinkItem
import com.example.linkchecker.model.Platform

object PlatformHandler {

    // 包名常量
    private const val PACKAGE_DOUYIN = "com.ss.android.ugc.aweme"
    private const val PACKAGE_XIAOHONGSHU = "com.xingin.xhs"
    private const val PACKAGE_TOUTIAO = "com.ss.android.article.news"
    private const val PACKAGE_WECHAT = "com.tencent.mm"
    private const val PACKAGE_WEIBO = "com.sina.weibo"
    private const val PACKAGE_KUAISHOU = "com.smile.gifmaker"

    fun openLink(context: Context, linkItem: LinkItem): Boolean {
        val packageName = getAppPackageName(linkItem.platform)
        
        // 1. 尝试使用指定包名打开（如果包名已知）
        if (packageName != null) {
            if (openNativeApp(context, linkItem.url, packageName)) {
                return true
            }
        }
        
        // 2. 尝试使用通用 Intent 打开（系统会根据 URL 方案选择合适的 APP）
        return openWithBrowser(context, linkItem.url)
    }

    /**
     * 尝试使用指定的包名打开原生应用
     */
    private fun openNativeApp(context: Context, url: String, packageName: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                setPackage(packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 使用通用 Intent 打开，系统会弹出选择框或用默认浏览器
     */
    private fun openWithBrowser(context: Context, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getAppPackageName(platform: Platform): String? {
        return when (platform) {
            Platform.DOUYIN -> PACKAGE_DOUYIN
            Platform.XIAOHONGSHU -> PACKAGE_XIAOHONGSHU
            Platform.TOUTIAO -> PACKAGE_TOUTIAO
            Platform.WECHAT -> PACKAGE_WECHAT
            Platform.WEIBO -> PACKAGE_WEIBO
            Platform.KUAISHOU -> PACKAGE_KUAISHOU
            Platform.UNKNOWN -> null
        }
    }
}
