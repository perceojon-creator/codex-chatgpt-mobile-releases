package com.codex.chat.core.mcp.taint

import java.util.Collections
import java.util.EnumSet

/**
 * Rastrea la contaminacion de datos adversariales a nivel de sesion de chat.
 *
 * Auditoria v1.0.79 SEC-3: antes, isWebTainted era un booleano calculado por turno
 * (val isWebTainted = webGrounding.isNotBlank()). Un SMS adversarial entraba en el
 * turno N marcado como contaminado, pero en el turno N+1 ya no lo estaba, porque
 * webGrounding habia quedado vacio.
 *
 * Contrato: la contaminacion es MONOTONICA. Una vez que un origen contamina la sesion,
 * no puede limpiarse. Esto refleja que los datos adversariales ya estan en el contexto
 * del modelo y no pueden retirarse.
 *
 * Ciclo de vida: una instancia por sesion de chat. Al iniciar una sesion nueva, crear
 * una nueva instancia. Hilo-seguro: los accesos a _origins estan sincronizados.
 */
class SessionTaintTracker {

    private val _origins: MutableSet<TaintOrigin> =
        Collections.synchronizedSet(EnumSet.noneOf(TaintOrigin::class.java))

    /** Marca un origen como contaminante de esta sesion. Operacion idempotente. */
    fun markTainted(origin: TaintOrigin) { _origins.add(origin) }

    /**
     * Intento de limpiar la contaminacion. Por contrato, esta operacion es un NO-OP
     * deliberado: los datos adversariales ya estan en el contexto del modelo.
     * El metodo existe para que el llamador pueda expresar su intencion sin romper
     * el contrato desde el punto de vista de la API.
     */
    fun clearTaint() { /* monotonica: no se puede limpiar */ }

    /** true si cualquier origen ha contaminado esta sesion. */
    fun isSessionTainted(): Boolean = _origins.isNotEmpty()

    /**
     * true si la sesion esta contaminada por cualquier fuente.
     * Devuelve true para WEB_SEARCH, SMS_READ, NOTIFICATION_READ y CLIPBOARD_READ,
     * porque todos ellos pueden introducir prompts adversariales en el contexto.
     */
    fun isWebTainted(): Boolean = isSessionTainted()

    /** Conjunto de origenes que han contaminado esta sesion. */
    fun taintOrigins(): Set<TaintOrigin> = _origins.toSet()
}
