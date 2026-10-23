package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.common.currentBinder
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import kotlin.coroutines.ContinuationInterceptor

/** 3C spec 4.5: a manual run follows its own route; a Device-screen run keeps the scan binding (Review Focus 3). */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolRunnerRouteTest {
    private object FakeBinder : SocketBinder {
        override fun bind(socket: Socket) {}
        override fun bind(socket: DatagramSocket) {}
        override fun openConnection(url: URL): URLConnection = url.openConnection(Proxy.NO_PROXY)
    }
    /** Records the route each run is stored with; everything else is the 3B fake. */
    private class RouteStore(private val inner: ToolRunnerTestSupport.FakeStore = ToolRunnerTestSupport.FakeStore()) : ToolStore by inner {
        val routes = mutableListOf<String?>()
        val finished: List<Triple<Long, ToolRunStatus, String>> get() = inner.finished
        override suspend fun startRun(hostId: Long?, toolId: String, port: Int, startedAt: Long, optionsJson: String, route: String?, toolName: String): Long {
            routes += route
            return inner.startRun(hostId, toolId, port, startedAt, optionsJson)
        }
    }
    private class Rig(scope: CoroutineScope, routeBind: (suspend (ToolRoute, NetworkEnv, suspend () -> Unit) -> Unit)?) {
        val store = RouteStore()
        val seen = mutableListOf<SocketBinder?>()
        val scanBinds = mutableListOf<Inet4Address>()
        private val tool = ToolRunnerTestSupport.FakeTool(ToolKeys.TLS_CERTIFICATE) { _, _ -> seen += currentBinder(); ToolResult(ToolRunStatus.DONE, "ok") }
        private val dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        private val scanBind: suspend (Inet4Address, NetworkEnv, suspend () -> Unit) -> Unit = { ip, _, block -> scanBinds += ip; block() }
        private val env = MutableStateFlow<NetworkEnv?>(ToolRunnerTestSupport.home)
        val runner = if (routeBind == null)
            ToolRunner(scope, dispatcher, ToolRegistry(listOf(tool)), store, HostBusyRegistry(), AppMessages(), env, MutableStateFlow(true), scanBind, onFinished = {})
        else
            ToolRunner(scope, dispatcher, ToolRegistry(listOf(tool)), store, HostBusyRegistry(), AppMessages(), env, MutableStateFlow(true), scanBind, onFinished = {}, routeBind = routeBind)
    }
    private fun target(route: ToolRoute?) = ToolTarget(7, ToolRunnerTestSupport.ip("10.20.0.5"), 443, tls = true, serverName = null, route = route)

    @Test fun `a WIFI manual run is bound through the route binder and never through the scan bind`() = runTest {
        val rig = Rig(backgroundScope) { route, e, block -> ManualRoute.bind(route, e, { FakeBinder }) { block() } }
        rig.runner.start(target(ToolRoute.WIFI), ToolKeys.TLS_CERTIFICATE, ToolInputs.NONE); runCurrent()
        assertEquals(listOf<SocketBinder?>(FakeBinder), rig.seen)
        assertTrue("the scan bind is for Device-screen runs only", rig.scanBinds.isEmpty())
        assertEquals(listOf<String?>("WIFI"), rig.store.routes)
    }
    @Test fun `an OTHER manual run goes out unbound and never asks for a binder`() = runTest {
        var lookups = 0
        val rig = Rig(backgroundScope) { route, e, block -> ManualRoute.bind(route, e, { lookups++; FakeBinder }) { block() } }
        rig.runner.start(target(ToolRoute.OTHER), ToolKeys.TLS_CERTIFICATE, ToolInputs.NONE); runCurrent()
        assertEquals(listOf<SocketBinder?>(null), rig.seen); assertEquals(0, lookups)
        assertEquals(listOf<String?>("OTHER"), rig.store.routes)
        assertEquals(ToolRunStatus.DONE, rig.store.finished.single().second)
    }
    @Test fun `a WIFI manual run with no binder fails with the bind wording and the tool never runs`() = runTest {
        val rig = Rig(backgroundScope) { route, e, block -> ManualRoute.bind(route, e, { null }) { block() } }
        rig.runner.start(target(ToolRoute.WIFI), ToolKeys.TLS_CERTIFICATE, ToolInputs.NONE); runCurrent()
        assertTrue("no socket was opened", rig.seen.isEmpty())
        assertEquals(Triple(1L, ToolRunStatus.FAILED, ToolCopy.BIND_FAILED), rig.store.finished.single())
    }
    @Test fun `the default route binder refuses a Wi-Fi route rather than run it unbound`() = runTest {
        val rig = Rig(backgroundScope, routeBind = null)
        rig.runner.start(target(ToolRoute.WIFI), ToolKeys.TLS_CERTIFICATE, ToolInputs.NONE); runCurrent()
        assertTrue(rig.seen.isEmpty())
        assertEquals(Triple(1L, ToolRunStatus.FAILED, ToolCopy.BIND_FAILED), rig.store.finished.single())
    }
    @Test fun `a Device-screen run keeps the scan bind and stores no route`() = runTest {
        var routed = 0
        val rig = Rig(backgroundScope) { _, _, block -> routed++; block() }
        rig.runner.start(target(null), ToolKeys.TLS_CERTIFICATE, ToolInputs.NONE); runCurrent()
        assertEquals(listOf(ToolRunnerTestSupport.ip("10.20.0.5")), rig.scanBinds); assertEquals(0, routed)
        assertEquals(listOf<String?>(null), rig.store.routes)
    }
}
