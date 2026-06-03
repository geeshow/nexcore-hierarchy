package com.nexcore.callflow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;

/**
 * "NEXCORE Hierarchy" 툴윈도우. JCEF(내장 Chromium) 로 그래프 HTML 을 렌더링하고,
 * 노드/호출선 클릭 → 명령 접두사 브리지로 소스 이동·새로고침·이미지 처리를 연결한다.
 *
 * <p>Java 로 구현한다: Kotlin 으로 {@link ToolWindowFactory} 를 구현하면 인터페이스의
 * 내부/실험 default 메소드(getAnchor/getIcon/manage 등)에 대한 synthetic override 가
 * 생성되어 plugin verifier 가 내부 API 사용으로 보고하기 때문.
 */
public final class CallFlowToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        CallFlowPanelService service = CallFlowPanelService.Companion.getInstance(project);

        JComponent component;
        if (JBCefApp.isSupported()) {
            JBCefBrowser browser = new JBCefBrowser();

            // fire-and-forget 브리지. 명령 접두사로 분기.
            JBCefJSQuery query = JBCefJSQuery.create((JBCefBrowserBase) browser);
            query.addHandler(msg -> {
                if (msg.startsWith("node:")) {
                    service.navigateTo(msg.substring("node:".length()));
                } else if (msg.startsWith("edge:")) {
                    service.navigateToCall(msg.substring("edge:".length()));
                } else if (msg.equals("refresh")) {
                    service.refresh();
                } else if (msg.startsWith("copyimg:")) {
                    service.copyImage(msg.substring("copyimg:".length()));
                } else if (msg.startsWith("saveimg:")) {
                    service.saveImage(msg.substring("saveimg:".length()));
                }
                return null;
            });

            browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadEnd(CefBrowser cef, CefFrame frame, int httpStatusCode) {
                    String send = query.inject("__m");
                    cef.executeJavaScript(
                            "window.__send = function(__m){ " + send + " };"
                                    + "window.__navigate = function(id){ window.__send('node:' + id); };"
                                    + "window.__navigateCall = function(a,b){ window.__send('edge:' + a + '=>' + b); };"
                                    + "window.__refresh = function(){ window.__send('refresh'); };"
                                    + "window.__copyImage = function(d){ window.__send('copyimg:' + d); };"
                                    + "window.__saveImage = function(d){ window.__send('saveimg:' + d); };",
                            cef.getURL(), 0);
                }
            }, browser.getCefBrowser());

            service.registerBrowser(browser);
            component = browser.getComponent();
        } else {
            component = new JBLabel(
                    "이 IDE 런타임은 JCEF(내장 브라우저)를 지원하지 않습니다. "
                            + "Help > Find Action > 'Choose Boot Java Runtime' 에서 JCEF 포함 런타임을 선택하세요.");
        }

        Content content = ContentFactory.getInstance().createContent(component, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }
}
