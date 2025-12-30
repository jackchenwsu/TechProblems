# TechProblems

A collection of solutions to interesting technical problems, focusing on systems programming, concurrency, and algorithmic challenges.

## Projects

### 1. [DurableLogWriter](./DurableLogWriter/)

A high-performance, thread-safe, durable log writer implementation in Java.

**Problem**: Build a library that writes logs to disk where thousands of threads write concurrently, data must survive crashes, and performance must remain high despite slow `fsync()` operations.

**Key Challenges Solved**:
- **Durability**: `push()` blocks until data is physically on disk (fsync)
- **Concurrency**: Lock-free queue handles thousands of concurrent writers
- **Ordering**: Same-thread writes maintain order via sequence numbers
- **Performance**: Group commit batches multiple writes per fsync
- **Crash Recovery**: CRC validation and file truncation on recovery

**Technologies**: Java, NIO FileChannel, ConcurrentLinkedQueue, CompletableFuture

```java
try (DataWriter writer = new DataWriter("/path/to/log.dat")) {
    writer.push("Hello, World!".getBytes());  // Blocks until durable
}
```

---

### 2. [FireWallCIDRRules](./FireWallCIDRRules/)

A firewall rule evaluator that checks IP addresses against CIDR-based rules.

**Problem**: Given a list of firewall rules (ALLOW/DENY with CIDR blocks) and a target IP, determine if the IP should be allowed or denied using first-match-wins semantics.

**Key Challenges Solved**:
- **CIDR Matching**: Bitwise operations to check if IP falls within a CIDR range
- **IP Parsing**: Convert dotted-decimal to 32-bit numbers for fast comparison
- **Validation**: Strict validation of IPs, CIDRs, and actions
- **Default Deny**: If no rule matches, deny by default

**Technologies**: Java, Bitwise Operations, CRC32

```java
List<FirewallRule> rules = Arrays.asList(
    new FirewallRule("ALLOW", "192.168.1.0/24"),
    new FirewallRule("DENY", "0.0.0.0/0")
);

FirewallEvaluator evaluator = new FirewallEvaluator();
evaluator.evaluate(rules, "192.168.1.50");  // Returns "ALLOW"
evaluator.evaluate(rules, "8.8.8.8");       // Returns "DENY"
```

---

## Repository Structure

```
TechProblems/
├── README.md                 # This file
├── .gitignore
├── .github/
│   └── workflows/            # CI/CD workflows
├── DurableLogWriter/
│   ├── README.md             # Detailed documentation
│   ├── DataWriter.java       # Core durable log writer
│   ├── LogRecovery.java      # Crash recovery utility
│   ├── WriteRequest.java     # Write request model
│   ├── WriterConfig.java     # Configuration builder
│   └── DataWriterTest.java   # Test suite
└── FireWallCIDRRules/
    ├── README.md             # Detailed documentation
    └── src/
        └── main/java/com/firewall/
            ├── FirewallEvaluator.java  # Rule evaluation logic
            ├── FirewallRule.java       # Rule model
            └── Main.java               # Demo entry point
```

## Topics Covered

| Topic | Project |
|-------|---------|
| Thread Safety | DurableLogWriter |
| Durability / fsync | DurableLogWriter |
| Group Commit | DurableLogWriter |
| Crash Recovery | DurableLogWriter |
| Lock-free Data Structures | DurableLogWriter |
| CIDR / Subnetting | FireWallCIDRRules |
| Bitwise Operations | FireWallCIDRRules |
| IP Address Validation | FireWallCIDRRules |

## License

This repository is for educational and practice purposes.
