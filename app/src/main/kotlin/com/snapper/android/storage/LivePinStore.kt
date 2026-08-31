package com.snapper.android.storage

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class LivePinStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))

    fun load(): SnapRepository.LivePinSession {
        synchronized(IO_LOCK) {
            return readLocked()
        }
    }

    fun write(session: SnapRepository.LivePinSession): Boolean {
        synchronized(IO_LOCK) {
            val current = readLocked()
            if (current.revision > session.revision) {
                return true
            }
            return writeLocked(session)
        }
    }

    fun clear(revision: Long): Boolean {
        val tombstone = SnapRepository.LivePinSession(revision, true, emptyList())
        publishInProcess(tombstone)
        return write(tombstone)
    }

    private fun readLocked(): SnapRepository.LivePinSession {
        return try {
            val bytes = file.readFully()
            val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
            check(root.getInt("version") == VERSION) { "Unsupported live-pin state version" }
            val array = root.getJSONArray("pins")
            val pins = ArrayList<SnapRepository.LivePinState>(array.length())
            for (index in 0 until array.length()) {
                pins.add(parsePin(array.getJSONObject(index)))
            }
            SnapRepository.LivePinSession(
                root.getLong("revision"),
                root.getBoolean("user_pins_visible"),
                pins,
            )
        } catch (missingOrUnreadable: IOException) {
            SnapRepository.LivePinSession.empty()
        } catch (malformed: JSONException) {
            Log.w(TAG, "Ignoring malformed live-pin state", malformed)
            SnapRepository.LivePinSession.empty()
        }
    }

    private fun writeLocked(session: SnapRepository.LivePinSession): Boolean {
        val root = JSONObject()
        val array = JSONArray()
        root.put("version", VERSION)
        root.put("revision", session.revision)
        root.put("user_pins_visible", session.userPinsVisible)
        for (pin in session.pins) {
            val item = JSONObject()
            item.put("basename", pin.basename)
            item.put("center_x_fraction", pin.centerXFraction.toDouble())
            item.put("center_y_fraction", pin.centerYFraction.toDouble())
            item.put("width", pin.width)
            item.put("height", pin.height)
            item.put("display_width", pin.displayWidth)
            item.put("display_height", pin.displayHeight)
            item.put("display_rotation", pin.displayRotation)
            array.put(item)
        }
        root.put("pins", array)

        val output = try {
            file.startWrite()
        } catch (failure: IOException) {
            Log.w(TAG, "Could not persist live-pin state", failure)
            return false
        }
        return try {
            output.write(root.toString().toByteArray(StandardCharsets.UTF_8))
            file.finishWrite(output)
            true
        } catch (failure: IOException) {
            file.failWrite(output)
            Log.w(TAG, "Could not persist live-pin state", failure)
            false
        }
    }

    companion object {
        private const val TAG = "SnapperLivePins"
        private const val FILE_NAME = "live-pins.json"
        private const val VERSION = 1
        private val IO_LOCK = Any()
        private val ACTIVE_LOCK = Any()
        private var activeRevision = -1L
        private var activeBasenames: Set<String> = emptySet()
        private val reservedBasenames = mutableMapOf<String, Int>()

        fun publishInProcess(session: SnapRepository.LivePinSession) {
            val names = HashSet<String>(session.pins.size)
            for (pin in session.pins) {
                names.add(pin.basename)
            }
            synchronized(ACTIVE_LOCK) {
                if (session.revision < activeRevision) {
                    return
                }
                activeRevision = session.revision
                activeBasenames = names.toSet()
            }
        }

        fun protectInProcess(basename: String) {
            val name = SnapRepository.requireValidLivePinBasename(basename)
            synchronized(ACTIVE_LOCK) {
                if (activeBasenames.contains(name)) {
                    return
                }
                val names = HashSet(activeBasenames)
                names.add(name)
                activeBasenames = names.toSet()
            }
        }

        fun nextRevision(floor: Long): Long {
            require(floor >= 0L)
            synchronized(ACTIVE_LOCK) {
                activeRevision = Math.addExact(maxOf(activeRevision, floor), 1L)
                return activeRevision
            }
        }

        fun reserveInProcess(basename: String) {
            val name = SnapRepository.requireValidLivePinBasename(basename)
            synchronized(ACTIVE_LOCK) {
                reservedBasenames.merge(name, 1, Int::plus)
            }
        }

        fun releaseInProcess(basename: String) {
            val name = SnapRepository.requireValidLivePinBasename(basename)
            synchronized(ACTIVE_LOCK) {
                val count = checkNotNull(reservedBasenames[name])
                if (count == 1) {
                    reservedBasenames.remove(name)
                } else {
                    reservedBasenames[name] = count - 1
                }
            }
        }

        fun protectedBasenames(context: Context): Set<String> {
            val session = LivePinStore(context).load()
            val names = mutableSetOf<String>()
            for (pin in session.pins) {
                names.add(pin.basename)
            }
            synchronized(ACTIVE_LOCK) {
                names.addAll(activeBasenames)
                names.addAll(reservedBasenames.keys)
            }
            return names
        }

        private fun parsePin(item: JSONObject): SnapRepository.LivePinState {
            return SnapRepository.LivePinState(
                item.getString("basename"),
                item.getDouble("center_x_fraction").toFloat(),
                item.getDouble("center_y_fraction").toFloat(),
                item.getInt("width"),
                item.getInt("height"),
                item.getInt("display_width"),
                item.getInt("display_height"),
                item.getInt("display_rotation"),
            )
        }
    }
}
