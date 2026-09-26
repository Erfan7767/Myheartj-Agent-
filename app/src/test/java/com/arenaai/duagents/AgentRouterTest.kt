package com.arenaai.duagents

import com.arenaai.duagents.core.AgentRegistry
import com.arenaai.duagents.core.AgentRouter
import com.arenaai.duagents.data.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRouterTest {

    private val irfan = AgentRegistry.IRFAN
    private val elena = AgentRegistry.ELENA

    @Test
    fun `naming elena hands over fully`() {
        val decision = AgentRouter.decide("Elena, can you help me write a poem?", AgentMode.AUTO, irfan)
        assertFalse(decision.collaborative)
        assertEquals(elena.id, decision.newActiveAgent.id)
        assertEquals(1, decision.participants.size)
        assertEquals("elena", decision.participants.first().id)
    }

    @Test
    fun `arabic irfan mention routes to irfan`() {
        val decision = AgentRouter.decide("عرفان احسب لي المصروفات", AgentMode.AUTO, elena)
        assertEquals("irfan", decision.newActiveAgent.id)
        assertFalse(decision.collaborative)
    }

    @Test
    fun `both names together triggers collaboration`() {
        val decision = AgentRouter.decide("عرفان وإيلينا اكتبا لي خطة عمل معاً", AgentMode.AUTO, irfan)
        assertTrue(decision.collaborative)
        assertEquals(2, decision.participants.size)
    }

    @Test
    fun `english both-of-you triggers collaboration`() {
        val decision = AgentRouter.decide("I want both of you to design this website together", AgentMode.AUTO, irfan)
        assertTrue(decision.collaborative)
    }

    @Test
    fun `no mention keeps active agent`() {
        val decision = AgentRouter.decide("What is the weather like today?", AgentMode.AUTO, elena)
        assertEquals("elena", decision.newActiveAgent.id)
        assertFalse(decision.collaborative)
    }

    @Test
    fun `explicit mode overrides everything`() {
        val decision = AgentRouter.decide("anything at all", AgentMode.ELENA, irfan)
        assertEquals("elena", decision.participants.first().id)
        assertFalse(decision.collaborative)
    }
}
