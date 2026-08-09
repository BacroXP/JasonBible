package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import data.BibleRepository
import data.MorphologyExplainer
import data.SoundEvent
import data.SoundManager
import data.StrongsRepository
import data.WordLexicon
import data.WordLexicon.LanguageKind


/**
 * Central word-study view ("Wortstudie"): a searchable Hebrew / Greek
 * lexicon that combines Strong's definitions, the original word, root
 * navigation, morphology explanations and every occurrence of a word —
 * fully offline from the bundled resources.
 *
 * Searching matches Strong's numbers, transliterations, original words
 * (Hebrew / Greek script) and pronunciation. Opening a result shows the
 * detail view: original word, transliteration, pronunciation, meaning,
 * the clickable ROOT (jumping to that lemma), related words, grammar
 * (tvm), readable morphology (Greek parsing codes) and all occurrences
 * grouped by book — each clickable to open the passage in the Bible.
 */
@Composable
fun LexiconScreen(
    initialQuery: String? = null,
    onInitialQueryConsumed: () -> Unit = {},
    onOpenVerse: (book: String, chapter: Int, verse: Int) -> Unit,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf(initialQuery.orEmpty()) }
    var results by remember { mutableStateOf<List<WordLexicon.SearchHit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var selectedNumber by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var defsReady by remember { mutableStateOf(false) }
    var bookNames by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }

    LaunchedEffect(Unit) {
        WordLexicon.ensureLoaded()
        ready = true
        defsReady = true
        bookNames = BibleRepository.loadBooks().associate { it.book to it.name }
        val seed = initialQuery
        if (!seed.isNullOrBlank()) {
            query = seed
            onInitialQueryConsumed()
        }
    }

    // Re-run the search whenever the query or the index readiness changes.
    // The scan walks ~15k definitions plus the whole surface-word index,
    // so it runs on a background thread; the LaunchedEffect re-keying
    // cancels a stale scan, so only the latest query's result lands.
    LaunchedEffect(query, ready) {
        if (!ready) return@LaunchedEffect
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList()
            searched = false
        } else {
            results = withContext(Dispatchers.Default) { WordLexicon.search(q) }
            searched = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    SoundManager.play(SoundEvent.Click)
                    onBack()
                    true
                } else {
                    false
                }
            }
    ) {
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
                        onBack()
                    }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
            Icon(
                imageVector = RibbonIcons.WordStudy,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Word Study",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )

        when {
            !ready || !defsReady -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Building the word index…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(14.dp))
                LexiconSearchField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))

                val current = selectedNumber
                if (current == null) {
                    // ---- Search results ----
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (searched && results.isEmpty()) {
                            Text(
                                text = "No lexicon entries found for “$query”.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!searched && query.isBlank()) {
                            Text(
                                text = "Search by Strong's number (e.g. G25 or H430), transliteration (e.g. “agapao”), the original Hebrew / Greek word, or a root. Aramaic word data is prepared but not yet bundled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        results.forEach { hit ->
                            SearchResultRow(hit = hit) {
                                SoundManager.play(SoundEvent.Click)
                                selectedNumber = hit.number
                            }
                        }
                    }
                } else {
                    // ---- Detail view ----
                    LexiconDetail(
                        number = current,
                        bookNames = bookNames,
                        onOpenNumber = { selectedNumber = it },
                        onOpenVerse = onOpenVerse,
                        onBackToResults = { selectedNumber = null }
                    )
                }
            }
        }
    }
}


@Composable
private fun LexiconSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = modifier
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}


@Composable
private fun SearchResultRow(hit: WordLexicon.SearchHit, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            NumberBadge(number = hit.number)
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = hit.originalWord.ifEmpty { hit.transliteration.ifEmpty { hit.number } },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hit.transliteration.isNotEmpty() && hit.transliteration != hit.originalWord) {
                    Text(
                        text = hit.transliteration,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}


/**
 * The detail view for one Strong's number: original word, transliteration,
 * pronunciation, meaning, clickable root, related words, grammar (tvm),
 * readable morphology (Greek parsing codes) and occurrences grouped by
 * book.
 */
@Composable
private fun LexiconDetail(
    number: String,
    bookNames: Map<Int, String>,
    onOpenNumber: (String) -> Unit,
    onOpenVerse: (String, Int, Int) -> Unit,
    onBackToResults: () -> Unit
) {
    val definition = StrongsRepository.definition(number)
    val language = WordLexicon.languageOf(number)
    val occurrences = WordLexicon.occurrences(number)
    val originalWords = WordLexicon.originalWords(number)
    val root = WordLexicon.rootOf(number)
    val related = WordLexicon.relatedNumbers(number)

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp)
    ) {
        Text(
            text = "← Search results",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                SoundManager.play(SoundEvent.Click)
                onBackToResults()
            }
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberBadge(number = number)
                    Text(
                        text = language.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (originalWords.isNotEmpty()) {
                    Text(
                        text = originalWords.take(6).joinToString(" · "),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (definition != null) {
                    if (definition.transliteration.isNotEmpty()) {
                        DetailRow("Transliteration", definition.transliteration)
                    }
                    if (definition.pronunciation.isNotEmpty()) {
                        DetailRow("Pronunciation", definition.pronunciation)
                    }
                }
            }
        }

        // ---- Meaning ----
        if (definition != null && definition.entry.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionLabel("Meaning")
                    Text(
                        text = definition.entry,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ---- Root navigation ----
        if (root != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionLabel("Root word")
                    RelatedChipRow(
                        numbers = listOf(root),
                        labelFor = { n ->
                            StrongsRepository.definition(n)?.let {
                                if (it.rootWord.isNotEmpty()) it.rootWord else it.transliteration
                            } ?: n
                        },
                        onOpenNumber = onOpenNumber
                    )
                }
            }
        }

        // ---- Grammar (tense / voice / mood record) ----
        if (definition?.tvm != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionLabel("Grammar")
                    Text(
                        text = definition.tvm,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ---- Morphology (Greek parsing codes, readable) ----
        val greekParsing = occurrences
            .filter { it.language == LanguageKind.GREEK && !it.parsing.isNullOrBlank() }
            .groupBy { it.parsing!! }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .take(8)
        if (greekParsing.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    SectionLabel("Morphology (Greek original)")
                    greekParsing.forEach { (code, count) ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = code,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = MorphologyExplainer.explain(code).joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = if (count == 1) "1 occurrence" else "$count occurrences",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- Related words ----
        if (related.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionLabel("Related words")
                    RelatedChipRow(
                        numbers = related.take(24),
                        labelFor = { n ->
                            StrongsRepository.definition(n)?.let {
                                if (it.rootWord.isNotEmpty()) it.rootWord else it.transliteration
                            } ?: n
                        },
                        onOpenNumber = onOpenNumber
                    )
                }
            }
        }

        // ---- Occurrences, grouped by book ----
        if (occurrences.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionLabel("Occurrences (${occurrences.size})")
                    val byBook = occurrences.groupBy { it.book }
                    byBook.toSortedMap().forEach { (bookNumber, list) ->
                        val name = bookNames[bookNumber] ?: "Book $bookNumber"
                        Text(
                            text = "$name (${list.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        list.forEach { occ ->
                            OccurrenceRow(
                                occurrence = occ,
                                onClick = {
                                    SoundManager.play(SoundEvent.Click)
                                    onOpenVerse(name, occ.chapter, occ.verse)
                                }
                            )
                        }
                    }
                }
            }
        }

        // ---- No data fallback ----
        if (definition == null && occurrences.isEmpty()) {
            Text(
                text = "No lexicon data available for $number.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}


@Composable
private fun OccurrenceRow(occurrence: WordLexicon.Occurrence, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = "${occurrence.chapter}:${occurrence.verse}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(min = 44.dp)
        )
        Text(
            text = occurrence.word,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        )
        Text(
            text = occurrence.sourceLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}


/** A horizontal row of clickable Strong's-number chips. */
@Composable
private fun RelatedChipRow(
    numbers: List<String>,
    labelFor: (String) -> String,
    onOpenNumber: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        numbers.forEach { number ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                modifier = Modifier.clickable {
                    SoundManager.play(SoundEvent.Click)
                    onOpenNumber(number)
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    NumberBadge(number = number)
                    Text(
                        text = labelFor(number),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}


@Composable
private fun NumberBadge(number: String) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFC107).copy(alpha = 0.18f)
        )
    ) {
        Text(
            text = number,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFFFB300)
        )
    }
}


@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}


@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 120.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
