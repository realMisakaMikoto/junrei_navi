package cn.anitabi.navigator.data.repository

import cn.anitabi.navigator.core.model.PlannerDraft
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class PlannerDraftProblem { MISSING, CORRUPT, UNSUPPORTED, WRITE_FAILED }

data class PlannerDraftState(
    val initialized: Boolean = false,
    val draft: PlannerDraft? = null,
    val revision: Long = 0,
    val problem: PlannerDraftProblem? = null,
)

@Serializable
data class PlannerDraftEnvelope(
    val schemaVersion: Int = 1,
    val revision: Long,
    /** A null draft is a durable tombstone; never restore an older draft over it. */
    val draft: PlannerDraft?,
)

sealed interface PlannerDraftRead {
    data class Loaded(val envelope: PlannerDraftEnvelope) : PlannerDraftRead
    data class Unavailable(val problem: PlannerDraftProblem) : PlannerDraftRead
}

interface PlannerDraftStorage {
    suspend fun read(): PlannerDraftRead
    suspend fun write(envelope: PlannerDraftEnvelope)
}

/** Construct with a dedicated directory below Context.filesDir, never cacheDir. */
class FilePlannerDraftStorage(
    private val directory: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlannerDraftStorage {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val current get() = File(directory, "current.json")

    override suspend fun read(): PlannerDraftRead = withContext(dispatcher) {
        if (!current.exists()) return@withContext PlannerDraftRead.Unavailable(PlannerDraftProblem.MISSING)
        try {
            val content = json.parseToJsonElement(current.readText())
            val version = content.jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
                ?: return@withContext PlannerDraftRead.Unavailable(PlannerDraftProblem.CORRUPT)
            if (version != 1) return@withContext PlannerDraftRead.Unavailable(PlannerDraftProblem.UNSUPPORTED)
            val envelope = json.decodeFromJsonElement(PlannerDraftEnvelope.serializer(), content)
            require(envelope.revision >= 0)
            envelope.draft?.validate()
            PlannerDraftRead.Loaded(envelope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PlannerDraftRead.Unavailable(PlannerDraftProblem.CORRUPT)
        }
    }

    override suspend fun write(envelope: PlannerDraftEnvelope): Unit = withContext(dispatcher) {
        envelope.draft?.validate()
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "current.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.encodeToString(PlannerDraftEnvelope.serializer(), envelope).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        // Same-directory atomic replacement leaves either complete revision, including a tombstone.
        Files.move(temporary.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }
}

/** One app-scoped owner. Edits publish immediately; one writer checkpoints at most 250 ms later. */
class PlannerDraftRepository(
    private val storage: PlannerDraftStorage,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val checkpointMillis: Long = 250,
) {
    private val lock = Any()
    private val writes = Mutex()
    private val wakeWriter = Channel<Unit>(Channel.CONFLATED)
    private val loaded = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(PlannerDraftState())
    val state: StateFlow<PlannerDraftState> = mutableState.asStateFlow()
    private var edited = false
    private var persistedRevision = -1L

    init {
        scope.launch {
            val result = try {
                storage.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PlannerDraftRead.Unavailable(PlannerDraftProblem.CORRUPT)
            }
            synchronized(lock) {
                val disk = (result as? PlannerDraftRead.Loaded)?.envelope
                if (!edited) {
                    mutableState.value = PlannerDraftState(
                        initialized = true,
                        draft = disk?.draft,
                        revision = disk?.revision ?: 0,
                        problem = (result as? PlannerDraftRead.Unavailable)?.problem,
                    )
                    persistedRevision = disk?.revision ?: -1
                } else {
                    // The old disk read must not replace a selection started while it was in flight.
                    mutableState.value = mutableState.value.copy(
                        initialized = true,
                        revision = maxOf(mutableState.value.revision, (disk?.revision ?: 0) + 1),
                    )
                }
            }
            loaded.complete(Unit)
        }
        scope.launch {
            loaded.await()
            for (signal in wakeWriter) {
                delay(checkpointMillis)
                // Fixed-window coalescing, not debounce: continuous edits cannot defer every write.
                while (wakeWriter.tryReceive().isSuccess) Unit
                persistLatest()
            }
        }
    }

    suspend fun awaitLoaded(): PlannerDraftState {
        loaded.await()
        return state.value
    }

    fun replace(draft: PlannerDraft): String {
        draft.validate()
        synchronized(lock) { publish(draft) }
        return draft.draftId
    }

    fun update(draftId: String, transform: (PlannerDraft) -> PlannerDraft): Boolean = synchronized(lock) {
        val current = state.value.draft?.takeIf { it.draftId == draftId } ?: return@synchronized false
        val next = transform(current)
        require(next.draftId == current.draftId)
        next.validate()
        if (next != current) publish(next)
        true
    }

    /** Also invalidates pending restores and is written even if no draft was loaded yet. */
    fun clear(draftId: String? = null) {
        synchronized(lock) {
            if (draftId != null && state.value.draft?.draftId != draftId) return
            publish(null)
        }
        scope.launch { flush() }
    }

    private fun publish(draft: PlannerDraft?) {
        edited = true
        mutableState.value = mutableState.value.copy(draft = draft, revision = state.value.revision + 1, problem = null)
        wakeWriter.trySend(Unit)
    }

    /** Page transitions must await this and verify the same draft still owns the selection. */
    suspend fun flush(draftId: String? = null): Boolean {
        loaded.await()
        while (true) {
            if (draftId != null && state.value.draft?.draftId != draftId) return false
            if (!persistLatest()) return false
            val durable = synchronized(lock) { persistedRevision >= state.value.revision }
            if (durable) return draftId == null || state.value.draft?.draftId == draftId
        }
    }

    private suspend fun persistLatest(): Boolean = writes.withLock {
        val snapshot = synchronized(lock) {
            val value = state.value
            if (persistedRevision >= value.revision) return@withLock true
            PlannerDraftEnvelope(revision = value.revision, draft = value.draft)
        }
        try {
            storage.write(snapshot)
            synchronized(lock) {
                persistedRevision = snapshot.revision
                if (state.value.revision == snapshot.revision && state.value.problem == PlannerDraftProblem.WRITE_FAILED) {
                    mutableState.value = state.value.copy(problem = null)
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            synchronized(lock) { mutableState.value = state.value.copy(problem = PlannerDraftProblem.WRITE_FAILED) }
            false
        }
    }
}
