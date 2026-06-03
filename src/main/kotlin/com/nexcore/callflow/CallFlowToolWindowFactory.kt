package com.nexcore.callflow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.JComponent

/**
 * "NEXCORE Call Flow" 툴윈도우. JCEF(내장 Chromium) 로 그래프 HTML 을 렌더링하고,
 * 노드 클릭 → window.__navigate(id), 호출선 클릭 → window.__navigateCall(from,to) 를
 * JBCefJSQuery 브리지로 소스 이동에 연결한다.
 */
class CallFlowToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = CallFlowPanelService.getInstance(project)

        val component: JComponent = if (JBCefApp.isSupported()) {
            val browser = JBCefBrowser()

            // fire-and-forget 브리지. 명령 접두사로 분기.
            val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
            query.addHandler { msg ->
                when {
                    msg.startsWith("node:") -> service.navigateTo(msg.removePrefix("node:"))
                    msg.startsWith("edge:") -> service.navigateToCall(msg.removePrefix("edge:"))
                    msg == "refresh" -> service.refresh()
                    msg.startsWith("copyimg:") -> service.copyImage(msg.removePrefix("copyimg:"))
                    msg.startsWith("saveimg:") -> service.saveImage(msg.removePrefix("saveimg:"))
                }
                null
            }

            browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cef: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    val send = query.inject("__m")
                    cef.executeJavaScript(
                        "window.__send = function(__m){ $send };" +
                            "window.__navigate = function(id){ window.__send('node:' + id); };" +
                            "window.__navigateCall = function(a,b){ window.__send('edge:' + a + '=>' + b); };" +
                            "window.__refresh = function(){ window.__send('refresh'); };" +
                            "window.__copyImage = function(d){ window.__send('copyimg:' + d); };" +
                            "window.__saveImage = function(d){ window.__send('saveimg:' + d); };",
                        cef.url, 0,
                    )
                }
            }, browser.cefBrowser)

            service.registerBrowser(browser)
            browser.component
        } else {
            JBLabel(
                "이 IDE 런타임은 JCEF(내장 브라우저)를 지원하지 않습니다. " +
                    "Help > Find Action > 'Choose Boot Java Runtime' 에서 JCEF 포함 런타임을 선택하세요.",
            )
        }

        val content = ContentFactory.getInstance().createContent(component, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
