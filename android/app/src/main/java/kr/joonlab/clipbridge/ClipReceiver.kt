package kr.joonlab.clipbridge

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 맥에서 보낸 텍스트를 폰 클립보드에 넣는다.
 *
 * 핵심 사실(2026-09-21 실측): Android 10+ 의 "포커스 있는 앱만" 제약은
 * OP_READ_CLIPBOARD 에만 걸린다. **쓰기(setPrimaryClip)는 포커스를 요구하지 않는다.**
 * 그래서 폰→맥은 2동작이지만 맥→폰은 완전 자동이 된다.
 */
class ClipReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SET_CLIP = "kr.joonlab.clipbridge.SET_CLIP"
        private const val TAG = "ClipBridge"

        /** 백그라운드에서도 호출된다. 성공하면 true. */
        fun setClipboard(context: Context, text: String): Boolean = try {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("ClipBridge", text))
            Log.i(TAG, "clipboard set: ${text.length}자")
            true
        } catch (e: Exception) {
            Log.e(TAG, "clipboard set 실패", e)
            false
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_CLIP) return
        val text = intent.getStringExtra("text") ?: run {
            Log.w(TAG, "text extra 없음")
            return
        }
        setClipboard(context, text)
    }
}
