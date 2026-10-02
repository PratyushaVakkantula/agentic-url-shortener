package com.agentic.orchestration.governance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs every policy against a stage output and returns all decisions (including ALLOWs, so the
 * audit trail shows each guardrail was actually checked, not just the ones that fired).
 *
 * <p><b>A policy that throws yields REQUIRE_APPROVAL</b>: it fails safe (output is not accepted
 * automatically), but one buggy rule cannot halt every run in the system; a human decides.
 */
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private final List<Policy> policies;

    public PolicyEngine(List<Policy> policies) {
        this.policies = policies.stream().sorted(Comparator.comparing(Policy::name)).toList();
    }

    public static PolicyEngine none() {
        return new PolicyEngine(List.of());
    }

    public List<PolicyDecision> evaluate(PolicyInput input) {
        List<PolicyDecision> decisions = new ArrayList<>(policies.size());
        for (Policy policy : policies) {
            try {
                PolicyDecision decision = policy.evaluate(input);
                decisions.add(decision != null ? decision : errored(policy, "returned no decision"));
            } catch (RuntimeException e) {
                log.error("Policy {} failed on stage {}", policy.name(), input.stageId(), e);
                decisions.add(errored(policy, e.getClass().getSimpleName()));
            }
        }
        return decisions;
    }

    public static PolicyOutcome mostSevere(List<PolicyDecision> decisions) {
        return decisions.stream().map(PolicyDecision::outcome).max(Comparator.naturalOrder()).orElse(PolicyOutcome.ALLOW);
    }

    private static PolicyDecision errored(Policy policy, String why) {
        return new PolicyDecision(policy.name(), policy.category(), PolicyOutcome.REQUIRE_APPROVAL,
                "policy could not be evaluated (" + why + "); human review required");
    }
}
