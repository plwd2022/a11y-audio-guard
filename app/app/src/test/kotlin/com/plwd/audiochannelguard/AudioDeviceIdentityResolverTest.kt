package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDeviceIdentityResolverTest {

    @Test
    fun `identity key prefers address when available`() {
        val key = AudioDeviceIdentityResolver.identityKey(
            type = 8,
            address = "AA:BB",
            productName = "AirPods"
        )

        assertEquals("8:AA:BB", key)
    }

    @Test
    fun `identity key falls back to product name when address is missing`() {
        val key = AudioDeviceIdentityResolver.identityKey(
            type = 7,
            address = null,
            productName = "骨传导耳机"
        )

        assertEquals("7:骨传导耳机", key)
    }

    @Test
    fun `identity key falls back to type when both address and product name are missing`() {
        val key = AudioDeviceIdentityResolver.identityKey(
            type = 22,
            address = "",
            productName = ""
        )

        assertEquals("22", key)
    }

    @Test
    fun `physical device match prefers non empty equal addresses`() {
        val same = AudioDeviceIdentityResolver.isSamePhysicalDevice(
            firstAddress = "AA:BB",
            firstProductName = "Device A",
            secondAddress = "AA:BB",
            secondProductName = "Device B"
        )

        assertTrue(same)
    }

    @Test
    fun `physical device match falls back to product name when address is unavailable`() {
        val same = AudioDeviceIdentityResolver.isSamePhysicalDevice(
            firstAddress = "",
            firstProductName = "Device A",
            secondAddress = "",
            secondProductName = "Device A"
        )

        assertTrue(same)
    }

    @Test
    fun `physical device match falls back to product name when addresses differ but first is empty`() {
        val same = AudioDeviceIdentityResolver.isSamePhysicalDevice(
            firstAddress = "",
            firstProductName = "Device A",
            secondAddress = "XX:YY",
            secondProductName = "Device A"
        )

        assertTrue(same)
    }

    @Test
    fun `physical device match returns false when both address and product name differ`() {
        val same = AudioDeviceIdentityResolver.isSamePhysicalDevice(
            firstAddress = "AA:BB",
            firstProductName = "Device A",
            secondAddress = "CC:DD",
            secondProductName = "Device B"
        )

        assertFalse(same)
    }
}
