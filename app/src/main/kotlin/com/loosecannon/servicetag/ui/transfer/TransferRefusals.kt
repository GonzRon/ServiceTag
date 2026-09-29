package com.loosecannon.servicetag.ui.transfer

import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.ui.transfer.`import`.TransferImportStrings

/**
 * #77 (C19, B4 hand-off 1): the one mapping of a write the guard refused because its asset was transferred out from
 * this phone — P77-35, from its one home — onto the line a screen would otherwise say. A stale screen (one opened
 * before the asset left) is the only way to reach such a write; everything else hides the action.
 */
internal fun Throwable.transferredOutOr(otherwise: String): String =
    if (this is AssetTransferredOut) TransferImportStrings.ASSET_TRANSFERRED_OUT else otherwise
