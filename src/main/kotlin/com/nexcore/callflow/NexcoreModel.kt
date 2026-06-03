package com.nexcore.callflow

import com.intellij.psi.PsiClass

/**
 * NEXCORE BizUnit 호출 구조 도메인 모델.
 *
 * methodflow 는 "직접 메소드 호출" 한 종류만 다뤘지만, NEXCORE 는
 *  (1) @BizUnitBind 필드의 직접 호출(local-call), 그리고
 *  (2) AbstractBizUnit 의 표준 call* / sendOutbound* 메소드(문자열 ID 기반)
 * 두 메커니즘이 있으며, (2)는 **메소드 이름이 곧 호출 종류(kind)** 를 결정한다.
 */

/** 노드(유닛) 타입 — 색/뱃지 구분. 외부(소스 없음)는 svc/batch/out. */
enum class NodeType(val wire: String) {
    PM("pm"),       // ProcessUnit  — endpoint(거래 진입점)
    FM("fm"),       // FunctionUnit — 업무 로직
    DM("dm"),       // DataUnit     — DB 접근
    SVC("svc"),     // 연동거래(외부)
    BATCH("batch"), // 배치 JOB(외부)
    OUT("out");     // 아웃바운드 FEP/EDW/Kafka(외부)
}

/**
 * 호출선(엣지) 종류. README "호출 흐름(kind) 커버리지 매트릭스" 의 16종 전부.
 *
 * @param wire     JSON/CSS 식별자
 * @param category 색 카테고리: local|cross|linked|batch|out
 * @param async    true=비동기(점선)
 * @param newTx    true=새 트랜잭션(RequiresNew → TX 뱃지)
 * @param afterCommit true=본 거래 커밋 후 수행
 * @param label    한글 표기(엣지 라벨/툴팁)
 */
enum class CallKind(
    val wire: String,
    val category: String,
    val async: Boolean,
    val newTx: Boolean,
    val afterCommit: Boolean,
    val label: String,
) {
    LOCAL_CALL("local-call", "local", false, false, false, "직접호출"),
    LOCAL_NEW_TX("local-new-tx", "local", false, true, false, "직접·새TX"),
    SHARED_CALL("shared-call", "cross", false, false, false, "공유호출"),
    SHARED_NEW_TX("shared-new-tx", "cross", false, true, false, "공유·새TX"),
    LINKED_SYNC("linked-tx-sync", "linked", false, false, false, "연동동기"),
    LINKED_SYNC_NEW("linked-tx-sync-new", "linked", false, true, false, "연동동기·새TX"),
    LINKED_ASYNC_NOW("linked-tx-async-now", "linked", true, false, false, "연동비동기"),
    LINKED_ASYNC_AC("linked-tx-async-after-commit", "linked", true, false, true, "연동비동기·커밋후"),
    LINKED_DELAY("linked-tx-delay-async", "linked", true, false, false, "연동지연비동기"),
    BATCH_NOW("batch-now", "batch", false, false, false, "배치즉시"),
    BATCH_AC("batch-after-commit", "batch", false, false, true, "배치커밋후"),
    FEP_SYNC("fep-sync", "out", false, false, false, "FEP동기"),
    EDW_SYNC("edw-sync", "out", false, false, false, "EDW동기"),
    FEP_ASYNC_NOW("fep-async-now", "out", true, false, false, "FEP비동기"),
    FEP_ASYNC_AC("fep-async-after-commit", "out", true, false, true, "FEP비동기·커밋후"),
    KAFKA_PUBLISH("kafka-publish", "out", true, false, false, "Kafka발행");

    /** 외부 호출이면 대상 노드 타입(연동거래/배치/아웃바운드), 내부면 null. */
    fun externalNodeType(): NodeType? = when (category) {
        "linked" -> NodeType.SVC
        "batch" -> NodeType.BATCH
        "out" -> NodeType.OUT
        else -> null   // local / cross 는 실제 유닛으로 resolve
    }
}

object NexcoreModel {

    /** AbstractBizUnit 의 표준 호출 API 가 선언된 FQN. 이 클래스의 메소드만 프레임웍 호출로 인정. */
    const val ABSTRACT_BIZ_UNIT_FQN = "com.kakaopay.moneyball.base.AbstractBizUnit"

    val PROCESS_UNIT_FQN = "com.kakaopay.moneyball.base.ProcessUnit"
    val FUNCTION_UNIT_FQN = "com.kakaopay.moneyball.base.FunctionUnit"
    val DATA_UNIT_FQN = "com.kakaopay.moneyball.base.DataUnit"

    /**
     * 프레임웍 호출 메소드 이름(+ 아웃바운드 KIND 상수명) → CallKind.
     * 표준 API 가 아니면(=local-call 직접호출이거나 일반 메소드) null.
     *
     * @param outboundKindConst callOutbound/sendOutbound* 의 첫 인자 KIND_* 상수명(예: "KIND_FEP"), 없으면 null
     */
    fun frameworkCallKind(methodName: String, outboundKindConst: String?): CallKind? = when (methodName) {
        "callMethodByRequiresNew" -> CallKind.LOCAL_NEW_TX
        "callSharedMethodByDirect" -> CallKind.SHARED_CALL
        "callSharedMethodByRequiresNew" -> CallKind.SHARED_NEW_TX
        "callService" -> CallKind.LINKED_SYNC
        "callServiceByRequiresNew" -> CallKind.LINKED_SYNC_NEW
        "callAsyncServiceNow" -> CallKind.LINKED_ASYNC_NOW
        "callAsyncServiceAfterCommit" -> CallKind.LINKED_ASYNC_AC
        "callDelayAsyncService" -> CallKind.LINKED_DELAY
        "callBatchJobNow" -> CallKind.BATCH_NOW
        "callBatchJobAfterCommit" -> CallKind.BATCH_AC
        "callOutbound" -> outboundKind(outboundKindConst, CallKind.FEP_SYNC, CallKind.EDW_SYNC, CallKind.FEP_SYNC)
        "sendOutboundNow" -> outboundKind(outboundKindConst, CallKind.FEP_ASYNC_NOW, CallKind.FEP_ASYNC_NOW, CallKind.KAFKA_PUBLISH)
        "sendOutboundAfterCommit" -> outboundKind(outboundKindConst, CallKind.FEP_ASYNC_AC, CallKind.FEP_ASYNC_AC, CallKind.KAFKA_PUBLISH)
        else -> null
    }

    /** AbstractBizUnit 표준 호출 메소드 이름 집합(미해석 환경에서 이름만으로 프레임웍 호출 판별). */
    private val FRAMEWORK_CALL_NAMES = setOf(
        "callMethodByRequiresNew",
        "callSharedMethodByDirect", "callSharedMethodByRequiresNew",
        "callService", "callServiceByRequiresNew",
        "callAsyncServiceAfterCommit", "callAsyncServiceNow", "callDelayAsyncService",
        "callBatchJobNow", "callBatchJobAfterCommit",
        "callOutbound", "sendOutboundNow", "sendOutboundAfterCommit",
    )

    fun isFrameworkCallName(name: String): Boolean = name in FRAMEWORK_CALL_NAMES

    private fun outboundKind(const: String?, fep: CallKind, edw: CallKind, kafka: CallKind): CallKind = when {
        const == null -> fep
        const.startsWith("KIND_EDW") -> edw
        const.startsWith("KIND_KAFKA") -> kafka
        else -> fep   // KIND_FEP (기본)
    }

    /** 유닛 클래스의 노드 타입을 상위 클래스(우선) → 클래스명 접두 순으로 판정. */
    fun nodeTypeOf(cls: PsiClass): NodeType {
        var c: PsiClass? = cls.superClass
        while (c != null) {
            when (c.qualifiedName) {
                PROCESS_UNIT_FQN -> return NodeType.PM
                FUNCTION_UNIT_FQN -> return NodeType.FM
                DATA_UNIT_FQN -> return NodeType.DM
            }
            c = c.superClass
        }
        return when (cls.name?.firstOrNull()) {
            'P' -> NodeType.PM
            'F' -> NodeType.FM
            'D' -> NodeType.DM
            else -> NodeType.FM
        }
    }

    /** PsiClass 가 BizUnit(PU/FU/DU) 인지. */
    fun isBizUnit(cls: PsiClass): Boolean {
        var c: PsiClass? = cls.superClass
        while (c != null) {
            when (c.qualifiedName) {
                PROCESS_UNIT_FQN, FUNCTION_UNIT_FQN, DATA_UNIT_FQN -> return true
            }
            c = c.superClass
        }
        return false
    }

    /** "FAC0001.fAC0001" 형식 문자열을 (unit, method) 로 분해. */
    fun splitUnitMethod(s: String): Pair<String, String>? {
        val dot = s.indexOf('.')
        if (dot <= 0 || dot >= s.length - 1) return null
        return s.substring(0, dot) to s.substring(dot + 1)
    }
}
