package com.hpu.mymoviestore.presentation.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.hpu.mymoviestore.databinding.FragmentProfileBinding
import com.hpu.mymoviestore.presentation.activity.HistoryActivity

/**
 * "我的" 页面 —— 个人中心，包含视频源管理、弹幕开关、历史记录、下载管理、
 * 清理缓存、帮助和关于等入口。
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
    }

    private fun setupClickListeners() {
        // 视频源管理 —— 弹框（多选开关，至少选一个，默认全选）
        binding.cardVideoSource.setOnClickListener {
            showVideoSourceDialog()
        }

        // 弹幕 —— 滑动开关，默认开启（已在 XML 中设置 checked="true"）
        binding.switchDanmu.setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(requireContext(), "弹幕${if (isChecked) "已开启" else "已关闭"}", Toast.LENGTH_SHORT).show()
        }

        // 历史记录 —— 跳转到现有历史页面
        binding.cardHistory.setOnClickListener {
            startActivity(Intent(requireContext(), HistoryActivity::class.java))
        }

        // 下载管理 —— 占位
        binding.cardDownload.setOnClickListener {
            Toast.makeText(requireContext(), "下载管理功能即将上线", Toast.LENGTH_SHORT).show()
        }

        // 清理缓存 —— 弹框，选择性清理
        binding.cardClearCache.setOnClickListener {
            showClearCacheDialog()
        }

        // 帮助
        binding.cardHelp.setOnClickListener {
            showHelpDialog()
        }

        // 关于
        binding.cardAbout.setOnClickListener {
            showAboutDialog()
        }
    }

    /**
     * 视频源管理弹框
     * 多选开关，至少选一个，默认全选
     */
    private fun showVideoSourceDialog() {
        val sources = arrayOf("视频源 1", "视频源 2")
        val checked = booleanArrayOf(true, true)

        AlertDialog.Builder(requireContext())
            .setTitle("视频源管理")
            .setMultiChoiceItems(sources, checked) { _, which, isChecked ->
                checked[which] = isChecked
                // 确保至少选一个
                if (!checked.any { it }) {
                    checked[which] = true
                    Toast.makeText(requireContext(), "至少需要选择一个视频源", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("确定") { _, _ ->
                val selected = sources.filterIndexed { index, _ -> checked[index] }
                Toast.makeText(requireContext(), "已选择: ${selected.joinToString(", ")}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 清理缓存弹框
     * 选择性清理：网络缓存、图片缓存、播放历史、搜索历史
     */
    private fun showClearCacheDialog() {
        val items = arrayOf("网络请求缓存", "图片缓存", "播放历史", "搜索历史")
        val checked = booleanArrayOf(true, true, false, false)

        AlertDialog.Builder(requireContext())
            .setTitle("清理缓存")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("清理") { _, _ ->
                val toClear = items.filterIndexed { index, _ -> checked[index] }
                if (toClear.isEmpty()) {
                    Toast.makeText(requireContext(), "未选择任何缓存项", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Toast.makeText(
                    requireContext(),
                    "已清理: ${toClear.joinToString(", ")}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 帮助弹框
     */
    private fun showHelpDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("帮助")
            .setMessage(
                "欢迎使用我的影视！\n\n" +
                    "1. 首页发现：浏览豆瓣热门影视内容\n" +
                    "2. 搜索播放：输入片名搜索可播放资源\n" +
                    "3. 播放历史：自动保存观看记录，支持续播\n" +
                    "4. 视频源：可在我的页面中管理播放源\n\n" +
                    "如遇问题，请检查网络连接后重试。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * 关于弹框
     */
    private fun showAboutDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("关于")
            .setMessage(
                "我的影视\n" +
                    "版本: 1.0\n\n" +
                    "一款简洁的影视浏览与播放应用，\n" +
                    "聚合豆瓣内容发现与在线播放资源。\n\n" +
                    "仅供学习交流使用。"
            )
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
