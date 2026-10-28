package com.pps.cardboardpoc

import com.pps.cardboardpoc.VoiceCommands.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandsTest {

    @Test
    fun `cada palabra sola dispara su comando`() {
        assertEquals(Command.CLICK, VoiceCommands.commandFor("tocar"))
        assertEquals(Command.GRAB, VoiceCommands.commandFor("agarrar"))
        assertEquals(Command.RELEASE, VoiceCommands.commandFor("soltar"))
        assertEquals(Command.CENTER, VoiceCommands.commandFor("centrar"))
        assertEquals(Command.BACK, VoiceCommands.commandFor("volver"))
    }

    @Test
    fun `las transcripciones equivocadas conocidas cuentan como el comando`() {
        assertEquals(Command.CLICK, VoiceCommands.commandFor("toca"))
        assertEquals(Command.GRAB, VoiceCommands.commandFor("agarra"))
        assertEquals(Command.RELEASE, VoiceCommands.commandFor("soldar"))
    }

    @Test
    fun `un comando dentro de una frase no dispara nada`() {
        assertNull(VoiceCommands.commandFor("tocá ahí"))
        assertNull(VoiceCommands.commandFor("tocar agarrar"))
        assertNull(VoiceCommands.commandFor("vamos a soltar esto"))
    }

    @Test
    fun `palabras fuera de los comandos y el silencio no disparan nada`() {
        assertNull(VoiceCommands.commandFor("clic"))
        assertNull(VoiceCommands.commandFor("saltar"))
        assertNull(VoiceCommands.commandFor(""))
    }
}
