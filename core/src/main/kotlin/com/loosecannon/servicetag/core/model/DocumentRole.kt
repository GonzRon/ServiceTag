package com.loosecannon.servicetag.core.model

/**
 * What a document is *for* on its asset (#67, C1). Independent of the document's kind — an
 * attachment's [AttachmentKind] or a reference's [ReferenceKind]: neither one constrains the other
 * (R67-7), and any number of an asset's documents may share a role (R67-3). Null is "no role".
 *
 * The values name no owner. Who may carry one is each owner's own rule, stated once beside that
 * owner: `AttachmentOwner.accepts` for an attachment (R67-11) and `ReferenceKind.accepts` for a
 * reference (#91, R91-1). A later owner adds its own rule and reuses these values unchanged.
 */
enum class DocumentRole { PURCHASE_INVOICE_OR_RECEIPT, USER_MANUAL, SERVICE_MANUAL }
