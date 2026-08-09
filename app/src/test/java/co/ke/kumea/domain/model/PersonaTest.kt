package co.ke.kumea.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persona classification (P1-T7). The crux is the officer exclusion: an
 * extension_officer must classify to a persona that can NEVER see earnings, and
 * a farmer (no linked agent) must never be handed an agent surface. These are the
 * unit-level proof that "an officer can never render the agent earnings view".
 */
class PersonaTest {

    @Test
    fun `no linked agent maps to farmer`() {
        assertEquals(Persona.FARMER, Persona.fromAgentRole(null))
        assertEquals(Persona.FARMER, Persona.fromAgentRole(""))
        assertEquals(Persona.FARMER, Persona.fromAgentRole("   "))
    }

    @Test
    fun `extension_officer maps to officer persona`() {
        assertEquals(Persona.EXTENSION_OFFICER, Persona.fromAgentRole("extension_officer"))
    }

    @Test
    fun `commission-eligible roles map to village_agent persona`() {
        assertEquals(Persona.VILLAGE_AGENT, Persona.fromAgentRole("village_agent"))
        assertEquals(Persona.VILLAGE_AGENT, Persona.fromAgentRole("agro_dealer"))
        assertEquals(Persona.VILLAGE_AGENT, Persona.fromAgentRole("cooperative"))
    }

    @Test
    fun `officer persona never allows earnings`() {
        assertFalse(Persona.EXTENSION_OFFICER.allowsEarnings)
    }

    @Test
    fun `farmer persona never allows earnings`() {
        assertFalse(Persona.FARMER.allowsEarnings)
    }

    @Test
    fun `only the village_agent persona allows earnings`() {
        assertTrue(Persona.VILLAGE_AGENT.allowsEarnings)
        // Exhaustive: no other persona may ever reach the earnings surface.
        val earners = Persona.entries.filter { it.allowsEarnings }
        assertEquals(listOf(Persona.VILLAGE_AGENT), earners)
    }

    @Test
    fun `an officer role never classifies into an earnings-allowed persona`() {
        val persona = Persona.fromAgentRole("extension_officer")
        assertFalse(persona.allowsEarnings)
    }
}
