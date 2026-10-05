package com.easywhatsappbackup.easy_whatsapp_backup

object TarProgressBus {
    private val listeners = mutableSetOf<(Map<String, Any?>) -> Unit>()
    private var lastEvent: Map<String, Any?>? = null

    @Synchronized
    fun addListener(listener: (Map<String, Any?>) -> Unit): Map<String, Any?>? {
        listeners += listener
        return lastEvent
    }

    @Synchronized
    fun removeListener(listener: (Map<String, Any?>) -> Unit) {
        listeners -= listener
    }

    fun send(event: Map<String, Any?>) {
        val snapshot: List<(Map<String, Any?>) -> Unit>
        synchronized(this) {
            lastEvent = event
            snapshot = listeners.toList()
        }
        snapshot.forEach { it(event) }
    }
}
