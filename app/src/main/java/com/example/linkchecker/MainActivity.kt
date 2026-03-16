package com.example.linkchecker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.linkchecker.databinding.ActivityMainBinding
import com.example.linkchecker.model.LinkItem
import com.example.linkchecker.model.Platform
import com.example.linkchecker.service.LinkCheckAccessibilityService
import com.example.linkchecker.util.LinkAdapter
import com.example.linkchecker.util.LinkExtractor
import com.example.linkchecker.util.PlatformHandler

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: LinkAdapter
    private val links = mutableListOf<LinkItem>()
    private var currentIndex = -1
    private val handler = Handler(Looper.getMainLooper())
    
    // V2.1 优化：缩短超时时间至 20 秒，增加更频繁的进度反馈
    private val TIMEOUT_MS = 20000L 
    private val timeoutRunnable = Runnable { handleTimeout() }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.linkchecker.LIKE_RESULT") {
                handler.removeCallbacks(timeoutRunnable)
                val fans = intent.getStringExtra("fans")
                val likes = intent.getStringExtra("likes")
                val likesNumber = intent.getIntExtra("likesNumber", -1)
                val comments = intent.getStringExtra("comments")
                val shares = intent.getStringExtra("shares")
                val isAboveThreshold = intent.getBooleanExtra("isAboveThreshold", false)
                val isInvalid = intent.getBooleanExtra("isInvalid", false)

                if (currentIndex in links.indices) {
                    val item = links[currentIndex]
                    links[currentIndex] = item.copy(
                        fans = fans ?: item.fans,
                        likes = likes ?: item.likes,
                        likesNumber = if (likesNumber != -1) likesNumber else item.likesNumber,
                        comments = comments ?: item.comments,
                        shares = shares ?: item.shares,
                        isAboveThreshold = isAboveThreshold,
                        isInvalid = isInvalid,
                        isChecked = true
                    )
                    adapter.notifyItemChanged(currentIndex)
                    
                    // 延迟一小会儿处理下一个，给系统返回留时间
                    handler.postDelayed({ checkNext() }, 1000)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        
        registerReceiver(resultReceiver, IntentFilter("com.example.linkchecker.LIKE_RESULT"), RECEIVER_EXPORTED)
    }

    private fun setupRecyclerView() {
        adapter = LinkAdapter(links)
        binding.recyclerViewLinks.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewLinks.adapter = adapter
    }

    private fun setupListeners() {
        binding.buttonExtract.setOnClickListener {
            val input = binding.editTextInput.text.toString()
            if (input.isBlank()) {
                Toast.makeText(this, "请先输入内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            links.clear()
            links.addAll(LinkExtractor.extract(input))
            adapter.notifyDataSetChanged()
            binding.buttonCheck.isEnabled = links.isNotEmpty()
            binding.textViewStatus.text = "已提取 ${links.size} 条链接"
        }

        binding.buttonCheck.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            
            currentIndex = -1
            binding.layoutProgress.visibility = View.VISIBLE
            binding.progressBar.max = links.size
            checkNext()
        }

        binding.buttonAccessibility.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun checkNext() {
        currentIndex++
        if (currentIndex < links.size) {
            val item = links[currentIndex]
            updateProgressUI(item)
            
            // 启动无障碍监听状态
            LinkCheckAccessibilityService.instance?.startWaiting(item.platform)
            
            // 执行跳转
            PlatformHandler.openLink(this, item)
            
            // 设置超时
            handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        } else {
            finishCheck()
        }
    }

    private fun updateProgressUI(item: LinkItem) {
        binding.progressBar.progress = currentIndex + 1
        binding.textViewProgress.text = "正在检测 (${currentIndex + 1}/${links.size}): ${item.title ?: item.url}"
        binding.textViewStatus.text = "正在处理: ${item.platform}"
    }

    private fun handleTimeout() {
        if (currentIndex in links.indices) {
            val item = links[currentIndex]
            links[currentIndex] = item.copy(isChecked = true, isInvalid = false) // 标记为已检查但无数据
            adapter.notifyItemChanged(currentIndex)
            
            // 强制拉回主应用
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
            
            Toast.makeText(this, "链接检测超时，跳过: ${item.platform}", Toast.LENGTH_SHORT).show()
            
            handler.postDelayed({ checkNext() }, 1500)
        }
    }

    private fun finishCheck() {
        binding.layoutProgress.visibility = View.GONE
        binding.textViewStatus.text = "检测完成"
        
        val intent = Intent(this, ResultActivity::class.java)
        intent.putParcelableArrayListExtra("links", ArrayList(links))
        startActivity(intent)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return LinkCheckAccessibilityService.instance != null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(resultReceiver)
        handler.removeCallbacksAndMessages(null)
    }
}
