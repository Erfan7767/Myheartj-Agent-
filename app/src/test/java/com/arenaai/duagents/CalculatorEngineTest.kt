package com.arenaai.duagents

import com.arenaai.duagents.tools.CalculatorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CalculatorEngineTest {

    private fun assertNum(expected: Double, actual: Double, delta: Double = 1e-9) {
        assertTrue("expected=$expected actual=$actual", abs(expected - actual) < delta)
    }

    @Test
    fun `basic precedence`() {
        assertNum(14.0, CalculatorEngine.evaluate("2+3*4"))
        assertNum(20.0, CalculatorEngine.evaluate("(2+3)*4"))
        assertNum(8.0, CalculatorEngine.evaluate("16/2"))
    }

    @Test
    fun `power is right associative`() {
        assertNum(512.0, CalculatorEngine.evaluate("2^3^2")) // 2^(3^2) = 2^9 = 512
        assertNum(1024.0, CalculatorEngine.evaluate("2^10"))
    }

    @Test
    fun `unary minus and modulo`() {
        assertNum(-2.0, CalculatorEngine.evaluate("-5+3"))
        assertNum(1.0, CalculatorEngine.evaluate("10%3"))
        assertNum(-8.0, CalculatorEngine.evaluate("-(2*4)"))
    }

    @Test
    fun `functions and constants`() {
        assertNum(6.0, CalculatorEngine.evaluate("sqrt(16)+abs(-2)"))
        assertNum(1.0, CalculatorEngine.evaluate("ln(e)"))
        assertNum(1.0, CalculatorEngine.evaluate("sin(pi/2)"))
        assertNum(3.141592653589793, CalculatorEngine.evaluate("pi"))
    }

    @Test
    fun `text output strips trailing zero`() {
        assertEquals("14", CalculatorEngine.evaluateToText("2*7"))
        assertTrue(CalculatorEngine.evaluateToText("1/3").startsWith("0.3333"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `incomplete expression is rejected`() {
        CalculatorEngine.evaluate("2+")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `division by zero is rejected as non-finite`() {
        CalculatorEngine.evaluate("5/0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `garbage input is rejected`() {
        CalculatorEngine.evaluate("hello world")
    }
}
