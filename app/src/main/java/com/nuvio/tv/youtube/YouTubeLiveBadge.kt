package com.nuvio.tv.youtube

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The red LIVE marker shown over a live YouTube broadcast while the controls are up. */
@Composable
fun YouTubeLiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(20.dp)
            .background(Color(0xFFE50914), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(stringResource(R.string.onetube_live_badge), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
