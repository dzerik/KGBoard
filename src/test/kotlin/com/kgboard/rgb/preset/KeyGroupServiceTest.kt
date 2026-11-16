package com.kgboard.rgb.preset

import com.kgboard.rgb.client.RgbDeviceInfo
import com.kgboard.rgb.target.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KeyGroupServiceTest {
    @Test fun `legacy groups gain stable UUID and remain unconfirmed until layout selection`() {
        val service = KeyGroupService()
        val input = KeyGroupService.GroupState(mutableListOf(KeyGroupService.UserGroup("Mine", "0")))
        service.loadState(input)
        val stored = service.state.userGroups.single()
        assertTrue(stored.id.startsWith("user:"))
        java.util.UUID.fromString(stored.id.removePrefix("user:"))
        val device = RgbDeviceInfo(1,"B","V","",1,listOf("Esc"),emptyList(),emptyList())
        val target = service.definitions().getValue(stored.id).target
        assertTrue(TargetResolver.resolve(target,device,service.definitions()).indices.isEmpty())
        val reload = KeyGroupService()
        reload.loadState(service.state.copyDeep())
        assertEquals(stored.id, reload.state.userGroups.single().id)
        input.userGroups.single().name = "External mutation"
        assertEquals("Mine", service.state.userGroups.single().name)
    }

    @Test fun `renaming typed group retains target and cannot shadow built in namespace`() {
        val service = KeyGroupService()
        val id = "user:00000000-0000-4000-8000-000000000001"
        val target = TargetRecord.from(TargetSpec.NamedKeys(listOf("Esc")))
        service.loadState(KeyGroupService.GroupState(mutableListOf(
            KeyGroupService.UserGroup("Before", "", id, target))))
        val copy = service.state.copyDeep()
        copy.userGroups.single().name = "After"
        service.loadState(copy)
        assertEquals("After", service.definitions().getValue(id).name)
        assertEquals(TargetSpec.NamedKeys(listOf("Esc")), service.definitions().getValue(id).target)
        copy.userGroups.add(KeyGroupService.UserGroup("Invalid shadow", "", "builtin:wasd", TargetRecord.from(TargetSpec.All)))
        service.loadState(copy)
        assertTrue(service.definitions().getValue("builtin:wasd").target is TargetSpec.NamedKeys)
    }
}
