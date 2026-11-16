package com.kgboard.rgb.bridge

import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.*
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.ui.UIUtil
import com.kgboard.rgb.runtime.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.ConcurrentLinkedQueue

class RuntimeLifecyclePlatformTest {
    private lateinit var fixture: IdeaProjectTestFixture
    @BeforeEach fun setUp() {
        fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(javaClass.simpleName).fixture
        EdtTestUtil.runInEdtAndWait<Throwable> { fixture.setUp() }
    }
    @AfterEach fun tearDown() { EdtTestUtil.runInEdtAndWait<Throwable> { fixture.tearDown() } }

    @Test fun `real selectionChanged and last editor close invalidate selected file`() {
        val events = ConcurrentLinkedQueue<RuntimeEvent>()
        val binding = ProjectRuntimeBinding(RuntimeEvents { events.add(it) }, ProjectSettingsSnapshot())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = EditorStateAdapter(fixture.project, binding, scope)
        binding.start()
        try {
            EdtTestUtil.runInEdtAndWait<Throwable> {
                adapter.start()
                val file = LightVirtualFile("selected.txt", "text")
                FileEditorManager.getInstance(fixture.project).openTextEditor(OpenFileDescriptor(fixture.project, file), true)
                UIUtil.dispatchAllInvocationEvents()
                assertTrue(events.filterIsInstance<RuntimeEvent.EditorSelected>().any { it.revision?.fileUrl == file.url })
                FileEditorManager.getInstance(fixture.project).closeFile(file)
                UIUtil.dispatchAllInvocationEvents()
                assertNull(events.filterIsInstance<RuntimeEvent.EditorSelected>().last().revision)
            }
        } finally { EdtTestUtil.runInEdtAndWait<Throwable> { Disposer.dispose(adapter); Disposer.dispose(binding) }; scope.cancel() }
    }
    @Test fun `subscribing during dumb mode publishes initial indexing without waiting for callback`() {
        val events = ConcurrentLinkedQueue<RuntimeEvent>()
        val binding = ProjectRuntimeBinding(RuntimeEvents { events.add(it) }, ProjectSettingsSnapshot())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = EditorStateAdapter(fixture.project, binding, scope)
        binding.start()
        try {
            DumbModeTestUtils.runInDumbModeSynchronously(fixture.project) {
                EdtTestUtil.runInEdtAndWait<Throwable> { adapter.start() }
                assertTrue(events.filterIsInstance<RuntimeEvent.IndexingChanged>().first().indexing)
            }
        } finally { EdtTestUtil.runInEdtAndWait<Throwable> { Disposer.dispose(adapter); Disposer.dispose(binding) }; scope.cancel() }
    }
}
