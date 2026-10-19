package com.northwalessolutions.netprobe.data

import android.content.Context

object RawResources {
    fun lines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    fun read(context: Context, id: Int): String = context.resources.openRawResource(id).bufferedReader().use { it.readText() }
}
