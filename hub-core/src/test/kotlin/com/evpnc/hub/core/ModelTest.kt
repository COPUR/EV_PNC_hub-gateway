package com.evpnc.hub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ModelTest {
    @Test
    fun `a secret never prints its value`() {
        val request = authorizeRequest().copy(pin = Secret("1234"))
        assertFalse(request.toString().contains("1234"), request.toString())
        assertEquals("1234", request.pin!!.reveal())
    }

    @Test
    fun `a denial cannot carry the ALLOWED outcome`() {
        assertFailsWith<IllegalArgumentException> {
            AuthorizeDecision.Denied(DecisionOutcome.ALLOWED, emptyList())
        }
    }
}
