Real Razorpay test-mode run, 2026-10-24 (IST 20:56-20:58), tunnel https://populations-greensboro-drew-norm.trycloudflare.com
- order order_TrnJnSL3xrxCoC created 15:26:58.647Z (payment 01a16e85-b457-7f2a-b820-19f1098fa6c7)
- checkout: contact overlay (9999999999 rejected as invalid; 9123456789 ok), Netbanking -> Canara Bank -> test bank page -> Success
- webhook payment.authorized id TrnK3jvjm9foLq at 15:27:14.372Z; payment.captured id TrnK4ZJPmFnrnq at 15:27:15.069Z (0.70 s later); payment pay_TrnK15jey9SDYq captured_at 15:27:15.071Z; J-CAPTURE posted
- browser: bank Success at 18.0 s, "You are committed" at 30.2 s (callback + membership poll)
- leave -> refund requested 15:27:27.712Z (BUYER_LEFT); POST /v1/payments/pay_TrnK15jey9SDYq/refund -> 400 BAD_REQUEST_ERROR "invalid request sent" for JSON (amount/receipt/speed), amount only, empty body, form-encoded. Owner confirmed refunds are not enabled for this Razorpay test account.
Differences vs fake: webhook event ids have no "evt_" prefix; refunds can be disabled per account (generic 400); checkout asks for a contact number and rejects repeated digits; worker treated 400 as retryable.
