package xyz.cncnlp.soundwave.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * accept6-uipolish2（用户 2026-11-04 22:21 定版）：短选项选择器两列网格归类——
 * 2..10 个选项且标签全 ≤8 字走两列；长文案、长列表、单选项保持竖排。
 * 样本取自现行全部 SettingsPickerSheet 消费方的真实选项集。
 */
class SettingsPickerLayoutTest {

    @Test
    fun shortOptionPickersUseTwoColumns() {
        // 悬浮歌词行数
        assertTrue(settingsPickerUsesTwoColumns(listOf("单行", "双行")))
        // 悬浮歌词字号（9 项）
        assertTrue(settingsPickerUsesTwoColumns(listOf("12sp", "14sp", "16sp", "18sp", "20sp", "22sp", "24sp", "26sp", "28sp")))
        // 悬浮文字颜色（8 项单字）
        assertTrue(settingsPickerUsesTwoColumns(listOf("蓝", "白", "黄", "青", "粉", "绿", "橙", "紫")))
        // 悬浮背景/文字透明度（百分比 5 项）
        assertTrue(settingsPickerUsesTwoColumns(listOf("0%", "15%", "35%", "55%", "75%")))
        assertTrue(settingsPickerUsesTwoColumns(listOf("40%", "55%", "75%", "90%", "100%")))
        // 悬浮显示宽度
        assertTrue(settingsPickerUsesTwoColumns(listOf("自适应", "200dp", "280dp", "360dp")))
        // 歌词字体 / 全局字体
        assertTrue(settingsPickerUsesTwoColumns(listOf("系统", "无衬线", "衬线", "等宽")))
        // 预解析数量 / 请求间隔
        assertTrue(settingsPickerUsesTwoColumns(listOf("1 首", "2 首", "3 首", "4 首", "5 首")))
        assertTrue(settingsPickerUsesTwoColumns(listOf("3 秒", "6 秒", "9 秒", "12 秒", "15 秒")))
        // 下载文件名（最长「专辑 - 歌名」7 字）
        assertTrue(settingsPickerUsesTwoColumns(listOf("歌名-歌手", "仅歌名", "专辑 - 歌名")))
        // 缓存上限
        assertTrue(settingsPickerUsesTwoColumns(listOf("256 MB", "512 MB", "1 GB", "2 GB", "不限")))
    }

    @Test
    fun longTextOrLongListPickersStayVertical() {
        // 长文案
        assertFalse(settingsPickerUsesTwoColumns(listOf("按专辑和歌手自动整理下载目录", "平铺")))
        // 长列表（>10 项）
        assertFalse(settingsPickerUsesTwoColumns((1..12).map { "${it}sp" }))
        // 单选项 / 空列表不入格
        assertFalse(settingsPickerUsesTwoColumns(listOf("单行")))
        assertFalse(settingsPickerUsesTwoColumns(emptyList()))
    }
}
