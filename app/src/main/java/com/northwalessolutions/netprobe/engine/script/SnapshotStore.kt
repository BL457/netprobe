package com.northwalessolutions.netprobe.engine.script

import java.io.File
import java.io.IOException

/**
 * The page pictures of script runs, as JPEG files under `<root>/<runId>/<n>.jpg` (spec section 12). A picture is named by its relative path,
 * `<runId>/<n>.jpg`, which is what a finding stores; [fileFor] only ever resolves a path of exactly that shape that stays inside the root.
 * A run's folder is deleted with the run's history row ([deleteRun], [sweep]).
 */
class SnapshotStore(val root: File) {
    /** Writes picture [n] of run [runId] and returns its relative path. */
    fun write(runId: Long, n: Int, jpeg: ByteArray): String {
        val dir = File(root, runId.toString())
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create ${dir.name}")
        val relative = "$runId/$n.jpg"
        File(root, relative).writeBytes(jpeg)
        return relative
    }

    /** The file for a stored relative path, or null when the path is malformed, outside the root or missing. */
    fun fileFor(relative: String): File? {
        if (!PATH.matches(relative)) return null
        val file = File(root, relative)
        val inside = try { file.canonicalPath.startsWith(root.canonicalPath + File.separator) } catch (_: IOException) { false }
        return file.takeIf { inside && it.isFile }
    }

    /** Deletes every picture of one run. */
    fun deleteRun(runId: Long) { File(root, runId.toString()).deleteRecursively() }

    /**
     * Deletes the folder of every run that is not in [liveRunIds] (the runs still in the history). Folders whose name is not a run id are
     * left alone, and so are folders of runs newer than [highestRunId] (the highest id issued when the caller read [liveRunIds]): a run that
     * started after that read has a row the caller has not seen. Returns how many folders were deleted.
     */
    fun sweep(liveRunIds: Set<Long>, highestRunId: Long = Long.MAX_VALUE): Int {
        var deleted = 0
        for (dir in root.listFiles().orEmpty()) {
            val id = dir.name.toLongOrNull() ?: continue
            if (id in liveRunIds || id > highestRunId || !dir.isDirectory) continue
            if (dir.deleteRecursively()) deleted++
        }
        return deleted
    }

    private companion object { val PATH = Regex("[0-9]{1,18}/[0-9]{1,6}\\.jpg") }
}
