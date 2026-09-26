package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.journal.CategoryKey
import com.loosecannon.servicetag.core.model.Asset

/**
 * How many Assets use a category (#74, C8), counted over **every** Asset — archived and retired
 * included — by key. The Categories screen shows this number and [DeleteCategory] refuses with it,
 * so the two agree even though the Assets list hides archived rows by default.
 */
object CategoryUsage {
    fun count(assets: Collection<Asset>, key: String): Int = assets.count { CategoryKey.of(it.category) == key }
}
