package com.ggsapple.remotear.util

object PublicIdFormatter {
    fun normalize(raw: String): String =
        raw.filter { it.isDigit() }

    fun isValid(raw: String): Boolean =
        normalize(raw).length == 11

    fun formatDisplay(raw: String?): String {
        val digits = normalize(raw.orEmpty())
        if (digits.length != 11) return raw.orEmpty().ifBlank { "—" }
        return "${digits[0]}-${digits.substring(1, 4)}-${digits.substring(4, 7)}-${digits.substring(7, 11)}"
    }
}
