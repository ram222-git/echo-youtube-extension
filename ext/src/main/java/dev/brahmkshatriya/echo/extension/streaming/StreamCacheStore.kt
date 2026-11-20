package dev.brahmkshatriya.echo.extension.streaming

import dev.brahmkshatriya.echo.common.models.Streamable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A resolved stream written to disk, so the **first** tap after reopening Echo can
 * be a cache hit instead of a fresh player request.
 *
 * The signed URL is stored verbatim (it is IP-bound to this device and already
 * carries its own signature) together with its absolute expiry. Entries are never
 * revived past [expiresAt], so the disk cache can only ever be as stale as the
 * in-memory one.
 */
@Serializable
public data class StoredStream(
    val key: String,
    val expiresAt: Long,
    val media: Streamable.Media
)

/** Disk layer behind [StreamMediaCache]. Cheap, best-effort and failure-tolerant. */
public interface StreamCacheStore {
    fun load(): List<StoredStream>
    fun save(streams: List<StoredStream>)
    fun clear()
}

/**
 * Persists the cache as one small JSON file in the host app's private storage.
 *
 * Writes go through a temporary file so a process kill mid-write cannot leave a
 * truncated cache behind; an unreadable file is deleted and treated as empty.
 */
public class FileStreamCacheStore(
    private val file: File,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) : StreamCacheStore {

    override fun load(): List<StoredStream> = runCatching {
        if (!file.isFile) return emptyList()
        json.decodeFromString<List<StoredStream>>(file.readText())
    }.getOrElse {
        println("StreamCacheStore: ignoring unreadable cache (${it.javaClass.simpleName}: ${it.message})")
        runCatching { file.delete() }
        emptyList()
    }

    override fun save(streams: List<StoredStream>) {
        runCatching {
            file.parentFile?.mkdirs()
            val encoded = json.encodeToString(streams)
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(encoded)
            if (!temp.renameTo(file)) {
                // Windows refuses to rename over an existing file.
                file.writeText(encoded)
                temp.delete()
            }
        }.onFailure {
            println("StreamCacheStore: could not persist ${streams.size} stream(s) (${it.message})")
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }
}

/**
 * Finds the host application context by reflection, without a compile-time
 * Android dependency, so this file also loads on a plain JVM (unit tests).
 */
public object AndroidAppContext {

    fun find(): Any? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null)
    } catch (_: Throwable) {
        try {
            Class.forName("android.app.AppGlobals")
                .getMethod("getInitialApplication")
                .invoke(null)
        } catch (_: Throwable) {
            null
        }
    }

    /** The host app's private files directory, or `null` off-device. */
    fun filesDir(): File? {
        val context = find() ?: return null
        return runCatching {
            context.javaClass.getMethod("getFilesDir").invoke(context) as? File
        }.getOrNull()
    }
}

public object AndroidStreamCacheFile {
    fun resolve(): File? = AndroidAppContext.filesDir()?.let { File(it, "stream-cache.json") }
}
