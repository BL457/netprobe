package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.serviceid.TlsDetails
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolModelCopy

/** TLS certificate (spec 6.2): one handshake (named, then on the IP) mapped to findings by the pure CertSummaries. */
class TlsCertificateTool(
    private val peek: suspend (target: ToolTarget, serverName: String?) -> TlsDetails?,
    private val now: () -> Long = System::currentTimeMillis,
) : ToolModule {
    override val descriptor = ToolDescriptor(ToolKeys.TLS_CERTIFICATE, ToolCopy.TLS_CERTIFICATE, ToolModelCopy.TLS_CERTIFICATE_DESCRIPTION, ToolIcon.LOCK,
        ToolCategory.WEB, TargetKind.PORT, emptyList())
    override fun applies(port: Int, service: ServiceInfo) = ToolApplicability.appliesTo(descriptor.key, port, service)

    override suspend fun run(target: ToolTarget?, inputs: ToolInputs, control: RunControl, progress: ToolProgress): ToolResult {
        if (target == null) return ToolResult(ToolRunStatus.FAILED, ToolCopy.failed(ToolModelCopy.NO_TARGET))
        control.gate()
        val details = control.excusedOnce<TlsDetails?>({ it == null }) { peek(target, target.serverName) }   // spec 4.8 (plan review G5)
            ?: return ToolResult(ToolRunStatus.FAILED, ToolCopy.TLS_NO_HANDSHAKE)
        val at = now()
        var shown = 0
        for (f in CertSummaries.of(details, at)) {
            progress.finding(f)
            if (f.kind != FindingKind.HINT) { shown++; progress.update(1, 1, shown) }
        }
        return ToolResult(ToolRunStatus.DONE, CertSummaries.summary(details, at))
    }
}
