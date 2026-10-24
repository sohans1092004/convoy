package convoy.platform.statemachine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One state machine from spec-tools/vectors/transitions.json (generated from docs/rules/state-machines).
 * The full table is loaded; a service lists the transition ids it implements in P1. {@link #check}
 * rejects a (from, to) pair not in the table with invalid-transition, and a valid transition that is not
 * built yet with 501 not-implemented-in-p1, so the gaps stay visible.
 */
public final class TransitionTable {
    private final String machine;
    /** Several transitions may share a (from, to) pair with different triggers (e.g. QUO-T09 and QUO-T10). */
    private final Map<String, Set<String>> pairToIds = new HashMap<>();
    private final Set<String> allIds = new HashSet<>();
    private final Set<String> terminal = new HashSet<>();
    private final Set<String> implemented;

    private TransitionTable(String machine, JsonNode m, Set<String> implemented) {
        this.machine = machine;
        this.implemented = Set.copyOf(implemented);
        m.path("valid").forEach(t -> {
            pairToIds.computeIfAbsent(t.path("from").asText() + "->" + t.path("to").asText(), k -> new HashSet<>()).add(t.path("id").asText());
            allIds.add(t.path("id").asText());
        });
        m.path("terminal").forEach(s -> terminal.add(s.asText()));
        for (String id : implemented) {
            if (!allIds.contains(id)) throw new IllegalArgumentException(machine + ": unknown transition id " + id);
        }
    }

    /** Load machine {@code name} from the classpath copy of transitions.json. */
    public static TransitionTable load(String name, Set<String> implemented) {
        try (InputStream in = TransitionTable.class.getResourceAsStream("/convoy/transitions.json")) {
            JsonNode all = new ObjectMapper().readTree(in);
            if (!all.has(name)) throw new IllegalArgumentException("no machine " + name);
            return new TransitionTable(name, all.get(name), implemented);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Transition ids for the pair (empty if the pair is not in the table). */
    public Set<String> idsFor(String from, String to) { return pairToIds.getOrDefault(from + "->" + to, Set.of()); }

    public boolean isTerminal(String state) { return terminal.contains(state); }

    public boolean isImplemented(String id) { return implemented.contains(id); }

    /** Returns an implemented transition id for the pair, or throws invalid-transition / not-implemented-in-p1. */
    public String check(String from, String to) {
        Set<String> ids = idsFor(from, to);
        if (ids.isEmpty()) {
            throw ApiProblem.of(ProblemType.INVALID_TRANSITION, machine + " cannot go from " + from + " to " + to + ".")
                    .with("machine", machine).with("from", from).with("to", to);
        }
        for (String id : ids) if (implemented.contains(id)) return id;
        String id = ids.stream().sorted().findFirst().orElseThrow();
        throw ApiProblem.of(ProblemType.NOT_IMPLEMENTED_IN_P1, id + " (" + from + " -> " + to + ") is specified but not built in Phase 1.")
                .with("machine", machine).with("transition", id);
    }

    public String machine() { return machine; }

    public Set<String> implemented() { return implemented; }
}
