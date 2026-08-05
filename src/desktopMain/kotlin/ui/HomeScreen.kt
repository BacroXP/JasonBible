package ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.hoverable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import data.SoundManager
import data.SoundEvent


@Composable
fun HomeScreen(
    openBible: () -> Unit,
    openNotes: () -> Unit,
    openSettings: () -> Unit,
    openQuit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "📖 Daily Verse",
                    style = MaterialTheme.typography.headlineMedium
                )

                Card {
                    Text(
                        modifier = Modifier.padding(20.dp),
                        text = "\"Am Anfang schuf Gott Himmel und Erde.\"\n\n1 Mose 1:1"
                    )
                }

                Button(
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        openBible()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Bible")
                }

                Button(
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        openNotes()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Notes")
                }

                Button(
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        openSettings()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Settings")
                }

                Button(
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        openQuit()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Quit")
                }
            }
        }
    }
}
