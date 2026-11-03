package dev.nanodash.core.camera

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CameraFleetTest {
    private val baseUtc = 1_793_676_000_000L
    private val id = "00112233445566778899aabbccddeeff"

    private fun TestScope.clock() = object : Clock {
        override fun utcMillis() = baseUtc + currentTime
        override fun monotonicNanos() = currentTime * 1_000_000
    }

    private class Cam(val nano: FakeNano, var reachable: Boolean = true)

    private fun TestScope.cam() = Cam(FakeNano(backgroundScope) { currentTime })

    private suspend fun TestScope.fleet(vararg cams: Pair<String, Cam>): CameraFleet {
        val links = cams.associate { (name, cam) ->
            name to CameraLink(backgroundScope, clock(), id) {
                if (!cam.reachable) throw IOException("not found")
                cam.nano.also { it.reconnect() }
            }
        }
        for (link in links.values) assertTrue(link.connect())
        return CameraFleet(links)
    }

    @Test
    fun twoCamerasStartTogetherAndStopTogether() = runTest {
        val a = cam(); val b = cam()
        val fleet = fleet("A" to a, "B" to b)
        assertEquals(listOf("A", "B"), fleet.readyIds())

        val started = fleet.startReady()
        assertIs<StartResult.Started>(started.getValue("A"))
        assertIs<StartResult.Started>(started.getValue("B"))
        // both were commanded in the same instant, not one after the other
        assertEquals(started.getValue("A").let { (it as StartResult.Started).commandSent.utcMillis },
            (started.getValue("B") as StartResult.Started).commandSent.utcMillis)

        delay(5_000)
        val stopped = fleet.stop(started.keys)
        assertIs<StopResult.Stopped>(stopped.getValue("A"))
        assertIs<StopResult.Stopped>(stopped.getValue("B"))
        fleet.close()
    }

    @Test
    fun aCameraThatIsNotReadyIsLeftOutAndTheOtherStillRecords() = runTest {
        val a = cam(); val b = cam()
        val fleet = fleet("A" to a, "B" to b)
        b.reachable = false
        b.nano.linkUp = false
        delay(3_000)
        assertEquals(listOf("A"), fleet.readyIds())

        val started = fleet.startReady()
        assertEquals(setOf("A"), started.keys)
        assertIs<StartResult.Started>(started.getValue("A"))
        delay(5_000)
        assertIs<StopResult.Stopped>(fleet.stop(started.keys).getValue("A"))
        fleet.close()
    }

    @Test
    fun oneCameraLosingItsLinkDoesNotHoldBackTheOthersStop() = runTest {
        val a = cam(); val b = cam()
        val fleet = fleet("A" to a, "B" to b)
        val started = fleet.startReady()
        delay(5_000)

        b.reachable = false
        b.nano.linkUp = false
        delay(3_000)
        val before = currentTime
        val aStop = async { fleet.stop(listOf("A")) }
        assertIs<StopResult.Stopped>(aStop.await().getValue("A"))
        assertTrue(currentTime - before < 5_000, "A waited ${currentTime - before} ms")
        assertEquals(setOf("A", "B"), started.keys)
        fleet.close()
    }
}
