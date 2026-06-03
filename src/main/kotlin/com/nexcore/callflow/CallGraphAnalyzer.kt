package com.nexcore.callflow

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.TextOccurenceProcessor
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * NEXCORE BizUnit 호출 그래프 분석기.
 *
 * 기준 메소드에서 출발해 **지연(lazy) 양방향 BFS** 로 그래프를 만든다.
 * 호출의 판별/대상/kind 는 모두 **구문(syntactic) 정보** 로 결정하므로,
 * 모듈/의존성이 연결되지 않아 cross-file resolve 가 안 되는 환경에서도 동작한다.
 * resolve 가 되는 경우에는 추가로 (a) 소스 점프 위치, (b) callee 2-depth 확장을 얻는다.
 *
 *  - callee(이 메소드가 호출):
 *      · 직접호출(local-call): 수식어가 @BizUnitBind 필드 → 그 **필드 선언 타입명** 이 대상 유닛
 *      · call* 계열         : 수식어 없는(상속) 표준 메소드 이름 → kind, 문자열 인자 → 대상
 *  - caller(이 메소드를 호출):
 *      · 직접호출: 코드 인덱스에서 `필드.이 메소드(` 를 찾아 필드 타입명이 이 유닛인지 확인
 *      · call*  : "Unit.method" 리터럴을 문자열 인덱스로 찾아 감싸는 call* 식에서 kind 판정
 */
object CallGraphAnalyzer {

    private const val MAX_DEPTH = 10      // 각 방향(호출/피호출) 확장 깊이(긴 체인 표시)
    private const val MAX_CHILDREN = 16   // 노드당 표시할 최대 가지 수
    private const val MAX_NODES = 250     // 전체 노드 상한(폭주 방지). seen 으로 노드당 1회만 확장

    data class Loc(val file: VirtualFile, val offset: Int)

    class Node(
        val id: String,
        val name: String,
        val cls: String,
        val comp: String,
        val type: NodeType,
        val internal: Boolean,   // 실제 유닛(소스 점프 대상) 여부. 외부(SVC/JOB/OUT)는 false.
        val desc: String,        // 업무명(@BizMethod 값 또는 Javadoc 첫 줄)
    ) {
        val calls = LinkedHashSet<String>()   // "to|kindWire"
    }

    data class Result(
        val baseId: String,
        val graphJson: String,
        val locations: Map<String, Loc>,       // 노드 id -> 선언 위치
        val edgeLocations: Map<String, Loc>,   // "callerId=>calleeId" -> 호출(call-site) 위치
    )

    private class Ctx {
        val nodes = LinkedHashMap<String, Node>()
        val locations = LinkedHashMap<String, Loc>()
        val edgeLoc = LinkedHashMap<String, Loc>()
    }

    /** callee 1건: 대상 id/kind/표시정보/(해석되면 대상 메소드). */
    private class Callee(
        val toId: String,
        val kind: CallKind,
        val name: String,
        val cls: String,
        val comp: String,
        val type: NodeType,
        val internal: Boolean,
        val target: PsiMethod?,
    )

    fun analyze(project: Project, base: PsiMethod): Result = runReadAction {
        val ctx = Ctx()
        val baseId = nodeForMethod(base, ctx)
        expandCallees(base, baseId, 0, ctx, HashSet())
        expandCallers(project, base, baseId, 0, ctx, HashSet())
        Result(baseId, toJson(ctx, baseId), ctx.locations, ctx.edgeLoc)
    }

    fun enclosingMethod(element: PsiElement): PsiMethod? =
        PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)

    // ---------- callee 방향 ----------

    private fun expandCallees(method: PsiMethod, fromId: String, depth: Int, ctx: Ctx, seen: MutableSet<String>) {
        if (depth >= MAX_DEPTH || ctx.nodes.size >= MAX_NODES) return
        if (!seen.add(fromId)) return
        val body = method.body ?: return
        val cls = method.containingClass
        val facade = JavaPsiFacade.getInstance(method.project)
        val binds = bindFields(cls)                 // 필드명 -> 유닛 타입명
        val pkg = packageNameOf(cls)
        val added = LinkedHashSet<String>()
        for (call in PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)) {
            if (added.size >= MAX_CHILDREN) break
            val callee = resolveCallee(facade, call, binds, pkg) ?: continue
            registerCallee(callee, ctx)
            if (fromId == callee.toId) continue
            addEdge(ctx, fromId, callee.toId, callee.kind, call.methodExpression)
            if (callee.toId !in added) {
                added.add(callee.toId)
                if (callee.target != null) expandCallees(callee.target, callee.toId, depth + 1, ctx, seen)
            }
        }
    }

    private fun resolveCallee(
        facade: JavaPsiFacade, call: PsiMethodCallExpression,
        binds: Map<String, String>, pkg: String,
    ): Callee? {
        val ref = call.methodExpression
        val name = ref.referenceName ?: return null
        val qualifier = ref.qualifierExpression

        // 수식어 없는 호출 = 상속받은 프레임웍 call* (또는 dbXxx/getLog 등은 제외)
        if (qualifier == null) {
            if (!NexcoreModel.isFrameworkCallName(name)) return null
            return frameworkCallee(facade, call, name, pkg)
        }
        // 직접호출: 수식어가 @BizUnitBind 필드여야 함
        val qname = (qualifier as? PsiReferenceExpression)?.referenceName ?: return null
        val unit = binds[qname] ?: return null
        val target = findUnitMethod(call.project, "$pkg.$unit", unit, name)
        return unitCallee(target, unit, name, shortComponent(pkg), CallKind.LOCAL_CALL)
    }

    private fun frameworkCallee(facade: JavaPsiFacade, call: PsiMethodCallExpression, name: String, pkg: String): Callee? {
        val args = call.argumentList.expressions
        val isOutbound = name == "callOutbound" || name == "sendOutboundNow" || name == "sendOutboundAfterCommit"
        val outboundConst = if (isOutbound) (args.getOrNull(0) as? PsiReferenceExpression)?.referenceName else null
        val kind = NexcoreModel.frameworkCallKind(name, outboundConst) ?: return null

        return when (kind.category) {
            "local" -> {
                val um = stringArg(facade, args, 0) ?: return null
                val (unit, m) = NexcoreModel.splitUnitMethod(um) ?: return null
                val target = findUnitMethod(call.project, "$pkg.$unit", unit, m)
                unitCallee(target, unit, m, shortComponent(pkg), kind)
            }
            "cross" -> {
                val comp = stringArg(facade, args, 0) ?: return null
                val um = stringArg(facade, args, 1) ?: return null
                val (unit, m) = NexcoreModel.splitUnitMethod(um) ?: return null
                val target = findUnitMethod(call.project, "$comp.biz.$unit", unit, m)
                unitCallee(target, unit, m, comp.substringAfterLast('.'), kind)
            }
            "linked" -> external("SVC:${stringArg(facade, args, 0) ?: "?"}", stringArg(facade, args, 0) ?: "?", "연동거래", NodeType.SVC, kind)
            "batch" -> external("JOB:${stringArg(facade, args, 0) ?: "?"}", stringArg(facade, args, 0) ?: "?", "배치 JOB", NodeType.BATCH, kind)
            "out" -> {
                val (tag, suffix) = outboundTagAndLabel(call, kind, args)
                val id = if (suffix.isEmpty()) "OUT:$tag" else "OUT:$tag:$suffix"
                val nm = if (suffix.isEmpty()) tag else "$tag · $suffix"
                external(id, nm, "아웃바운드", NodeType.OUT, kind)
            }
            else -> null
        }
    }

    /** 유닛 대상 callee. 해석되면 점프/확장 가능(internal=true, target!=null). 미해석이어도 노드는 표시. */
    private fun unitCallee(target: PsiMethod?, unit: String, method: String, comp: String, kind: CallKind): Callee {
        return if (target != null) {
            val tc = target.containingClass
            Callee(
                "${tc?.name}.${target.name}", kind, target.name, tc?.name ?: unit,
                shortComponent(packageNameOf(tc)), tc?.let { NexcoreModel.nodeTypeOf(it) } ?: typeByUnitPrefix(unit),
                internal = true, target = target,
            )
        } else {
            Callee("$unit.$method", kind, method, unit, comp, typeByUnitPrefix(unit), internal = false, target = null)
        }
    }

    private fun external(id: String, name: String, cls: String, type: NodeType, kind: CallKind): Callee =
        Callee(id, kind, name, cls, "", type, internal = false, target = null)

    private fun registerCallee(c: Callee, ctx: Ctx) {
        if (c.target != null) { nodeForMethod(c.target, ctx); return }
        registerNode(ctx, c.toId, c.name, c.cls, c.comp, c.type, c.internal)
    }

    // ---------- caller 방향 ----------

    private fun expandCallers(project: Project, method: PsiMethod, toId: String, depth: Int, ctx: Ctx, seen: MutableSet<String>) {
        if (depth >= MAX_DEPTH || ctx.nodes.size >= MAX_NODES) return
        if (!seen.add(toId)) return

        val callers = LinkedHashMap<PsiMethod, Pair<CallKind, PsiElement>>()
        collectDirectCallers(project, method, callers)
        collectStringCallers(project, method, callers)

        var count = 0
        for ((cm, pair) in callers) {
            if (count >= MAX_CHILDREN) break
            val fromId = nodeForMethod(cm, ctx)
            if (fromId == toId) continue
            addEdge(ctx, fromId, toId, pair.first, pair.second)
            count++
            expandCallers(project, cm, fromId, depth + 1, ctx, seen)
        }
    }

    /** 직접호출 caller: resolve 기반(ReferencesSearch) + 구문 기반(코드 인덱스). */
    private fun collectDirectCallers(project: Project, method: PsiMethod, out: MutableMap<PsiMethod, Pair<CallKind, PsiElement>>) {
        val unit = method.containingClass?.name ?: return
        val mname = method.name
        val scope = GlobalSearchScope.projectScope(project)

        ReferencesSearch.search(method, scope).findAll().forEach { ref ->
            enclosingMethod(ref.element)?.let { out.putIfAbsent(it, CallKind.LOCAL_CALL to ref.element) }
        }

        val processor = TextOccurenceProcessor { element, _ ->
            val callExpr = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java)
            val mref = callExpr?.methodExpression
            if (callExpr != null && mref?.referenceName == mname) {
                val qname = (mref.qualifierExpression as? PsiReferenceExpression)?.referenceName
                val callerClass = PsiTreeUtil.getParentOfType(callExpr, PsiClass::class.java)
                if (qname != null && callerClass != null && fieldTypeName(callerClass, qname) == unit) {
                    enclosingMethod(callExpr)?.let { out.putIfAbsent(it, CallKind.LOCAL_CALL to mref) }
                }
            }
            true
        }
        PsiSearchHelper.getInstance(project)
            .processElementsWithWord(processor, scope, mname, UsageSearchContext.IN_CODE, true)
    }

    /** call*("Unit.method") 문자열 caller. */
    private fun collectStringCallers(project: Project, method: PsiMethod, out: MutableMap<PsiMethod, Pair<CallKind, PsiElement>>) {
        val unit = method.containingClass?.name ?: return
        val needle = "$unit.${method.name}"
        val scope = GlobalSearchScope.projectScope(project)
        val processor = TextOccurenceProcessor { element, _ ->
            val lit = element as? PsiLiteralExpression
                ?: PsiTreeUtil.getParentOfType(element, PsiLiteralExpression::class.java, false)
            if ((lit?.value as? String) == needle) {
                val callExpr = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java)
                val cname = callExpr?.methodExpression?.referenceName
                if (callExpr != null && cname != null && NexcoreModel.isFrameworkCallName(cname)) {
                    val kind = NexcoreModel.frameworkCallKind(cname, null)
                    if (kind != null && (kind.category == "local" || kind.category == "cross")) {
                        enclosingMethod(callExpr)?.let { out.putIfAbsent(it, kind to callExpr.methodExpression) }
                    }
                }
            }
            true
        }
        PsiSearchHelper.getInstance(project)
            .processElementsWithWord(processor, scope, method.name, UsageSearchContext.IN_STRINGS, true)
    }

    // ---------- 노드/엣지 ----------

    private fun nodeForMethod(method: PsiMethod, ctx: Ctx): String {
        val cls = method.containingClass
        val id = "${cls?.name ?: "?"}.${method.name}"
        if (id !in ctx.nodes) {
            registerNode(
                ctx, id, method.name, cls?.name ?: "?",
                shortComponent(packageNameOf(cls)),
                cls?.let { NexcoreModel.nodeTypeOf(it) } ?: NodeType.FM, true,
                methodLabel(method),
            )
        }
        if (id !in ctx.locations) {
            val file = method.containingFile?.virtualFile
            if (file != null) ctx.locations[id] = Loc(file, navOffset(method))
        }
        return id
    }

    private fun registerNode(
        ctx: Ctx, id: String, name: String, cls: String, comp: String,
        type: NodeType, internal: Boolean, desc: String = "",
    ) {
        if (id !in ctx.nodes) ctx.nodes[id] = Node(id, name, cls, comp, type, internal, desc)
    }

    // ---------- 업무명(라벨) 추출: @BizMethod 값 우선, 없으면 Javadoc 첫 설명 줄 ----------

    private fun methodLabel(method: PsiMethod): String {
        val biz = bizMethodValue(method)
        if (!biz.isNullOrBlank() && !biz.equals(method.name, ignoreCase = true)) return biz.trim()
        docFirstLine(method, method.name)?.let { return it }
        if (!biz.isNullOrBlank()) return biz.trim()
        return ""
    }

    private fun bizMethodValue(method: PsiMethod): String? {
        val ann = method.modifierList.annotations
            .firstOrNull { it.nameReferenceElement?.referenceName == "BizMethod" } ?: return null
        val attr = ann.findAttributeValue("value") ?: return null
        (attr as? PsiLiteralExpression)?.value?.let { if (it is String) return it }
        return JavaPsiFacade.getInstance(method.project).constantEvaluationHelper.computeConstantExpression(attr) as? String
    }

    /** Javadoc 의 설명부(태그 이전) 첫 의미 줄. `<태그>` 제거, 메소드명만 있는 줄은 건너뜀. */
    private fun docFirstLine(method: PsiMethod, methodName: String): String? {
        val doc = method.docComment ?: return null
        for (raw in doc.text.split('\n')) {
            var l = raw.trim().removePrefix("/**").trim()
            l = l.removeSuffix("*/").trim().trimStart('*').trim()
            if (l.startsWith("@")) break                    // 태그 시작 = 설명 끝
            l = l.replace(Regex("<[^>]+>"), " ").trim()     // HTML 태그 제거(<pre> 등)
            val cleaned = l.trimEnd('.', ' ').trim()
            if (cleaned.isEmpty() || cleaned.equals(methodName, ignoreCase = true)) continue
            return cleaned
        }
        return null
    }

    private fun addEdge(ctx: Ctx, from: String, to: String, kind: CallKind, site: PsiElement) {
        if (from == to) return
        ctx.nodes[from]?.calls?.add("$to|${kind.wire}")
        val key = "$from=>$to"
        if (key !in ctx.edgeLoc) {
            val file = site.containingFile?.virtualFile
            if (file != null) ctx.edgeLoc[key] = Loc(file, site.textOffset)
        }
    }

    /** 아웃바운드 종류 태그(FEP/EDW/KAFKA) + header setter 역방향 스캔으로 라벨 보강. */
    private fun outboundTagAndLabel(call: PsiMethodCallExpression, kind: CallKind, args: Array<PsiExpression>): Pair<String, String> {
        val tag = when (kind) {
            CallKind.EDW_SYNC -> "EDW"
            CallKind.KAFKA_PUBLISH -> "KAFKA"
            else -> "FEP"
        }
        val enclosing = enclosingMethod(call) ?: return tag to ""
        val headerVar = (args.getOrNull(1) as? PsiReferenceExpression)?.referenceName ?: return tag to ""
        val wantSetter = when (tag) {
            "EDW" -> "setEDW_TR_URL"
            "KAFKA" -> "setEVENT_CODE"
            else -> "setFEP_IF_ID"
        }
        val callOffset = call.textOffset
        var best = ""
        PsiTreeUtil.collectElementsOfType(enclosing.body, PsiMethodCallExpression::class.java).forEach { c ->
            if (c.textOffset >= callOffset) return@forEach
            val q = (c.methodExpression.qualifierExpression as? PsiReferenceExpression)?.referenceName
            if (q == headerVar && c.methodExpression.referenceName == wantSetter) {
                val v = (c.argumentList.expressions.getOrNull(0))?.let { lit -> literalString(lit) }
                if (v != null) best = if (tag == "EDW") v.substringAfterLast('/') else v
            }
        }
        return tag to best
    }

    // ---------- 헬퍼 ----------

    private fun findUnitMethod(project: Project, fqn: String, unitName: String, methodName: String): PsiMethod? =
        findUnitClass(project, fqn, unitName)?.findMethodsByName(methodName, false)?.firstOrNull()

    /**
     * 유닛 클래스를 찾는다. 먼저 자바 클래스 인덱스(findClass)를, 안 되면(모듈/소스루트 미설정)
     * **파일명 인덱스**로 `<Unit>.java` 를 찾아 그 안의 클래스를 반환한다. → 모듈 미설정 환경에서도
     * cross-file 대상 해석/본문 확장/점프가 동작한다. (NEXCORE: 1 유닛 = 1 파일, 클래스명=파일명)
     */
    private fun findUnitClass(project: Project, fqn: String, unitName: String): PsiClass? {
        JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))?.let { return it }
        val targetPkg = fqn.substringBeforeLast('.', "")
        var fallback: PsiClass? = null
        for (vf in FilenameIndex.getVirtualFilesByName("$unitName.java", GlobalSearchScope.projectScope(project))) {
            val pf = PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile ?: continue
            val c = pf.classes.firstOrNull { it.name == unitName } ?: continue
            if (pf.packageName == targetPkg) return c
            if (fallback == null) fallback = c
        }
        return fallback
    }

    /** @BizUnitBind 필드: 필드명 -> 선언 타입명(단순). resolve 불필요(타입 텍스트 사용). */
    private fun bindFields(cls: PsiClass?): Map<String, String> {
        cls ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (f in cls.fields) {
            val bound = f.modifierList?.annotations?.any { it.nameReferenceElement?.referenceName == "BizUnitBind" } ?: false
            if (!bound) continue
            val name = f.name
            val type = f.typeElement?.text?.substringBefore('<')?.trim() ?: continue
            map[name] = type.substringAfterLast('.')
        }
        return map
    }

    /** 클래스에서 필드명으로 선언 타입명(단순)을 찾는다. resolve 불필요. */
    private fun fieldTypeName(cls: PsiClass, fieldName: String): String? =
        cls.findFieldByName(fieldName, true)?.typeElement?.text?.substringBefore('<')?.trim()?.substringAfterLast('.')

    private fun typeByUnitPrefix(unit: String): NodeType = when (unit.firstOrNull()) {
        'P' -> NodeType.PM
        'F' -> NodeType.FM
        'D' -> NodeType.DM
        else -> NodeType.FM
    }

    private fun packageNameOf(cls: PsiClass?): String =
        (cls?.containingFile as? PsiJavaFile)?.packageName ?: ""

    /** "...ac.acgo0002.biz" → "acgo0002" */
    private fun shortComponent(pkg: String): String =
        pkg.removeSuffix(".biz").substringAfterLast('.')

    /** 문자열 인자: 리터럴 우선(미해석에도 동작), 안 되면 상수식 평가. */
    private fun stringArg(facade: JavaPsiFacade, args: Array<PsiExpression>, i: Int): String? {
        val e = args.getOrNull(i) ?: return null
        (e as? PsiLiteralExpression)?.value?.let { if (it is String) return it }
        return facade.constantEvaluationHelper.computeConstantExpression(e) as? String
    }

    private fun literalString(e: PsiExpression): String? {
        (e as? PsiLiteralExpression)?.value?.let { if (it is String) return it }
        return JavaPsiFacade.getInstance(e.project).constantEvaluationHelper.computeConstantExpression(e) as? String
    }

    private fun navOffset(el: PsiElement): Int =
        (el as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: el.textOffset

    // ---------- 직렬화 ----------

    private fun toJson(ctx: Ctx, baseId: String): String {
        val sb = StringBuilder("{")
        var first = true
        for (n in ctx.nodes.values) {
            if (!first) sb.append(",")
            first = false
            sb.append('"').append(esc(n.id)).append("\":{")
            sb.append("\"name\":\"").append(esc(n.name)).append("\",")
            sb.append("\"desc\":\"").append(esc(n.desc)).append("\",")
            sb.append("\"cls\":\"").append(esc(n.cls)).append("\",")
            sb.append("\"comp\":\"").append(esc(n.comp)).append("\",")
            sb.append("\"type\":\"").append(n.type.wire).append("\",")
            sb.append("\"internal\":").append(n.internal)
            if (n.id == baseId) sb.append(",\"root\":true")
            sb.append(",\"calls\":[")
            var f2 = true
            for (c in n.calls) {
                val bar = c.lastIndexOf('|')
                val to = c.substring(0, bar)
                if (to !in ctx.nodes) continue
                if (!f2) sb.append(",")
                f2 = false
                sb.append("{\"to\":\"").append(esc(to)).append("\",\"kind\":\"").append(c.substring(bar + 1)).append("\"}")
            }
            sb.append("]}")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
