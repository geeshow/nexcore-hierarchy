package com.nexcore.callflow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * ⌘⌃H 단축키 액션.
 *
 *  - NEXCORE 프로젝트  : NEXCORE Hierarchy(컴포넌트 호출 관계도) 를 띄운다.
 *  - 그 외 프로젝트    : 플랫폼 기본 "MethodHierarchy" 액션을 ActionManager.tryToExecute 로 실행.
 *
 * 위임은 모두 public API(ActionManager#getAction / #tryToExecute)만 사용한다.
 * (AnAction#update/#actionPerformed 직접 호출은 @OverrideOnly, ActionUtil/BrowseMethodHierarchyAction 은
 *  @Internal 이라 plugin verifier 에서 막힘)
 */
class NexcoreHierarchyAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasContext = e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.PSI_FILE) != null
        val nexcore = project != null && NexcoreHierarchy.isNexcoreProject(project)
        e.presentation.text = if (nexcore) "NEXCORE Hierarchy" else "Method Hierarchy"
        e.presentation.isEnabledAndVisible = hasContext
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (NexcoreHierarchy.isNexcoreProject(project)) {
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
            NexcoreHierarchy.show(project, editor, psiFile)
        } else {
            val am = ActionManager.getInstance()
            val methodHierarchy = am.getAction("MethodHierarchy") ?: return
            val input = e.inputEvent ?: return
            am.tryToExecute(methodHierarchy, input, input.component, e.place, true)
        }
    }
}
