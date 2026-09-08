package me.huanlin.gbuca

import me.huanlin.gbuca.domain.oobe.OobeFlow
import me.huanlin.gbuca.domain.oobe.OobeStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OobeFlowTest {

    @Test
    fun `steps follow the wizard order`() {
        assertEquals(
            listOf(
                OobeStep.Address,
                OobeStep.Login,
                OobeStep.Permissions,
                OobeStep.Reminders,
                OobeStep.Done,
            ),
            OobeFlow.steps,
        )
    }

    @Test
    fun `next walks forward and stops at done`() {
        assertEquals(OobeStep.Login, OobeFlow.next(OobeStep.Address))
        assertEquals(OobeStep.Permissions, OobeFlow.next(OobeStep.Login))
        assertEquals(OobeStep.Reminders, OobeFlow.next(OobeStep.Permissions))
        assertEquals(OobeStep.Done, OobeFlow.next(OobeStep.Reminders))
        assertNull(OobeFlow.next(OobeStep.Done))
    }

    @Test
    fun `previous walks backward and stops at address`() {
        assertEquals(OobeStep.Reminders, OobeFlow.previous(OobeStep.Done))
        assertEquals(OobeStep.Permissions, OobeFlow.previous(OobeStep.Reminders))
        assertEquals(OobeStep.Login, OobeFlow.previous(OobeStep.Permissions))
        assertEquals(OobeStep.Address, OobeFlow.previous(OobeStep.Login))
        assertNull(OobeFlow.previous(OobeStep.Address))
    }

    @Test
    fun `start step follows configuration state`() {
        assertEquals(OobeStep.Address, OobeFlow.startStep(hostsConfigured = false, hasCredentials = false))
        assertEquals(OobeStep.Address, OobeFlow.startStep(hostsConfigured = false, hasCredentials = true))
        assertEquals(OobeStep.Login, OobeFlow.startStep(hostsConfigured = true, hasCredentials = false))
        assertEquals(OobeStep.Permissions, OobeFlow.startStep(hostsConfigured = true, hasCredentials = true))
    }

    @Test
    fun `progress counts four steps and hides on done`() {
        assertEquals(1 to 4, OobeFlow.progressOf(OobeStep.Address))
        assertEquals(2 to 4, OobeFlow.progressOf(OobeStep.Login))
        assertEquals(3 to 4, OobeFlow.progressOf(OobeStep.Permissions))
        assertEquals(4 to 4, OobeFlow.progressOf(OobeStep.Reminders))
        assertNull(OobeFlow.progressOf(OobeStep.Done))
    }
}
