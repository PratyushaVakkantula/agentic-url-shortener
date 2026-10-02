package com.agentic.orchestration.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * COMPLIANCE: personal data in generated artifacts (fixtures, logs, docs) needs a human decision.
 * Card numbers are Luhn-checked to avoid flagging every long number (ids, timestamps).
 */
@Component
public class PersonalDataPolicy implements Policy {

    private static final Pattern US_SSN = Pattern.compile("\\b(?!000|666|9\\d\\d)\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}\\b");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");

    @Override
    public String name() {
        return "personal-data";
    }

    @Override
    public PolicyCategory category() {
        return PolicyCategory.COMPLIANCE;
    }

    @Override
    public PolicyDecision evaluate(PolicyInput input) {
        List<String> findings = new ArrayList<>();
        if (US_SSN.matcher(input.outputText()).find()) {
            findings.add("US social security number");
        }
        Matcher card = CARD.matcher(input.outputText());
        while (card.find()) {
            if (luhnValid(card.group().replaceAll("[ -]", ""))) {
                findings.add("payment card number");
                break;
            }
        }
        if (findings.isEmpty()) {
            return PolicyDecision.allow(this);
        }
        return new PolicyDecision(name(), category(), PolicyOutcome.REQUIRE_APPROVAL,
                "output appears to contain " + String.join(", ", findings) + "; confirm it is synthetic test data");
    }

    static boolean luhnValid(String digits) {
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
