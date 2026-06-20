package org.example.miniwsa.event;

/**
 * Attack category carried on the rule (assignment domain model). An unknown value is rejected
 * by Jackson at deserialization — that's how "valid enum value" is enforced (spec §8).
 */
public enum Category {
    INJECTION         ("WAF-001", "SQL Injection",        "SQL injection payload in request parameter"),
    XSS               ("WAF-002", "XSS Attempt",          "Cross-site scripting payload detected"),
    PROTOCOL_VIOLATION("WAF-006", "Protocol Violation",   "Malformed HTTP request detected"),
    DATA_LEAKAGE      ("WAF-007", "Data Leakage Attempt", "Possible sensitive data exfiltration"),
    BOT               ("WAF-003", "Bot Traffic",          "Automated scanner or bot detected"),
    DOS               ("WAF-004", "DoS Attack Pattern",   "High-rate request pattern detected"),
    RATE_LIMIT        ("WAF-005", "Rate Limit Exceeded",  "Client exceeded allowed request rate");

    public final String ruleId;
    public final String ruleName;
    public final String ruleMessage;

    Category(String ruleId, String ruleName, String ruleMessage) {
        this.ruleId      = ruleId;
        this.ruleName    = ruleName;
        this.ruleMessage = ruleMessage;
    }
}
