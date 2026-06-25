package io.github.nitanmarcel.jdex.debug

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class DeviceBridgeTest {

    @Test
    fun connectsToAdbServerAndListsDevices() {
        val adb = DeviceBridge.adbPath()
        assumeTrue(adb != null, "adb not found; skipping device-bridge smoke test")
        assertTrue(adb!!.canExecute())
        try {
            val devices = DeviceBridge.devices()
            assertNotNull(devices, "bridge should return a (possibly empty) device list")
            devices.forEach { d ->
                assertTrue(d.serial.isNotEmpty())
                if (d.online) DeviceBridge.processes(d.serial)
            }
        } finally {
            DeviceBridge.disconnect()
        }
    }
}
