package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.serviceid.HttpStackRule
import com.northwalessolutions.netprobe.engine.serviceid.MatchRole

/**
 * The identity rules the stack file implies (Phase 3E spec 7). A `device` role stack becomes one rule of weight [WEIGHT] on the port field
 * `stack` that sets the vendor, model and type the stack rule names; they are built from the same file as the stack hits, so the two cannot
 * disagree. `software` stacks make no rule: the identifier lists them in its software slot ("Runs Plex"). The generated rules come after
 * `identify_rules.json`, and a tie at equal weight keeps the first rule, as ever.
 */
object StackIdentity {
    const val WEIGHT = 60
    const val ID_PREFIX = "stack-"

    fun rules(stacks: List<HttpStackRule>): List<Rule> {
        val seen = HashSet<String>()
        val out = ArrayList<Rule>()
        for (s in stacks) {
            if (s.role != MatchRole.DEVICE) continue
            if (s.vendor == null && s.model == null && s.type == null) continue
            if (!seen.add(s.stack.lowercase())) continue            // the first rule of a stack name speaks for it, as ties do
            out += Rule(id = ID_PREFIX + s.id, field = "stack", pattern = "^" + Regex.escape(s.stack) + "$", vendor = s.vendor, model = s.model,
                type = s.type?.name, weight = WEIGHT, sample = s.stack)
        }
        return out
    }
}
