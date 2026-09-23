package com.loosecannon.servicetag.ui.asset

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * The fields a search reaches (#39), moved to the Assets screen by the owner's 2026-09-23
 * instruction that the quick filter leaves the Dashboard: the name; the category the row already
 * shows as its own subtitle, so someone who typed "pump" there expects it to work here too; and
 * the four an owner reads off the machine itself when they cannot remember what they called it —
 * make, model, serial, and where the thing is. `description`, `notes`, `vendor` and the warranty
 * prose are deliberately out: they are paragraphs, and a row that shows a name and its parent could
 * not explain a hit buried in one.
 */
private val SEARCHED_FIELDS: List<(Asset) -> String> = listOf(
    Asset::name,
    Asset::category,
    Asset::manufacturer,
    Asset::model,
    Asset::serialNumber,
    Asset::location,
)

/** Case-insensitive substring over [SEARCHED_FIELDS]. A blank query matches everything. */
internal fun Asset.matches(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return SEARCHED_FIELDS.any { field -> field(this).contains(needle, ignoreCase = true) }
}

/**
 * The quick filter (#39), moved here from the Dashboard by the owner's 2026-09-23 instruction. An
 * `OutlinedTextField`, not a Material 3 `SearchBar`: a `SearchBar` expands over the screen and owns
 * a results surface of its own, and what this needs is one line that narrows the list already
 * underneath it. The clear action appears only once there is something to clear, so a first look is
 * not two glyphs and a hint.
 */
@Composable
internal fun SearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = ControlShape,
        placeholder = { Text("Search assets and components") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                }
            }
        },
        // Once text is entered the placeholder is gone and the field has no accessible name, and
        // every other field in the app gets one from `FormField`'s label (F10). A `label` here would
        // be a new string, so the ratified placeholder is reused rather than a fifth sentence added.
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Search assets and components" },
    )
}
