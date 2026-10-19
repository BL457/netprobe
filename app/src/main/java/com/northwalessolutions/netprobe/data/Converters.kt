package com.northwalessolutions.netprobe.data

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter fun listToJson(l: List<String>): String = Json.encodeToString(l)
    @TypeConverter fun jsonToList(s: String): List<String> = runCatching { Json.decodeFromString<List<String>>(s) }.getOrDefault(emptyList())
}
