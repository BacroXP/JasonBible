package ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import data.SettingsManager
import data.SoundEvent
import data.SoundManager


@Composable
fun SettingsScreen(
    back: () -> Unit
) {
    val scrollState = rememberScrollState()

    val languageOptions = listOf("Deutsch", "English")
    val translationOptions = listOf("Luther 1912", "Luther 2017")

    var languageMenuOpen by remember { mutableStateOf(false) }
    var translationMenuOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium
                    )

                    Text(
                        text = "Appearance",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("Dark mode")
                            Text(
                                if (SettingsManager.darkMode) {
                                    "Uses a darker color scheme"
                                } else {
                                    "Uses a lighter color scheme"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Switch(
                            checked = SettingsManager.darkMode,
                            onCheckedChange = { enabled ->
                                SettingsManager.darkMode = enabled
                            }
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("Fullscreen")
                            Text(
                                if (SettingsManager.fullScreen) {
                                    "Starts the app maximized to the whole screen"
                                } else {
                                    "Starts the app in a window"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Switch(
                            checked = SettingsManager.fullScreen,
                            onCheckedChange = { enabled ->
                                SettingsManager.fullScreen = enabled
                            }
                        )
                    }

                    Text(
                        text = "Layout",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Bible max width")
                            Text("${SettingsManager.bibleMaxWidth.value.toInt()} dp")
                        }
                        Slider(
                            value = SettingsManager.bibleMaxWidth.value,
                            onValueChange = { value ->
                                SettingsManager.bibleMaxWidth = value.dp
                            },
                            valueRange = 480f..1800f,
                            steps = 0,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Editor max width")
                            Text("${SettingsManager.editorMaxWidth.value.toInt()} dp")
                        }
                        Slider(
                            value = SettingsManager.editorMaxWidth.value,
                            onValueChange = { value ->
                                SettingsManager.editorMaxWidth = value.dp
                            },
                            valueRange = 480f..1800f,
                            steps = 0,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Text(
                        text = "Sound",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Sound effects")
                            Text(
                                if (SettingsManager.soundEffectsEnabled) {
                                    "Plays hover / click / open-close sfx"
                                } else {
                                    "All sound effects muted"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Switch(
                            checked = SettingsManager.soundEffectsEnabled,
                            onCheckedChange = { enabled ->
                                SoundManager.play(SoundEvent.Click)
                                SettingsManager.soundEffectsEnabled = enabled
                            }
                        )
                    }

                    if (SettingsManager.soundEffectsEnabled) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Master volume")
                                Text("${SettingsManager.soundVolume}%")
                            }
                            Slider(
                                value = SettingsManager.soundVolume.toFloat(),
                                onValueChange = { value ->
                                    SettingsManager.soundVolume = value.toInt()
                                },
                                valueRange = 0f..100f,
                                steps = 0,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = { SoundManager.play(SoundEvent.Click) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Test click sound")
                            }
                        }
                    }

                    Text(
                        text = "Bible preferences",
                        style = MaterialTheme.typography.titleMedium
                    )

                    DropdownSettingRow(
                        label = "Language",
                        value = SettingsManager.language,
                        expanded = languageMenuOpen,
                        onExpandedChange = { languageMenuOpen = it },
                        options = languageOptions,
                        onOptionSelected = { selected ->
                            SoundManager.play(SoundEvent.Click)
                            SettingsManager.language = selected
                            languageMenuOpen = false
                        }
                    )

                    DropdownSettingRow(
                        label = "Translation",
                        value = SettingsManager.translation,
                        expanded = translationMenuOpen,
                        onExpandedChange = { translationMenuOpen = it },
                        options = translationOptions,
                        onOptionSelected = { selected ->
                            SoundManager.play(SoundEvent.Click)
                            SettingsManager.translation = selected
                            translationMenuOpen = false
                        }
                    )

                    Text(
                        text = "Current: ${SettingsManager.language} · ${SettingsManager.translation}",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Button(
                        onClick = {
                            SoundManager.play(SoundEvent.Click)
                            back()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Back")
                    }
                }

                if (scrollState.maxValue > 0) {
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scrollState),
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(12.dp)
                    )
                }
            }
        }
    }
}


@Composable
private fun DropdownSettingRow(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<String>,
    onOptionSelected: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label)

        Box {
            Button(
                onClick = { onExpandedChange(true) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(value)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = { onOptionSelected(option) }
                    )
                }
            }
        }
    }
}
