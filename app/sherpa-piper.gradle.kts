import java.net.URL
import org.gradle.api.GradleException

/**
 * Downloads Sherpa-ONNX JNI (arm64) and Piper VITS models (EN + DE) on first build.
 * Requires network once; uses marker files to skip when present.
 */
val sherpaOnnxVersion = "v1.12.39"
val sherpaAndroidTar =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/$sherpaOnnxVersion/" +
        "sherpa-onnx-$sherpaOnnxVersion-android.tar.bz2"
val piperEnTar =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low.tar.bz2"
val piperDeTar =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-de_DE-thorsten-medium.tar.bz2"

fun downloadTo(url: String, dest: java.io.File) {
    dest.parentFile?.mkdirs()
    URL(url).openStream().use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    }
}

fun extractTarBz2(archive: java.io.File, workingDir: java.io.File, vararg paths: String) {
    val pb = ProcessBuilder(
        listOf("tar", "xjf", archive.absolutePath, "-C", workingDir.absolutePath) + paths.toList(),
    )
    pb.redirectErrorStream(true)
    val p = pb.start()
    val out = p.inputStream.bufferedReader().readText()
    val code = p.waitFor()
    if (code != 0) {
        throw GradleException("tar failed ($code): $out")
    }
}

tasks.register("ensureSherpaPiperAssets") {
    group = "setup"
    description = "Download Sherpa JNI + Piper models if missing (one-time, needs network)."
    doLast {
        val main = layout.projectDirectory.dir("src/main").asFile
        val jniMarker = File(main, "jniLibs/arm64-v8a/.sherpa_onnx_ok")
        val assetsRoot = File(main, "assets/tts/piper")
        val enMarker = File(assetsRoot, "vits-piper-en_US-amy-low/.piper_ok")
        val deMarker = File(assetsRoot, "vits-piper-de_DE-thorsten-medium/.piper_ok")
        val tmp = layout.buildDirectory.dir("tmp/sherpa-bootstrap").get().asFile

        if (!jniMarker.parentFile.exists()) jniMarker.parentFile.mkdirs()
        if (!jniMarker.exists()) {
            println("ensureSherpaPiperAssets: downloading Sherpa-ONNX Android JNI ($sherpaOnnxVersion)...")
            val tar = File(tmp, "sherpa-android.tar.bz2")
            downloadTo(sherpaAndroidTar, tar)
            extractTarBz2(tar, main, "jniLibs/arm64-v8a")
            jniMarker.writeText("extracted\n")
        }

        if (!enMarker.exists()) {
            println("ensureSherpaPiperAssets: downloading Piper EN (amy-low)...")
            assetsRoot.mkdirs()
            val tar = File(tmp, "piper-en.tar.bz2")
            downloadTo(piperEnTar, tar)
            extractTarBz2(tar, assetsRoot)
            enMarker.writeText("ok\n")
        }

        if (!deMarker.exists()) {
            println("ensureSherpaPiperAssets: downloading Piper DE (thorsten-medium)...")
            val tar = File(tmp, "piper-de.tar.bz2")
            downloadTo(piperDeTar, tar)
            extractTarBz2(tar, assetsRoot)
            deMarker.writeText("ok\n")
        }
    }
}

tasks.named("preBuild").configure { dependsOn("ensureSherpaPiperAssets") }
