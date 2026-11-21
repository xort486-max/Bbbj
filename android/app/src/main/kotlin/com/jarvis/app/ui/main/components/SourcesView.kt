package com.jarvis.app.ui.main.components

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvis.app.ui.theme.JarvisColors
import com.jarvis.app.ui.theme.JarvisType
import com.jarvis.core.model.SourceLink

/**
 * 답변 출처 링크. 누르면 외부 브라우저로 원래 페이지를 그대로 연다 (중간 페이지·클릭 추적 없음 — Gemini 약관).
 */
@Composable
fun SourceLinks(sources: List<SourceLink>, provider: String?) {
    if (sources.isEmpty()) return
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (provider == "google") "출처 · Google 검색" else "출처 · Claude 웹 검색",
            style = JarvisType.hud(9.sp, FontWeight.Bold, JarvisColors.TextSecondary),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sources.take(8).forEachIndexed { index, source ->
                val shape = RoundedCornerShape(50)
                Text(
                    "${index + 1}. ${source.title}",
                    style = JarvisType.body(11.sp, color = JarvisColors.Cyan),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .background(JarvisColors.Cyan.copy(alpha = 0.08f), shape)
                        .border(0.8.dp, JarvisColors.PanelBorder, shape)
                        .clickable(role = Role.Button) { runCatching { uriHandler.openUri(source.url) } }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .semantics { contentDescription = "출처 ${index + 1}: ${source.title}" },
                )
            }
        }
    }
}

/**
 * Google 검색 제안 위젯 (Gemini Grounding의 search_suggestions HTML).
 *
 * 약관에 따라 근거 결과와 함께 제공된 HTML을 수정하지 않고 표시하며, 제안을 누르면 외부 브라우저에서
 * google.com 검색 결과를 연다. 앱은 클릭을 추적하지 않는다. 스크립트는 실행하지 않는다.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SearchSuggestions(html: String, modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) {
        Box(
            modifier.fillMaxWidth().height(56.dp).background(JarvisColors.Panel, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("Google 검색 제안", style = JarvisType.hud(10.sp)) }
        return
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: ActivityNotFoundException) {
                            // 브라우저가 없는 기기 — 아무것도 하지 않음
                        }
                        return true
                    }
                }
            }
        },
        update = { webView ->
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL("https://www.google.com", html, "text/html", "utf-8", null)
            }
        },
        modifier = modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = "Google 검색 제안" },
    )
}
