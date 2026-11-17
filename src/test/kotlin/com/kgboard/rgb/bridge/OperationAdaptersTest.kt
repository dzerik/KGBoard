package com.kgboard.rgb.bridge

import com.kgboard.rgb.runtime.OperationOutcome
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OperationAdaptersTest {
    @Test
    fun `core operation classes load with Java Git and SM packages unavailable`() {
        val resource =
            requireNotNull(OperationIdentityRegistry::class.java.getResource("OperationIdentityRegistry.class"))
        val location =
            if (resource.protocol == "jar") java.net.URI(resource.toString().removePrefix("jar:").substringBefore("!/"))
                .toURL()
            else java.net.URI(
                resource.toString().removeSuffix("com/kgboard/rgb/bridge/OperationIdentityRegistry.class")
            ).toURL()
        val blocked = listOf("git4idea.", "com.intellij.execution.testframework.", "com.intellij.psi.PsiJava")
        val loader = object : java.net.URLClassLoader(arrayOf(location), javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
                if (blocked.any { name.startsWith(it) }) throw ClassNotFoundException("Optional package unavailable: $name")
                val found = findLoadedClass(name) ?: if (name.startsWith("com.kgboard.rgb."))
                    runCatching { findClass(name) }.getOrElse { super.loadClass(name, false) }
                else super.loadClass(name, false)
                if (resolve) resolveClass(found)
                found
            }
        }
        loader.use {
            for (name in listOf(
                "OperationIdentityRegistryKt", "IdeOperationAdapter", "ExecutionAdapter", "DebugAdapter",
                "EditorStateAdapter", "ProjectRuntimeService"
            )) {
                val type = Class.forName("com.kgboard.rgb.bridge.$name", true, loader)
                type.declaredFields; type.declaredMethods; type.declaredConstructors
            }
        }
    }

    @Test
    fun `only executed leaf tests can produce success`() {
        assertEquals(OperationOutcome.NOT_EXECUTED, testOutcome(false, 0, 0, 0))
        assertEquals(OperationOutcome.NOT_EXECUTED, testOutcome(false, 4, 4, 0))
        assertEquals(OperationOutcome.CANCELLED, testOutcome(true, 4, 0, 0))
        assertEquals(OperationOutcome.FAILURE, testOutcome(false, 4, 0, 1))
        assertEquals(OperationOutcome.SUCCESS, testOutcome(false, 4, 1, 0))
    }
}
