import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ── 개인 설정(맥 주소·토큰)은 소스에 두지 않는다 ────────────────────────────────
// 읽는 순서: -P 로 준 gradle 속성 → android/clipbridge.properties(커밋 안 함) → 환경변수.
// 예시는 android/clipbridge.properties.example. clipbridge-deploy 는 ~/.config/clipbridge/clipbridge.env 를
// 읽어 환경변수로 넘기므로, 그 파일만 채워 두면 따로 할 일이 없다.
val localCfg = Properties().apply {
    val f = rootProject.file("clipbridge.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(prop: String, env: String): String =
    (findProperty(prop) as String?) ?: localCfg.getProperty(prop) ?: System.getenv(env) ?: ""

val macHost = cfg("clipbridge.macHost", "CLIPBRIDGE_MAC_HOST").trim()
val cbToken = cfg("clipbridge.token", "CLIPBRIDGE_TOKEN").trim()
if (macHost.isEmpty() || cbToken.isEmpty()) {
    logger.warn("⚠️ ClipBridge: clipbridge.macHost / clipbridge.token 이 비어 있다 — 앱은 빌드되지만 맥에 닿지 않는다. " +
        "android/clipbridge.properties.example 참고")
}
fun kotlinString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Android 9+ 는 평문 HTTP 를 막는다. 전부 열지 않고 «맥 주소 하나»에만 예외를 주는
// network_security_config.xml 을 빌드 때 만든다(주소가 개인 값이라 res/ 에 고정해 둘 수 없다).
abstract class GenerateNetworkSecurityConfig : DefaultTask() {
    @get:Input abstract val host: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val xml = outputDir.get().dir("xml").asFile.apply { mkdirs() }.resolve("network_security_config.xml")
        val h = host.get().replace("&", "").replace("<", "").replace(">", "")
        val domain = if (h.isEmpty()) "" else
            "    <domain-config cleartextTrafficPermitted=\"true\">\n" +
            "        <domain includeSubdomains=\"false\">$h</domain>\n" +
            "    </domain-config>\n"
        xml.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<!-- 빌드 때 생성됨(app/build.gradle.kts). tailnet 은 WireGuard 로 암호화돼 있어 평문 HTTP 라도 선로상 노출이 없다. -->\n" +
            "<network-security-config>\n" + domain +
            "    <base-config cleartextTrafficPermitted=\"false\" />\n" +
            "</network-security-config>\n")
    }
}

val generateNsc = tasks.register<GenerateNetworkSecurityConfig>("generateNetworkSecurityConfig") {
    host.set(macHost)
    outputDir.set(layout.buildDirectory.dir("generated/clipbridge/res"))
}

android {
    namespace = "kr.joonlab.clipbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.joonlab.clipbridge"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // 정보 줄 맨 아래 caption 에만 보인다(머리줄에는 두지 않는다).
        val stamp = SimpleDateFormat("MM-dd HH:mm").apply {
            timeZone = TimeZone.getTimeZone("Asia/Seoul")
        }.format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$stamp\"")
        // 맥(clipd) 주소와 공유 토큰 — 위 cfg() 에서 읽는다
        buildConfigField("String", "MAC_HOST", kotlinString(macHost))
        buildConfigField("String", "CLIPBRIDGE_TOKEN", kotlinString(cbToken))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateNsc, GenerateNetworkSecurityConfig::outputDir)
    }
}

// 서비스·리시버·타일은 의존성 없이 돈다. Compose 는 화면(MainActivity·ShareActivity)만 쓴다.
// BOM 2026.08+ 는 compileSdk 37 을 요구하므로 2026.06.01 고정.
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
}
