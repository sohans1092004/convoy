package convoy.payment;

import convoy.platform.messaging.Uuid7;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Balanced, immutable journal entries (deposit-contract.md §5, AMENDMENT-003). Always inside the state change's transaction. */
@Component
public class Ledger {
    public static final String CLEARING = "1100", BANK = "1200", FEES_RECEIVABLE = "1300", HELD = "2100", HELD_FOR_BOOKING = "2200", FEE_REVENUE = "4100";
    private final JdbcTemplate jdbc;

    public Ledger(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** One debit line and one credit line of the same amount. */
    public UUID post(String kind, UUID paymentId, String drAccount, String crAccount, long amount) {
        UUID id = Uuid7.next();
        jdbc.update("insert into journal_entries(id, kind, payment_id) values (?, ?, ?)", id, kind, paymentId);
        jdbc.update("insert into journal_lines(entry_id, line_no, account, dr, cr) values (?, 1, ?, ?, 0), (?, 2, ?, 0, ?)",
                id, drAccount, amount, id, crAccount, amount);
        return id;
    }

    public UUID capture(UUID paymentId, long amount) { return post("J-CAPTURE", paymentId, CLEARING, HELD, amount); }

    public UUID hold(UUID paymentId, long amount) { return post("J-HOLD", paymentId, HELD, HELD_FOR_BOOKING, amount); }

    /** J-FEE: the dealer owes the platform its success fee; never touches a deposit account (deposit-contract.md §5). */
    public UUID successFee(UUID paymentId, long amount) { return post("J-FEE", paymentId, FEES_RECEIVABLE, FEE_REVENUE, amount); }

    /** J-REFUND (kind REFUND) or J-RELEASE (kind RELEASE): source account back to Razorpay clearing. */
    public UUID refund(UUID paymentId, String kind, String sourceAccount, long amount) {
        return post("RELEASE".equals(kind) ? "J-RELEASE" : "J-REFUND", paymentId, sourceAccount, CLEARING, amount);
    }
}
