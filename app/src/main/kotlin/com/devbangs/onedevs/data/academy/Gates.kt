package com.devbangs.onedevs.data.academy

import com.devbangs.onedevs.R
import com.devbangs.onedevs.lab.ApkReport
import com.devbangs.onedevs.lab.Check
import com.devbangs.onedevs.lab.Checks
import com.devbangs.onedevs.lab.Msg
import com.devbangs.onedevs.lab.Status

/** What a gate made of one APK: the checks it ran, and whether they pass. */
data class GateResult(val checks: List<Check>, val passed: Boolean)

/**
 * Gates: the competencies that end with the learner's own build.
 *
 * Each gate is a selection of the checks the Lab already runs, so a gate and
 * the Lab can never disagree about the same APK. A gate passes when nothing
 * in it fails and the one thing it exists to prove is positively there. That
 * second half matters: the Lab warns when it cannot tell whether R8 ran, and
 * a warning is fine for a release checklist but not for a credential, which
 * has to rest on something that was seen.
 */
object Gates {

    fun evaluate(gate: ArtifactGate, r: ApkReport): GateResult {
        val checks = when (gate) {
            ArtifactGate.TargetSdk -> Checks.targetSdk(r)
            ArtifactGate.Signing -> Checks.signing(r)
            ArtifactGate.ReleaseBuild -> Checks.debugSignals(r) + Checks.proguard(r).take(1)
            ArtifactGate.NativeCode -> Checks.natives(r).ifEmpty {
                listOf(Check(Status.Pass, Msg.Str(R.string.acad_gate_no_native)))
            }
        }
        val proven = when (gate) {
            ArtifactGate.TargetSdk -> r.meetsPlayTargetFloor
            ArtifactGate.Signing -> r.signatureSha256 != null && !r.debugCertificate && r.schemes.modern
            ArtifactGate.ReleaseBuild -> !r.debuggable && !r.testOnly && r.compiler?.shrunk == true
            ArtifactGate.NativeCode -> Checks.missing64(r).isEmpty()
        }
        // A gate that is not proven always shows a failing line saying why,
        // even when every check it borrowed only warned.
        val shown = if (!proven && checks.none { it.status == Status.Fail }) {
            checks + Check(Status.Fail, Msg.Str(unproven(gate)))
        } else {
            checks
        }
        return GateResult(shown, proven && shown.none { it.status == Status.Fail })
    }

    private fun unproven(gate: ArtifactGate): Int = when (gate) {
        ArtifactGate.TargetSdk -> R.string.acad_gate_unproven_target
        ArtifactGate.Signing -> R.string.acad_gate_unproven_signing
        ArtifactGate.ReleaseBuild -> R.string.acad_gate_unproven_release
        ArtifactGate.NativeCode -> R.string.acad_gate_unproven_native
    }
}
