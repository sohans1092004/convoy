# Catalogue sources and method (collected 2026-10-24)

## Method
1. **Sources used:** official manufacturer pages only, one page per model, read once on 2026-10-24 with a single fetch — about 35 page reads in total. No crawling, no API reverse-engineering, and no automated bulk extraction.
2. **Aggregators rejected:** CarDekho was considered and **rejected**. Its robots.txt allows the crawler, but its Terms (User's Obligation, s.7) prohibit copying content through automated means, including scraping, crawlers and bots ([terms](https://www.cardekho.com/info/terms_and_condition)). One CarDekho page was read during evaluation; its figures were **discarded**, and none appear in `models.yaml`. CarWale and similar aggregators were not used for the same reason.
3. **Recording:** each price is recorded exactly as shown, converted to paise. The page's own wording ("Starting at", "Ex-showroom (Delhi)", etc.) is kept in `price_label`.
4. **City attribution:** official pages mostly show a single price with no city. Those prices are recorded with `city_basis: NOT_STATED`. For the four plan cities (Mumbai, Delhi, Bengaluru, Pune), applying that price to a city is an **ASSUMPTION** (Q-003). The UI labels it "catalogue estimate, city not confirmed".
5. **Gaps:** nothing was guessed. Where a page was blocked, rendered no prices, or didn't label its price as ex-showroom, the value is `null`/`UNVERIFIED`, or recorded but marked UNVERIFIED.

## Counts
| Measure | Count |
|---|---|
| Brands / models | 8 / 30 |
| Variants listed | 41 |
| **Variant ex-showroom prices verified** (official page, named variant) | **31** |
| Variant prices unverified (listed without price, or page failed) | 10 |
| Model starting prices verified (not tied to a variant) | 4 (Venue, Verna, Exter, Sonet) |
| Model prices seen but basis not labelled ex-showroom (UNVERIFIED) | 7 (Carens, City, Elevate; Mahindra ranges ×4) |
| Models with **no verified price** | 17 of 30 (Baleno, Fronx; Tata ×5; Mahindra ×4; Carens; Toyota ×3; City, Elevate) |
| City-verified variant price rows (page names the city) | 3 of 124 variant × city rows (Brezza ×3, Delhi) |
| City-verified model starting prices | Sonet: "all India" → 4 cities |
| Variant × city rows priced by ASSUMPTION (national price, city not stated) | 121 of 124 |

## Failures (why the gaps exist)
| Brand / model | Reason |
|---|---|
| Tata (Nexon, Punch, Harrier, Safari, Altroz) | `cars.tatamotors.com` returned an **expired TLS certificate** on 2026-10-24. Not bypassed, because skipping certificate validation is unsafe. Retry in P1. |
| Toyota (Innova Hycross, Fortuner, Hyryder) | `toyotabharat.com` returned HTTP 404 and 403 to the fetcher. |
| Maruti NEXA (Baleno, Fronx) | Page rendered template placeholders with no prices. |
| Mahindra (4 models) | Only menu price ranges are visible, not labelled ex-showroom, with no city and no variants. |
| Kia Carens; Honda City, Elevate | Prices shown as "starts from" without an ex-showroom label. |
| Kia Seltos/Sonet, Hyundai Venue/Verna/Exter/i20 | Variant price tables are empty; only FAQ or starting prices are available. |

## Sources (all accessed 2026-10-24)
- Maruti Suzuki Arena: [Swift](https://www.marutisuzuki.com/arena/swift/price), [Dzire](https://www.marutisuzuki.com/arena/dzire/price), [Brezza](https://www.marutisuzuki.com/arena/brezza/price) (labelled "Ex-showroom Price (Delhi). Effective from 24th July'26"), [Ertiga](https://www.marutisuzuki.com/arena/ertiga/price)
- NEXA: [Baleno](https://www.nexaexperience.com/baleno/price), [Fronx](https://www.nexaexperience.com/fronx/price) (no prices rendered)
- Hyundai India: [Creta](https://www.hyundai.com/in/en/find-a-car/creta/price), [Venue](https://www.hyundai.com/in/en/find-a-car/venue/price), [Verna](https://www.hyundai.com/in/en/find-a-car/verna/price), [i20](https://www.hyundai.com/in/en/find-a-car/i20/price), [Exter](https://www.hyundai.com/in/en/find-a-car/exter/price)
- Kia India: [Sonet](https://www.kia.com/in/our-vehicles/sonet/showroom.html), [Seltos](https://www.kia.com/in/our-vehicles/seltos/showroom.html), [Carens](https://www.kia.com/in/our-vehicles/carens/showroom.html)
- MG Motor India: [Windsor EV](https://www.mgmotor.co.in/vehicles/windsor-ev-electric-car-in-india), [Hector](https://www.mgmotor.co.in/vehicles/mghector)
- Mahindra: [XUV 3XO](https://auto.mahindra.com/suv/xuv-3xo), [Scorpio-N](https://auto.mahindra.com/suv/scorpio-n), [XUV 7XO](https://auto.mahindra.com/suv/xuv700), [Thar](https://auto.mahindra.com/suv/thar)
- Honda Cars India: [City](https://www.hondacarindia.com/honda-city), [Elevate](https://www.hondacarindia.com/honda-elevate)
- Tata Motors: [Nexon](https://cars.tatamotors.com/nexon/ice/price.html), [Punch](https://cars.tatamotors.com/punch/ice.html) (TLS failure)
- Toyota Bharat: [Innova Hycross](https://www.toyotabharat.com/showroom/innova-hycross/), [Fortuner](https://www.toyotabharat.com/showroom/fortuner/) (404/403)

## Cross-check
The Swift LXi price (₹5,83,900) also appears as the "Price starts at" figure on Maruti's model page [marutisuzuki.com/swift/price](https://www.marutisuzuki.com/swift/price). Two official pages agree.

## Before P1
- Retry Tata and Toyota once their pages load. Where available, use official price-list PDFs (state-wise lists name the state).
- For P1 seeding, use only `status: VERIFIED` variants with known fuel; Swift, Dzire, Brezza, Creta and Windsor EV cover petrol, CNG, diesel and EV.
