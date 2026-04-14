package com.securecam.core.network

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

// CRITICAL FIX: Corrected import to fi.iki.elonen and decoupled from Room DAO to guarantee successful compilation
class LocalApiServer(port: Int, val token: String, val context: Context) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        if (session.parameters["token"]?.firstOrNull() != token) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        return when (session.uri) {
            "/api/logs" -> { 
                try {
                    val dbFolder = context.getDatabasePath("dummy").parentFile
                    val dbs = dbFolder?.listFiles { _, name -> name.endsWith(".db") && !name.contains("google") }
                    val activeDb = dbs?.firstOrNull()
                    if (activeDb == null) return newFixedLengthResponse(Response.Status.OK, "application/json", "[]")

                    val db = SQLiteDatabase.openDatabase(activeDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                    if (session.method == Method.DELETE) {
                        val id = session.parameters["id"]?.firstOrNull()
                        if (id != null) {
                            val c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name!='android_metadata' AND name!='sqlite_sequence' AND name!='room_master_table'", null)
                            if (c.moveToFirst()) db.execSQL("DELETE FROM ${c.getString(0)} WHERE id = $id")
                            c.close()
                        }
                        db.close()
                        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Deleted")
                    } else {
                        val logs = mutableListOf<Map<String, Any?>>()
                        val c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name!='android_metadata' AND name!='sqlite_sequence' AND name!='room_master_table'", null)
                        if (c.moveToFirst()) {
                            val dc = db.rawQuery("SELECT * FROM ${c.getString(0)} ORDER BY logTime DESC", null)
                            val cols = dc.columnNames
                            while (dc.moveToNext()) {
                                val m = mutableMapOf<String, Any?>()
                                for (i in cols.indices) {
                                    when (dc.getType(i)) {
                                        android.database.Cursor.FIELD_TYPE_INTEGER -> m[cols[i]] = dc.getLong(i)
                                        android.database.Cursor.FIELD_TYPE_FLOAT -> m[cols[i]] = dc.getFloat(i)
                                        android.database.Cursor.FIELD_TYPE_STRING -> m[cols[i]] = dc.getString(i)
                                        else -> m[cols[i]] = null
                                    }
                                }
                                logs.add(m)
                            }
                            dc.close()
                        }
                        c.close()
                        db.close()
                        newFixedLengthResponse(Response.Status.OK, "application/json", Gson().toJson(logs))
                    }
                } catch(e: Exception) {
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
                }
            }
            "/api/video" -> { 
                val f = File(context.filesDir, session.parameters["file"]?.firstOrNull() ?: "")
                if (f.exists()) newChunkedResponse(Response.Status.OK, "video/mp4", FileInputStream(f)) 
                else newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404") 
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404")
        }
    }
}