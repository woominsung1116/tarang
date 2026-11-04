package com.taraang.app.vehicle

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

class AssetReplayAssets(private val assets: AssetManager, private val dir: String = "replay") : ReplayAssets {

    override fun list(): List<String> =
        try {
            assets.list(dir).orEmpty().filter { it.endsWith(".json") }.sorted()
        } catch (e: IOException) {
            emptyList()
        }

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        // 경로 조작 방지: 목록에 있는 파일 이름만 연다.
        if (name.contains('/') || name.contains("..")) return@withContext null
        try {
            assets.open("$dir/$name").bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            null
        }
    }
}
