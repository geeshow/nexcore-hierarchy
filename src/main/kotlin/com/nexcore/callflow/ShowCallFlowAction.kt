package com.nexcore.callflow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * 에디터 우클릭 메뉴용 "Show NEXCORE Hierarchy" 액션. NEXCORE 프로젝트에서만 노출.
 * (단축키 ⌘⇧H 는 [NexcoreHierarchyAction] 이 담당)
 */
class ShowCallFlowAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        NexcoreHierarchy.show(project, editor, psiFile)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null &&
            e.getData(CommonDataKeys.EDITOR) != null &&
            NexcoreHierarchy.isNexcoreProject(project)
    }
}
