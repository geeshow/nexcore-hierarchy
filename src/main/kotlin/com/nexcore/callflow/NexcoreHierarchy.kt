package com.nexcore.callflow

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * NEXCORE Hierarchy 공통 로직 — 커서 메소드 분석/표시, 그리고 "NEXCORE 프로젝트" 판별.
 * 우클릭 팝업 액션과 ⌘⇧H 오버라이드 액션이 함께 사용한다.
 */
object NexcoreHierarchy {

    private val NEXCORE_PROJECT = Key.create<Boolean>("nexcore.callflow.isNexcoreProject")

    /** 커서 위치의 BizUnit 메소드를 분석해 툴윈도우에 표시. */
    fun show(project: Project, editor: Editor, psiFile: PsiFile) {
        val method = runReadAction {
            val element = psiFile.findElementAt(editor.caretModel.offset) ?: return@runReadAction null
            CallGraphAnalyzer.enclosingMethod(element)
        }
        if (method == null) {
            Messages.showInfoMessage(
                project,
                "커서를 PU/FU/DU 메소드(예: pACU0001, fAC0001, s001) 안에 둔 뒤 다시 실행해주세요.",
                "NEXCORE Hierarchy",
            )
            return
        }
        val result = CallGraphAnalyzer.analyze(project, method)
        CallFlowPanelService.getInstance(project)
            .show(result.baseId, result.graphJson, result.locations, result.edgeLocations)
    }

    /**
     * 현재 프로젝트가 NEXCORE 프레임웍 프로젝트인지(= AbstractBizUnit 보유) 판별.
     * 세션 동안 캐시. 인덱싱(dumb) 중에는 캐시하지 않고 false 반환(차후 재판별).
     */
    fun isNexcoreProject(project: Project): Boolean {
        project.getUserData(NEXCORE_PROJECT)?.let { return it }
        if (DumbService.isDumb(project)) return false
        val found = runReadAction {
            val all = GlobalSearchScope.allScope(project)
            JavaPsiFacade.getInstance(project).findClass(NexcoreModel.ABSTRACT_BIZ_UNIT_FQN, all) != null ||
                FilenameIndex.getVirtualFilesByName("AbstractBizUnit.java", GlobalSearchScope.projectScope(project)).isNotEmpty()
        }
        project.putUserData(NEXCORE_PROJECT, found)
        return found
    }
}
