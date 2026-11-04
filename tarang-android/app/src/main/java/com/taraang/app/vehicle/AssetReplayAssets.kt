package com.taraang.app.vehicle

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

class AssetReplayAssets(private val assets: AssetManager, private val dir: String = "replay") : ReplayAssets {

    override fun list(): List<String> =
        try {
            replayFileNames(assets.list(dir))
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

    companion object {
        /** X.expected.json은 X.json의 기대 결과(테스트 오라클)라 재생 대상이 아니다. */
        internal fun replayFileNames(files: Array<String>?): List<String> =
            files.orEmpty().filter { it.endsWith(".json") && !it.endsWith(".expected.json") }.sorted()
    }
}
