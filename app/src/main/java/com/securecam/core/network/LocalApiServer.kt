package com.securecam.core.network
import android.content.Context
import com.google.gson.Gson
import com.securecam.data.local.LogDao
import fi.iki.elisanano.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
class LocalApiServer(port: Int, val token: String, val context: Context, val logDao: LogDao) : NanoHTTPD(port) {
override fun serve(session: IHTTPSession): Response {
if (session.parameters["token"]?.firstOrNull() != token) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
return when (session.uri) {
"/api/logs" -> { if(session.method==Method.DELETE) { val id = session.parameters["id"]?.firstOrNull()?.toIntOrNull(); if(id!=null) runBlocking { logDao.deleteLogById(id) }; newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Deleted") } else { newFixedLengthResponse(Response.Status.OK, "application/json", Gson().toJson(runBlocking{logDao.getAllLogsSync()})) } }
"/api/video" -> { val f = File(context.filesDir, session.parameters["file"]?.firstOrNull() ?: ""); if (f.exists()) newChunkedResponse(Response.Status.OK, "video/mp4", FileInputStream(f)) else newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404") }
else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404")
}
}
}