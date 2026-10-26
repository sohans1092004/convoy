package convoy.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.messaging.Envelope;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The recipient graph, the fan-out into notifications + email deliveries, and the inbox API (E130-E132, E158, E159). */
@RestController
public class Inbox {
    /** Always sent, whatever the user's preferences (P5.A: payment and refund messages). */
    static final Set<String> MANDATORY = Set.of("DEPOSIT_RECEIVED", "REFUND_DONE");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern FIELD = Pattern.compile("\\{([a-z_]+)}");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonNode templates;

    public Inbox(JdbcTemplate jdbc, ObjectMapper json) throws java.io.IOException {
        this.jdbc = jdbc;
        this.json = json;
        try (var in = Inbox.class.getResourceAsStream("/templates.json")) {
            this.templates = json.readTree(in);
        }
    }

    // ---------------------------------------------------------------- consuming (inside the inbox transaction)

    /**
     * Graph writes serialise: otherwise a link and the event it unlocks, committed concurrently by two pollers, each miss the
     * other (READ COMMITTED) and nobody fans out. ponytail: one global lock (a few ms per event); per-pool locks if W8 needs it.
     */
    private void lock() { jdbc.queryForList("select pg_advisory_xact_lock(5001)"); }

    void link(String key, String target) {
        lock();
        jdbc.update("insert into links(key, target) values (?, ?) on conflict do nothing", key, target);
        fanout(key);
    }

    /** pool -> buyer, versioned: keeps the latest commit and the latest leave, so arrival order does not matter. */
    void member(Envelope e, String poolId, String buyerId, boolean committed) {
        lock();
        String col = committed ? "from_version" : "to_version";
        jdbc.update("insert into links(key, target, " + col + ") values (?, ?, ?) on conflict (key, target) do update set "
                + col + " = greatest(links." + col + ", excluded." + col + ")", "pool:" + poolId, "user:" + buyerId, e.aggregateVersion());
        if (committed) fanout("pool:" + poolId);
    }

    void event(Envelope e, String kind, String key) { event(e, kind, key, e.aggregateVersion()); }

    /** `version` is compared with pool membership versions; Long.MAX_VALUE = every current member (event of another aggregate). */
    void event(Envelope e, String kind, String key, long version) {
        lock();
        jdbc.update("insert into events(event_id, key, kind, version, payload, occurred_at) values (?, ?, ?, ?, ?::jsonb, ?::timestamptz) on conflict do nothing",
                e.eventId(), key, kind, version, e.payload().toString(), e.occurredAt());
        fanout(key);
    }

    /**
     * Every stored event whose key is `key` or links to it is (re)delivered to every user it now reaches.
     * unique(user_id, event_id) makes a repeat a no-op, so one event = one notification + one email per user.
     * ponytail: a buyer who left gets a pool event if the leave is processed after it (rare reorder); messages cannot be unsent.
     */
    void fanout(String key) {
        jdbc.update("""
                with recursive down(k) as (
                    select ?::text union select l.key from links l join down d on l.target = d.k),
                up(event_id, k) as (
                    select e.event_id, e.key from events e where e.key in (select k from down)
                    union
                    select u.event_id, l.target from up u join links l on l.key = u.k join events e on e.event_id = u.event_id
                    where l.from_version is null and l.to_version is null
                       or e.version > l.from_version and (l.to_version is null or l.to_version < l.from_version)),
                ins as (
                    insert into notifications(user_id, event_id, kind)
                    select substr(u.k, 6)::uuid, e.event_id, e.kind from up u join events e on e.event_id = u.event_id where u.k like 'user:%'
                    on conflict (user_id, event_id) do nothing
                    returning id, user_id, kind),
                push as (
                    insert into deliveries(notification_id, channel)
                    select ins.id, 'PUSH' from ins where exists (select 1 from push_subscriptions s where s.user_id = ins.user_id))
                insert into deliveries(notification_id, channel, status)
                select ins.id, 'EMAIL', case when ins.kind = any(?) or coalesce(p.enabled, true) then 'PENDING' else 'SKIPPED' end
                from ins left join preferences p on p.user_id = ins.user_id and p.channel = 'EMAIL'
                """, key, MANDATORY.toArray(String[]::new));
    }

    // ---------------------------------------------------------------- rendering

    record Rendered(String subject, String body, String link, boolean draft) {}

    Rendered render(String kind, JsonNode payload, String locale) {
        JsonNode t = templates.path(kind);
        JsonNode l = t.has(locale) ? t.path(locale) : t.path("en");
        return new Rendered(fill(l.path("subject").asText(), payload), fill(l.path("body").asText(), payload), fill(t.path("link").asText(), payload),
                l.path("draft").asBoolean(false));
    }

    static String fill(String text, JsonNode payload) {
        Matcher m = FIELD.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(format(m.group(1), payload.path(m.group(1)))));
        return m.appendTail(out).toString();
    }

    /** Integer paise -> "₹12,34,567.89" (Indian grouping); timestamps in IST; missing -> "-". */
    static String format(String field, JsonNode v) {
        if (v.isMissingNode() || v.isNull()) return "-";
        if (field.endsWith("_paise")) {
            long p = v.asLong();
            String rupees = Long.toString(p / 100);
            String grouped = rupees.length() <= 3 ? rupees : rupees.substring(0, rupees.length() - 3).replaceAll("\\B(?=(\\d{2})+$)", ",") + "," + rupees.substring(rupees.length() - 3);
            return "₹" + grouped + (p % 100 == 0 ? "" : String.format(".%02d", p % 100));
        }
        if (field.endsWith("deadline")) {
            return DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'IST'").format(Instant.parse(v.asText()).atZone(IST));
        }
        return v.asText();
    }

    // ---------------------------------------------------------------- API

    public record Notification(String id, String kind, String subject, String body, String link, boolean draft, String createdAt, @Nullable String readAt) {}

    public record InboxPage(List<Notification> notifications, long unreadCount) {}

    public record Preferences(boolean inApp, boolean email) {}

    public record Read(String id) {}

    public record Marked(int marked) {}

    /** E130: my notifications, newest first, in my locale (en | hi; Hindi is a marked draft). */
    @GetMapping("/me/notifications")
    public InboxPage list(@RequestParam(defaultValue = "en") String locale, @RequestParam(defaultValue = "50") int limit) {
        UUID me = Caller.current().userId();
        boolean inApp = enabled(me, "IN_APP");
        String filter = inApp ? "" : " and n.kind = any(?)";
        Object[] args = inApp ? new Object[] {me, Math.min(Math.max(limit, 1), 200)} : new Object[] {me, MANDATORY.toArray(String[]::new), Math.min(Math.max(limit, 1), 200)};
        List<Notification> list = jdbc.query("select n.id, n.kind, n.created_at, n.read_at, e.payload::text as payload from notifications n join events e on e.event_id = n.event_id "
                + "where n.user_id = ?" + filter + " order by n.created_at desc, n.id limit ?", (rs, i) -> {
                    Rendered r = render(rs.getString("kind"), read(rs.getString("payload")), "hi".equals(locale) ? "hi" : "en");
                    java.sql.Timestamp readAt = rs.getTimestamp("read_at");
                    return new Notification(rs.getString("id"), rs.getString("kind"), r.subject(), r.body(), r.link(), r.draft(),
                            rs.getTimestamp("created_at").toInstant().toString(), readAt == null ? null : readAt.toInstant().toString());
                }, args);
        Long unread = jdbc.queryForObject("select count(*) from notifications n where n.user_id = ? and n.read_at is null" + filter, Long.class,
                inApp ? new Object[] {me} : new Object[] {me, MANDATORY.toArray(String[]::new)});
        return new InboxPage(list, unread);
    }

    /** E131: mark one as read (idempotent). */
    @PostMapping("/me/notifications/{id}/read")
    public Read read(@PathVariable UUID id) {
        if (jdbc.update("update notifications set read_at = coalesce(read_at, clock_timestamp()) where id = ? and user_id = ?", id, Caller.current().userId()) == 0) {
            throw ApiProblem.of(ProblemType.NOT_FOUND, "No such notification.");
        }
        return new Read(id.toString());
    }

    /** E158: mark all as read. */
    @PostMapping("/me/notifications/read-all")
    public Marked readAll() {
        int n = jdbc.update("update notifications set read_at = clock_timestamp() where user_id = ? and read_at is null", Caller.current().userId());
        return new Marked(n);
    }

    /** E159: my channel preferences (payment and refund messages are sent regardless). */
    @GetMapping("/me/notification-preferences")
    public Preferences preferences() {
        UUID me = Caller.current().userId();
        return new Preferences(enabled(me, "IN_APP"), enabled(me, "EMAIL"));
    }

    /** E132: set them. */
    @PutMapping("/me/notification-preferences")
    public Preferences setPreferences(@RequestBody Preferences p) {
        UUID me = Caller.current().userId();
        for (var c : Map.of("IN_APP", p.inApp(), "EMAIL", p.email()).entrySet()) {
            jdbc.update("insert into preferences(user_id, channel, enabled) values (?, ?, ?) on conflict (user_id, channel) do update set enabled = excluded.enabled",
                    me, c.getKey(), c.getValue());
        }
        return preferences();
    }

    boolean enabled(UUID user, String channel) {
        List<Boolean> r = jdbc.queryForList("select enabled from preferences where user_id = ? and channel = ?", Boolean.class, user, channel);
        return r.isEmpty() || r.get(0);
    }

    JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
