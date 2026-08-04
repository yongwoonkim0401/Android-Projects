package com.example.sangilwidget

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class HikingAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingCommand: String? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val command = intent.getStringExtra(Constants.EXTRA_COMMAND) ?: return
            val prefs = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
            val pkg = prefs.getString(Constants.PREF_PACKAGE, Constants.SANGIL_PACKAGE)!!

            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent == null) {
                toast("산길샘 앱을 찾을 수 없습니다.\n설정에서 패키지명을 확인하세요.")
                return
            }

            // 산길샘이 이미 포그라운드인지 확인
            val currentPkg = rootInActiveWindow?.packageName?.toString()
            if (currentPkg == pkg) {
                // 이미 열려 있으면 바로 실행
                handler.postDelayed({ executeCommand(command) }, 300)
            } else {
                // 앱 실행 후 로드 대기
                pendingCommand = command
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(launchIntent)
                // onAccessibilityEvent 에서 앱 전환 감지 후 실행됨
                // 5초 후에도 실행 안 되면 강제 시도 (안전망)
                handler.postDelayed({
                    pendingCommand?.let {
                        pendingCommand = null
                        executeCommand(it)
                    }
                }, 5000)
            }
        }
    }

    override fun onServiceConnected() {
        val filter = IntentFilter(Constants.ACTION_ACCESSIBILITY_CMD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
        toast("산길샘 위젯 서비스 활성화")
    }

    override fun onDestroy() {
        unregisterReceiver(commandReceiver)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 산길샘이 포그라운드로 올라왔고, 실행 대기 중인 명령이 있으면 실행
        val prefs = getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
        val pkg = prefs.getString(Constants.PREF_PACKAGE, Constants.SANGIL_PACKAGE)

        if (event.packageName?.toString() == pkg && pendingCommand != null) {
            val cmd = pendingCommand!!
            pendingCommand = null
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ executeCommand(cmd) }, 800)  // 화면 렌더링 대기
        }
    }

    override fun onInterrupt() {}

    // ─── 실제 버튼 탭 ───────────────────────────────────────────────

    private fun executeCommand(command: String) {
        val prefs = getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
        val targetText = when (command) {
            Constants.CMD_START -> prefs.getString(Constants.PREF_BTN_START, Constants.DEFAULT_BTN_START)!!
            Constants.CMD_STOP  -> prefs.getString(Constants.PREF_BTN_STOP,  Constants.DEFAULT_BTN_STOP)!!
            else -> return
        }

        val root = rootInActiveWindow
        if (root == null) {
            toast("화면을 읽을 수 없습니다. 산길샘이 열려 있는지 확인하세요.")
            return
        }

        val clicked = clickNodeByText(root, targetText)
        if (!clicked) {
            toast("\"$targetText\" 버튼을 찾지 못했습니다.\n설정에서 버튼 텍스트를 확인하세요.")
        }
    }

    /**
     * 화면에서 [text]가 포함된 노드를 찾아 클릭합니다.
     * 텍스트 노드 자체가 클릭 불가능하면 부모를 최대 3단계 올라가며 클릭 시도.
     */
    private fun clickNodeByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (tryClick(node)) return true
        }
        return false
    }

    private fun tryClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        var parent = node.parent
        repeat(3) {
            if (parent != null && parent.isClickable) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            parent = parent?.parent
        }
        return false
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
