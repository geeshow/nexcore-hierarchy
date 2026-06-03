package com.nexcore.callflow

import com.intellij.ide.hierarchy.actions.BrowseMethodHierarchyAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * ⌘⇧H 단축키 액션. 플랫폼 기본 "MethodHierarchy" 액션을 override 하여 동일 단축키를 단독 점유한다.
 *
 *  - NEXCORE 프로젝트  : NEXCORE Hierarchy(컴포넌트 호출 관계도) 를 띄운다.
 *  - 그 외 프로젝트    : 보관해 둔 기본 Method Hierarchy 액션에 그대로 위임 → 기존 계층 패널.
 *
 * BrowseMethodHierarchyAction 은 final 이라 상속 불가 → 인스턴스를 만들어 위임한다.
 */
class NexcoreHierarchyAction : AnAction() {

    private val methodHierarchy = BrowseMethodHierarchyAction()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project != null && NexcoreHierarchy.isNexcoreProject(project)) {
            e.presentation.text = "NEXCORE Hierarchy"
            e.presentation.isEnabledAndVisible =
                e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.PSI_FILE) != null
        } else {
            methodHierarchy.update(e)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (NexcoreHierarchy.isNexcoreProject(project)) {
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
            NexcoreHierarchy.show(project, editor, psiFile)
        } else {
            methodHierarchy.actionPerformed(e)
        }
    }
}
