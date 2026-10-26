package convoy.notification;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends due email deliveries over SMTP (AMENDMENT-011: Mailpit locally, the SES SMTP interface on AWS), with the same
 * JavaMailSender and spring.mail settings as identity's OTP mail. One delivery per transaction under
 * its row lock (skip locked, so instances never send the same row). Failures back off by the DB clock; the last failure
 * leaves the delivery DEAD with an ALARM log line. English only while the Hindi templates are drafts.
 * ponytail: a crash after the SMTP server accepts and before commit sends that email twice; SMTP has no idempotency key.
 */
@Component
public class Mailer {
    private static final Logger log = LoggerFactory.getLogger(Mailer.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Inbox inbox;
    private final JavaMailSender mail;
    private final RestClient identity;
    private final NotificationApplication.Settings s;

    public Mailer(JdbcTemplate jdbc, TransactionTemplate tx, Inbox inbox, JavaMailSender mail, RestClient identityClient, NotificationApplication.Settings s,
                  TaskScheduler scheduler) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.inbox = inbox;
        this.mail = mail;
        this.identity = identityClient;
        this.s = s;
        scheduler.scheduleWithFixedDelay(this::drain, s.pollEvery());
    }

    /** Sends until nothing is due. */
    void drain() {
        try {
            while (Boolean.TRUE.equals(tx.execute(st -> sendOne()))) {
                // next
            }
        } catch (RuntimeException e) {
            log.warn("mailer pass failed: {}", e.toString());
        }
    }

    private boolean sendOne() {
        List<Map<String, Object>> due = jdbc.queryForList("""
                select d.notification_id, d.attempts, n.user_id, n.kind, e.payload::text as payload from deliveries d
                join notifications n on n.id = d.notification_id join events e on e.event_id = n.event_id
                where d.status = 'PENDING' and d.channel = 'EMAIL' and d.next_attempt_at <= clock_timestamp()
                order by d.next_attempt_at limit 1 for update of d skip locked""");
        if (due.isEmpty()) return false;
        Map<String, Object> d = due.get(0);
        UUID id = (UUID) d.get("notification_id");
        int attempts = ((Number) d.get("attempts")).intValue() + 1;
        try {
            convoy.platform.Faults.hit("notification.send");
            String to = (String) identity.get().uri("/internal/users/{id}/contact", d.get("user_id")).retrieve().body(Map.class).get("email");
            Inbox.Rendered r = inbox.render((String) d.get("kind"), inbox.read((String) d.get("payload")), "en");
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(s.mailFrom());
            m.setTo(to);
            m.setSubject(r.subject());
            m.setText(r.body() + "\n\nhttps://convoy.test" + r.link());
            mail.send(m);
            jdbc.update("update deliveries set status = 'SENT', attempts = ?, sent_at = clock_timestamp(), last_error = null where notification_id = ? and channel = 'EMAIL'",
                    attempts, id);
        } catch (HttpClientErrorException.NotFound e) {
            jdbc.update("update deliveries set status = 'DEAD', attempts = ?, last_error = 'no such user' where notification_id = ? and channel = 'EMAIL'", attempts, id);
        } catch (RuntimeException e) {
            failed(jdbc, s, id, "EMAIL", attempts, e);
        }
        return true;
    }

    /** Backoff by the DB clock; DEAD (with an ALARM line) after the last attempt. Shared by the email and push channels. */
    static void failed(JdbcTemplate jdbc, NotificationApplication.Settings s, UUID id, String channel, int attempts, Exception e) {
        boolean dead = attempts >= s.maxAttempts();
        long wait = Math.min(s.baseBackoff().toMillis() << Math.min(attempts - 1, 20), s.maxBackoff().toMillis());
        jdbc.update("update deliveries set status = ?, attempts = ?, last_error = ?, next_attempt_at = clock_timestamp() + make_interval(secs => ?) "
                + "where notification_id = ? and channel = ?", dead ? "DEAD" : "PENDING", attempts, String.valueOf(e.getMessage()), wait / 1000.0, id, channel);
        if (dead) log.error("ALARM {}_DEAD notification {} after {} attempts: {}", channel, id, attempts, e.toString());
        else log.warn("{} {} attempt {} failed, retry in {} ms: {}", channel, id, attempts, wait, e.toString());
    }
}
