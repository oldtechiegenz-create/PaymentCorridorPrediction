package com.corridoriq.backend.engine;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CorridorIQ reference data (test data): banks, corridors, route templates, causes and the portal's seeded
 * incidents. (This replaced the Python reference.py.) All banks, BICs, fees and FX levels are fictional.
 * Maps keep insertion order, which the database rows and the corridor picker rely on.
 */
public final class Reference {

    public static final String ORDERING_BANK = "ORIG";
    public static final double LARGE_TICKET_USD = 250_000;
    public static final double FI_FIRST_HOP_FEE_CAP = 15.0;

    public record Currency(String code, String name, int decimals, int peggedToUsd) {
    }

    /** weekendDays: 0 = Sunday ... 6 = Saturday. currency and extraRule may be null. */
    public record Country(String code, String name, String tzName, Set<Integer> weekendDays, String currency,
                          String extraRule) {
        public ZoneId zone() {
            return ZoneId.of(tzName);
        }
    }

    /**
     * speed is hidden ground truth used only to realise history; it is not written to the database, so the
     * simulator does not know it and a model has to learn each bank's real pace from outcomes.
     * cutoff is the local hour after which the bank waits for the next day; null for a bank that runs all day.
     */
    public record Bank(String id, String name, String bic, String city, String country, String role, Integer cutoff,
                       boolean allDay, boolean intermediary, boolean nonbank, int risk, double speed) {
    }

    public record BeneShare(String bankId, double share) {
    }

    public record Corridor(String id, String destCountry, String currency, String localRail, int countryRisk,
                           String clearingBank, List<BeneShare> beneBanks, List<String> templates) {
    }

    /**
     * hops may contain CLR (the corridor's clearing bank) and BENE (the payment's beneficiary bank).
     * cap is null for no per-transfer cap; batchHop is null when no hop waits for the Thursday netting batch.
     */
    public record Template(String id, String name, String via, List<String> hops, double[] fees, double fxBpsStandard,
                           double fxBpsLarge, double[] procHours, double stpRate, Double cap, boolean retailOnly,
                           boolean nonBank, boolean instantPayout, Integer batchHop, boolean gpi, String note) {
    }

    public record CauseCode(String code, String category, String label) {
    }

    /** A portal incident reproduced as an explicit case; repairCause and repairHours are null when none. */
    public record SeedIncident(String date, String corridor, String template, double amount, String client,
                               String repairCause, Double repairHours) {
    }

    public record FxAnchor(double mid, double volatility) {
    }

    public static final List<Currency> CURRENCIES = List.of(
            new Currency("USD", "US dollar", 2, 0),
            new Currency("JPY", "Japanese yen", 0, 0),
            new Currency("INR", "Indian rupee", 2, 0),
            new Currency("AED", "UAE dirham", 2, 1),
            new Currency("SAR", "Saudi riyal", 2, 1),
            new Currency("EUR", "Euro", 2, 0));

    public static final Map<String, Country> COUNTRIES = new LinkedHashMap<>();
    public static final Map<String, Bank> BANKS = new LinkedHashMap<>();
    public static final Map<String, Corridor> CORRIDORS = new LinkedHashMap<>();
    public static final Map<String, Template> TEMPLATES = new LinkedHashMap<>();
    public static final Map<String, FxAnchor> FX_ANCHOR = new LinkedHashMap<>();

    /** Hop most likely to raise an information request, per template (index into hops). */
    public static final Map<String, Integer> REPAIR_HOP =
            Map.of("EXP", 2, "ASIA", 2, "LDN", 2, "GULF", 2, "PAY", 2, "ECO", 3);

    public static final List<CauseCode> CAUSE_CODES = List.of(
            new CauseCode("CAL_WEEKEND", "calendar", "Correspondent or beneficiary weekend"),
            new CauseCode("CAL_HOLIDAY", "calendar", "Public or bank holiday en route"),
            new CauseCode("CUTOFF_MISSED", "cutoff", "Arrived after a bank's cut-off"),
            new CauseCode("BATCH_WAIT", "batch", "Waited for the next netting batch"),
            new CauseCode("RFI_PURPOSE", "repair", "Information request: purpose of payment"),
            new CauseCode("RFI_ADDRESS", "repair", "Information request: structured address incomplete"),
            new CauseCode("RFI_NAME", "repair", "Information request: beneficiary name check"),
            new CauseCode("RFI_DOCS", "repair", "Information request: supporting documents"),
            new CauseCode("RET_ACCOUNT_CLOSED", "return", "Returned: beneficiary account closed"),
            new CauseCode("RET_NAME_MISMATCH", "return", "Returned: name does not match account"),
            new CauseCode("RET_INVALID_ACCOUNT", "return", "Returned: invalid account number"));

    public static final List<String> ANNOTATORS = List.of("R. Mehta", "L. Ortega", "K. Brooks", "A. Fischer");

    /** Portal incidents reproduced as explicit cases (released 14:00 ET). */
    public static final List<SeedIncident> SEEDS = List.of(
            new SeedIncident("2025-10-09", "USD-JPY", "GULF", 900000, "FI", null, null),
            new SeedIncident("2026-04-28", "USD-JPY", "EXP", 15000, "Retail", null, null),
            new SeedIncident("2026-08-06", "USD-JPY", "ASIA", 42000, "Retail", "RFI_PURPOSE", 6.0),
            new SeedIncident("2026-09-17", "USD-JPY", "ECO", 600, "Retail", null, null),
            new SeedIncident("2026-09-11", "USD-INR", "EXP", 7000, "Retail", null, null),
            new SeedIncident("2026-07-02", "USD-AED", "GULF", 120000, "FI", null, null),
            new SeedIncident("2026-06-11", "USD-SAR", "EXP", 30000, "Retail", null, null),
            new SeedIncident("2026-08-28", "USD-EUR", "LDN", 9000, "Retail", null, null));

    // Synthetic names (no real people or companies intended)
    public static final Map<String, List<List<String>>> BENE_PERSON = new LinkedHashMap<>();
    public static final Map<String, List<String>> BENE_COMPANY = new LinkedHashMap<>();
    public static final List<String> SENDER_FIRST = List.of("James", "Maria", "Robert", "Linda", "Michael", "Priya",
            "David", "Aiko", "Omar", "Sarah", "Daniel", "Elena", "Raj", "Grace", "Thomas", "Nadia");
    public static final List<String> SENDER_LAST = List.of("Miller", "Johnson", "Patel", "Nguyen", "Garcia", "Smith",
            "Khan", "Brown", "Tanaka", "Schmidt", "Lopez", "Wilson", "Iyer", "Anderson");
    public static final List<String> FI_CLIENTS = List.of(
            "Great Lakes Machine Tools Inc", "Maumee Valley Foods LLC", "Erie Shore Logistics Inc",
            "Buckeye Medical Devices Inc", "Toledo Glassworks Co", "Ohio River Plastics LLC", "Cuyahoga Software Inc",
            "Sandusky Marine Supply Co", "Lakeshore Credit Union", "Northwest Ohio Community Bank",
            "Heartland Farm Cooperative", "Midwest Auto Components Inc", "Findlay Precision Castings Inc",
            "Lima Industrial Controls LLC", "Fremont Packaging Corp", "Bowling Green Biotech Inc",
            "Perrysburg Steel Fabricators", "Defiance Electrical Supply", "Wood County Health System",
            "Maumee Bay Hospitality Group");

    private Reference() {
    }

    public static Country country(String code) {
        return COUNTRIES.get(code);
    }

    public static ZoneId zone(String countryCode) {
        return ZoneId.of(COUNTRIES.get(countryCode).tzName());
    }

    public static Bank bank(String id) {
        return BANKS.get(id);
    }

    public static Corridor corridor(String id) {
        return CORRIDORS.get(id);
    }

    public static Template template(String id) {
        return TEMPLATES.get(id);
    }

    public static int currencyDecimals(String code) {
        return CURRENCIES.stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().decimals();
    }

    /** The cause's label without its "Returned: " or "Information request: " prefix. */
    public static String causeLabel(String code) {
        String label = CAUSE_CODES.stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().label();
        int i = label.indexOf(": ");
        return i < 0 ? label : label.substring(i + 2);
    }

    // ------------------------------------------------------------------ data
    private static void country(String code, String name, String tz, Set<Integer> weekend, String currency, String rule) {
        COUNTRIES.put(code, new Country(code, name, tz, weekend, currency, rule));
    }

    private static void bank(String id, String name, String bic, String city, String country, String role,
                             Integer cutoff, int allDay, int intermediary, int nonbank, int risk, double speed) {
        BANKS.put(id, new Bank(id, name, bic, city, country, role, cutoff, allDay == 1, intermediary == 1,
                nonbank == 1, risk, speed));
    }

    private static void corridor(String id, String dest, String ccy, String rail, int risk, String clearing,
                                 List<BeneShare> bene, List<String> templates) {
        CORRIDORS.put(id, new Corridor(id, dest, ccy, rail, risk, clearing, bene, templates));
    }

    private static void template(String id, String name, String via, List<String> hops, double[] fees, double bpsStd,
                                 double bpsLarge, double[] proc, double stp, Double cap, int retailOnly, int nonBank,
                                 int instant, Integer batchHop, int gpi, String note) {
        TEMPLATES.put(id, new Template(id, name, via, hops, fees, bpsStd, bpsLarge, proc, stp, cap, retailOnly == 1,
                nonBank == 1, instant == 1, batchHop, gpi == 1, note));
    }

    private static BeneShare b(String id, double share) {
        return new BeneShare(id, share);
    }

    private static List<String> names(String... n) {
        return List.of(n);
    }

    static {
        country("US", "United States", "America/New_York", Set.of(0, 6), "USD", null);
        country("JP", "Japan", "Asia/Tokyo", Set.of(0, 6), "JPY", null);
        country("SG", "Singapore", "Asia/Singapore", Set.of(0, 6), null, null);
        country("GB", "United Kingdom", "Europe/London", Set.of(0, 6), null, null);
        country("BH", "Bahrain", "Asia/Bahrain", Set.of(5, 6), null, null);
        country("IN", "India", "Asia/Kolkata", Set.of(0), "INR", "IN_2ND_4TH_SAT");
        country("AE", "UAE", "Asia/Dubai", Set.of(0, 6), "AED", null);
        country("SA", "Saudi Arabia", "Asia/Riyadh", Set.of(5, 6), "SAR", null);
        country("DE", "Germany", "Europe/Berlin", Set.of(0, 6), "EUR", null);

        bank("ORIG", "Lakeshore Community Bank", "LKSBUS41", "Toledo, Ohio", "US", "Ordering bank", 16, 0, 0, 0, 1, 1.0);
        bank("NY1", "Hudson Meridian Bank", "HDMBUS33", "New York", "US", "USD correspondent", 17, 0, 1, 0, 1, 1.0);
        bank("NY2", "Atlantic Union Clearing", "AUCLUS3N", "New York", "US", "USD correspondent", 18, 0, 1, 0, 1, 1.0);
        bank("SG", "Lion City Commerce Bank", "LCCBSGSG", "Singapore", "SG", "Asia hub", 15, 0, 1, 0, 1, 1.0);
        bank("LN", "Thames Commerce Bank", "THCBGB2L", "London", "GB", "Europe hub", 16, 0, 1, 0, 1, 1.0);
        bank("BH", "Gulf Crescent Bank", "GCBKBHBM", "Manama", "BH", "Gulf hub", 13, 0, 1, 0, 2, 1.1);
        bank("PAY", "Kite Payout Network", "API", "Licensed MSB, San Francisco", "US", "Payout partner", null, 1, 1, 1, 2, 1.0);
        // local clearing banks
        bank("JP_CLR", "Nihon Trust Bank", "NHTBJPJT", "Tokyo", "JP", "JPY clearing bank", 15, 0, 0, 0, 1, 1.0);
        bank("IN_CLR", "Deccan National Bank", "DNBKINBB", "Mumbai", "IN", "INR clearing bank", 15, 0, 0, 0, 2, 1.1);
        bank("AE_CLR", "Emirates Pearl Bank", "EPBKAEAD", "Dubai", "AE", "AED clearing bank", 14, 0, 0, 0, 2, 1.0);
        bank("SA_CLR", "Najd Commercial Bank", "NJCBSARI", "Riyadh", "SA", "SAR clearing bank", 14, 0, 0, 0, 2, 1.0);
        bank("DE_CLR", "Rheinland Handelsbank", "RHHBDEFF", "Frankfurt", "DE", "EUR clearing bank", 16, 0, 0, 0, 1, 1.0);
        // beneficiary banks (first of each country matches the portal)
        bank("JP_B1", "Shirakawa Shinkin Bank", "SHKWJPJK", "Kyoto", "JP", "Beneficiary bank", 15, 0, 0, 0, 1, 1.0);
        bank("JP_B2", "Minato Regional Bank", "MNRBJPJY", "Yokohama", "JP", "Beneficiary bank", 15, 0, 0, 0, 1, 0.9);
        bank("JP_B3", "Hokkai Credit Bank", "HKCBJPJS", "Sapporo", "JP", "Beneficiary bank", 15, 0, 0, 0, 1, 1.25);
        bank("IN_B1", "Konkan Cooperative Bank", "KNKCINBB", "Ratnagiri", "IN", "Beneficiary bank", 15, 0, 0, 0, 2, 1.3);
        bank("IN_B2", "Malabar Coast Bank", "MLCBINBK", "Kochi", "IN", "Beneficiary bank", 15, 0, 0, 0, 2, 1.0);
        bank("IN_B3", "Narmada Valley Bank", "NMVBINBI", "Indore", "IN", "Beneficiary bank", 15, 0, 0, 0, 2, 1.15);
        bank("AE_B1", "Sharjah Coastal Bank", "SHCBAEAS", "Sharjah", "AE", "Beneficiary bank", 14, 0, 0, 0, 2, 1.0);
        bank("AE_B2", "Al Ain Oasis Bank", "AAOBAEAA", "Al Ain", "AE", "Beneficiary bank", 14, 0, 0, 0, 2, 1.15);
        bank("AE_B3", "Creek Merchant Bank", "CRMBAEAD", "Dubai", "AE", "Beneficiary bank", 14, 0, 0, 0, 2, 0.85);
        bank("SA_B1", "Asir Regional Bank", "ASRBSAJE", "Abha", "SA", "Beneficiary bank", 14, 0, 0, 0, 2, 1.2);
        bank("SA_B2", "Hejaz Trading Bank", "HJTBSAJE", "Jeddah", "SA", "Beneficiary bank", 14, 0, 0, 0, 2, 1.0);
        bank("SA_B3", "Eastern Gulf Bank", "EGBKSADM", "Dammam", "SA", "Beneficiary bank", 14, 0, 0, 0, 2, 1.05);
        bank("DE_B1", "Bodensee Privatbank", "BDPBDE6K", "Konstanz", "DE", "Beneficiary bank", 16, 0, 0, 0, 1, 1.0);
        bank("DE_B2", "Elbe Sparbank", "ELSPDEHH", "Hamburg", "DE", "Beneficiary bank", 16, 0, 0, 0, 1, 0.9);
        bank("DE_B3", "Isar Volksbank", "ISVBDEMM", "Munich", "DE", "Beneficiary bank", 16, 0, 0, 0, 1, 1.1);

        corridor("USD-JPY", "JP", "JPY", "Zengin", 1, "JP_CLR",
                List.of(b("JP_B1", .45), b("JP_B2", .35), b("JP_B3", .20)), List.of("EXP", "ASIA", "LDN", "GULF", "PAY", "ECO"));
        corridor("USD-INR", "IN", "INR", "NEFT / IMPS", 2, "IN_CLR",
                List.of(b("IN_B1", .30), b("IN_B2", .40), b("IN_B3", .30)), List.of("EXP", "ASIA", "LDN", "GULF", "PAY", "ECO"));
        corridor("USD-AED", "AE", "AED", "UAEFTS", 2, "AE_CLR",
                List.of(b("AE_B1", .40), b("AE_B2", .25), b("AE_B3", .35)), List.of("EXP", "LDN", "GULF", "PAY"));
        corridor("USD-SAR", "SA", "SAR", "SARIE", 2, "SA_CLR",
                List.of(b("SA_B1", .30), b("SA_B2", .40), b("SA_B3", .30)), List.of("EXP", "LDN", "GULF"));
        corridor("USD-EUR", "DE", "EUR", "SEPA Instant", 1, "DE_CLR",
                List.of(b("DE_B1", .35), b("DE_B2", .35), b("DE_B3", .30)), List.of("EXP", "LDN", "PAY"));

        // FX anchor (mid, units per USD) at the end of the window, and annualised volatility
        FX_ANCHOR.put("JPY", new FxAnchor(147.85, 0.095));
        FX_ANCHOR.put("INR", new FxAnchor(88.40, 0.045));
        FX_ANCHOR.put("AED", new FxAnchor(3.6725, 0.0));
        FX_ANCHOR.put("SAR", new FxAnchor(3.75, 0.0));
        FX_ANCHOR.put("EUR", new FxAnchor(0.8560, 0.075));

        template("EXP", "Express gpi", "New York direct", List.of("ORIG", "NY1", "CLR", "BENE"),
                new double[] {30, 18, 12, 0}, 38, 15, new double[] {0.5, 1.5, 2, 1.5}, .972, null, 0, 0, 0, null, 1,
                "Fewest hops. Hudson Meridian holds a direct nostro with the local clearing bank.");
        template("ASIA", "Asia hub", "Singapore", List.of("ORIG", "NY2", "SG", "CLR", "BENE"),
                new double[] {22, 10, 8, 10, 0}, 22, 9, new double[] {1, 2.5, 4, 3, 2}, .948, null, 0, 0, 0, null, 1,
                "Lion City converts at Asia-session prices with deep JPY and INR liquidity.");
        template("LDN", "Europe hub", "London", List.of("ORIG", "NY1", "LN", "CLR", "BENE"),
                new double[] {22, 14, 12, 10, 0}, 27, 11, new double[] {1, 2, 3, 3, 2}, .955, null, 0, 0, 0, null, 1,
                "Thames Commerce prices off the London session, with strong EUR and AED books.");
        template("GULF", "Gulf hub", "Manama", List.of("ORIG", "NY2", "BH", "CLR", "BENE"),
                new double[] {20, 10, 6, 10, 0}, 16, 6, new double[] {1, 2.5, 5, 4, 2}, .931, null, 0, 0, 0, null, 1,
                "Tightest FX for large tickets. Gulf Crescent works a Friday-Saturday weekend.");
        template("PAY", "Payout partner", "prefunded local account", List.of("ORIG", "PAY", "BENE"),
                new double[] {5, 0, 0}, 50, 50, new double[] {0.3, 0.7, 0.5}, .986, 5000.0, 1, 1, 1, null, 0,
                "Pays out from a prefunded local account on the domestic instant rail. Retail only, capped per transfer.");
        template("ECO", "Economy batch", "weekly netting, Singapore", List.of("ORIG", "NY2", "SG", "CLR", "BENE"),
                new double[] {2, 1, 0, 1, 0}, 10, 10, new double[] {1, 2, 72, 8, 4}, .962, 25000.0, 0, 0, 0, 2, 0,
                "Netted with other flows in Thursday's batch run. Lowest cost, slowest credit.");

        BENE_PERSON.put("USD-JPY", List.of(
                names("Kenji", "Haruto", "Yui", "Sakura", "Ren", "Aoi", "Takumi", "Mei", "Sota", "Hina"),
                names("Morita", "Tanaka", "Sato", "Kobayashi", "Watanabe", "Ito", "Nakamura", "Yamada", "Kato", "Mori")));
        BENE_PERSON.put("USD-INR", List.of(
                names("Aarav", "Priya", "Rohan", "Ananya", "Vikram", "Isha", "Karan", "Meera", "Arjun", "Divya"),
                names("Sharma", "Iyer", "Nair", "Kulkarni", "Reddy", "Gupta", "Menon", "Desai", "Rao", "Joshi")));
        BENE_PERSON.put("USD-AED", List.of(
                names("Omar", "Fatima", "Rashid", "Mariam", "Khalid", "Noura", "Saeed", "Aisha", "Hamdan", "Layla"),
                names("Al Mansoori", "Al Hashimi", "Al Suwaidi", "Al Nuaimi", "Al Ketbi", "Al Falasi", "Al Shamsi", "Al Marri")));
        BENE_PERSON.put("USD-SAR", List.of(
                names("Abdullah", "Sara", "Faisal", "Reem", "Turki", "Hessa", "Nasser", "Lama", "Majed", "Dana"),
                names("Al Qahtani", "Al Harbi", "Al Otaibi", "Al Ghamdi", "Al Zahrani", "Al Dosari", "Al Shehri", "Al Mutairi")));
        BENE_PERSON.put("USD-EUR", List.of(
                names("Lukas", "Anna", "Felix", "Lena", "Jonas", "Marie", "Paul", "Sophie", "Leon", "Clara"),
                names("Becker", "Schmidt", "Weber", "Hoffmann", "Wagner", "Krause", "Richter", "Neumann", "Wolf", "Braun")));

        BENE_COMPANY.put("USD-JPY", names("Hokuriku Precision Components KK", "Seto Ceramics Works KK",
                "Kansai Fine Instruments KK", "Tohoku Textile Mills KK", "Kyushu Marine Parts KK"));
        BENE_COMPANY.put("USD-INR", names("Western Ghats Engineering Pvt Ltd", "Coromandel Software Services Pvt Ltd",
                "Deccan Agro Exports Pvt Ltd", "Indus Pharma Ingredients Ltd", "Saffron Textiles Pvt Ltd"));
        BENE_COMPANY.put("USD-AED", names("Desert Rose Logistics FZE", "Creekside Trading LLC",
                "Pearl Coast Building Materials LLC", "Falcon Aviation Services FZE", "Oasis Foodstuff Trading LLC"));
        BENE_COMPANY.put("USD-SAR", names("Najd Industrial Supplies Co", "Red Sea Packaging Co",
                "Hejaz Medical Equipment Co", "Eastern Province Steel Co", "Asir Agricultural Co"));
        BENE_COMPANY.put("USD-EUR", names("Bodensee Maschinenbau GmbH", "Elbe Logistik GmbH", "Rheintal Chemie GmbH",
                "Isar Medizintechnik GmbH", "Schwarzwald Werkzeuge GmbH"));
    }
}
