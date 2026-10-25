package convoy.pool;

import convoy.contracts.events.AcceptancesFrozen;
import convoy.contracts.events.DecisionOpened;
import convoy.contracts.events.FinalRoundOpened;
import convoy.contracts.events.QuoteRoundOpened;
import convoy.contracts.events.RoundClosed;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.Uuid7;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Deadline and system transitions (POOL-T02..T07, T11, T12; RND-T01/T02). The sweeper is the wake-up;
 * each transition re-reads the pool under lock and checks state, version and the DB clock itself
 * (deadline-rule.md). Sync calls to dealer/quote happen before the transaction; failures retry next tick.
 */
@Component
public class PoolSweeper {
    private static final Logger log = LoggerFactory.getLogger(PoolSweeper.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Outbox outbox;
    private final Pools pools;
    private final PoolApplication.Clients clients;
    private final PoolApplication.PoolSettings settings;

    public PoolSweeper(JdbcTemplate jdbc, TransactionTemplate tx, Outbox outbox, Pools pools, PoolApplication.Clients clients,
                       PoolApplication.PoolSettings settings, TaskScheduler scheduler) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.outbox = outbox;
        this.pools = pools;
        this.clients = clients;
        this.settings = settings;
        scheduler.scheduleWithFixedDelay(this::sweep, settings.sweeperInterval());
    }

    public void sweep() {
        try {
            for (UUID id : jdbc.queryForList("select id from pools where state = 'FORMING' and forming_deadline <= clock_timestamp()", UUID.class)) guarded(id, this::expireForming);
            for (UUID id : jdbc.queryForList("select id from pools where state = 'READY_FOR_QUOTES'", UUID.class)) guarded(id, this::openSealedRound);
            for (UUID id : jdbc.queryForList("select id from pools where state = 'READY_FOR_QUOTES' and dealer_wait_deadline <= clock_timestamp()", UUID.class)) guarded(id, this::noEligibleDealer);
            for (UUID id : jdbc.queryForList("select p.id from pools p join rounds r on r.pool_id = p.id and r.round_set = p.round_set and r.round_no = 1 "
                    + "where p.state = 'QUOTE_OPEN' and r.state = 'OPEN' and r.deadline <= clock_timestamp()", UUID.class)) guarded(id, this::closeSealedRound);
            for (UUID id : jdbc.queryForList("select p.id from pools p join rounds r on r.pool_id = p.id and r.round_set = p.round_set and r.round_no = 2 "
                    + "where p.state = 'FINAL_ROUND' and r.state = 'OPEN' and r.deadline <= clock_timestamp()", UUID.class)) guarded(id, this::closeFinalRound);
            for (UUID id : jdbc.queryForList("select id from pools where state = 'DECISION_OPEN' and decision_deadline <= clock_timestamp()", UUID.class)) guarded(id, this::freezeAcceptances);
            for (UUID id : jdbc.queryForList("select id from memberships where state = 'REROUND_CHOICE' and choice_deadline <= clock_timestamp()", UUID.class)) guarded(id, this::reroundNoResponse);
        } catch (RuntimeException e) {
            log.warn("sweep failed: {}", e.toString());
        }
    }

    private void guarded(UUID poolId, java.util.function.Consumer<UUID> step) {
        try {
            step.accept(poolId);
        } catch (RuntimeException e) {
            log.warn("pool {} transition deferred: {}", poolId, e.toString());
        }
    }

    // POOL-T02 + RND-T01
    @SuppressWarnings("unchecked")
    void openSealedRound(UUID poolId) {
        Map<String, Object> p = jdbc.queryForMap("select brand, city from pools where id = ?", poolId);
        Map<String, Object> elig;
        try {
            elig = clients.dealer().get().uri(b -> b.path("/internal/eligibility").queryParam("brand", p.get("brand")).queryParam("city", p.get("city")).build())
                    .retrieve().body(Map.class);
        } catch (RestClientException e) {
            log.info("dealer eligibility unavailable for pool {}: retry next tick", poolId);
            return;
        }
        List<String> invited = ((List<Object>) elig.get("dealership_ids")).stream().map(String::valueOf).toList();
        if (invited.isEmpty()) return; // re-checked every tick until dealer_wait_deadline, then POOL-T12
        tx.executeWithoutResult(s -> {
            Map<String, Object> pool = pools.lockPool(poolId);
            if (!"READY_FOR_QUOTES".equals(pool.get("state"))) return;
            Pools.POOL.check("READY_FOR_QUOTES", "QUOTE_OPEN");
            Pools.ROUND.check("(none)", "OPEN");
            UUID roundId = Uuid7.next();
            int roundSet = (int) pool.get("round_set");
            Timestamp deadline = jdbc.queryForObject("insert into rounds(id, pool_id, round_set, round_no, kind, state, deadline) values (?, ?, ?, 1, 'SEALED', 'OPEN', "
                    + "clock_timestamp() + ?::interval) returning deadline", Timestamp.class, roundId, poolId, roundSet, Pools.interval(settings.durations().sealedRound()));
            for (String d : invited) jdbc.update("insert into round_invitations(round_id, dealership_id) values (?, ?)", roundId, UUID.fromString(d));
            long v = jdbc.queryForObject("update pools set state = 'QUOTE_OPEN', version = version + 1 where id = ? returning version", Long.class, poolId);
            outbox.append(poolId, v, new QuoteRoundOpened(poolId.toString(), roundId.toString(), (long) roundSet, 1L, "SEALED", deadline.toInstant(), invited,
                    variants(pool), ((Number) pool.get("committed_count")).longValue(), (String) pool.get("state_code"), Pools.variantFacts(pool)));
        });
    }

    // POOL-T03 + RND-T02 (round 1) + RND-T01 (round 2)
    void closeSealedRound(UUID poolId) {
        Map<String, Object> r1 = jdbc.queryForMap("select r.id, r.deadline, p.round_set from rounds r join pools p on p.id = r.pool_id "
                + "where r.pool_id = ? and r.round_set = p.round_set and r.round_no = 1", poolId);
        Map<String, Object> summary = summary((UUID) r1.get("id"));
        if (summary == null) return;
        long valid = ((Number) summary.get("valid_count")).longValue();
        tx.executeWithoutResult(s -> {
            Map<String, Object> pool = pools.lockPool(poolId);
            if (!"QUOTE_OPEN".equals(pool.get("state")) || Pools.before(pool, (Timestamp) r1.get("deadline"))) return;
            if (valid == 0) { // POOL-T04
                closeRound(poolId, r1, "SEALED", 0);
                closePool(poolId, "QUOTE_OPEN", "NO_QUOTES", "NO_VALID_QUOTES");
                return;
            }
            Pools.POOL.check("QUOTE_OPEN", "FINAL_ROUND");
            Pools.ROUND.check("OPEN", "CLOSED");
            Timestamp closedAt = jdbc.queryForObject("update rounds set state = 'CLOSED', closed_at = clock_timestamp(), version = version + 1 where id = ? returning closed_at",
                    Timestamp.class, r1.get("id"));
            UUID r2 = Uuid7.next();
            Timestamp deadline2 = jdbc.queryForObject("insert into rounds(id, pool_id, round_set, round_no, kind, state, deadline) values (?, ?, ?, 2, 'FINAL', 'OPEN', "
                    + "clock_timestamp() + ?::interval) returning deadline", Timestamp.class, r2, poolId, pool.get("round_set"), Pools.interval(settings.durations().finalRound()));
            List<String> invited = jdbc.queryForList("select dealership_id::text from round_invitations where round_id = ?", String.class, r1.get("id"));
            for (String d : invited) jdbc.update("insert into round_invitations(round_id, dealership_id) values (?, ?)", r2, UUID.fromString(d));
            long v = jdbc.queryForObject("update pools set state = 'FINAL_ROUND', version = version + 1 where id = ? returning version", Long.class, poolId);
            outbox.append(poolId, v, new RoundClosed(poolId.toString(), r1.get("id").toString(), "SEALED", ((Timestamp) r1.get("deadline")).toInstant(), closedAt.toInstant(), valid));
            long v2 = nextVersion(poolId); // one aggregate version per event, so version-ordered consumers never see a tie
            outbox.append(poolId, v2, new FinalRoundOpened(poolId.toString(), r2.toString(), ((Number) pool.get("round_set")).longValue(), 2L, "FINAL", deadline2.toInstant(),
                    invited, variants(pool), ((Number) pool.get("committed_count")).longValue(), (String) pool.get("state_code"), Pools.variantFacts(pool)));
        });
    }

    // POOL-T05 + RND-T02 (round 2). The summary call after the deadline also finalises the quotes (QUO-T04, idempotent).
    @SuppressWarnings("unchecked")
    void closeFinalRound(UUID poolId) {
        Map<String, Object> r2 = jdbc.queryForMap("select r.id, r.deadline from rounds r join pools p on p.id = r.pool_id "
                + "where r.pool_id = ? and r.round_set = p.round_set and r.round_no = 2", poolId);
        Map<String, Object> summary = summary((UUID) r2.get("id"));
        if (summary == null) return;
        long finals = ((Number) summary.get("final_count")).longValue();
        List<Map<String, Object>> quotes = (List<Map<String, Object>>) summary.get("quotes");
        tx.executeWithoutResult(s -> {
            Map<String, Object> pool = pools.lockPool(poolId);
            if (!"FINAL_ROUND".equals(pool.get("state")) || Pools.before(pool, (Timestamp) r2.get("deadline"))) return;
            if (finals == 0) { // POOL-T06
                closeRound(poolId, r2, "FINAL", 0);
                closePool(poolId, "FINAL_ROUND", "NO_QUOTES", "ALL_WITHDRAWN");
                return;
            }
            Pools.POOL.check("FINAL_ROUND", "DECISION_OPEN");
            Pools.ROUND.check("OPEN", "CLOSED");
            Timestamp closedAt = jdbc.queryForObject("update rounds set state = 'CLOSED', closed_at = clock_timestamp(), version = version + 1 where id = ? returning closed_at",
                    Timestamp.class, r2.get("id"));
            List<String> finalIds = new ArrayList<>();
            for (Map<String, Object> q : quotes) {
                if (!"FINAL".equals(q.get("state"))) continue;
                finalIds.add((String) q.get("quote_id"));
                jdbc.update("insert into quote_replica(quote_id, pool_id, dealership_id, state, variant_ids) values (?, ?, ?, 'FINAL', ?::uuid[]) "
                                + "on conflict (quote_id) do update set state = 'FINAL', variant_ids = excluded.variant_ids",
                        UUID.fromString((String) q.get("quote_id")), poolId, UUID.fromString((String) q.get("dealership_id")),
                        "{" + String.join(",", (List<String>) q.get("variant_ids")) + "}");
            }
            Timestamp decision = jdbc.queryForObject("update pools set state = 'DECISION_OPEN', decision_deadline = clock_timestamp() + ?::interval, version = version + 1 "
                    + "where id = ? returning decision_deadline", Timestamp.class, Pools.interval(settings.durations().decision()), poolId);
            long v = jdbc.queryForObject("select version from pools where id = ?", Long.class, poolId);
            outbox.append(poolId, v, new RoundClosed(poolId.toString(), r2.get("id").toString(), "FINAL", ((Timestamp) r2.get("deadline")).toInstant(), closedAt.toInstant(), finals));
            outbox.append(poolId, nextVersion(poolId), new DecisionOpened(poolId.toString(), decision.toInstant(), finalIds));
        });
    }

    // POOL-T07 with MEM-T10 (no acceptance -> RELEASED) and MEM-T11 (ACCEPTED -> HANDED_TO_ORDER) in one transaction
    void freezeAcceptances(UUID poolId) {
        tx.executeWithoutResult(s -> {
            Map<String, Object> pool = pools.lockPool(poolId);
            if (!"DECISION_OPEN".equals(pool.get("state")) || Pools.before(pool, (Timestamp) pool.get("decision_deadline"))) return;
            Pools.POOL.check("DECISION_OPEN", "CONFIRMATION");
            List<Map<String, Object>> released = new ArrayList<>(), acceptances = new ArrayList<>();
            for (Map<String, Object> m : jdbc.queryForList("select id, buyer_id, current_payment_id from memberships where pool_id = ? and state = 'COMMITTED' for update", poolId)) {
                Pools.MEMBERSHIP.check("COMMITTED", "RELEASED");
                released.add(Map.of("buyer_id", m.get("buyer_id").toString(), "membership_id", m.get("id").toString(),
                        "payment_id", String.valueOf(m.get("current_payment_id"))));
            }
            for (Map<String, Object> m : jdbc.queryForList("select id, buyer_id, accepted_quote_id, accepted_variant_id, current_payment_id from memberships "
                    + "where pool_id = ? and state = 'ACCEPTED' for update", poolId)) {
                Pools.MEMBERSHIP.check("ACCEPTED", "HANDED_TO_ORDER");
                Map<String, Object> a = new HashMap<>();
                a.put("buyer_id", m.get("buyer_id").toString());
                a.put("membership_id", m.get("id").toString());
                a.put("quote_id", m.get("accepted_quote_id").toString());
                a.put("variant_id", m.get("accepted_variant_id").toString());
                a.put("payment_id", String.valueOf(m.get("current_payment_id")));
                acceptances.add(a);
            }
            jdbc.update("update memberships set state = 'RELEASED', release_reason = 'NO_ACCEPTANCE', version = version + 1 where pool_id = ? and state = 'COMMITTED'", poolId);
            jdbc.update("update memberships set state = 'HANDED_TO_ORDER', version = version + 1 where pool_id = ? and state = 'ACCEPTED'", poolId);
            Timestamp conf = jdbc.queryForObject("update pools set state = 'CONFIRMATION', confirmation_deadline = clock_timestamp() + ?::interval, version = version + 1 "
                    + "where id = ? returning confirmation_deadline", Timestamp.class, Pools.interval(settings.durations().confirmation()), poolId);
            long v = jdbc.queryForObject("select version from pools where id = ?", Long.class, poolId);
            outbox.append(poolId, v, new AcceptancesFrozen(poolId.toString(), ((Number) pool.get("round_set")).longValue(), conf.toInstant(),
                    acceptances, pools.acceptanceCounts(poolId), released));
        });
    }

    // MEM-T15: no re-round choice by choice_deadline means refund (AMENDMENT-002).
    void reroundNoResponse(UUID membershipId) {
        tx.executeWithoutResult(s -> {
            Map<String, Object> m = jdbc.queryForMap("select * from memberships where id = ? for update", membershipId);
            if (!"REROUND_CHOICE".equals(m.get("state"))) return;
            pools.lockPool((UUID) m.get("pool_id"));
            Pools.MEMBERSHIP.check("REROUND_CHOICE", "RELEASED");
            pools.release((UUID) m.get("pool_id"), m, "REROUND_NO_RESPONSE");
        });
    }

    // POOL-T11: the minimum was not reached by the forming deadline.
    void expireForming(UUID poolId) {
        tx.executeWithoutResult(s -> {
            Map<String, Object> pool = pools.lockPool(poolId);
            if (!"FORMING".equals(pool.get("state")) || Pools.before(pool, (Timestamp) pool.get("forming_deadline"))) return;
            if ((int) pool.get("committed_count") >= (int) pool.get("min_members")) return; // POOL-T01 handles it
            closePool(poolId, "FORMING", "EXPIRED", "MINIMUM_NOT_REACHED");
        });
    }

    // POOL-T12: no eligible dealer by the dealer-wait deadline.
    void noEligibleDealer(UUID poolId) {
        tx.executeWithoutResult(s -> {
            Map<String, Object> pool = pools.lockPool(poolId);
            if (!"READY_FOR_QUOTES".equals(pool.get("state")) || Pools.before(pool, (Timestamp) pool.get("dealer_wait_deadline"))) return;
            closePool(poolId, "READY_FOR_QUOTES", "NO_QUOTES", "NO_ELIGIBLE_DEALER");
        });
    }

    /** RND-T02 for a round that closes without moving on (POOL-T04 / T06). */
    private void closeRound(UUID poolId, Map<String, Object> round, String kind, long validCount) {
        Pools.ROUND.check("OPEN", "CLOSED");
        Timestamp closedAt = jdbc.queryForObject("update rounds set state = 'CLOSED', closed_at = clock_timestamp(), version = version + 1 where id = ? returning closed_at",
                Timestamp.class, round.get("id"));
        outbox.append(poolId, nextVersion(poolId), new RoundClosed(poolId.toString(), round.get("id").toString(), kind,
                ((Timestamp) round.get("deadline")).toInstant(), closedAt.toInstant(), validCount));
    }

    /**
     * Ends a pool (POOL-T04/T06/T11/T12) with MEM-T10 for committed members, in the caller's transaction. Refunds are driven
     * by PoolClosed, which payment consumes: captured deposits are refunded, unpaid ones voided (one event per pool, not per member).
     */
    private void closePool(UUID poolId, String from, String endState, String endReason) { pools.closePool(poolId, from, endState, endReason); }

    private long nextVersion(UUID poolId) {
        return jdbc.queryForObject("update pools set version = version + 1 where id = ? returning version", Long.class, poolId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(UUID roundId) {
        try {
            return clients.quote().get().uri("/internal/rounds/{id}/summary", roundId).retrieve().body(Map.class);
        } catch (RestClientException e) {
            log.info("quote summary for round {} unavailable: {} (retry next tick)", roundId, e.getMessage());
            return null;
        }
    }

    private static List<String> variants(Map<String, Object> pool) {
        return List.of((Object[]) Pools.toArray(pool.get("accepted_variant_ids"))).stream().map(String::valueOf).toList();
    }
}
