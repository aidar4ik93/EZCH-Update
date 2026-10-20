package com.example.homeezch

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class EvaSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        object : VoiceInteractionSession(this) {
            override fun onCreateContentView(): View {
                val panel = LinearLayout(this@EvaSessionService).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 24, 32, 24)
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
                panel.addView(TextView(this@EvaSessionService).apply {
                    text = "Ai EvA: системная сессия получена.\n" +
                        "Микрофон не включён. Это проверка вызова ассистента.\n" +
                        "Для проверки речи откройте карточку Ai EvA в лаунчере."
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 22f
                })
                panel.addView(Button(this@EvaSessionService).apply {
                    text = "Закрыть"
                    setOnClickListener { finish() }
                    requestFocus()
                })
                return panel
            }
        }
}
