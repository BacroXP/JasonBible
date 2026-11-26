package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import data.BibleRepository
import data.CollectionEntry
import data.SavedReference
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import model.Book
import ui.components.MaxWidthScaffold


/**
 * Personal Bible-reference collections (Sammlungen): name, optional
 * description, any number of references, optional notes and tags — all
 * stored locally via [SettingsManager]. A collection card shows its
 * metadata and an expandable list of references; clicking a reference
 * jumps to the verse in the Bible pane. References are added from the
 * Bible pane's \"＋ Collection\" action ([AddToCollectionDialog]).
 */
@Composable
fun CollectionsScreen(
    back: () -> Unit,
    onOpenVerse: (bookName: String, chapter: Int, verse: Int) -> Unit
) {
    // Books of the active translation, used to resolve reference
    // numbers into display names. Loaded once per visit (off the UI
    // thread); [SettingsManager.collections] is Compose state, so the
    // list below recomposes whenever a collection changes.
    var books by remember { mutableStateOf<List<Book>?>(null) }
    LaunchedEffect(Unit) {
        books = BibleRepository.loadBooks()
    }

    var editMode by remember { mutableStateOf<CollectionDialogMode?>(null) }
    var deleteTarget by remember { mutableStateOf<CollectionEntry?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Compact top bar mirroring the Statistics screen pattern.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "← Back",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable {
                        SoundManager.play(SoundEvent.Click)
                        back()
                    }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
            Icon(
                imageVector = RibbonIcons.Collections,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Collections",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            MaxWidthScaffold(
                compact = false,
                maxWidth = SettingsManager.bibleMaxWidth
            ) {
                val loaded = books
                if (loaded == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Loading collections…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val collections = SettingsManager.collections
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        item {
                            Button(
                                onClick = {
                                    SoundManager.play(SoundEvent.Click)
                                    editMode = CollectionDialogMode.Create
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = RibbonIcons.New,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "New collection",
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                        if (collections.isEmpty()) {
                            item {
                                Text(
                                    text = "No collections yet. Select a verse in the Bible and " +
                                        "use \"＋ Collection\" to build your first one.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                        }
                        items(collections, key = { it.id }) { collection ->
                            CollectionCard(
                                collection = collection,
                                books = loaded,
                                onEdit = {
                                    editMode = CollectionDialogMode.Edit(collection)
                                },
                                onDelete = {
                                    deleteTarget = collection
                                },
                                onOpenVerse = onOpenVerse
                            )
                        }
                    }
                }
            }
        }
    }

    // Create / edit dialog.
    editMode?.let { mode ->
        CollectionEditDialog(
            mode = mode,
            onDismiss = { editMode = null },
            onConfirm = { name, description, notes, tags ->
                val existing = (mode as? CollectionDialogMode.Edit)?.collection
                SettingsManager.saveCollection(
                    CollectionEntry(
                        id = existing?.id ?: "c${System.nanoTime()}",
                        name = name,
                        description = description,
                        references = existing?.references.orEmpty(),
                        notes = notes,
                        tags = tags
                    )
                )
                editMode = null
            }
        )
    }

    // Delete confirmation.
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete collection?") },
            text = {
                Text("\"${target.name}\" and all ${target.references.size} references " +
                    "will be removed. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    SoundManager.play(SoundEvent.Click)
                    SettingsManager.deleteCollection(target.id)
                    deleteTarget = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}


/** One collection card: metadata plus an expandable reference list. */
@Composable
private fun CollectionCard(
    collection: CollectionEntry,
    books: List<Book>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenVerse: (String, Int, Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = collection.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    SoundManager.play(SoundEvent.Click)
                    onEdit()
                }) {
                    Icon(
                        imageVector = RibbonIcons.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text("Edit", modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = {
                    SoundManager.play(SoundEvent.Click)
                    onDelete()
                }) {
                    Icon(
                        imageVector = RibbonIcons.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text("Delete", modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (collection.description.isNotBlank()) {
                Text(
                    text = collection.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (collection.tags.isNotEmpty()) {
                Text(
                    text = collection.tags.joinToString(" ") { "#$it" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (collection.notes.isNotBlank()) {
                Text(
                    text = collection.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
            Text(
                text = "${collection.references.size} references" +
                    (if (collection.references.isEmpty()) "" else "  ·  " +
                        (if (expanded) "hide ▴" else "show ▾")),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = collection.references.isNotEmpty()) {
                        SoundManager.play(SoundEvent.Click)
                        expanded = !expanded
                    }
                    .padding(vertical = 2.dp)
            )
            if (expanded) {
                if (collection.references.isEmpty()) {
                    Text(
                        text = "No references yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        collection.references.forEach { ref ->
                            val bookName = books.find { it.book == ref.bookNumber }?.name
                                ?: "Book ${ref.bookNumber}"
                            val label = if (ref.verse != null) {
                                "$bookName ${ref.chapter}:${ref.verse}"
                            } else {
                                "$bookName ${ref.chapter}"
                            }
                            Text(
                                text = "• $label",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        SoundManager.play(SoundEvent.Click)
                                        onOpenVerse(
                                            bookName,
                                            ref.chapter,
                                            ref.verse ?: 1
                                        )
                                    }
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}


private sealed interface CollectionDialogMode {
    data object Create : CollectionDialogMode
    data class Edit(val collection: CollectionEntry) : CollectionDialogMode
}


/** Create / edit dialog for one collection (name, description, tags, notes). */
@Composable
private fun CollectionEditDialog(
    mode: CollectionDialogMode,
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String, notes: String, tags: List<String>) -> Unit
) {
    val existing = (mode as? CollectionDialogMode.Edit)?.collection
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    var tagsText by remember { mutableStateOf(existing?.tags?.joinToString(", ").orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (existing != null) "Edit collection" else "New collection")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("Tags (comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    SoundManager.play(SoundEvent.Click)
                    onConfirm(
                        name.trim(),
                        description.trim(),
                        notes.trim(),
                        tagsText.split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    )
                },
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}


/**
 * \"Add the selected verse to a collection\" dialog, opened from the
 * Bible pane's \"＋ Collection\" action. Lists the existing collections
 * (one tap adds the verse and closes) plus an inline \"new collection\"
 * name field. Adding an already-present verse is a no-op.
 */
@Composable
internal fun AddToCollectionDialog(
    bookNumber: Int,
    bookName: String,
    chapter: Int,
    verse: Int,
    onDismiss: () -> Unit
) {
    var newCollectionName by remember { mutableStateOf("") }
    val collections = SettingsManager.collections

    val addTo = { collection: CollectionEntry ->
        val alreadyThere = collection.references.any {
            it.bookNumber == bookNumber && it.chapter == chapter && it.verse == verse
        }
        if (!alreadyThere) {
            SettingsManager.saveCollection(
                collection.copy(
                    references = collection.references +
                        SavedReference(bookNumber, chapter, verse)
                )
            )
        }
        SoundManager.play(SoundEvent.Click)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add $bookName $chapter:$verse to a collection") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (collections.isEmpty()) {
                    Text(
                        text = "No collections yet — create one below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                collections.forEach { collection ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                addTo(collection)
                            }
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = collection.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            if (collection.description.isNotBlank()) {
                                Text(
                                    text = collection.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = newCollectionName,
                    onValueChange = { newCollectionName = it },
                    label = { Text("New collection name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = {
                        val name = newCollectionName.trim()
                        if (name.isNotEmpty()) {
                            val created = CollectionEntry(
                                id = "c${System.nanoTime()}",
                                name = name,
                                references = listOf(SavedReference(bookNumber, chapter, verse))
                            )
                            SettingsManager.saveCollection(created)
                            SoundManager.play(SoundEvent.Click)
                            onDismiss()
                        }
                    },
                    enabled = newCollectionName.isNotBlank()
                ) {
                    Text("Create & add")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
