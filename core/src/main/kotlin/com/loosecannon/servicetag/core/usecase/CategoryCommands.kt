package com.loosecannon.servicetag.core.usecase

// #74's refusals (C6, C7). None carries a user-visible sentence: the words are the screen's, from the
// ratified strings, and each refusal hands over only what those words name.

/** 422: a rename to blank text — a category needs a name, and blank text has no key. */
class CategoryValidation : IllegalArgumentException("a category cannot be blank")

/** 409: a rename onto a built-in's key; [label] is that built-in's. Built-ins are never rows. */
class CategoryIsBuiltIn(val label: String) : IllegalStateException("$label is a built-in category key")

/** 409: a rename onto another row's key; [existingDisplay] is that row's spelling. */
class CategoryExists(val existingDisplay: String) : IllegalStateException("category key of $existingDisplay is taken")

/**
 * 409: a delete of a category [count] Assets still use — archived and retired ones included, the
 * number the Categories screen shows. Nothing is written, and nothing is ever reassigned by a delete.
 */
class CategoryInUse(val display: String, val count: Int) :
    IllegalStateException("category $display is used by $count asset(s)")

/** The row a rename or delete was aimed at is no longer there — a stale screen, usually. */
class NoSuchCategory(key: String) : IllegalArgumentException("no category $key")
