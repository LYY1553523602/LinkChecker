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

    fun openLink(context: Context, linkItem: LinkItem): Boolean {
        return when (linkItem.platform) {
            Platform.DOUYIN -> openDouyin(context, linkItem.url)
            Platform.XIAOHONGSHU -> openXiaohongshu(context, linkItem.url)
            Platform.TOUTIAO -> openToutiao(context, linkItem.url)
            Platform.WECHAT -> openWechatArticle(context, linkItem.url)
            Platform.UNKNOWN -> openWithBrowser(context, linkItem.url)
        }
    }

    private fun openDouyin(context: Context, url: String): Boolean {
        return try {
            // 尝试用抖音APP打开
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                setPackage(PACKAGE_DOUYIN)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // 如果抖音APP未安装，用浏览器打开
            openWithBrowser(context, url)
        }
    }

    private fun openXiaohongshu(context: Context, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                setPackage(PACKAGE_XIAOHONGSHU)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            openWithBrowser(context, url)
        }
    }

    private fun openToutiao(context: Context, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                setPackage(PACKAGE_TOUTIAO)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            openWithBrowser(context, url)
        }
    }

    private fun openWechatArticle(context: Context, url: String): Boolean {
        // 微信文章用内置浏览器打开
        return openWithBrowser(context, url)
    }

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
            Platform.UNKNOWN -> null
        }
    }
}
