package foo

// NOTE THIS FILE IS AUTO-GENERATED by the generateTestDataForReservedWords.kt. DO NOT EDIT!

interface Trait {
    fun foo(export: String)
}

class TraitImpl : Trait {
    override fun foo(export: String) {
    assertEquals("123", export)
    testRenamed("export", { export })
}
}

class TestDelegate : Trait by TraitImpl() {
    fun test() {
        foo("123")
    }
}

fun box(): String {
    TestDelegate().test()

    return "OK"
}