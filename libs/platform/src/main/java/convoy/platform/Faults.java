package convoy.platform;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named crash points for fault-injection tests (messaging-failure-tests.md). A test arms a point; the next
 * hit throws {@link SimulatedCrash} once, as if the process died there. Unarmed hits cost one set lookup.
 */
public final class Faults {
    private static final Set<String> ARMED = ConcurrentHashMap.newKeySet();
    /** Points that fail on every hit until disarmed (a deterministic poison message, MF-5). */
    private static final Set<String> STUCK = ConcurrentHashMap.newKeySet();

    private Faults() {}

    public static void arm(String point) { ARMED.add(point); }

    public static void armUntilDisarmed(String point) { STUCK.add(point); }

    public static void disarm(String point) { STUCK.remove(point); }

    public static void disarmAll() {
        ARMED.clear();
        STUCK.clear();
    }

    public static void hit(String point) {
        if (ARMED.remove(point) || STUCK.contains(point)) throw new SimulatedCrash(point);
    }

    public static final class SimulatedCrash extends RuntimeException {
        public SimulatedCrash(String point) { super("simulated crash at " + point); }
    }
}
