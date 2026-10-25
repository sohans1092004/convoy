package convoy.payment;

import convoy.contracts.events.ReconciliationMismatchFound;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.Uuid7;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciliation (deposit-contract.md §4): the ledger and local states against Razorpay test mode, stuck webhooks, and the
 * ledger invariants. Each mismatch is a row, a ReconciliationMismatchFound event and an ALARM; nothing is auto-corrected.
 */
@Service
public class Reconciliation {
    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);
    static final List<String> CAPTURED_LOCALLY = List.of("CAPTURED", "HELD_FOR_BOOKING", "REFUND_PENDING", "REFUNDED", "RELEASED");
    private final JdbcTemplate jdbc;
    private final RazorpayClient razorpay;
    private final Outbox outbox;
    private final Duration stuckWebhookAfter;

    public Reconciliation(JdbcTemplate jdbc, RazorpayClient razorpay, Outbox outbox, PaymentApplication.PaymentSettings settings) {
        this.jdbc = jdbc;
        this.razorpay = razorpay;
        this.outbox = outbox;
        this.stuckWebhookAfter = settings.stuckWebhookAfter();
    }

    @Transactional
    public UUID run() {
        UUID run = Uuid7.next();
        jdbc.update("insert into reconciliation_runs(id) values (?)", run);
        int[] found = {0};
        List<Map<String, Object>> ps = jdbc.queryForList("select id, state, amount_paise, razorpay_order_id, razorpay_payment_id, "
                + "created_at < clock_timestamp() - ?::interval as stale from payments where razorpay_order_id is not null "
                + "and greatest(created_at, coalesce(captured_at, created_at)) > clock_timestamp() - interval '7 days' order by created_at",
                stuckWebhookAfter.toMillis() + " milliseconds");
        for (Map<String, Object> p : ps) {
            UUID id = (UUID) p.get("id");
            String state = (String) p.get("state");
            long amount = ((Number) p.get("amount_paise")).longValue();
            List<RazorpayClient.RemotePayment> remote = razorpay.orderPayments((String) p.get("razorpay_order_id"));
            long capturedRemote = remote.stream().filter(r -> "captured".equals(r.status()) || "refunded".equals(r.status())).mapToLong(RazorpayClient.RemotePayment::amount).sum();
            boolean local = CAPTURED_LOCALLY.contains(state);
            if (capturedRemote > 0 && !local) found[0] += mismatch(run, "CAPTURED_REMOTE_NOT_LOCAL", id, "Razorpay captured " + capturedRemote + " paise; local state " + state + " (missed webhook?)");
            if (capturedRemote == 0 && local) found[0] += mismatch(run, "LOCAL_CAPTURED_REMOTE_NOT", id, "local state " + state + "; Razorpay has no captured payment");
            if (capturedRemote > 0 && local && capturedRemote != amount) found[0] += mismatch(run, "AMOUNT_MISMATCH", id, "Razorpay captured " + capturedRemote + " paise; local amount " + amount);
            if ("PENDING".equals(state) && (boolean) p.get("stale")) found[0] += mismatch(run, "STUCK_WEBHOOK", id, "PENDING longer than " + stuckWebhookAfter + " without payment.captured");
            if (p.get("razorpay_payment_id") != null && local) {
                List<RazorpayClient.Refund> refunds = razorpay.refunds((String) p.get("razorpay_payment_id"));
                if (refunds.size() > 1) found[0] += mismatch(run, "POSSIBLE_DOUBLE_REFUND", id, refunds.size() + " refunds at Razorpay for one deposit");
                boolean remoteProcessed = refunds.stream().anyMatch(r -> "processed".equals(r.status()));
                List<Map<String, Object>> lr = jdbc.queryForList("select state, manual from refunds where payment_id = ?", id);
                boolean localRefunded = !lr.isEmpty() && "REFUNDED".equals(lr.get(0).get("state")) && !(boolean) lr.get(0).get("manual");
                if (remoteProcessed != localRefunded) found[0] += mismatch(run, "REFUND_STATUS_MISMATCH", id, "Razorpay refund processed=" + remoteProcessed + ", local refunded=" + localRefunded);
            }
        }
        for (String broken : invariantViolations()) found[0] += mismatch(run, "LEDGER_INVARIANT", null, broken);
        jdbc.update("update reconciliation_runs set finished_at = clock_timestamp(), checked = ?, mismatch_count = ? where id = ?", ps.size(), found[0], run);
        log.info("reconciliation {}: {} deposits checked, {} mismatches", run, ps.size(), found[0]);
        return run;
    }

    private int mismatch(UUID run, String cls, UUID paymentId, String detail) {
        jdbc.update("insert into reconciliation_mismatches(id, run_id, mismatch_class, payment_id, detail) values (?, ?, ?, ?, ?)", Uuid7.next(), run, cls, paymentId, detail);
        long n = jdbc.queryForObject("select count(*) from reconciliation_mismatches where run_id = ?", Long.class, run);
        outbox.append(run, n, new ReconciliationMismatchFound(run.toString(), cls, paymentId == null ? null : paymentId.toString(), null));
        log.error("ALARM RECONCILIATION_MISMATCH {} payment {}: {}", cls, paymentId, detail);
        return 1;
    }

    /** deposit-contract.md §5 invariants 1, 3, 4, 7 as queries (2 is enforced by triggers). Empty means healthy. */
    public List<String> invariantViolations() {
        return jdbc.queryForList("""
                select 'entry ' || entry_id || ' does not balance' from journal_lines group by entry_id having sum(dr) <> sum(cr)
                union all
                select 'deposit ' || e.payment_id || ' has ' || count(*) || ' terminal entries' from journal_entries e
                  where e.kind in ('J-REFUND', 'J-RELEASE', 'J-REFUND-MANUAL') group by e.payment_id having count(*) > 1
                union all
                select 'deposit ' || p.id || ' is ' || p.state || ' but holds ' || coalesce(sum(l.cr - l.dr), 0) || ' paise in 2100/2200'
                  from payments p left join journal_entries e on e.payment_id = p.id left join journal_lines l on l.entry_id = e.id and l.account in ('2100', '2200')
                  where p.state in ('REFUNDED', 'RELEASED') group by p.id, p.state having coalesce(sum(l.cr - l.dr), 0) <> 0
                union all
                select 'deposit ' || p.id || ' is ' || p.state || ' without exactly one full terminal entry of the right kind' from payments p
                  where p.state in ('REFUNDED', 'RELEASED') and (select count(*) from journal_entries e join journal_lines l on l.entry_id = e.id and l.line_no = 1
                    where e.payment_id = p.id and l.dr = p.amount_paise
                      and e.kind = any(case when p.state = 'RELEASED' then array['J-RELEASE'] else array['J-REFUND', 'J-REFUND-MANUAL'] end)) <> 1
                union all
                select 'account 1300 touched by ' || e.kind from journal_entries e join journal_lines l on l.entry_id = e.id where l.account = '1300' and e.kind <> 'J-FEE'
                """, String.class);
    }

    public List<Map<String, Object>> runs() {
        return jdbc.queryForList("select id::text as id, started_at, finished_at, checked, mismatch_count from reconciliation_runs order by started_at desc limit 30").stream()
                .map(r -> {
                    Map<String, Object> out = new java.util.LinkedHashMap<>(r);
                    out.replaceAll((k, v) -> v instanceof java.sql.Timestamp t ? t.toInstant().toString() : v);
                    out.put("mismatches", jdbc.queryForList("select mismatch_class, payment_id::text as payment_id, detail from reconciliation_mismatches where run_id = ?::uuid "
                            + "order by created_at", r.get("id")));
                    return out;
                }).toList();
    }
}
