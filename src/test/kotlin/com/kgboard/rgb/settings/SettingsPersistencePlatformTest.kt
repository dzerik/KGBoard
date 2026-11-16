package com.kgboard.rgb.settings

import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.*
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.openapi.util.JDOMUtil
import com.kgboard.rgb.preset.KeyGroupService
import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SettingsPersistencePlatformTest {
    private lateinit var fixture: IdeaProjectTestFixture
    @BeforeEach fun setUp() {
        fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(javaClass.simpleName).fixture
        EdtTestUtil.runInEdtAndWait<Throwable> { fixture.setUp() }
    }
    @AfterEach fun tearDown() { EdtTestUtil.runInEdtAndWait<Throwable> { fixture.tearDown() } }
    private fun xml(name: String) = javaClass.getResourceAsStream("/settings/$name.xml")!!.use { JDOMUtil.load(it) }
    @Test fun `real settings UI cancel and invalid apply never mutate persisted fields`() {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            val persisted = KgBoardSettings.getInstance()
            val previous = RuntimeSettings.capture(persisted.state).toState()
            persisted.loadState(KgBoardSettings.State(host = "original", autoConnect = false, enabled = false))
            val configurable = KgBoardConfigurable()
            fun fields(component: java.awt.Component): List<javax.swing.JTextField> =
                (if (component is javax.swing.JTextField) listOf(component) else emptyList()) +
                    (component as? java.awt.Container)?.components.orEmpty().flatMap(::fields)
            try {
                val component = configurable.createComponent()!!
                val inputs = fields(component)
                inputs.first { it.name == "app.host" }.text = "draft-host"
                inputs.first { it.name == "app.buildLedIndices" }.text = ",,"
                assertThrows(com.intellij.openapi.options.ConfigurationException::class.java) { configurable.apply() }
                assertEquals("original", persisted.host)
                inputs.first { it.name == "app.buildLedIndices" }.text = "1,2"
                configurable.apply()
                assertEquals("draft-host", persisted.host)
                inputs.first { it.name == "app.host" }.text = "cancelled-host"
                configurable.disposeUIResources()
                assertEquals("draft-host", persisted.host)
            } finally { configurable.disposeUIResources(); persisted.loadState(previous) }
        }
    }
    @Test fun `device table refuses invalid in progress edit before apply`() {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            val persisted = KgBoardSettings.getInstance()
            val previous = RuntimeSettings.capture(persisted.state).toState()
            persisted.loadState(KgBoardSettings.State(autoConnect = false, enabled = false,
                deviceConfigs = mutableListOf(KgBoardSettings.DeviceConfig(0, "Keyboard"))))
            val configurable = KgBoardConfigurable()
            fun tables(component: java.awt.Component): List<javax.swing.JTable> =
                (if (component is javax.swing.JTable) listOf(component) else emptyList()) +
                    (component as? java.awt.Container)?.components.orEmpty().flatMap(::tables)
            try {
                val table = tables(configurable.createComponent()).single()
                assertTrue(table.editCellAt(0, 2))
                (table.editorComponent as javax.swing.JTextField).text = "-1"
                assertThrows(com.intellij.openapi.options.ConfigurationException::class.java) { configurable.apply() }
                assertEquals(0, persisted.state.deviceConfigs.single().deviceIndex)
            } finally { configurable.disposeUIResources(); persisted.loadState(previous) }
        }
    }
    @Test fun `legacy XML values survive runtime snapshots and serializer round trip`() {
        val app = XmlSerializer.deserialize(xml("app"), KgBoardSettings.State::class.java)
        val project = XmlSerializer.deserialize(xml("project"), KgBoardProjectSettings.State::class.java)
        val groups = XmlSerializer.deserialize(xml("groups"), KeyGroupService.GroupState::class.java)
        assertEquals("192.0.2.1", app.host)
        assertEquals(2, app.deviceConfigs.single().deviceIndex)
        assertTrue(app.skipCustomMode)
        assertEquals("^feature/", project.gitBranchColorRules.single().pattern)
        assertEquals("1,2", groups.userGroups.single().ledIndices)
        val appCopy = RuntimeSettings.capture(app).toState()
        val projectCopy = ProjectSettingsSnapshot.capture(project).toState()
        assertEquals(app, XmlSerializer.deserialize(XmlSerializer.serialize(appCopy), KgBoardSettings.State::class.java))
        assertEquals(project, XmlSerializer.deserialize(XmlSerializer.serialize(projectCopy), KgBoardProjectSettings.State::class.java))
        assertEquals(groups, XmlSerializer.deserialize(XmlSerializer.serialize(groups), KeyGroupService.GroupState::class.java))
    }
}
