package convoy.dealer;

import java.util.regex.Pattern;

/**
 * GSTIN shape and check digit. 15 characters: 2-digit state code, the 10-character PAN, entity number, 'Z', and a
 * check character computed with the GSTN mod-36 algorithm (alternate weights 1 and 2, each product folded as
 * quotient + remainder of 36). This checks the format only; it does not prove the GSTIN is registered.
 */
final class Gstin {
    private Gstin() {}

    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Pattern SHAPE = Pattern.compile("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]");

    static char checkChar(String first14) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int product = CHARS.indexOf(first14.charAt(i)) * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return CHARS.charAt((36 - sum % 36) % 36);
    }

    /** Null when valid, else the reason (shown to the dealer). */
    static String problem(String gstin) {
        if (gstin == null || !SHAPE.matcher(gstin).matches()) return "A GSTIN has 15 characters: state code, PAN, entity number, Z and a check character.";
        int state = Integer.parseInt(gstin.substring(0, 2));
        if (state < 1 || state > 38) return "The first two digits must be a valid state code (01-38).";
        if (checkChar(gstin.substring(0, 14)) != gstin.charAt(14)) return "The check character does not match; please re-check the GSTIN.";
        return null;
    }
}
