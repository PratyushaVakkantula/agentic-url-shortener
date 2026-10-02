package com.agentic.orchestration.governance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * SECURITY: blocks outputs that contain credentials (e.g. a generated config with a real key).
 * Reports <i>which rule</i> matched and never the value: echoing the secret into the audit log
 * would turn one leak into a permanent second one.
 */
@Component
public class SecretLeakPolicy implements Policy {

    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    static {
        RULES.put("aws-access-key-id", Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"));
        RULES.put("private-key", Pattern.compile("-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"));
        RULES.put("github-token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"));
        RULES.put("slack-token", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"));
        // key = "value" assignments; placeholders like ${DB_PASSWORD}, <secret> or *** are allowed.
        RULES.put("hardcoded-credential", Pattern.compile(
                "(?i)\\b(password|passwd|secret|api[_-]?key|access[_-]?token)\\b\\s*[:=]\\s*[\"']?(?![$<*{])[^\\s\"',}]{8,}"));
    }

    @Override
    public String name() {
        return "secret-leak";
    }

    @Override
    public PolicyCategory category() {
        return PolicyCategory.SECURITY;
    }

    @Override
    public PolicyDecision evaluate(PolicyInput input) {
        List<String> hits = RULES.entrySet().stream()
                .filter(rule -> rule.getValue().matcher(input.outputText()).find())
                .map(Map.Entry::getKey)
                .toList();
        if (hits.isEmpty()) {
            return PolicyDecision.allow(this);
        }
        return new PolicyDecision(name(), category(), PolicyOutcome.BLOCK,
                "possible credential(s) in output, matched rule(s) " + hits + "; value redacted");
    }
}
