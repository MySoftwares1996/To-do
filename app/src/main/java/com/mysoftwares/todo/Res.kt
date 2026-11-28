package com.mysoftwares.todo

import android.content.Context
import androidx.annotation.StringRes

/**
 * Text lookup for places without a Context at hand (enum labels, list sections, times).
 * Set once in App.onCreate, which always runs before any screen, receiver or service.
 */
object Res {
    lateinit var ctx: Context
    fun s(@StringRes id: Int): String = ctx.getString(id)
    fun f(@StringRes id: Int, vararg args: Any): String = ctx.getString(id, *args)
}
