package com.agentic.orchestration.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.orchestration.model.Requirement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class PoliciesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static PolicyInput input(Object output) {
        var node = MAPPER.valueToTree(output);
        return new PolicyInput("wf", "stage", new Requirement("t", "d"), node, MAPPER.writeValueAsString(node));
    }

    @Nested
    class SecretLeak {

        private final SecretLeakPolicy policy = new SecretLeakPolicy();

        @ParameterizedTest
        @ValueSource(strings = {
                "aws_access_key_id = AKIAIOSFODNN7EXAMPLE",
                "-----BEGIN RSA PRIVATE KEY-----\\nMIIE...",
                "token: ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789",
                "spring.datasource.password=SuperS3cretValue!",
                "api_key: 'k3y-abcdef-123456'"
        })
        void blocksCredentials(String content) {
            PolicyDecision d = policy.evaluate(input(Map.of("file", content)));
            assertThat(d.outcome()).isEqualTo(PolicyOutcome.BLOCK);
        }

        @Test
        void neverEchoesTheSecretIntoTheAuditReason() {
            PolicyDecision d = policy.evaluate(input(Map.of("file", "password=SuperS3cretValue!")));
            assertThat(d.reason()).contains("hardcoded-credential").doesNotContain("SuperS3cretValue");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "spring.datasource.password=${DB_PASSWORD}",
                "password: <set-me>",
                "password = ****",
                "Rotate the API key every 90 days."
        })
        void allowsPlaceholdersAndProse(String content) {
            assertThat(policy.evaluate(input(Map.of("file", content))).outcome()).isEqualTo(PolicyOutcome.ALLOW);
        }
    }

    @Nested
    class PersonalData {

        private final PersonalDataPolicy policy = new PersonalDataPolicy();

        @Test
        void flagsSsnAndLuhnValidCardNumbers() {
            assertThat(policy.evaluate(input(Map.of("fixture", "ssn 123-45-6789"))).outcome())
                    .isEqualTo(PolicyOutcome.REQUIRE_APPROVAL);
            assertThat(policy.evaluate(input(Map.of("fixture", "card 4111 1111 1111 1111"))).outcome())
                    .isEqualTo(PolicyOutcome.REQUIRE_APPROVAL);
        }

        @Test
        void ignoresLongNumbersThatFailLuhnAndInvalidSsnRanges() {
            assertThat(policy.evaluate(input(Map.of("id", "order 4111111111111112, ssn 000-12-3456"))).outcome())
                    .isEqualTo(PolicyOutcome.ALLOW);
        }

        @Test
        void luhn() {
            assertThat(PersonalDataPolicy.luhnValid("4111111111111111")).isTrue();
            assertThat(PersonalDataPolicy.luhnValid("4111111111111112")).isFalse();
        }
    }

    @Nested
    class ChangeControl {

        private final ChangeControlPolicy policy = new ChangeControlPolicy(3, 100);

        private static Map<String, Object> change(String path, String op, int lines) {
            return Map.of("path", path, "operation", op, "linesChanged", lines);
        }

        @Test
        void blocksEditingAnAppliedMigration() {
            PolicyDecision d = policy.evaluate(input(Map.of("changes",
                    List.of(change("src/main/resources/db/migration/V1__init.sql", "MODIFY", 3)))));
            assertThat(d.outcome()).isEqualTo(PolicyOutcome.BLOCK);
            assertThat(d.reason()).contains("MODIFY of applied migration");
        }

        @Test
        void newMigrationOrSecurityChangeNeedsApproval() {
            assertThat(policy.evaluate(input(Map.of("changes",
                    List.of(change("src/main/resources/db/migration/V5__add_limit.sql", "ADD", 5))))).outcome())
                    .isEqualTo(PolicyOutcome.REQUIRE_APPROVAL);
            assertThat(policy.evaluate(input(Map.of("changes",
                    List.of(change("src/main/java/com/x/platform/security/SecurityConfig.java", "MODIFY", 2))))).reason())
                    .contains("security configuration");
        }

        @Test
        void largeBlastRadiusNeedsApproval() {
            PolicyDecision d = policy.evaluate(input(Map.of("changes", List.of(
                    change("a.java", "MODIFY", 40), change("b.java", "MODIFY", 40),
                    change("c.java", "MODIFY", 40), change("d.java", "ADD", 40)))));
            assertThat(d.outcome()).isEqualTo(PolicyOutcome.REQUIRE_APPROVAL);
            assertThat(d.reason()).contains("4 files changed (limit 3)").contains("160 lines changed (limit 100)");
        }

        @Test
        void smallOrdinaryChangeIsAllowedAndMissingChangesetIsIgnored() {
            assertThat(policy.evaluate(input(Map.of("changes", List.of(change("src/Foo.java", "MODIFY", 10))))).outcome())
                    .isEqualTo(PolicyOutcome.ALLOW);
            assertThat(policy.evaluate(input(Map.of("summary", "no code"))).outcome()).isEqualTo(PolicyOutcome.ALLOW);
        }
    }

    @Nested
    class DependencyLicense {

        private final DependencyLicensePolicy policy = new DependencyLicensePolicy();

        private static Map<String, Object> dep(String name, String license) {
            return Map.of("name", name, "version", "1.0", "license", license);
        }

        @Test
        void blocksDeniedLicensesAndEscalatesUnknownOnes() {
            assertThat(policy.evaluate(input(Map.of("dependencies", List.of(dep("lib", "AGPL-3.0"))))).outcome())
                    .isEqualTo(PolicyOutcome.BLOCK);
            assertThat(policy.evaluate(input(Map.of("dependencies", List.of(dep("lib", "WTFPL"))))).outcome())
                    .isEqualTo(PolicyOutcome.REQUIRE_APPROVAL);
            assertThat(policy.evaluate(input(Map.of("dependencies", List.of(dep("caffeine", "Apache-2.0"))))).outcome())
                    .isEqualTo(PolicyOutcome.ALLOW);
        }
    }

    @Nested
    class Engine {

        @Test
        void aThrowingPolicyFailsSafeToHumanReviewAndOthersStillRun() {
            Policy broken = new Policy() {
                public String name() {
                    return "broken";
                }

                public PolicyCategory category() {
                    return PolicyCategory.SECURITY;
                }

                public PolicyDecision evaluate(PolicyInput in) {
                    throw new IllegalStateException("bug");
                }
            };
            PolicyEngine engine = new PolicyEngine(List.of(broken, new SecretLeakPolicy()));

            List<PolicyDecision> decisions = engine.evaluate(input(Map.of("ok", true)));

            assertThat(decisions).hasSize(2);
            assertThat(decisions.getFirst().outcome()).isEqualTo(PolicyOutcome.REQUIRE_APPROVAL);
            assertThat(PolicyEngine.mostSevere(decisions)).isEqualTo(PolicyOutcome.REQUIRE_APPROVAL);
        }
    }
}
