package kr.joonlab.clipbridge

/**
 * 맥(clipd) 주소와 공유 토큰. 소스에 두지 않고 빌드 때 넣는다(app/build.gradle.kts 의 cfg()).
 * 값은 android/clipbridge.properties 또는 환경변수 CLIPBRIDGE_MAC_HOST / CLIPBRIDGE_TOKEN.
 */
object Config {
    /** 맥의 Tailscale 주소. tailnet 안에서는 네트워크가 바뀌어도 이 주소가 유지된다. */
    val MAC_HOST: String = BuildConfig.MAC_HOST
    const val MAC_PORT = 8787
    val MAC_BASE: String get() = "http://$MAC_HOST:$MAC_PORT"

    /** 맥과 나눠 갖는 토큰. tailnet 안이라도 같은 폰의 다른 앱·다른 기기는 막는다. */
    val TOKEN: String = BuildConfig.CLIPBRIDGE_TOKEN
    const val TOKEN_HEADER = "X-ClipBridge-Token"
}
