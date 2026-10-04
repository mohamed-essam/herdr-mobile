package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.mobile.ui.theme.HerdrType
import dev.herdr.mobile.ui.theme.AvatarInk
import dev.herdr.mobile.ui.theme.avatarColor

/** First 1–2 alphanumeric chars of [name], uppercased; "?" when none. */
fun monogram(name: String): String {
    val letters = name.filter { it.isLetterOrDigit() }
    return when {
        letters.isEmpty() -> "?"
        letters.length == 1 -> letters.uppercase()
        else -> letters.take(2).uppercase()
    }
}

/** A colored rounded-square monogram avatar for a repo name. */
@Composable
fun RepoAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val dark = isSystemInDarkTheme()
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(avatarColor(name, dark)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            monogram(name),
            color = AvatarInk,
            style = HerdrType.badge.copy(fontSize = (size.value / 2).sp, letterSpacing = 0.sp),
        )
    }
}
