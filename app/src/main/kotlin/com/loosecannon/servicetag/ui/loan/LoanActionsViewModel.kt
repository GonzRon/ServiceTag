package com.loosecannon.servicetag.ui.loan

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.contacts.PickedContact
import com.loosecannon.servicetag.contacts.PickedContactReader
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.usecase.LoanProblem
import com.loosecannon.servicetag.core.usecase.LoanValidation
import com.loosecannon.servicetag.core.usecase.RelinkLoanContact
import com.loosecannon.servicetag.core.usecase.ReturnLoan
import com.loosecannon.servicetag.di.AppGraph
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

/**
 * #72 (C18): the return dialog (P72-34) as typed — [date] ("Returned on", P72-35) opens on today; [problem]
 * is the line under it; [saving] is set before the first suspension, so a second tap writes once.
 */
data class ReturnPrompt(
    val loanId: String,
    val date: String,
    val problem: String? = null,
    val saving: Boolean = false,
)

/**
 * #72 (C18; R72-5, R72-15, R72-17, R72-18): the open loan's two writes from the Lending section.
 *
 * **Mark returned** — the only exit (R72-17): a dialog titled P72-34 whose date opens on today; the
 * shipped date line, P72-36 or S25 under it when refused; a return writes the loan's own row through
 * `ReturnLoan` (the row stays, as history) and then runs the in-app sweep once, so a reminder waiting on
 * the loan is withdrawn now (R72-15). **Choose from Contacts** — a relink of the open loan: the pick is
 * read once, in its callback, and `RelinkLoanContact` replaces the link and the name together; no form,
 * and no sweep (the borrower rides in the reminder's facts, never its content). P72-30 and P72-46 are
 * snackbars; anything else is P72-32, logged. A returned loan is frozen (R72-18): nothing here writes
 * to one.
 */
class LoanActionsViewModel(
    private val returnLoan: ReturnLoan,
    private val relinkLoanContact: RelinkLoanContact,
    private val reader: PickedContactReader,
    private val today: Today,
    /** The shipped in-app sweep (`graph.reminderReconcile`), run once after a return. Null runs none. */
    private val reconcile: ReminderReconcile? = null,
    /** Where a pick is read: `Dispatchers.IO` in the app, a test's own scheduler on the JVM. */
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    constructor(graph: AppGraph, reader: PickedContactReader = graph.pickedContactReader) : this(
        graph.returnLoan, graph.relinkLoanContact, reader, graph.today, graph.reminderReconcile,
    )

    private val _returning = MutableStateFlow<ReturnPrompt?>(null)
    val returning: StateFlow<ReturnPrompt?> = _returning.asStateFlow()

    /** P72-30, P72-45, P72-46 and P72-32, once each. */
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** "Mark returned" on the open loan [loanId]: the dialog opens on today and writes nothing. */
    fun askReturn(loanId: String) {
        _returning.value = ReturnPrompt(loanId, today.localDate().toString())
    }

    fun onReturnedOn(text: String) = _returning.update { prompt ->
        if (prompt == null || prompt.saving) prompt else prompt.copy(date = text, problem = null)
    }

    /** "Cancel", or any other dismissal: nothing is written. */
    fun dismissReturn() = _returning.update { prompt -> prompt?.takeIf { it.saving } }

    /** The dialog's "Mark returned": one `ReturnLoan`, then the sweep once. */
    fun confirmReturn() {
        val prompt = _returning.value ?: return
        if (prompt.saving) return
        _returning.value = prompt.copy(saving = true, problem = null)
        viewModelScope.launch {
            try {
                returnLoan.run(AssetLoanId(prompt.loanId), prompt.date)
            } catch (refused: LoanValidation) {
                val line = refused.problems.firstNotNullOfOrNull(::returnLineFor)
                if (line != null) {
                    _returning.value = prompt.copy(problem = line)
                } else {
                    Log.w(TAG, "a return the dialog could not mark was refused", refused)
                    _returning.value = null
                    _messages.tryEmit(COULD_NOT_SAVE_THIS_LOAN)
                }
                return@launch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                Log.w(TAG, "a return failed", failed)
                _returning.value = null
                _messages.tryEmit(COULD_NOT_SAVE_THIS_LOAN)
                return@launch
            }
            _returning.value = null
            reconcile?.let { sweep ->
                runCatching { sweep.run() }.onFailure { if (it is CancellationException) throw it }
            }
        }
    }

    /**
     * "Choose from Contacts" on the open loan [loanId]: the pick's [uri], read once, now. A person or an
     * organisation relinks the loan — link and name together, no sweep; P72-30 or P72-46 say why not.
     */
    fun relink(loanId: String, uri: String) {
        viewModelScope.launch {
            when (val read = withContext(io) { reader.read(uri) }) {
                PickedContact.NoName -> _messages.tryEmit(THIS_CONTACT_HAS_NO_NAME_TO_SHOW)
                PickedContact.Unreadable -> _messages.tryEmit(COULD_NOT_READ_THIS_CONTACT)
                is PickedContact.Picked -> try {
                    relinkLoanContact.run(AssetLoanId(loanId), read.lookupUri, read.displayName)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    // A loan returned meanwhile (frozen), gone, or a store failure.
                    Log.w(TAG, "a relink failed", failed)
                    _messages.tryEmit(COULD_NOT_SAVE_THIS_LOAN)
                }
            }
        }
    }

    /** P72-45: the phone has no contact picker. */
    fun onNoPicker() {
        _messages.tryEmit(NO_APP_CAN_PICK_A_CONTACT)
    }

    /** "Open contact" answered false (C15): P72-20. */
    fun say(line: String) {
        _messages.tryEmit(line)
    }

    private companion object {
        const val TAG = "LoanActions"
    }
}

/** The return date's refusal, in its ratified words; null for a problem the dialog cannot explain. */
private fun returnLineFor(problem: LoanProblem): String? = when (problem) {
    is LoanProblem.BadDate -> ENTER_A_DATE.takeIf { problem.field == "returnedOn" }
    LoanProblem.ReturnedBeforeLent -> THE_RETURN_DATE_CANNOT_BE_BEFORE_LENT
    LoanProblem.ReturnedAfterToday -> DATE_NOT_LATER_THAN_TODAY
    else -> null
}
