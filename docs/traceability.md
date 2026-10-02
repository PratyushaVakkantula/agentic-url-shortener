# Traceability: requirement → implementation → proof

Every requirement from [requirements.md](requirements.md) mapped to where it is implemented and
the test that proves it. Test names are `Class.method`; all run in CI (`./mvnw verify`).

Rules marked **🧬** were additionally **mutation-checked**: the rule was deliberately removed from
the code and the named test was confirmed to fail, so the test protects the rule rather than just
passing beside it.

## URL shortener: functional

| ID | Requirement | Implementation | Verified by |
|---|---|---|---|
| FR-1 | Create link (optional alias, expiry) | `ShortLinkController`, `ShortLinkService.create` | `ShortenerApiIntegrationTest.createsLinkWithGeneratedCode`, `…createsLinkWithAliasAndExpiry`, `ShortLinkServiceIntegrationTest.createsAndPersistsLinkWithGeneratedCode` |
| FR-2 | Redirect with 302 | `RedirectController`, `RedirectService` | `ShortenerApiIntegrationTest.redirectsWith302AndNoStore` |
| FR-3 | 404 unknown, 410 expired or deactivated | `ShortenerExceptionHandler`, `ShortLink.statusAt` | `…unknownCodeIs404Problem`, `…cachedLinkStillStopsRedirectingTheMomentItExpires`, `ShortLinkTest.expiresExactlyAtExpiresAt` |
| FR-4 | Link metadata | `GET /api/v1/urls/{code}` | `…deactivationEvictsCacheAndIsIdempotent` (reads metadata) |
| FR-5 | Analytics: total, per day, referrers, browsers | `AnalyticsService`, `ClickEventRepository` | `…aggregatesClicksPerDayReferrerAndBrowser`, `…bucketsDaysInUtcRegardlessOfServerTimeZone` |
| FR-6 | Soft-delete (deactivate) | `ShortLinkService.deactivate` | `…deactivationEvictsCacheAndIsIdempotent`, `…requiresAdmin` |
| FR-7 | Every redirect records a click | `ClickRecorder`, `ClickBatchWriter` | `…aggregatesClicksPerDayReferrerAndBrowser`, `ClickRecorderTest.*` |

## URL shortener: non-functional

| ID | Requirement | Implementation | Verified by |
|---|---|---|---|
| NFR-1 | Scheme allow-list; block private, loopback and link-local hosts (incl. alternate IP spellings) | `UrlSafetyValidator` ([ADR-0003](adr/0003-url-safety-without-dns.md)) | `UrlSafetyValidatorTest` (55 cases) 🧬 |
| NFR-2 | Unpredictable codes | `RandomShortCodeGenerator` ([ADR-0002](adr/0002-random-short-codes.md)) | `RandomShortCodeGeneratorTest.usesTheWholeAlphabetRoughlyUniformly`, `…codesDoNotRepeatInPractice` |
| NFR-3 | Bounded collision retries; atomic click counter | `ShortLinkService`, `ShortLinkRepository.incrementClicks` | `ShortLinkServiceIntegrationTest.retriesOnCodeCollisionAndUsesNextFreeCode`, `…failsLoudlyWhenRetryBudgetIsExhausted`, `…concurrentClaimsOfSameAliasProduceExactlyOneWinner` |
| NFR-4 | Per-client rate limit, 429 + Retry-After | `RateLimitFilter`, `TokenBucket` | `RateLimitIntegrationTest.*`, `TokenBucketTest.*` |
| NFR-5 | One error shape (RFC 9457 + errorCode + requestId) | `ApiProblem`, `GlobalExceptionHandler`, `ProblemDetailsAuthHandler` | `…missingUrlListsFieldErrors`, `…malformedJsonDoesNotEchoParserDetails`, `PlatformSecurityIntegrationTest.unauthenticatedRequestToProtectedRouteGets401ProblemDetail` |
| NFR-6 | No raw IPs or full referrers stored | `ClickContext`, `click_event` schema | `ClickContextTest.referrerKeepsOnlyTheHost` |
| NFR-7 | Health and metrics | Actuator, `MeteredRunEventStore` | `PlatformSecurityIntegrationTest.healthIsPublic`, `…adminCanReadMetrics` |
| NFR-8 | Flyway owns the schema; portable SQL; durable commits | `db/migration/V1–V4`, `ddl-auto: validate`, `WRITE_DELAY=0` ([ADR-0010](adr/0010-durability-of-committed-events.md)) | `ApplicationContextTest.contextLoadsWithMigrationsAppliedAndMappingsValidated`, `MigrationTest.v3RecomputesClickDaysThatV2BackfilledInTheSessionTimeZone`, `DurabilityTest.committedRowsSurviveSigkillWithTheConfiguredDatabaseOptions` 🧬 |
| NFR-9 | Redirect cache that never extends a link's life | `RedirectCache`, `LinkSnapshot` ([ADR-0004](adr/0004-redirect-hot-path.md)) | `…cachedLinkStillStopsRedirectingTheMomentItExpires`, `…deactivationEvictsCacheAndIsIdempotent` |
| NFR-10 | Async clicks, bounded queue, visible drops | `ClickRecorder` | `ClickRecorderTest.dropsAndCountsClicksWhenBufferIsFullWithoutBlocking`, `…stopDrainsEverythingThatWasQueued`, `…idleWorkerDoesNotStarveFlush` 🧬 |
| NFR-11 | Role-based access (REQUESTER, APPROVER, ADMIN) | `SecurityConfig`, `@PreAuthorize` | `PlatformSecurityIntegrationTest.*`, `WorkflowApiIntegrationTest.onlyRequestersMayStartRuns`, `…safeStopIsAdminOnly` |
| NFR-12 | OpenAPI contract | springdoc, `@Operation` | `PlatformSecurityIntegrationTest.openApiSpecIsPublic` |

## Orchestrator

| ID | Brief clause | Implementation | Verified by |
|---|---|---|---|
| OR-1 | Explicit dependency graph | `WorkflowDefinition` (validated DAG) | `WorkflowDefinitionTest.rejectsCycleAndNamesThePath`, `…computesDeterministicTopologicalOrder` |
| OR-2 | Entry/exit gates | `Gate`, `Gates`, `RunCoordinator.dispatch` | `WorkflowEngineTest.failedEntryGateFailsStageWithoutRunningItAndSkipsDownstream`, `…failedExitGateRejectsOutputButKeepsItForAudit`, `…gatesFailClosedWhenTheyThrow` |
| OR-3 | Sequential and parallel paths with synchronization | `RunCoordinator.schedule` (fan-in join) | `WorkflowEngineTest.parallelBranchesRunConcurrentlyAndJoinWaitsForBoth` 🧬, `ScenarioIntegrationTest.greenfield_…` |
| OR-4 | Cross-stage context and decision lineage | `StageContext` (ancestor-only, observed reads), `Decision.basedOn` | `WorkflowEngineTest.decisionLineageListsExactlyTheArtifactVersionsTheAgentRead`, `…agentMayNotReadArtifactsOutsideItsAncestors` |
| OR-5 | Human approval for high-impact actions | `StagePolicy.requiresApproval`, policy `REQUIRE_APPROVAL` | `GovernanceTest.highImpactStageWaitsForAHumanAndDownstreamWaitsWithIt`, `…policyFindingRoutesOutputToHumanApproval` |
| OR-6 | Bounded retries, fallback | `RetryPolicy`, `RunCoordinator.failAttempt` | `GovernanceTest.retriesWithExponentialBackoffUntilSuccess`, `…fallbackAgentTakesOverOnceRetriesAreExhausted`, `…failingFallbackFailsTheStageWithoutFurtherAttempts` |
| OR-7 | Rollback | `Compensation`, `RollbackStarted`, reverse completion order | `GovernanceTest.rejectionRollsBackCompletedStagesInReverseOrder` 🧬, `…failedCompensationIsSurfacedForManualCleanup` |
| OR-8 | Safe-stop | `StopRequested`, `StageContext.isCancelled` | `GovernanceTest.operatorSafeStopLetsCooperativeAgentsFinishAndWithdrawsOpenApprovals`, `…blockingPolicySafeStopsTheRunWithoutRollingBack` 🧬 |
| OR-9 | Policy guardrails: security, compliance, change control | `PolicyEngine`, `SecretLeakPolicy`, `PersonalDataPolicy`, `DependencyLicensePolicy`, `ChangeControlPolicy` | `PoliciesTest.*`, `PoliciesTest.Engine.aThrowingPolicyFailsSafeToHumanReviewAndOthersStillRun` |
| OR-10 | Audit-grade observability and traceability | `RunEvent` log, `/runs/{id}/events`, MDC run and request ids | `RecoveryIntegrationTest.everyEventTypeRoundTripsThroughTheDatabase`, `WorkflowEngineTest.agentThreadsCarryTheRunIdForLogCorrelation` |
| OR-11 | Reliability metrics (success rate, retry and rollback frequency, MTTR, latency) | `ReliabilityMetrics`, `MeteredRunEventStore` | `ReliabilityMetricsTest.computesEveryMetricExactlyFromTheLog`, `WorkflowApiIntegrationTest.reliabilityMetricsAreExposedAsReportAndAsLiveMeters` |
| OR-12 | Dynamic re-planning | Artifact provenance plus hashes, `StageInvalidated` ([ADR-0008](adr/0008-replanning-and-reliability-metrics.md)) | `ReplanningTest.revisionReRunsOnlyStagesThatConsumedTheChangedOutputAndVoidsTheirApproval` 🧬, `…reRunThatReproducesIdenticalOutputStopsTheCascade` 🧬, `…outputComputedFromAnInputRevisedMidFlightIsDiscardedAndRecomputed` 🧬, `…revisionsPassTheSameGuardrailsAsAgentOutput` 🧬, `ScenarioIntegrationTest.ambiguous_…` |
| OR-13 | Controlled autonomy: agents only propose | `Agent` contract; ArchUnit boundary | `ArchitectureTest.agentsCannotReachEngineStateOrEvents` |
| OR-14 | Approval bound to content; separation of duties; expiry | `RunCoordinator.onApprovalCommand` | `GovernanceTest.approvalOfADifferentArtifactVersionIsRefused` 🧬, `…initiatorCannotApproveTheirOwnRun` 🧬, `…unansweredApprovalExpiresAndFailsTheStage`, `WorkflowApiIntegrationTest.initiatorWithApproverRoleStillCannotSelfApprove` |
| OR-15 | Event-sourced, crash recovery | `JdbcRunEventStore`, `RunState.replay`, `resumeUnfinishedRuns` ([ADR-0006](adr/0006-durable-event-store-and-recovery.md)) | `WorkflowEngineTest.replayingTheEventLogReproducesTheLiveState`, `RecoveryIntegrationTest.runInterruptedMidStageIsResumedByTheNextProcess`, `…openApprovalSurvivesRestartAndCanBeDecidedOnTheNewProcess`, `DurabilityTest` 🧬 |
| OR-16 | Per-attempt timeouts; cooperative cancel | `AttemptTimedOut`, stale-outcome discard | `GovernanceTest.timedOutAttemptIsCancelledItsLateResultDiscardedAndTheRetrySucceeds` 🧬 |
| OR-17 | Brownfield codebase reasoning | `CodebaseIndex`, `ImpactAnalysisAgent` | `ImpactAnalysisAgentTest.*`, `ScenarioIntegrationTest.brownfield_staticAnalysisOfThisRepositoryDrivesDesignAndChangeControl` |

## Engineering standards

| Standard | Enforced by |
|---|---|
| Module boundaries and acyclicity | `ArchitectureTest.modulesAreFreeOfCycles`, `…platformDoesNotDependOnBusinessModules`, `…shortenerDoesNotDependOnOrchestration`, `…orchestrationDoesNotDependOnShortener`, `…orchestrationPackagesAreFreeOfCycles` |
| Persistence only via the service layer | `ArchitectureTest.repositoriesAreOnlyUsedByServices` |
| Time only via the injected `Clock` | `ArchitectureTest.timeComesFromInjectedClock` |
| No field injection, java.util.logging or System.out | `ArchitectureTest.noFieldInjection`, `…noJavaUtilLogging`, `…noStandardStreams` |
| Tests catch local-time assumptions | Surefire runs with `-Duser.timezone=America/New_York` |
| CI on every push | `.github/workflows/ci.yml` |
