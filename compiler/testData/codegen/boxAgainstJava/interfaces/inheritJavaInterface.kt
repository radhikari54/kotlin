internal interface B : A {
    fun bar() = 1
}

internal interface C : B

internal class D : C {
    override fun foo() {}
}

fun box(): String {
    val d = D()
    d.foo()
    d.bar()
    return "OK"
}
