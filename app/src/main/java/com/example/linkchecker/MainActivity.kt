package com.example.linkchecker

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.linkchecker.databinding.ActivityMainBinding
import com.example.linkchecker.model.CheckStatus
import com.example.linkchecker.model.LinkItem
import com.example.linkchecker.service.LinkCheckAccessibilityService
import com.example.linkchecker.util.LinkAdapter
import com.example.linkchecker.util.LinkExtractor
import com.example.linkchecker.util.PlatformHandler
import java.util.ArrayList

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var linkAdapter: LinkAdapter
    private var linkList = mutableListOf<LinkItem>()
    private var currentCheckIndex = 0
    private var isChecking = false
    private val handler = Handler(Looper.getMainLooper())
    
    private val checkTimeout = 30000L // 30秒超时，给自动点击和页面加载留足时间
    private var checkRunnable: Runnable? = null

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.linkchecker.LIKE_RESULT") {
                val found = intent.getBooleanExtra("found", false)
                val isInvalid = intent.getBooleanExtra("isInvalid", false)
                
                if (isInvalid) {
                    updateCurrentLinkInvalid()
                } else if (found && currentCheckIndex < linkList.size) {
                    // 抓取到的数据，如果为空则保留原有的元数据
                    val fans = intent.getStringExtra("fans")
                    val likes = intent.getStringExtra("likes")
                    val likesNumber = intent.getIntExtra("likesNumber", -1)
                    val comments = intent.getStringExtra("comments")
                    val shares = intent.getStringExtra("shares")
                    val isAboveThreshold = intent.getBooleanExtra("isAboveThreshold", false)
                    
                    updateCurrentLinkResult(fans, likes, likesNumber, comments, shares, isAboveThreshold)
                }

                // 自动检查下一个
                handler.postDelayed({ checkNextLink() }, 1500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        registerReceiver(resultReceiver, IntentFilter("com.example.linkchecker.LIKE_RESULT"))
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityButton()
        // 如果正在检测中回到前台，确保进度 UI 显示
        if (isChecking) {
            binding.layoutProgress.visibility = View.VISIBLE
            updateProgressUI()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(resultReceiver)
        handler.removeCallbacksAndMessages(null)
    }

    private fun setupRecyclerView() {
        linkAdapter = LinkAdapter(linkList)
        binding.recyclerViewLinks.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = linkAdapter
        }
    }

    private fun setupButtons() {
        binding.buttonExtract.setOnClickListener {
            extractLinks()
        }

        binding.buttonCheck.setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                startChecking()
            } else {
                showAccessibilityDialog()
            }
        }

        binding.buttonAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    private fun extractLinks() {
        val text = binding.editTextInput.text.toString()
        if (text.isEmpty()) {
            Toast.makeText(this, "请先粘贴聊天记录", Toast.LENGTH_SHORT).show()
            return
        }

        linkList.clear()
        linkList.addAll(LinkExtractor.extractLinks(text))
        currentCheckIndex = 0

        if (linkList.isEmpty()) {
            binding.textViewStatus.text = "未找到链接"
            binding.buttonCheck.isEnabled = false
        } else {
            binding.textViewStatus.text = "找到 ${linkList.size} 个链接"
            binding.buttonCheck.isEnabled = true
        }

        linkAdapter.notifyDataSetChanged()
    }

    private fun startChecking() {
        if (linkList.isEmpty()) return
        if (isChecking) return

        isChecking = true
        currentCheckIndex = 0
        
        binding.buttonCheck.isEnabled = false
        binding.buttonExtract.isEnabled = false
        binding.layoutProgress.visibility = View.VISIBLE
        binding.progressBar.max = linkList.size
        
        linkList = linkList.map { it.copy(status = CheckStatus.PENDING) }.toMutableList()
        linkAdapter.notifyDataSetChanged()

        checkNextLink()
    }

    private fun updateProgressUI() {
        binding.progressBar.progress = currentCheckIndex
        binding.textViewProgress.text = "正在检测: ${currentCheckIndex + 1}/${linkList.size}"
    }

    private fun checkNextLink() {
        checkRunnable?.let { handler.removeCallbacks(it) }

        if (currentCheckIndex >= linkList.size) {
            finishChecking()
            return
        }

        updateProgressUI()
        val linkItem = linkList[currentCheckIndex]
        linkList[currentCheckIndex] = linkItem.copy(status = CheckStatus.CHECKING)
        linkAdapter.notifyItemChanged(currentCheckIndex)
        binding.textViewStatus.text = "正在检测: ${LinkExtractor.getPlatformName(linkItem.platform)}"

        LinkCheckAccessibilityService.reset()
        LinkCheckAccessibilityService.instance?.startWaiting(linkItem.platform)

        val success = PlatformHandler.openLink(this, linkItem)
        if (!success) {
            updateCurrentLinkFailed()
            handler.postDelayed({ checkNextLink() }, 1000)
            return
        }

        // 超时保护逻辑
        checkRunnable = Runnable {
            if (currentCheckIndex < linkList.size && linkList[currentCheckIndex].status == CheckStatus.CHECKING) {
                updateCurrentLinkTimeout()
                
                // 尝试强行拉回主界面
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(intent)
                
                handler.postDelayed({ checkNextLink() }, 1500)
            }
        }
        handler.postDelayed(checkRunnable!!, checkTimeout)
    }

    private fun updateCurrentLinkResult(fans: String?, likes: String?, likesNumber: Int, comments: String?, shares: String?, isAboveThreshold: Boolean) {
        if (currentCheckIndex >= linkList.size) return
        
        val linkItem = linkList[currentCheckIndex]
        linkList[currentCheckIndex] = linkItem.copy(
            status = CheckStatus.SUCCESS,
            fans = fans ?: linkItem.fans,
            likes = likes ?: linkItem.likes,
            likesNumber = if (likesNumber != -1) likesNumber else linkItem.likesNumber,
            comments = comments ?: linkItem.comments,
            shares = shares ?: linkItem.shares,
            isAboveThreshold = isAboveThreshold
        )
        linkAdapter.notifyItemChanged(currentCheckIndex)
        currentCheckIndex++
    }

    private fun updateCurrentLinkInvalid() {
        if (currentCheckIndex >= linkList.size) return
        val linkItem = linkList[currentCheckIndex]
        linkList[currentCheckIndex] = linkItem.copy(status = CheckStatus.INVALID, isInvalid = true)
        linkAdapter.notifyItemChanged(currentCheckIndex)
        currentCheckIndex++
    }

    private fun updateCurrentLinkFailed() {
        if (currentCheckIndex >= linkList.size) return
        val linkItem = linkList[currentCheckIndex]
        linkList[currentCheckIndex] = linkItem.copy(status = CheckStatus.FAILED)
        linkAdapter.notifyItemChanged(currentCheckIndex)
        currentCheckIndex++
    }

    private fun updateCurrentLinkTimeout() {
        if (currentCheckIndex >= linkList.size) return
        val linkItem = linkList[currentCheckIndex]
        linkList[currentCheckIndex] = linkItem.copy(status = CheckStatus.TIMEOUT)
        linkAdapter.notifyItemChanged(currentCheckIndex)
        currentCheckIndex++
    }

    private fun finishChecking() {
        isChecking = false
        binding.buttonCheck.isEnabled = true
        binding.buttonExtract.isEnabled = true
        binding.layoutProgress.visibility = View.GONE
        binding.textViewStatus.text = "检测完成"
        
        val intent = Intent(this, ResultActivity::class.java).apply {
            putParcelableArrayListExtra("links", ArrayList(linkList))
        }
        startActivity(intent)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName) return true
        }
        return false
    }

    private fun updateAccessibilityButton() {
        if (isAccessibilityServiceEnabled()) {
            binding.buttonAccessibility.text = "无障碍服务已开启"
            binding.buttonAccessibility.isEnabled = false
        } else {
            binding.buttonAccessibility.text = "开启无障碍服务"
            binding.buttonAccessibility.isEnabled = true
        }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要无障碍权限")
            .setMessage("本应用需要无障碍服务权限才能实现全自动检测。请在设置中开启。")
            .setPositiveButton("去开启") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}
