package kr.joonlab.clipbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 재부팅·앱 교체 후 수신 서버를 다시 띄운다.
 *
 * ⚠️ Android 12+ 는 **백그라운드에서 포그라운드 서비스 시작**을 막는다.
 * BOOT_COMPLETED 는 면제 목록에 있어 안전하지만, MY_PACKAGE_REPLACED 는 아니다 —
 * 폰이 Doze 중일 때 앱을 재설치하면 여기서 조용히 실패한다(2026-09-21 실측).
 * 그래서 예외를 잡아 «왜 안 떴는지»를 남긴다. 그 경우 앱을 한 번 열면 뜬다
 * (배포는 clipbridge-deploy 가 설치 직후 앱을 열어 이 구멍을 메운다).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> try {
                ClipServerService.start(context)
                Log.i("ClipBridge", "부팅/교체 후 수신 서버 시작 요청 (${intent.action})")
            } catch (e: Exception) {
                Log.w("ClipBridge", "수신 서버 자동 시작 실패 — 앱을 한 번 열 것: ${e.javaClass.simpleName}")
            }
        }
    }
}
