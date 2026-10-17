"""CorridorIQ reference data (test data, mirrors the demo portal).

All banks, BICs, fees and FX levels are fictional. This module is the single source the
generator loads into SQLite; the MVP 1 route engine should read the same tables.
"""

ORDERING_BANK = "ORIG"
LARGE_TICKET_USD = 250_000
FI_FIRST_HOP_FEE_CAP = 15.0

CURRENCIES = [
    # code, name, decimals, pegged_to_usd
    ("USD", "US dollar", 2, 0),
    ("JPY", "Japanese yen", 0, 0),
    ("INR", "Indian rupee", 2, 0),
    ("AED", "UAE dirham", 2, 1),
    ("SAR", "Saudi riyal", 2, 1),
    ("EUR", "Euro", 2, 0),
]

# code: name, IANA zone, weekend days (0=Sun..6=Sat), currency, extra rule
COUNTRIES = {
    "US": ("United States", "America/New_York", (0, 6), "USD", None),
    "JP": ("Japan", "Asia/Tokyo", (0, 6), "JPY", None),
    "SG": ("Singapore", "Asia/Singapore", (0, 6), None, None),
    "GB": ("United Kingdom", "Europe/London", (0, 6), None, None),
    "BH": ("Bahrain", "Asia/Bahrain", (5, 6), None, None),
    "IN": ("India", "Asia/Kolkata", (0,), "INR", "IN_2ND_4TH_SAT"),
    "AE": ("UAE", "Asia/Dubai", (0, 6), "AED", None),
    "SA": ("Saudi Arabia", "Asia/Riyadh", (5, 6), "SAR", None),
    "DE": ("Germany", "Europe/Berlin", (0, 6), "EUR", None),
}

# Arguments for the `holidays` library (financial centre of each bank)
HOLIDAY_ARGS = {
    "US": {}, "JP": {}, "SG": {}, "GB": {"subdiv": "ENG"}, "BH": {},
    "IN": {"subdiv": "MH"}, "AE": {}, "SA": {}, "DE": {"subdiv": "HE"},
}

# bank_id: name, bic, city, country, role, cutoff hour (local), all_day, intermediary, nonbank, risk, speed
# `speed` is hidden ground truth used only to realise history; it is not written to the database,
# so the simulator does not know it and a model has to learn each bank's real pace from outcomes.
BANKS = {
    "ORIG":   ("Lakeshore Community Bank", "LKSBUS41", "Toledo, Ohio", "US", "Ordering bank", 16, 0, 0, 0, 1, 1.0),
    "NY1":    ("Hudson Meridian Bank", "HDMBUS33", "New York", "US", "USD correspondent", 17, 0, 1, 0, 1, 1.0),
    "NY2":    ("Atlantic Union Clearing", "AUCLUS3N", "New York", "US", "USD correspondent", 18, 0, 1, 0, 1, 1.0),
    "SG":     ("Lion City Commerce Bank", "LCCBSGSG", "Singapore", "SG", "Asia hub", 15, 0, 1, 0, 1, 1.0),
    "LN":     ("Thames Commerce Bank", "THCBGB2L", "London", "GB", "Europe hub", 16, 0, 1, 0, 1, 1.0),
    "BH":     ("Gulf Crescent Bank", "GCBKBHBM", "Manama", "BH", "Gulf hub", 13, 0, 1, 0, 2, 1.1),
    "PAY":    ("Kite Payout Network", "API", "Licensed MSB, San Francisco", "US", "Payout partner", None, 1, 1, 1, 2, 1.0),
    # local clearing banks
    "JP_CLR": ("Nihon Trust Bank", "NHTBJPJT", "Tokyo", "JP", "JPY clearing bank", 15, 0, 0, 0, 1, 1.0),
    "IN_CLR": ("Deccan National Bank", "DNBKINBB", "Mumbai", "IN", "INR clearing bank", 15, 0, 0, 0, 2, 1.1),
    "AE_CLR": ("Emirates Pearl Bank", "EPBKAEAD", "Dubai", "AE", "AED clearing bank", 14, 0, 0, 0, 2, 1.0),
    "SA_CLR": ("Najd Commercial Bank", "NJCBSARI", "Riyadh", "SA", "SAR clearing bank", 14, 0, 0, 0, 2, 1.0),
    "DE_CLR": ("Rheinland Handelsbank", "RHHBDEFF", "Frankfurt", "DE", "EUR clearing bank", 16, 0, 0, 0, 1, 1.0),
    # beneficiary banks (first of each country matches the portal)
    "JP_B1":  ("Shirakawa Shinkin Bank", "SHKWJPJK", "Kyoto", "JP", "Beneficiary bank", 15, 0, 0, 0, 1, 1.0),
    "JP_B2":  ("Minato Regional Bank", "MNRBJPJY", "Yokohama", "JP", "Beneficiary bank", 15, 0, 0, 0, 1, 0.9),
    "JP_B3":  ("Hokkai Credit Bank", "HKCBJPJS", "Sapporo", "JP", "Beneficiary bank", 15, 0, 0, 0, 1, 1.25),
    "IN_B1":  ("Konkan Cooperative Bank", "KNKCINBB", "Ratnagiri", "IN", "Beneficiary bank", 15, 0, 0, 0, 2, 1.3),
    "IN_B2":  ("Malabar Coast Bank", "MLCBINBK", "Kochi", "IN", "Beneficiary bank", 15, 0, 0, 0, 2, 1.0),
    "IN_B3":  ("Narmada Valley Bank", "NMVBINBI", "Indore", "IN", "Beneficiary bank", 15, 0, 0, 0, 2, 1.15),
    "AE_B1":  ("Sharjah Coastal Bank", "SHCBAEAS", "Sharjah", "AE", "Beneficiary bank", 14, 0, 0, 0, 2, 1.0),
    "AE_B2":  ("Al Ain Oasis Bank", "AAOBAEAA", "Al Ain", "AE", "Beneficiary bank", 14, 0, 0, 0, 2, 1.15),
    "AE_B3":  ("Creek Merchant Bank", "CRMBAEAD", "Dubai", "AE", "Beneficiary bank", 14, 0, 0, 0, 2, 0.85),
    "SA_B1":  ("Asir Regional Bank", "ASRBSAJE", "Abha", "SA", "Beneficiary bank", 14, 0, 0, 0, 2, 1.2),
    "SA_B2":  ("Hejaz Trading Bank", "HJTBSAJE", "Jeddah", "SA", "Beneficiary bank", 14, 0, 0, 0, 2, 1.0),
    "SA_B3":  ("Eastern Gulf Bank", "EGBKSADM", "Dammam", "SA", "Beneficiary bank", 14, 0, 0, 0, 2, 1.05),
    "DE_B1":  ("Bodensee Privatbank", "BDPBDE6K", "Konstanz", "DE", "Beneficiary bank", 16, 0, 0, 0, 1, 1.0),
    "DE_B2":  ("Elbe Sparbank", "ELSPDEHH", "Hamburg", "DE", "Beneficiary bank", 16, 0, 0, 0, 1, 0.9),
    "DE_B3":  ("Isar Volksbank", "ISVBDEMM", "Munich", "DE", "Beneficiary bank", 16, 0, 0, 0, 1, 1.1),
}

# corridor_id: dest country, currency, local rail, country risk, clearing bank, beneficiary banks with shares, templates
CORRIDORS = {
    "USD-JPY": ("JP", "JPY", "Zengin", 1, "JP_CLR", [("JP_B1", .45), ("JP_B2", .35), ("JP_B3", .20)], ["EXP", "ASIA", "LDN", "GULF", "PAY", "ECO"]),
    "USD-INR": ("IN", "INR", "NEFT / IMPS", 2, "IN_CLR", [("IN_B1", .30), ("IN_B2", .40), ("IN_B3", .30)], ["EXP", "ASIA", "LDN", "GULF", "PAY", "ECO"]),
    "USD-AED": ("AE", "AED", "UAEFTS", 2, "AE_CLR", [("AE_B1", .40), ("AE_B2", .25), ("AE_B3", .35)], ["EXP", "LDN", "GULF", "PAY"]),
    "USD-SAR": ("SA", "SAR", "SARIE", 2, "SA_CLR", [("SA_B1", .30), ("SA_B2", .40), ("SA_B3", .30)], ["EXP", "LDN", "GULF"]),
    "USD-EUR": ("DE", "EUR", "SEPA Instant", 1, "DE_CLR", [("DE_B1", .35), ("DE_B2", .35), ("DE_B3", .30)], ["EXP", "LDN", "PAY"]),
}

# FX anchor (mid, units per USD) at the end of the window, and annualised volatility
FX_ANCHOR = {"JPY": (147.85, 0.095), "INR": (88.40, 0.045), "AED": (3.6725, 0.0), "SAR": (3.75, 0.0), "EUR": (0.8560, 0.075)}

# template_id: name, via, hops, fees, fx bps std, fx bps large, proc hours, stp, cap, retail only, nonbank, instant, batch hop, gpi, note
TEMPLATES = {
    "EXP":  ("Express gpi", "New York direct", ["ORIG", "NY1", "CLR", "BENE"], [30, 18, 12, 0], 38, 15, [0.5, 1.5, 2, 1.5], .972, None, 0, 0, 0, None, 1,
             "Fewest hops. Hudson Meridian holds a direct nostro with the local clearing bank."),
    "ASIA": ("Asia hub", "Singapore", ["ORIG", "NY2", "SG", "CLR", "BENE"], [22, 10, 8, 10, 0], 22, 9, [1, 2.5, 4, 3, 2], .948, None, 0, 0, 0, None, 1,
             "Lion City converts at Asia-session prices with deep JPY and INR liquidity."),
    "LDN":  ("Europe hub", "London", ["ORIG", "NY1", "LN", "CLR", "BENE"], [22, 14, 12, 10, 0], 27, 11, [1, 2, 3, 3, 2], .955, None, 0, 0, 0, None, 1,
             "Thames Commerce prices off the London session, with strong EUR and AED books."),
    "GULF": ("Gulf hub", "Manama", ["ORIG", "NY2", "BH", "CLR", "BENE"], [20, 10, 6, 10, 0], 16, 6, [1, 2.5, 5, 4, 2], .931, None, 0, 0, 0, None, 1,
             "Tightest FX for large tickets. Gulf Crescent works a Friday-Saturday weekend."),
    "PAY":  ("Payout partner", "prefunded local account", ["ORIG", "PAY", "BENE"], [5, 0, 0], 50, 50, [0.3, 0.7, 0.5], .986, 5000, 1, 1, 1, None, 0,
             "Pays out from a prefunded local account on the domestic instant rail. Retail only, capped per transfer."),
    "ECO":  ("Economy batch", "weekly netting, Singapore", ["ORIG", "NY2", "SG", "CLR", "BENE"], [2, 1, 0, 1, 0], 10, 10, [1, 2, 72, 8, 4], .962, 25000, 0, 0, 0, 2, 0,
             "Netted with other flows in Thursday's batch run. Lowest cost, slowest credit."),
}

# Hop most likely to raise an information request, per template (index into hops)
REPAIR_HOP = {"EXP": 2, "ASIA": 2, "LDN": 2, "GULF": 2, "PAY": 2, "ECO": 3}

CAUSE_CODES = [
    ("CAL_WEEKEND", "calendar", "Correspondent or beneficiary weekend"),
    ("CAL_HOLIDAY", "calendar", "Public or bank holiday en route"),
    ("CUTOFF_MISSED", "cutoff", "Arrived after a bank's cut-off"),
    ("BATCH_WAIT", "batch", "Waited for the next netting batch"),
    ("RFI_PURPOSE", "repair", "Information request: purpose of payment"),
    ("RFI_ADDRESS", "repair", "Information request: structured address incomplete"),
    ("RFI_NAME", "repair", "Information request: beneficiary name check"),
    ("RFI_DOCS", "repair", "Information request: supporting documents"),
    ("RET_ACCOUNT_CLOSED", "return", "Returned: beneficiary account closed"),
    ("RET_NAME_MISMATCH", "return", "Returned: name does not match account"),
    ("RET_INVALID_ACCOUNT", "return", "Returned: invalid account number"),
]

PURPOSES = {"EDUC": "Education", "OTHR": "Family support", "SUPP": "Supplier payment", "INTC": "Intra-company", "SALA": "Salary"}

ANNOTATORS = ["R. Mehta", "L. Ortega", "K. Brooks", "A. Fischer"]

# Portal incidents reproduced as explicit cases (release 14:00 ET unless stated)
SEEDS = [
    {"date": "2025-10-09", "corridor": "USD-JPY", "tpl": "GULF", "amount": 900000, "client": "FI"},
    {"date": "2026-04-28", "corridor": "USD-JPY", "tpl": "EXP", "amount": 15000, "client": "Retail"},
    {"date": "2026-08-06", "corridor": "USD-JPY", "tpl": "ASIA", "amount": 42000, "client": "Retail", "repair": ("RFI_PURPOSE", 6.0)},
    {"date": "2026-09-17", "corridor": "USD-JPY", "tpl": "ECO", "amount": 600, "client": "Retail"},
    {"date": "2026-09-11", "corridor": "USD-INR", "tpl": "LDN", "amount": 7000, "client": "Retail"},
    {"date": "2026-07-02", "corridor": "USD-AED", "tpl": "GULF", "amount": 120000, "client": "FI"},
    {"date": "2026-06-11", "corridor": "USD-SAR", "tpl": "EXP", "amount": 30000, "client": "Retail"},
    {"date": "2026-08-28", "corridor": "USD-EUR", "tpl": "LDN", "amount": 9000, "client": "Retail"},
]

# Synthetic names (no real people or companies intended)
BENE_PERSON = {
    "USD-JPY": (["Kenji", "Haruto", "Yui", "Sakura", "Ren", "Aoi", "Takumi", "Mei", "Sota", "Hina"],
                ["Morita", "Tanaka", "Sato", "Kobayashi", "Watanabe", "Ito", "Nakamura", "Yamada", "Kato", "Mori"]),
    "USD-INR": (["Aarav", "Priya", "Rohan", "Ananya", "Vikram", "Isha", "Karan", "Meera", "Arjun", "Divya"],
                ["Sharma", "Iyer", "Nair", "Kulkarni", "Reddy", "Gupta", "Menon", "Desai", "Rao", "Joshi"]),
    "USD-AED": (["Omar", "Fatima", "Rashid", "Mariam", "Khalid", "Noura", "Saeed", "Aisha", "Hamdan", "Layla"],
                ["Al Mansoori", "Al Hashimi", "Al Suwaidi", "Al Nuaimi", "Al Ketbi", "Al Falasi", "Al Shamsi", "Al Marri"]),
    "USD-SAR": (["Abdullah", "Sara", "Faisal", "Reem", "Turki", "Hessa", "Nasser", "Lama", "Majed", "Dana"],
                ["Al Qahtani", "Al Harbi", "Al Otaibi", "Al Ghamdi", "Al Zahrani", "Al Dosari", "Al Shehri", "Al Mutairi"]),
    "USD-EUR": (["Lukas", "Anna", "Felix", "Lena", "Jonas", "Marie", "Paul", "Sophie", "Leon", "Clara"],
                ["Becker", "Schmidt", "Weber", "Hoffmann", "Wagner", "Krause", "Richter", "Neumann", "Wolf", "Braun"]),
}
BENE_COMPANY = {
    "USD-JPY": ["Hokuriku Precision Components KK", "Seto Ceramics Works KK", "Kansai Fine Instruments KK", "Tohoku Textile Mills KK", "Kyushu Marine Parts KK"],
    "USD-INR": ["Western Ghats Engineering Pvt Ltd", "Coromandel Software Services Pvt Ltd", "Deccan Agro Exports Pvt Ltd", "Indus Pharma Ingredients Ltd", "Saffron Textiles Pvt Ltd"],
    "USD-AED": ["Desert Rose Logistics FZE", "Creekside Trading LLC", "Pearl Coast Building Materials LLC", "Falcon Aviation Services FZE", "Oasis Foodstuff Trading LLC"],
    "USD-SAR": ["Najd Industrial Supplies Co", "Red Sea Packaging Co", "Hejaz Medical Equipment Co", "Eastern Province Steel Co", "Asir Agricultural Co"],
    "USD-EUR": ["Bodensee Maschinenbau GmbH", "Elbe Logistik GmbH", "Rheintal Chemie GmbH", "Isar Medizintechnik GmbH", "Schwarzwald Werkzeuge GmbH"],
}
SENDER_FIRST = ["James", "Maria", "Robert", "Linda", "Michael", "Priya", "David", "Aiko", "Omar", "Sarah", "Daniel", "Elena", "Raj", "Grace", "Thomas", "Nadia"]
SENDER_LAST = ["Miller", "Johnson", "Patel", "Nguyen", "Garcia", "Smith", "Khan", "Brown", "Tanaka", "Schmidt", "Lopez", "Wilson", "Iyer", "Anderson"]
FI_CLIENTS = [
    "Great Lakes Machine Tools Inc", "Maumee Valley Foods LLC", "Erie Shore Logistics Inc", "Buckeye Medical Devices Inc",
    "Toledo Glassworks Co", "Ohio River Plastics LLC", "Cuyahoga Software Inc", "Sandusky Marine Supply Co",
    "Lakeshore Credit Union", "Northwest Ohio Community Bank", "Heartland Farm Cooperative", "Midwest Auto Components Inc",
    "Findlay Precision Castings Inc", "Lima Industrial Controls LLC", "Fremont Packaging Corp", "Bowling Green Biotech Inc",
    "Perrysburg Steel Fabricators", "Defiance Electrical Supply", "Wood County Health System", "Maumee Bay Hospitality Group",
]
