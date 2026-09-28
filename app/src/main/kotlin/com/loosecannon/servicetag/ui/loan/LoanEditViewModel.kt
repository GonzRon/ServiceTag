package com.loosecannon.servicetag.ui.loan

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.contacts.PickedContact
import com.loosecannon.servicetag.contacts.PickedContactReader
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.usecase.AssetAlreadyLent
import com.loosecannon.servicetag.core.usecase.LendAsset
import com.loosecannon.servicetag.core.usecase.LoanProblem
import com.loosecannon.servicetag.core.usecase.LoanTerms
import com.loosecannon.servicetag.core.usecase.LoanValidation
import com.loosecannon.servicetag.core.usecase.UpdateLoan
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.reminders.NotificationPermission
import com.loosecannon.servicetag.ui.condition.DATE_NOT_LATER_THAN_TODAY
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The lend form's fields that can carry a refusal (C17), by the key [LoanForm.problems] uses. */
object LoanField {
    const val BORROWER = "borrower"
    const val LENT_ON = "lentOn"
    const val DUE_ON = "dueOn"
}

/**
 * #72 (C17): the lend form as typed. [isNew] is "Lend out" (P72-14 its title, a picked borrower its
 * only borrower); an edit is "Edit loan" (P72-13), its borrower shown as text with no picker. [borrower]
 * and [lookupUri] are the pick's name and link — never typed. [reading] is a pick being read; [saving]
 * is set before the first suspension and stays set once a write has landed, so a second tap never
 * writes a second loan. [askingForNotifications] is P72-33, up after a write that first set a reminder.
 */
data class LoanForm(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val borrower: String = "",
    val lookupUri: String? = null,
    val lentOn: String = "",
    val dueOn: String = "",
    val mode: LoanReminderMode = LoanReminderMode.NONE,
    val notes: String = "",
    val problems: Map<String, String> = emptyMap(),
    val reading: Boolean = false,
    val saving: Boolean = false,
    val askingForNotifications: Boolean = false,
) {
    /** P72-14 on a new loan, P72-13 on an edit. */
    val screenTitle: String get() = if (isNew) LEND_OUT else EDIT_LOAN

    /** The reminder chips are enabled only with a due date; without one P72-27 is drawn under them. */
    val remindersEnabled: Boolean get() = dueOn.isNotBlank()

    val canSave: Boolean get() = loaded && !saving && !reading
}

/**
 * #72 (C17; R72-3, R72-9, R72-11, R72-15, R72-17): one loan's terms, new or corrected.
 *
 * **New** — on an asset the section offered "Lend out" on: the borrower is a contact picked from
 * Android Contacts ([onPicked] reads it once, through [reader], in the pick's callback) and never a typed
 * name; "Lent on" opens on today. **Edit** — an open loan's lent date, due date, reminder and notes;
 * its borrower is shown as text, and relinking lives on the section, not here.
 *
 * Clearing the due date resets the reminder to None: the use case refuses a reminder with no due date,
 * and the form never sends one. Every refusal lands on its field with its ratified line; a stale form
 * — the asset lent meanwhile — is P72-37 on the snackbar, and any other failure P72-32, logged. Cancel
 * and back write nothing: only [save] writes, once per tap.
 *
 * **After the write** (C17): when the reminder went from None — or from no loan — to Once or Until
 * returned and notifications are not granted, P72-33 goes up, at most once per editor; [requestNotifications]
 * ("OK") is the only place the permission is requested, and [dismissNotifications] ("Not now") requests
 * nothing. The lend form is the permission's third requester (R72-9). Then the in-app sweep runs once
 * when the loan is new or its due date or reminder moved — never for the notes or the lent date — so a
 * reminder is posted or withdrawn now rather than at the next digest (R72-15). Then [saved].
 */
class LoanEditViewModel(
    private val assets: AssetRepository,
    private val loans: AssetLoanRepository,
    private val lendAsset: LendAsset,
    private val updateLoan: UpdateLoan,
    private val reader: PickedContactReader,
    private val today: Today,
    private val assetId: AssetId,
    private val loanId: AssetLoanId?,
    /** Where a pick is read: `Dispatchers.IO` in the app, a test's own scheduler on the JVM. */
    private val io: CoroutineContext = Dispatchers.IO,
    /** The notification permission, as the schedule and asset editors take it. Null asks nothing. */
    private val notifications: NotificationPermission? = null,
    /** The shipped in-app sweep (`graph.reminderReconcile`). Null runs none. */
    private val reconcile: ReminderReconcile? = null,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: String, loanId: String?, reader: PickedContactReader = graph.pickedContactReader) : this(
        graph.assets, graph.loans, graph.lendAsset, graph.updateLoan, reader, graph.today,
        AssetId(assetId), loanId?.let(::AssetLoanId),
        notifications = graph.notificationPermission,
        reconcile = graph.reminderReconcile,
    )

    private val _state = MutableStateFlow(LoanForm(isNew = loanId == null))
    val state: StateFlow<LoanForm> = _state.asStateFlow()

    /** True once the asset (a new loan) or the loan (an edit) is not there: the screen leaves. */
    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing.asStateFlow()

    /** The written loan's asset id, once: the screen leaves. */
    private val _saved = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val saved: SharedFlow<String> = _saved.asSharedFlow()

    /** P72-37 and P72-32, once per failure. */
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** The loan as the editor opened it; null for a new loan. The sweep and P72-33 compare against it. */
    private var loaded: AssetLoan? = null

    /** P72-33 is asked at most once per editor. */
    private var rationaleAsked = false

    /** The written loan waiting on P72-33's answer before the sweep and [saved]. */
    private var afterRationale: AssetLoan? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (loanId != null) {
            val loan = loans.get(loanId) ?: return run { _missing.value = true }
            loaded = loan
            _state.update {
                it.copy(
                    loaded = true,
                    borrower = loan.borrowerName,
                    lookupUri = loan.contactLookupUri,
                    lentOn = loan.lentOn,
                    dueOn = loan.dueOn.orEmpty(),
                    mode = loan.reminderMode,
                    notes = loan.notes,
                )
            }
            return
        }
        assets.get(assetId) ?: return run { _missing.value = true }
        _state.update { it.copy(loaded = true, lentOn = today.localDate().toString()) }
    }

    fun onLentOn(value: String) = edit(LoanField.LENT_ON) { it.copy(lentOn = value) }

    /** A cleared due date takes the reminder back to None (P72-27 is drawn under the disabled chips). */
    fun onDueOn(value: String) = edit(LoanField.DUE_ON) {
        it.copy(dueOn = value, mode = if (value.isBlank()) LoanReminderMode.NONE else it.mode)
    }

    /** A reminder needs a due date: without one the chips are disabled, and a choice is ignored. */
    fun onMode(value: LoanReminderMode) = edit { form ->
        if (form.remindersEnabled || value == LoanReminderMode.NONE) form.copy(mode = value) else form
    }

    fun onNotes(value: String) = edit { it.copy(notes = value) }

    /**
     * An edit clears the lines of the fields it touches, and nothing while a save is out. A pick being
     * read does not stop the typing: its answer only ever touches the borrower.
     */
    private fun edit(vararg clears: String, change: (LoanForm) -> LoanForm) = _state.update { form ->
        if (form.saving) form else change(form).copy(problems = form.problems - clears.toSet())
    }

    /**
     * The pick's result, from its callback (C15): read once, now, while its grant lasts. A person or an
     * organisation becomes the borrower and its link; a nameless contact is P72-30 and an unreadable
     * one P72-46, both under Borrower, and either leaves no borrower behind. An edit has no picker.
     */
    fun onPicked(uri: String) {
        val form = _state.value
        if (!form.isNew || !form.loaded || form.saving || form.reading) return
        _state.update { it.copy(reading = true) }
        viewModelScope.launch {
            val read = withContext(io) { reader.read(uri) }
            _state.update {
                when (read) {
                    is PickedContact.Picked -> it.copy(
                        reading = false,
                        borrower = read.displayName,
                        lookupUri = read.lookupUri,
                        problems = it.problems - LoanField.BORROWER,
                    )
                    PickedContact.NoName -> it.withoutBorrower(THIS_CONTACT_HAS_NO_NAME_TO_SHOW)
                    PickedContact.Unreadable -> it.withoutBorrower(COULD_NOT_READ_THIS_CONTACT)
                }
            }
        }
    }

    /** P72-45: the phone has no contact picker. Nothing is written. */
    fun onNoPicker() {
        _messages.tryEmit(NO_APP_CAN_PICK_A_CONTACT)
    }

    /**
     * The one write: `LendAsset` for a new loan, `UpdateLoan` for an edit. `saving` is set before the
     * first suspension, so a double tap writes one loan; after a write it stays set and the screen leaves
     * on [saved] — through P72-33 first when [holdForRationale] says so.
     */
    fun save() {
        val form = _state.value
        if (!form.canSave) return
        _state.update { it.copy(saving = true, problems = emptyMap()) }
        viewModelScope.launch {
            val terms = LoanTerms(
                lentOn = form.lentOn.trim(),
                dueOn = form.dueOn.trim().ifBlank { null },
                reminderMode = if (form.dueOn.isBlank()) LoanReminderMode.NONE else form.mode,
                notes = form.notes,
            )
            val written = try {
                if (loanId == null) {
                    lendAsset.run(assetId, form.borrower, terms, form.lookupUri)
                } else {
                    updateLoan.run(loanId, terms)
                }
            } catch (refused: LoanValidation) {
                val marks = refused.problems.mapNotNull(::markFor).toMap()
                _state.update { it.copy(saving = false, problems = marks) }
                if (refused.problems.any { markFor(it) == null }) {
                    Log.w(TAG, "a loan save the form could not mark was refused", refused)
                    _messages.tryEmit(COULD_NOT_SAVE_THIS_LOAN)
                }
                return@launch
            } catch (lent: AssetAlreadyLent) {
                _state.update { it.copy(saving = false) }
                _messages.tryEmit(THIS_ASSET_IS_ALREADY_LENT_OUT)
                return@launch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                // LoanReturned, a vanished asset or loan, or a store failure: nothing the form can mark.
                Log.w(TAG, "a loan save failed", failed)
                _state.update { it.copy(saving = false) }
                _messages.tryEmit(COULD_NOT_SAVE_THIS_LOAN)
                return@launch
            }
            if (holdForRationale(written)) {
                _state.update { it.copy(askingForNotifications = true) }
            } else {
                finish(written)
            }
        }
    }

    /** P72-33's "OK": the only request this editor makes, after the loan is written. Either answer finishes. */
    fun requestNotifications() {
        val written = afterRationale ?: return
        afterRationale = null
        viewModelScope.launch {
            runCatching { notifications?.request() }.onFailure { if (it is CancellationException) throw it }
            _state.update { it.copy(askingForNotifications = false) }
            finish(written)
        }
    }

    /** P72-33's "Not now", and any other dismissal: nothing is requested; the loan stands. */
    fun dismissNotifications() {
        val written = afterRationale ?: return
        afterRationale = null
        _state.update { it.copy(askingForNotifications = false) }
        viewModelScope.launch { finish(written) }
    }

    /**
     * P72-33 goes up when the write first set a reminder — the loaded loan had None, or there was no
     * loan — while notifications are not granted, and only once per editor.
     */
    private fun holdForRationale(written: AssetLoan): Boolean {
        val permission = notifications ?: return false
        val before = loaded?.reminderMode ?: LoanReminderMode.NONE
        if (rationaleAsked || before != LoanReminderMode.NONE || written.reminderMode == LoanReminderMode.NONE) return false
        if (permission.granted()) return false
        rationaleAsked = true
        afterRationale = written
        return true
    }

    /** The sweep once when the loan is new or its due date or reminder moved, then [saved]. */
    private suspend fun finish(written: AssetLoan) {
        val before = loaded
        val moved = before == null || before.dueOn != written.dueOn || before.reminderMode != written.reminderMode
        if (moved) {
            reconcile?.let { sweep ->
                runCatching { sweep.run() }.onFailure { if (it is CancellationException) throw it }
            }
        }
        _saved.tryEmit(written.assetId.value)
    }

    private companion object {
        const val TAG = "LoanEdit"
    }
}

/** A failed pick: its line under Borrower, and no borrower left behind. */
private fun LoanForm.withoutBorrower(line: String): LoanForm =
    copy(reading = false, borrower = "", lookupUri = null, problems = problems + (LoanField.BORROWER to line))

/** One refusal on its field with its ratified line, or null for one no field of this form can explain. */
private fun markFor(problem: LoanProblem): Pair<String, String>? = when (problem) {
    LoanProblem.BorrowerRequired -> LoanField.BORROWER to CHOOSE_A_BORROWER
    LoanProblem.ContactLinkInvalid -> LoanField.BORROWER to COULD_NOT_READ_THIS_CONTACT
    is LoanProblem.BadDate -> when (problem.field) {
        "lentOn" -> LoanField.LENT_ON to ENTER_A_DATE
        "dueOn" -> LoanField.DUE_ON to ENTER_A_DATE
        else -> null
    }
    LoanProblem.LentAfterToday -> LoanField.LENT_ON to DATE_NOT_LATER_THAN_TODAY
    LoanProblem.DueBeforeLent -> LoanField.DUE_ON to THE_DUE_DATE_CANNOT_BE_BEFORE_LENT
    else -> null
}
