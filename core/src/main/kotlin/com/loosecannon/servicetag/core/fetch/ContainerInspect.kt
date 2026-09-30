package com.loosecannon.servicetag.core.fetch

/** C28: the one bounded look inside a staged container. */
object ContainerInspect {
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    fun inspects(head: ByteArray): Boolean = false

    fun classify(size: Long, head: ByteArray, tail: ByteArray, inspection: BoundedInspection): String? = null
}
