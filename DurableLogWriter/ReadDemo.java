import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Demo program to read and display contents of a DataWriter log file.
 */
public class ReadDemo {

    public static void main(String[] args) throws Exception {
        String filePath = args.length > 0 ? args[0] : "/tmp/datawriter_demo.log";

        System.out.println("=== Reading Log File: " + filePath + " ===\n");

        // Recovery check
        LogRecovery.RecoveryResult recovery = LogRecovery.recover(filePath);
        System.out.println("Recovery Result: " + recovery);
        System.out.println();

        // Read all records
        List<LogRecovery.Record> records = LogRecovery.readAll(filePath);
        System.out.println("Total records: " + records.size());

        // Count records per thread
        Map<Long, Integer> threadCounts = new HashMap<>();
        for (LogRecovery.Record r : records) {
            threadCounts.merge(r.threadId, 1, Integer::sum);
        }

        System.out.println("\nRecords per thread:");
        for (Map.Entry<Long, Integer> entry : threadCounts.entrySet()) {
            System.out.printf("  Thread %d: %d records%n", entry.getKey(), entry.getValue());
        }

        // Show first 20 records
        System.out.println("\n--- First 20 records ---");
        for (int i = 0; i < Math.min(20, records.size()); i++) {
            LogRecovery.Record r = records.get(i);
            System.out.printf("[%4d] tid=%-3d size=%-4d data='%s'%n",
                i, r.threadId, r.data.length, r.getDataAsString());
        }

        // Show last 10 records
        if (records.size() > 20) {
            System.out.println("\n--- Last 10 records ---");
            for (int i = Math.max(0, records.size() - 10); i < records.size(); i++) {
                LogRecovery.Record r = records.get(i);
                System.out.printf("[%4d] tid=%-3d size=%-4d data='%s'%n",
                    i, r.threadId, r.data.length, r.getDataAsString());
            }
        }

        // Verify ordering per thread
        System.out.println("\n--- Ordering Verification ---");
        Map<Long, Integer> lastSeenSeq = new HashMap<>();
        boolean orderingValid = true;

        for (LogRecovery.Record r : records) {
            String data = r.getDataAsString();
            // Extract sequence number from "Thread-X-Write-Y" format
            if (data.contains("-Write-")) {
                try {
                    int seq = Integer.parseInt(data.split("-Write-")[1]);
                    int lastSeq = lastSeenSeq.getOrDefault(r.threadId, -1);
                    if (seq <= lastSeq) {
                        System.out.printf("  ORDER VIOLATION: tid=%d, expected > %d, got %d%n",
                            r.threadId, lastSeq, seq);
                        orderingValid = false;
                    }
                    lastSeenSeq.put(r.threadId, seq);
                } catch (NumberFormatException e) {
                    // Skip records that don't match the format
                }
            }
        }

        if (orderingValid) {
            System.out.println("  All per-thread orderings are VALID");
        }
    }
}
