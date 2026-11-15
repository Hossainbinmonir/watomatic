package com.parishod.watomatic.model.rules

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class RuleTypeConverters {
    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: List<String>?): String {
        return gson.toJson(value ?: emptyList<String>())
    }

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(value, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromIntSet(value: Set<Int>?): String {
        return gson.toJson((value ?: emptySet()).sorted())
    }

    @TypeConverter
    fun toIntSet(value: String?): Set<Int> {
        if (value.isNullOrBlank()) return emptySet()
        return try {
            val type = object : TypeToken<Set<Int>>() {}.type
            gson.fromJson<Set<Int>>(value, type) ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    @TypeConverter
    fun fromMatchType(value: MatchType?): String = (value ?: MatchType.CONTAINS).name

    @TypeConverter
    fun toMatchType(value: String?): MatchType {
        return try {
            if (value.isNullOrBlank()) MatchType.CONTAINS else MatchType.valueOf(value)
        } catch (_: Exception) {
            MatchType.CONTAINS
        }
    }

    @TypeConverter
    fun fromContactFilterMode(value: ContactFilterMode?): String = (value ?: ContactFilterMode.ALL).name

    @TypeConverter
    fun toContactFilterMode(value: String?): ContactFilterMode {
        return try {
            if (value.isNullOrBlank()) ContactFilterMode.ALL else ContactFilterMode.valueOf(value)
        } catch (_: Exception) {
            ContactFilterMode.ALL
        }
    }

    @TypeConverter
    fun fromGroupFilterMode(value: GroupFilterMode?): String = (value ?: GroupFilterMode.BOTH).name

    @TypeConverter
    fun toGroupFilterMode(value: String?): GroupFilterMode {
        return try {
            if (value.isNullOrBlank()) GroupFilterMode.BOTH else GroupFilterMode.valueOf(value)
        } catch (_: Exception) {
            GroupFilterMode.BOTH
        }
    }
}
