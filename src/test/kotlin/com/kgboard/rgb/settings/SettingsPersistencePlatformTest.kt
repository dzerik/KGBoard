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

    @BeforeEach
    fun setUp() {
        fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(javaClass.simpleName).fixture
        EdtTestUtil.runInEdtAndWait<Throwable> { fixture.setUp() }
    }

    @AfterEach
    fun tearDown() {
        EdtTestUtil.runInEdtAndWait<Throwable> { fixture.tearDown() }
    }

    private fun xml(name: String) = javaClass.getResourceAsStream("/settings/$name.xml")!!.use { JDOMUtil.load(it) }

    @Test
    fun `configurables create Swing controls only when their panel is requested`() {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            val app = KgBoardConfigurable()
            val project = KgBoardProjectConfigurable(fixture.project)
            try {
                for (configurable in listOf(app, project)) {
                    val eagerlyCreated = configurable.javaClass.declaredFields.filter {
                        java.awt.Component::class.java.isAssignableFrom(it.type)
                    }.mapNotNull { field -> field.isAccessible = true; field.get(configurable) }
                    assertTrue(
                        eagerlyCreated.isEmpty(),
                        "${configurable.javaClass.simpleName} allocated controls in constructor"
                    )
                }
            } finally {
                app.disposeUIResources(); project.disposeUIResources()
            }
        }
    }

    @Test
    fun `color picker has a screen reader action and keyboard activation`() {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            val picker = SettingsUiUtil.colorPanel()
            assertNotNull(picker.accessibleContext)
            assertNotNull(picker.accessibleContext.accessibleAction)
            assertTrue(picker.isFocusable)
            assertNotNull(
                picker.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(
                    javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SPACE, 0)
                )
            )
        }
    }

    @Test
    fun `selecting the first device confirms only a detached assignment without writing a frame`() {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            RuntimeHarness(RuntimeSettings()).use { h ->
                val draft = AppDraft.capture(KgBoardSettings.State(autoConnect = false))
                val session = SettingsPreviewSession(RuntimeEvents { h.send(it) })
                try {
                    val editor = com.kgboard.rgb.settings.ui.DeviceSettingsPanel(draft, session) {}
                    val component = editor.createPanel()
                    editor.update(h.snapshotForTest())
                    val choice =
                        controlsOfType<javax.swing.JComboBox<*>>(component).single { it.name == "device.selection" }
                    assertEquals(-1, choice.selectedIndex)
                    choice.selectedIndex = 0
                    assertEquals(1, draft.bindings.size)
                    assertTrue(h.snapshotForTest().settings.bindings.isEmpty())
                    assertTrue(h.writer.history.isEmpty())
                } finally {
                    session.close()
                }
            }
        }
    }

    @Test
    fun `application settings expose four sections and a public settings action`() {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            val configurable = KgBoardConfigurable()
            fun controls(component: java.awt.Component): List<java.awt.Component> = listOf(component) +
                (component as? java.awt.Container)?.components.orEmpty().flatMap(::controls)
            try {
                val all = controls(configurable.createComponent())
                val navigation = all.firstOrNull { it.name == "kgboard.navigation" }
                assertNotNull(navigation, "Four-section native settings navigation is absent")
                assertNotNull(
                    com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("KGBoard.OpenSettings")
                )
                val unnamed = all.filterIsInstance<javax.swing.JTextField>().filter {
                    it.accessibleContext.accessibleName.isNullOrBlank()
                }
                assertTrue(unnamed.isEmpty(), "Editable controls need accessible names: " + unnamed.joinToString {
                    "${it.javaClass.name}(name=${it.name}, parent=${it.parent?.javaClass?.name}, visible=${it.isVisible})"
                })
            } finally {
                configurable.disposeUIResources()
            }
        }
    }

    @Test
    fun `real settings UI cancel and invalid apply never mutate persisted fields`() {
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
                val targetKind =
                    controlsOfType<javax.swing.JComboBox<*>>(component).single { it.name == "scenario.target.kind" }
                targetKind.selectedIndex = 4
                inputs.first { it.name == "scenario.target.raw" }.text = ",,"
                assertEquals(
                    "error",
                    inputs.first { it.name == "scenario.target.raw" }.getClientProperty("JComponent.outline")
                )
                assertThrows(com.intellij.openapi.options.ConfigurationException::class.java) { configurable.apply() }
                assertEquals("original", persisted.host)
                inputs.first { it.name == "scenario.target.raw" }.text = "1,2"
                configurable.apply()
                assertEquals("draft-host", persisted.host)
                assertFalse(configurable.isModified(), "Successful Apply establishes a new baseline")
                inputs.first { it.name == "app.host" }.text = "cancelled-host"
                configurable.disposeUIResources()
                assertEquals("draft-host", persisted.host)
            } finally {
                configurable.disposeUIResources(); persisted.loadState(previous)
            }
        }
    }

    @Test
    fun `invalid timing remains editable when its effect normally hides that field`() {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            val configurable = KgBoardConfigurable()
            try {
                val component = configurable.createComponent()
                val period = controlsOfType<javax.swing.JTextField>(component).single { it.name == "scenario.period" }
                period.text = "999999999999999999999999999999"
                controlsOfType<javax.swing.JComboBox<*>>(component).single { it.name == "scenario.effect" }
                    .selectedItem = com.kgboard.rgb.scenario.EffectStyle.STATIC
                assertTrue(period.isVisible, "Invalid hidden timing must remain available for correction")
                assertThrows(com.intellij.openapi.options.ConfigurationException::class.java) { configurable.apply() }
            } finally {
                configurable.disposeUIResources()
            }
        }
    }

    @Test
    fun `inactive duplicate bindings remain visible and can be removed without changing managed state`() {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            val app = KgBoardSettings.getInstance()
            val previous = RuntimeSettings.capture(app.state).toState()
            val state = SettingsMigration.app(KgBoardSettings.State(autoConnect = false, enabled = false))
            val id = java.util.UUID.randomUUID().toString()
            state.bindingRecords.clear()
            state.bindingRecords.add(BindingRecord(id = id, kind = "future-kind"))
            state.bindingRecords.add(BindingRecord(id = id, kind = "future-kind"))
            app.loadState(state)
            val configurable = KgBoardConfigurable()
            try {
                val component = configurable.createComponent()
                val list = controlsOfType<javax.swing.JList<*>>(component).single { it.name == "device.assignments" }
                assertEquals(1, list.model.size, "Duplicate records must have an actionable inactive row")
                controlsOfType<javax.swing.JTextField>(component).single { it.name == "app.host" }.text = "changed-host"
                configurable.apply()
                assertEquals(2, app.state.bindingRecords.size, "An unrelated edit preserves opaque records")
                list.selectedIndex = 0
                controlsOfType<javax.swing.JButton>(component).single { it.name == "device.remove" }.doClick()
                assertEquals(2, app.state.bindingRecords.size, "Removing edits only the draft")
                configurable.apply()
                assertTrue(app.state.bindingRecords.isEmpty())
            } finally {
                configurable.disposeUIResources(); app.loadState(previous)
            }
        }
    }

    @Test
    fun `opening and applying another field preserves unsupported scenario records and project XML`() {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            val app = KgBoardSettings.getInstance()
            val previous = RuntimeSettings.capture(app.state).toState()
            val state = SettingsMigration.app(KgBoardSettings.State(autoConnect = false, enabled = false))
            val opaque = state.ruleRecords.single { it.id == "build.progress" }
            opaque.effect = "FUTURE_EFFECT"
            opaque.target = com.kgboard.rgb.target.TargetRecord(kind = "future-target", raw = "opaque")
            val expected = opaque.copy(target = opaque.target.copyDeep())
            app.loadState(state)
            val projectBefore =
                ProjectSettingsSnapshot.capture(KgBoardProjectSettings.getInstance(fixture.project).state)
            val configurable = KgBoardConfigurable()
            try {
                val component = configurable.createComponent()
                assertFalse(configurable.isModified())
                controlsOfType<javax.swing.JTextField>(component).single { it.name == "app.host" }.text = "changed-host"
                configurable.apply()
                assertEquals(expected, app.state.ruleRecords.single { it.id == "build.progress" })
                assertEquals(
                    projectBefore,
                    ProjectSettingsSnapshot.capture(KgBoardProjectSettings.getInstance(fixture.project).state)
                )
            } finally {
                configurable.disposeUIResources(); app.loadState(previous)
            }
        }
    }

    @Test
    fun `checking project modification does not commit or interrupt a pending branch edit`() {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            val persisted = KgBoardProjectSettings.getInstance(fixture.project)
            val previous = ProjectSettingsSnapshot.capture(persisted.state).toState()
            persisted.loadState(
                KgBoardProjectSettings.State(
                    gitBranchColorRules = mutableListOf(
                        KgBoardProjectSettings.GitColorRule("main", "#123456")
                    )
                )
            )
            val configurable = KgBoardProjectConfigurable(fixture.project)
            try {
                val table = controlsOfType<javax.swing.JTable>(configurable.createComponent()).single()
                assertTrue(table.editCellAt(0, 0))
                (table.editorComponent as javax.swing.JTextField).text = "feat/["
                assertTrue(configurable.isModified())
                assertTrue(table.isEditing, "Settings polling must leave the user's cell editor active")
                assertEquals("main", persisted.state.gitBranchColorRules.single().pattern)
            } finally {
                configurable.disposeUIResources(); persisted.loadState(previous)
            }
        }
    }

    @Test
    fun `branch table refuses invalid pending input and project apply leaves application unchanged`() {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            val app = KgBoardSettings.getInstance()
            val appPrevious = RuntimeSettings.capture(app.state).toState()
            app.loadState(KgBoardSettings.State(autoConnect = false, enabled = false))
            val appBaseline = RuntimeSettings.capture(app.state)
            val persisted = KgBoardProjectSettings.getInstance(fixture.project)
            val previous = ProjectSettingsSnapshot.capture(persisted.state).toState()
            persisted.loadState(
                KgBoardProjectSettings.State(
                    gitBranchColorRules = mutableListOf(
                        KgBoardProjectSettings.GitColorRule("main", "#123456"),
                        KgBoardProjectSettings.GitColorRule(".*", "#654321")
                    )
                )
            )
            val configurable = KgBoardProjectConfigurable(fixture.project)
            try {
                val component = configurable.createComponent()
                val table = controlsOfType<javax.swing.JTable>(component).single()
                assertTrue(table.editCellAt(0, 0))
                (table.editorComponent as javax.swing.JTextField).text = "["
                assertThrows(com.intellij.openapi.options.ConfigurationException::class.java) { configurable.apply() }
                assertEquals("main", persisted.state.gitBranchColorRules.first().pattern)
                table.model.setValueAt(".*", 0, 0)
                configurable.apply()
                assertEquals(".*", persisted.state.gitBranchColorRules.first().pattern)
                table.setRowSelectionInterval(1, 1)
                controlsOfType<javax.swing.JButton>(component)
                    .single { it.text == com.kgboard.rgb.KgBoardBundle.message("branch.up") }.doClick()
                assertTrue(table.editCellAt(0, 1))
                (table.editorComponent as javax.swing.JTextField).text = "bad-color"
                assertThrows(com.intellij.openapi.options.ConfigurationException::class.java) { configurable.apply() }
                assertEquals("#123456", persisted.state.gitBranchColorRules.first().color)
                table.model.setValueAt("#AABBCC", 0, 1)
                configurable.apply()
                assertEquals(
                    "#AABBCC",
                    persisted.state.gitBranchColorRules.first { Regex(it.pattern).matches("main") }.color
                )
                assertEquals(appBaseline, RuntimeSettings.capture(app.state))
            } finally {
                configurable.disposeUIResources(); persisted.loadState(previous); app.loadState(appPrevious)
            }
        }
    }

    private fun allControls(component: java.awt.Component): List<java.awt.Component> = listOf(component) +
        (component as? java.awt.Container)?.components.orEmpty().flatMap(::allControls)

    private inline fun <reified T> controlsOfType(component: java.awt.Component): List<T> =
        allControls(component).filterIsInstance<T>()

    @Test
    fun `legacy XML values survive runtime snapshots and serializer round trip`() {
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
        assertEquals(
            app,
            XmlSerializer.deserialize(XmlSerializer.serialize(appCopy), KgBoardSettings.State::class.java)
        )
        assertEquals(
            project,
            XmlSerializer.deserialize(XmlSerializer.serialize(projectCopy), KgBoardProjectSettings.State::class.java)
        )
        assertEquals(
            groups,
            XmlSerializer.deserialize(XmlSerializer.serialize(groups), KeyGroupService.GroupState::class.java)
        )
    }

    @Test
    fun `typed groups and unknown target kinds round trip with stable IDs`() {
        val id = "user:00000000-0000-4000-8000-000000000001"
        val groups = KeyGroupService.GroupState(
            mutableListOf(
                KeyGroupService.UserGroup(
                    "Named", "", id,
                    com.kgboard.rgb.target.TargetRecord.from(com.kgboard.rgb.target.TargetSpec.NamedKeys(listOf("Key: Esc")))
                )
            )
        )
        val copy = XmlSerializer.deserialize(XmlSerializer.serialize(groups), KeyGroupService.GroupState::class.java)
        assertEquals(groups, copy)
        assertEquals(id, copy.userGroups.single().id)
        val unknown =
            com.kgboard.rgb.target.TargetRecord(kind = "future-kind", names = mutableListOf("Esc"), raw = "unparsed")
        val restored =
            XmlSerializer.deserialize(XmlSerializer.serialize(unknown), com.kgboard.rgb.target.TargetRecord::class.java)
        assertEquals(unknown, restored)
        assertNull(restored.toSpec())
    }

    @Test
    fun `versioned app and project records survive platform XML serialization`() {
        val app = SettingsMigration.app(KgBoardSettings.State())
        app.bindingRecords = mutableListOf(
            BindingRecord(
                id = "00000000-0000-4000-8000-000000000001",
                kind = "serial", type = 1, vendor = "Vendor", serial = "S", name = "Display hint", location = "USB hint"
            )
        )
        app.untrustedSerials = mutableListOf(SerialRecord(1, "Vendor", "Duplicate"))
        app.ruleRecords.single { it.id == "build.failure" }.target =
            com.kgboard.rgb.target.TargetRecord.from(com.kgboard.rgb.target.TargetSpec.NamedKeys(listOf("Key: Esc")))
        app.ruleRecords.add(
            com.kgboard.rgb.scenario.RuleRecord(
                id = "future.rule",
                target = com.kgboard.rgb.target.TargetRecord(kind = "future-target", raw = "opaque")
            )
        )
        val captured = RuntimeSettings.capture(app)
        assertEquals(
            app,
            XmlSerializer.deserialize(XmlSerializer.serialize(captured.toState()), KgBoardSettings.State::class.java)
        )
        val project = SettingsMigration.project(KgBoardProjectSettings.State())
        val projectSnapshot = ProjectSettingsSnapshot.capture(project)
        assertEquals(
            project,
            XmlSerializer.deserialize(
                XmlSerializer.serialize(projectSnapshot.toState()),
                KgBoardProjectSettings.State::class.java
            )
        )
        app.bindingRecords.single().serial = "mutated"
        app.ruleRecords.first().target.names.add("mutated")
        assertEquals("S", captured.toState().bindingRecords.single().serial)
        assertFalse(captured.toState().ruleRecords.first().target.names.contains("mutated"))
    }
}
