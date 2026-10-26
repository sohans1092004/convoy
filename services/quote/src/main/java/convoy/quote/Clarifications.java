package convoy.quote;

import convoy.contracts.events.ClarificationAnswered;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P5.E structured clarifications (E071, E072). Every clarification happens before the contact-consent point (the booking,
 * E155), so phone numbers, email addresses and links are refused in both directions.
 */
@Service
public class Clarifications {
    static final Set<String> TYPES = Set.of("DELIVERY_DATE", "STOCK_COLOUR", "PRICE_ITEM", "INSURANCE", "ACCESSORIES", "DOCUMENTS", "OTHER");
    static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{Nd}._%+-]+\\s*(@|\\(at\\)|\\[at\\])\\s*[\\p{L}\\p{Nd}-]+(\\.[\\p{L}\\p{Nd}-]+)+", Pattern.CASE_INSENSITIVE);
    static final Pattern LINK = Pattern.compile("(https?://|www\\.)\\S+|\\b[a-z0-9-]+\\.(com|in|net|org|co|io|me|app|link|ly|xyz|info|biz|site|online|shop)\\b",
            Pattern.CASE_INSENSITIVE);
    /** A run of digits with separators; 10 or more digits in it is a phone number (no price or date has that many). */
    static final Pattern DIGITS = Pattern.compile("\\+?\\p{Nd}[\\p{Nd}\\s\\-().+/]*");

    private final JdbcTemplate jdbc;
    private final Outbox outbox;
    private final Quotes quotes;

    public Clarifications(JdbcTemplate jdbc, Outbox outbox, Quotes quotes) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.quotes = quotes;
    }

    /** Why the text would share contact details, or null. Spelled-out numbers are not caught (ponytail: add if abuse shows up). */
    static String contactProblem(String text) {
        if (EMAIL.matcher(text).find()) return "Remove the email address: contact details are shared only after booking.";
        if (LINK.matcher(text).find()) return "Remove the link: contact details and websites are shared only after booking.";
        Matcher m = DIGITS.matcher(text);
        while (m.find()) {
            if (m.group().codePoints().filter(Character::isDigit).count() >= 10) return "Remove the phone number: contact details are shared only after booking.";
        }
        return null;
    }

    static String text(String s, int max) {
        String t = s == null ? "" : s.strip();
        if (t.isEmpty() || t.length() > max) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Write 1 to " + max + " characters.").with("field", "text");
        String why = contactProblem(t);
        if (why != null) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, why).with("field", "text");
        return t;
    }

    /** E071: a committed member asks about one FINAL quote of the pool. */
    @Transactional
    public Map<String, Object> ask(UUID poolId, UUID quoteId, String type, String question) {
        UUID buyer = Caller.current().require("buyer").userId();
        if (quotes.member(poolId, buyer).isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "Offers are shown to committed members of this pool.");
        List<String> state = jdbc.queryForList("select state from quotes where id = ? and pool_id = ?", String.class, quoteId, poolId);
        if (state.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such offer.");
        if (!"FINAL".equals(state.get(0))) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "Questions can be asked about final offers only.");
        if (type == null || !TYPES.contains(type)) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Choose a question type.").with("field", "question_type");
        UUID id = Uuid7.next();
        jdbc.update("insert into clarifications(id, pool_id, quote_id, buyer_id, question_type, question) values (?, ?, ?, ?, ?, ?)",
                id, poolId, quoteId, buyer, type, text(question, 300));
        return view(jdbc.queryForMap("select * from clarifications where id = ?", id));
    }

    /** E072: the quote's dealership answers an open clarification once. */
    @Transactional
    public Map<String, Object> answer(UUID id, String answer) {
        Caller c = Caller.current();
        UUID d = Quotes.dealership(c);
        List<Map<String, Object>> rs = jdbc.queryForList("select c.* from clarifications c join quotes q on q.id = c.quote_id where c.id = ? and q.dealership_id = ? for update of c", id, d);
        if (rs.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such question.");
        if (rs.get(0).get("answer") != null) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "This question is already answered.");
        jdbc.update("update clarifications set answer = ?, answered_by = ?, answered_at = clock_timestamp() where id = ?", text(answer, 500), c.userId(), id);
        Map<String, Object> row = jdbc.queryForMap("select * from clarifications where id = ?", id);
        String quoteId = row.get("quote_id").toString();
        jdbc.queryForList("select 1 from quotes where id = ?::uuid for update", quoteId); // serialises the stream version per quote
        // Own stream per quote (like RankChanged): the quote's version stays the dealer's If-Match for revisions.
        long v = jdbc.queryForObject("select count(*) from clarifications where quote_id = ?::uuid and answered_at is not null", Long.class, quoteId);
        outbox.append(stream(quoteId), v, new ClarificationAnswered(quoteId, row.get("pool_id").toString(), id.toString(), (String) row.get("question_type")));
        return view(row);
    }

    static UUID stream(String quoteId) { return UUID.nameUUIDFromBytes(("clarification:" + quoteId).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    /** Answered ones for every committed member, plus the caller's own open ones (buyer); every one of the quote for its dealer. */
    static List<Map<String, Object>> forQuote(JdbcTemplate jdbc, UUID quoteId, UUID buyer) {
        List<Map<String, Object>> out = new ArrayList<>();
        String where = buyer == null ? "" : " and (answer is not null or buyer_id = ?)";
        Object[] args = buyer == null ? new Object[] {quoteId} : new Object[] {quoteId, buyer};
        for (Map<String, Object> r : jdbc.queryForList("select * from clarifications where quote_id = ?" + where + " order by asked_at", args)) out.add(view(r));
        return out;
    }

    static Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id").toString());
        m.put("quote_id", r.get("quote_id").toString());
        m.put("question_type", r.get("question_type"));
        m.put("question", r.get("question")); // null once the asker erased their account
        m.put("answer", r.get("answer"));
        m.put("asked_at", ((Timestamp) r.get("asked_at")).toInstant().toString());
        m.put("answered_at", r.get("answered_at") == null ? null : ((Timestamp) r.get("answered_at")).toInstant().toString());
        return m;
    }
}
