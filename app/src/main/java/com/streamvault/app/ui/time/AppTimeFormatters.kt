package com.streamvault.app.ui.time

import androidx.compose.runtime.compositionLocalOf
import com.streamvault.domain.model.AppTimeFormat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

val LocalAppTimeFormat = compositionLocalOf { AppTimeFormat.SYSTEM }

fun AppTimeFormat.createTimeFormat(locale: Locale = Locale.getDefault()): DateFormat = when (this) {
    AppTimeFormat.SYSTEM -> DateFormat.getTimeInstance(DateFormat.SHORT, locale)
    AppTimeFormat.TWELVE_HOUR -> SimpleDateFormat("h:mm a", locale)
    AppTimeFormat.TWENTY_FOUR_HOUR -> SimpleDateFormat("HH:mm", locale)
}

fun AppTimeFormat.createDateTimeFormat(locale: Locale = Locale.getDefault()): DateFormat = when (this) {
    AppTimeFormat.SYSTEM -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
    AppTimeFormat.TWELVE_HOUR -> SimpleDateFormat("MMM d, h:mm a", locale)
    AppTimeFormat.TWENTY_FOUR_HOUR -> SimpleDateFormat("MMM d, HH:mm", locale)
}

fun AppTimeFormat.createTimeFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter = when (this) {
    AppTimeFormat.SYSTEM -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    AppTimeFormat.TWELVE_HOUR -> DateTimeFormatter.ofPattern("h:mm a", locale)
    AppTimeFormat.TWENTY_FOUR_HOUR -> DateTimeFormatter.ofPattern("HH:mm", locale)
}