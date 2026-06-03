package com.nexcore.callflow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiManager
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * 분석 결과(JSON)를 JCEF 웹뷰에 주입하고, 노드/호출선 클릭 시 소스로 점프시키는 프로젝트 서비스.
 * 액션 → 서비스.show() → 툴윈도우 활성화 → HTML 로드.
 * 웹뷰 클릭 → __navigate(id) / __navigateCall(from,to) → 브리지 → 에디터 이동.
 */
@Service(Service.Level.PROJECT)
class CallFlowPanelService(private val project: Project) {

    @Volatile
    var browser: JBCefBrowser? = null
        private set

    @Volatile
    private var pending: Pair<String, String>? = null   // baseId, graphJson

    private val locations = java.util.concurrent.ConcurrentHashMap<String, CallGraphAnalyzer.Loc>()
    private val edgeLocations = java.util.concurrent.ConcurrentHashMap<String, CallGraphAnalyzer.Loc>()

    @Volatile
    private var baseLoc: CallGraphAnalyzer.Loc? = null   // 새로고침 시 재분석할 기준 메소드 위치

    @Volatile
    private var baseId: String = ""                      // 기준 메소드 id(파일명 등에 사용)

    /** 액션에서 호출: 데이터 저장 후 툴윈도우를 띄우고 flush. */
    fun show(
        baseId: String,
        graphJson: String,
        locations: Map<String, CallGraphAnalyzer.Loc>,
        edgeLocations: Map<String, CallGraphAnalyzer.Loc>,
    ) {
        pending = baseId to graphJson
        this.locations.clear(); this.locations.putAll(locations)
        this.edgeLocations.clear(); this.edgeLocations.putAll(edgeLocations)
        baseLoc = locations[baseId]
        this.baseId = baseId
        val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (tw != null) {
            tw.activate({ flush() }, true)
        } else {
            flush()
        }
    }

    /** 툴윈도우 팩토리에서 브라우저 생성 직후 등록. */
    fun registerBrowser(browser: JBCefBrowser) {
        this.browser = browser
        flush()
    }

    /** 노드 클릭 → 메소드 선언으로 이동(외부 노드는 위치 없음 → 무시). */
    fun navigateTo(nodeId: String) = navigate(locations[nodeId])

    /** 호출선 클릭 → 실제 호출 위치(call-site)로 이동. key = "callerId=>calleeId" */
    fun navigateToCall(edgeKey: String) {
        val loc = edgeLocations[edgeKey] ?: locations[edgeKey.substringAfterLast("=>")]
        navigate(loc)
    }

    private fun navigate(loc: CallGraphAnalyzer.Loc?) {
        loc ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || !loc.file.isValid) return@invokeLater
            OpenFileDescriptor(project, loc.file, loc.offset).navigate(true)
        }
    }

    /** 새로고침: 저장된 기준 메소드 위치에서 PsiMethod 를 다시 찾아 재분석. */
    fun refresh() {
        val loc = baseLoc ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val method = runReadAction {
                if (!loc.file.isValid) return@runReadAction null
                val psiFile = PsiManager.getInstance(project).findFile(loc.file) ?: return@runReadAction null
                val el = psiFile.findElementAt(loc.offset) ?: return@runReadAction null
                CallGraphAnalyzer.enclosingMethod(el)
            } ?: return@invokeLater
            val result = CallGraphAnalyzer.analyze(project, method)
            show(result.baseId, result.graphJson, result.locations, result.edgeLocations)
        }
    }

    /** 웹뷰가 만든 PNG data-url 을 시스템 클립보드에 이미지로 복사. */
    fun copyImage(dataUrl: String) {
        val img = decodeImage(dataUrl) ?: return
        ApplicationManager.getApplication().invokeLater {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageTransferable(img), null)
        }
    }

    /** 웹뷰가 만든 PNG data-url 을 저장 다이얼로그로 파일 저장. */
    fun saveImage(dataUrl: String) {
        val img = decodeImage(dataUrl) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val descriptor = FileSaverDescriptor("이미지 저장", "NEXCORE Hierarchy 다이어그램을 PNG 로 저장", "png")
            val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            val safe = baseId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "diagram" }
            val wrapper = dialog.save(null as VirtualFile?, "nexcore-hierarchy-$safe.png") ?: return@invokeLater
            try {
                ImageIO.write(img, "png", wrapper.file)
            } catch (e: Exception) {
                Messages.showErrorDialog(project, "이미지 저장 실패: ${e.message}", "NEXCORE Hierarchy")
            }
        }
    }

    private fun decodeImage(dataUrl: String): java.awt.image.BufferedImage? {
        return try {
            val base64 = dataUrl.substringAfter("base64,", "")
            if (base64.isEmpty()) return null
            val bytes = Base64.getDecoder().decode(base64)
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            null
        }
    }

    /** AWT 클립보드용 이미지 Transferable. */
    private class ImageTransferable(private val image: Image) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
            return image
        }
    }

    private fun flush() {
        val b = browser ?: return
        val (baseId, json) = pending ?: return
        b.loadHTML(renderHtml(baseId, json))
    }

    private fun renderHtml(baseId: String, graphJson: String): String {
        val tpl = javaClass.getResource("/web/callflow.html")?.readText()
            ?: return "<html><body>callflow.html 리소스를 찾을 수 없습니다.</body></html>"
        return tpl
            .replace("/*__GRAPH__*/{}", graphJson)
            .replace("/*__BASE__*/\"\"", "\"" + baseId.replace("\"", "\\\"") + "\"")
    }

    companion object {
        const val TOOL_WINDOW_ID = "NEXCORE Hierarchy"
        fun getInstance(project: Project): CallFlowPanelService = project.service()
    }
}
