package kr.joonlab.clipbridge

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * 빠른 설정 패널의 "맥으로 복사" 타일.
 *
 * 왜 타일에서 클립보드를 직접 읽지 않고 액티비티를 띄우는가:
 * Android 10+ 는 "in focus" 인 앱에만 클립보드를 준다. 타일 서비스 자체는 포커스가
 * 아니지만, 타일이 띄운 액티비티는 포커스를 받는다. 그래서 투명 액티비티를 한 번
 * 통과시켜 읽는다 — 눈에는 거의 안 보인다.
 *
 * (오버레이+READ_LOGS 로 백그라운드에서 읽는 KDE Connect 식 우회로를 먼저 시도했으나
 *  Android 17 에서 ClipboardService 가 "not in focus" 로 거부한다. 실측 2026-09-20.)
 */
class ClipTileService : TileService() {

    override fun onClick() {
        super.onClick()

        val intent = Intent(this, ShareActivity::class.java).apply {
            action = ShareActivity.ACTION_SEND_CLIPBOARD
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
