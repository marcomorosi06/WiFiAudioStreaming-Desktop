object AppDebug {
    var enabled: Boolean = false
    @Volatile var sink: ((String) -> Unit)? = null
    fun log(msg: String) {
        if (!enabled) return
        val s = sink
        if (s != null) s(msg) else println(msg)
    }
}
