import kotlin.reflect.*
import kotlin.reflect.jvm.*

fun box(): String {
    val a = J()
    val p = J::class.members.single { it.name == "result" } as KMutableProperty0<String>
    p.isAccessible = true
    p.set("OK")
    return p.get()
}
