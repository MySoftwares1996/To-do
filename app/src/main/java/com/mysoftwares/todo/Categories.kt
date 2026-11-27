package com.mysoftwares.todo

import android.content.Context
import android.graphics.Color

/** User-editable task categories with a colour. Stored in device-protected storage. */
object Categories {
    data class Category(val name: String, val color: Int)

    /** Colours offered when adding a category (iOS system colours). */
    val PALETTE = listOf("#007AFF", "#FF3B30", "#34C759", "#AF52DE", "#FF9500", "#30B0C7", "#FF2D55", "#8E8E93")
        .map { Color.parseColor(it) }

    private val DEFAULTS = listOf(
        Category("Bank", PALETTE[0]), Category("Tax", PALETTE[1]), Category("Payroll", PALETTE[2]),
        Category("Closing", PALETTE[3]), Category("Vendor", PALETTE[4]), Category("Other", PALETTE[7]),
    )

    private fun prefs(c: Context) =
        c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("categories", Context.MODE_PRIVATE)

    fun all(c: Context): List<Category> {
        val raw = prefs(c).getString("list", null) ?: return DEFAULTS
        return raw.lines().filter { it.contains("|") }.map {
            Category(it.substringAfter("|"), it.substringBefore("|").toIntOrNull() ?: PALETTE[7])
        }
    }

    fun save(c: Context, list: List<Category>) {
        prefs(c).edit().putString("list", list.joinToString("\n") { "${it.color}|${it.name.replace("\n", " ")}" }).apply()
    }

    fun colorOf(c: Context, name: String): Int? = all(c).firstOrNull { it.name == name }?.color
}
