package app.filo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

object FileSearch {
    /** Walks [root] and emits growing result lists for names containing [query]. */
    fun search(root: File, query: String, showHidden: Boolean, limit: Int = 300): Flow<List<FileEntry>> = flow {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        val results = mutableListOf<FileEntry>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        var lastEmitted = -1
        while (stack.isNotEmpty() && results.size < limit) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children.sortedBy { it.name.lowercase() }) {
                if (child.name in FileOps.internalNames) continue
                if (!showHidden && child.name.startsWith(".")) continue
                if (child.name.lowercase().contains(needle)) {
                    results += FileEntry.from(child, countChildren = true, showHidden = showHidden)
                    if (results.size >= limit) break
                }
                if (child.isDirectory && !FileOps.isSymlink(child)) stack.addLast(child)
            }
            if (results.size - lastEmitted >= 25) {
                emit(results.toList())
                lastEmitted = results.size
            }
        }
        emit(results.toList())
    }.flowOn(Dispatchers.IO)
}
