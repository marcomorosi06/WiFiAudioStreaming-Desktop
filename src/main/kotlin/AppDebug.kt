object AppDebug {
    var enabled: Boolean = false
    fun log(msg: String) { if (enabled) println(msg) }
}
