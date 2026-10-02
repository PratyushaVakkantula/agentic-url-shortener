package com.agentic.orchestration.agent;

import com.agentic.orchestration.model.Artifact;
import com.agentic.orchestration.model.ArtifactRef;
import com.agentic.orchestration.model.Requirement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Read-only view an agent works from (OR-4).
 *
 * <p><b>Isolation:</b> only artifacts of the stage's <i>ancestors</i> are visible. This makes
 * every data dependency explicit in the graph, which is what lets re-planning invalidate exactly
 * the right stages: an agent cannot secretly depend on a stage it does not declare.
 *
 * <p><b>Lineage:</b> every artifact actually read is recorded, and the engine attaches those exact
 * versions to the agent's decisions. Lineage is observed, not self-reported.
 *
 * <p>The artifact snapshot is taken when the stage is dispatched and never changes during
 * execution, so an agent always sees one consistent version of its inputs.
 */
public final class StageContext {

    private final String runId;
    private final String stageId;
    private final int attempt;
    private final Requirement requirement;
    private final Map<String, Artifact> visibleArtifacts;
    private final Set<String> ancestors;
    private final Clock clock;
    private final BooleanSupplier cancelled;
    private final Set<ArtifactRef> read = new LinkedHashSet<>();

    public StageContext(String runId, String stageId, int attempt, Requirement requirement,
                        Map<String, Artifact> visibleArtifacts, Set<String> ancestors,
                        Clock clock, BooleanSupplier cancelled) {
        this.runId = runId;
        this.stageId = stageId;
        this.attempt = attempt;
        this.requirement = requirement;
        this.visibleArtifacts = Map.copyOf(visibleArtifacts);
        this.ancestors = Set.copyOf(ancestors);
        this.clock = clock;
        this.cancelled = cancelled;
    }

    public String runId() {
        return runId;
    }

    public String stageId() {
        return stageId;
    }

    public int attempt() {
        return attempt;
    }

    public Requirement requirement() {
        return requirement;
    }

    public Clock clock() {
        return clock;
    }

    /** True once a safe-stop was requested; long-running agents should return early. */
    public boolean isCancelled() {
        return cancelled.getAsBoolean();
    }

    public boolean hasArtifact(String upstreamStageId) {
        return ancestors.contains(upstreamStageId) && visibleArtifacts.containsKey(upstreamStageId);
    }

    /**
     * @throws ContextAccessException if {@code upstreamStageId} is not an ancestor of this stage
     */
    public synchronized Artifact artifact(String upstreamStageId) {
        if (!ancestors.contains(upstreamStageId)) {
            throw new ContextAccessException("Stage '" + stageId + "' may only read artifacts of its ancestors "
                    + ancestors + ", not '" + upstreamStageId + "'");
        }
        Artifact artifact = visibleArtifacts.get(upstreamStageId);
        if (artifact == null) {
            throw new ContextAccessException("Ancestor '" + upstreamStageId + "' has produced no artifact yet");
        }
        read.add(artifact.ref());
        return artifact;
    }

    /** Artifacts read so far, in first-read order. */
    public synchronized List<ArtifactRef> artifactsRead() {
        return new ArrayList<>(read);
    }
}
