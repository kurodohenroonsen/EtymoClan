package be.heyman.android.etymoclan.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File

@Composable
fun FullscreenScreenshotDialog(
    file: File,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(onDismissRequest = onDismissRequest) {
        val bitmap = remember(file) {
            try {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {
                null
            }
        }
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .clickable { onDismissRequest() }
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Fullscreen Screenshot",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                }
            } else {
                Text("Impossible de charger la capture d'écran.", color = Color.White)
            }
            
            // Close Button in top right
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .size(36.dp)
                    .background(Color(0xFF222222), RoundedCornerShape(18.dp))
                    .clickable { onDismissRequest() },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
