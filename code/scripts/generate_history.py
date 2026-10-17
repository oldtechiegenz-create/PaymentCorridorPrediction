#!/usr/bin/env python3
"""Generate synthetic CorridorIQ payment history into SQLite.

    python3 code/scripts/generate_history.py --payments 40000 --seed 42

What it produces
  * Reference data (banks, corridors, route templates, holiday calendars, FX history).
  * Settled payments from the ordering bank with a gpi-style hop timeline.
  * For every payment, EVERY route that was available, with the simulator's estimate at
    release and the outcome that route would have had. The route actually used was picked
    by a pre-CorridorIQ operator policy (calendar-blind most of the time), so the data
    contains the mistakes a ranking model should learn to avoid.
  * Ops annotations on delayed, repaired and returned payments.

Everything is synthetic and reproducible from --seed.
"""
import argparse
import hashlib
import math
import random
import sqlite3
import time as _time
import uuid
from itertools import accumulate
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path

import holidays as holidays_lib

import reference as R
from engine import (TZ, Calendar, build_holidays, calendar_conflict, day_label, naive_hours,
                    portal_dow, resolve_hops, simulate)

UTC = timezone.utc
GEN_VERSION = "1.0.0"
HERE = Path(__file__).resolve().parent
DATA_DIR = HERE.parents[1] / "Data" / "corridoriq_history"   # schema.sql and the generated .db live here

RETAIL_CORRIDOR_MIX = {"USD-INR": .40, "USD-JPY": .18, "USD-AED": .17, "USD-EUR": .13, "USD-SAR": .12}
FI_CORRIDOR_MIX = {"USD-JPY": .25, "USD-EUR": .25, "USD-AED": .20, "USD-INR": .20, "USD-SAR": .10}
RETAIL_MEDIAN = {"USD-JPY": 3000, "USD-INR": 900, "USD-AED": 1500, "USD-SAR": 1500, "USD-EUR": 2500}
RETAIL_PURPOSE = {
    "USD-JPY": {"EDUC": .45, "OTHR": .45, "SUPP": .10}, "USD-INR": {"OTHR": .70, "EDUC": .20, "SUPP": .10},
    "USD-AED": {"OTHR": .60, "SUPP": .25, "EDUC": .15}, "USD-SAR": {"OTHR": .65, "SUPP": .25, "EDUC": .10},
    "USD-EUR": {"EDUC": .35, "OTHR": .35, "SUPP": .30},
}
FI_PURPOSE = {"SUPP": .65, "INTC": .20, "SALA": .15}
CLOSURES = {("SG", date(2025, 3, 14)): "Core banking upgrade", ("BH", date(2026, 2, 10)): "Payment system outage",
            ("IN_CLR", date(2025, 11, 21)): "Settlement system maintenance"}


def iso(t: datetime) -> str:
    return t.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def fmt_h(h: float) -> str:
    return f"{h:.1f} h" if h < 48 else f"{h / 24:.1f} days"


def fmt_local(t: datetime, cc: str) -> str:
    L = t.astimezone(TZ[cc])
    return f"{day_label(L.date())[:-5]} {L:%H:%M}"


def pick(rng, weights: dict):
    return rng.choices(list(weights), weights=list(weights.values()))[0]


def round_amount(a: float) -> float:
    if a < 1000:
        return max(10.0, round(a / 10) * 10)
    if a < 10000:
        return round(a / 50) * 50
    if a < 100000:
        return round(a / 100) * 100
    return round(a / 1000) * 1000


# ------------------------------------------------------------------ FX
def fx_series(rng, start: date, end: date):
    rows, table = [], {}
    days = (end - start).days + 1
    for ccy, (anchor, vol) in R.FX_ANCHOR.items():
        sig = vol / math.sqrt(365) if vol else 0.00004
        v, vals = anchor, []
        for _ in range(days):
            vals.append(v)
            v = v * math.exp(rng.gauss(0, sig)) if vol else anchor * (1 + rng.gauss(0, sig))
        vals.reverse()  # walk backwards from the anchor so the series ends at the portal's rate
        for i, val in enumerate(vals):
            d = start + timedelta(days=i)
            dp = 2 if ccy in ("JPY", "INR") else 4
            table[(ccy, d)] = round(val, dp)
            rows.append((ccy, d.isoformat(), round(val, dp), "synthetic random walk"))
    return table, rows


# ------------------------------------------------------------------ helpers
def congestion_factor(seed: int, bank_id: str, d: date) -> float:
    """Deterministic bank-day backlog: most days 1.0, about 7% of days 1.5-3.5x."""
    hsh = hashlib.md5(f"{seed}|{bank_id}|{d}".encode()).digest()
    u, u2 = hsh[0] / 256, hsh[1] / 256
    return 1.5 + 2.0 * u2 if u < 0.07 else 1.0


def utility_labels(cands, priority, deadline_end):
    """Lower utility is better. Relevance 4 for the best realised route, then 3, 2, 1, 0."""
    el = [c for c in cands if c["eligible"]]
    if not el:
        return
    lt = [math.log(max(c["realized_hours"], 0.5)) for c in el]
    lc = [math.log(max(c["realized_cost"], 0.5)) for c in el]
    tmin, tmax, cmin, cmax = min(lt), max(lt), min(lc), max(lc)
    wt = priority / 100
    for c, a, b in zip(el, lt, lc):
        tn = (a - tmin) / (tmax - tmin) if tmax > tmin else 0.0
        cn = (b - cmin) / (cmax - cmin) if cmax > cmin else 0.0
        u = wt * tn + (1 - wt) * cn
        if c["realized_outcome"] == "Returned":
            u += 1.0
        elif deadline_end is not None and c["realized_end"] > deadline_end:
            u += 0.5
        c["utility"] = round(u, 4)
    for rank, c in enumerate(sorted(el, key=lambda c: (c["utility"], c["realized_cost"]))):
        c["relevance"] = max(0, 4 - rank)


def ops_choice(rng, cands, client_type, amount, priority):
    """Pre-CorridorIQ operator policy: habits plus a calendar-blind estimate most of the time."""
    el = [c for c in cands if c["eligible"]]
    aware = rng.random() < 0.30
    ts = [math.log(max(c["est_hours"] if aware else c["naive_hours"], 0.5)) for c in el]
    cs = [math.log(c["est_cost"]) for c in el]
    tmin, tmax, cmin, cmax = min(ts), max(ts), min(cs), max(cs)
    wt = priority / 100
    best, best_s = None, None
    for c, a, b in zip(el, ts, cs):
        tn = (a - tmin) / (tmax - tmin) if tmax > tmin else 0.0
        cn = (b - cmin) / (cmax - cmin) if cmax > cmin else 0.0
        s = wt * tn + (1 - wt) * cn
        t = c["template_id"]
        if t == "EXP":
            s -= 0.12                                   # familiar default
        if t == "GULF" and client_type == "FI" and amount >= R.LARGE_TICKET_USD:
            s -= 0.25                                   # chases the tightest large-ticket FX
        if t == "PAY":
            s += 0.08                                   # newer partner, used less
        if t == "ECO" and priority <= 20:
            s -= 0.10
        if "NY1" in c["banks"]:
            s -= 0.04                                   # preferred relationship
        s += rng.gauss(0, 0.12)
        if best_s is None or s < best_s:
            best, best_s = c, s
    return best


# ------------------------------------------------------------------ generation
def generate(args):
    rng = random.Random(args.seed)
    start, end = date.fromisoformat(args.start), date.fromisoformat(args.end)
    years = list(range(start.year - 1, end.year + 2))
    hol_table, hol_rows = build_holidays(years)
    cal = Calendar(hol_table, CLOSURES)
    fx_table, fx_rows = fx_series(rng, start - timedelta(days=120), end)

    # clients
    clients, retail_pool, retail_w, fi_pool, fi_w = [], [], [], [], []
    n_retail = max(400, int(args.payments * 0.12))
    for i in range(n_retail):
        cid = f"R{i + 1:06d}"
        home = pick(rng, RETAIL_CORRIDOR_MIX)
        seg = "Small business" if rng.random() < 0.12 else "Personal"
        name = f"{rng.choice(R.SENDER_FIRST)} {rng.choice(R.SENDER_LAST)}"
        clients.append((cid, "Retail", name, seg, home))
        retail_pool.append((cid, home, seg))
        retail_w.append(rng.paretovariate(1.4))
    for i, nm in enumerate(R.FI_CLIENTS):
        cid = f"F{i + 1:04d}"
        seg = "Financial institution" if any(k in nm for k in ("Bank", "Credit Union")) else "Corporate"
        mix = {k: v * rng.uniform(0.3, 1.7) for k, v in FI_CORRIDOR_MIX.items()}
        home = max(mix, key=mix.get)
        clients.append((cid, "FI", nm, seg, home))
        fi_pool.append((cid, mix, seg))
        fi_w.append(rng.paretovariate(1.6))

    retail_cum, fi_cum = list(accumulate(retail_w)), list(accumulate(fi_w))

    # business days and daily volume
    bdays, d = [], start
    while d <= end:
        if not cal.non_business("US", d):
            bdays.append(d)
        d += timedelta(days=1)
    span = max(1, (end - start).days)
    weights = []
    for i, d in enumerate(bdays):
        w = 0.8 + 0.45 * (d - start).days / span                     # growth
        w *= {1: 1.15, 5: 1.05}.get(portal_dow(d), 1.0)             # Monday, Friday
        nxt = [x for x in bdays[i + 1:i + 3]]
        if len(nxt) < 2 or nxt[-1].month != d.month:
            w *= 1.25                                                # month end
        weights.append(w)
    counts = [0] * len(bdays)
    n_seed = len([s for s in R.SEEDS if start <= date.fromisoformat(s["date"]) <= end])
    for idx in rng.choices(range(len(bdays)), weights=weights, k=args.payments - n_seed):
        counts[idx] += 1

    month_end_days = set()
    for i, d in enumerate(bdays):
        nxt = bdays[i + 1:i + 3]
        if len(nxt) < 2 or nxt[-1].month != d.month:
            month_end_days.add(d)
    jobs = []
    for d, n in zip(bdays, counts):
        for _ in range(n):
            jobs.append({"date": d})
    for s in R.SEEDS:
        sd = date.fromisoformat(s["date"])
        if start <= sd <= end:
            jobs.append({"date": sd, "seed": s})
    for j in jobs:
        j["month_end"] = j["date"] in month_end_days
        r = rng.random()
        hr = rng.uniform(9, 13) if r < 0.62 else rng.uniform(13, 16) if r < 0.93 else rng.uniform(16, 18)
        if "seed" in j:
            hr = j["seed"].get("hour", 14.0)
        j["hour"] = hr
    jobs.sort(key=lambda j: (j["date"], j["hour"]))

    payments, hops_rows, cand_rows, incidents = [], [], [], []
    seq_by_day = {}
    t_start = _time.time()
    for pid, j in enumerate(jobs, start=1):
        prng = random.Random(f"{args.seed}|{pid}")
        vd, s = j["date"], j.get("seed")
        tz_us = TZ["US"]
        h = j["hour"]
        t0 = datetime.combine(vd, time(int(h), int((h % 1) * 60)), tzinfo=tz_us).astimezone(UTC)

        # --- who, where, how much
        if s:
            ctype = s["client"]
            corridor = s["corridor"]
            if ctype == "FI":
                cid, _, seg = fi_pool[0]
            else:
                cid, _, seg = next((c for c in retail_pool if c[1] == corridor), retail_pool[0])
        elif prng.random() < 0.72:
            ctype = "Retail"
            cid, home, seg = rng.choices(retail_pool, cum_weights=retail_cum)[0]
            corridor = home if prng.random() < 0.9 else pick(prng, RETAIL_CORRIDOR_MIX)
        else:
            ctype = "FI"
            cid, mix, seg = rng.choices(fi_pool, cum_weights=fi_cum)[0]
            corridor = pick(prng, mix)
        dest_cc, ccy = R.CORRIDORS[corridor][0], R.CORRIDORS[corridor][1]

        if ctype == "Retail":
            purpose = "SUPP" if seg == "Small business" and prng.random() < 0.7 else pick(prng, RETAIL_PURPOSE[corridor])
            amt = prng.lognormvariate(math.log(RETAIL_MEDIAN[corridor]), 0.95) * (2.5 if purpose == "EDUC" else 1.0)
            amount = round_amount(min(60000, max(50, amt)))
            charges = pick(prng, {"OUR": .55, "SHA": .40, "BEN": .05})
            r = prng.random()
            if purpose == "EDUC" or r < 0.35:
                priority = prng.choice(range(80, 101, 5))
            elif r < 0.75:
                priority = prng.choice(range(50, 75, 5))
            else:
                priority = prng.choice(range(0, 30, 5))
            has_deadline = prng.random() < (0.35 if (purpose == "EDUC" or priority >= 80) else 0.08)
            deadline = vd + timedelta(days=prng.randint(2, 6)) if has_deadline else None
            bene_name = f"{prng.choice(R.BENE_PERSON[corridor][0])} {prng.choice(R.BENE_PERSON[corridor][1])}"
        else:
            purpose = pick(prng, FI_PURPOSE)
            amount = round_amount(min(5_000_000, max(5000, prng.lognormvariate(math.log(90000), 1.25))))
            charges = pick(prng, {"SHA": .70, "OUR": .25, "BEN": .05})
            priority = max(0, min(100, int(round(prng.gauss(50, 15) / 5) * 5)))
            deadline = vd + timedelta(days=prng.randint(1, 3)) if prng.random() < 0.15 else None
            bene_name = prng.choice(R.BENE_COMPANY[corridor])
        if s:
            amount = float(s["amount"])
            if s["tpl"] == "ECO":
                priority = 10
        bene_bank = prng.choices([b for b, _ in R.CORRIDORS[corridor][5]], weights=[w for _, w in R.CORRIDORS[corridor][5]])[0]
        deadline_end = (datetime.combine(deadline + timedelta(days=1), time(0), tzinfo=TZ[dest_cc]).astimezone(UTC)
                        if deadline else None)

        # --- shared randomness across candidate routes (consistent counterfactuals)
        noise, extra_fee = {}, {}

        def bank_noise(bid):
            if bid not in noise:
                noise[bid] = prng.lognormvariate(0, 0.30)
                extra_fee[bid] = prng.uniform(5, 15) if prng.random() < 0.06 else 0.0
            return noise[bid]

        large_factor = 1.0 + 0.25 * max(0.0, math.log10(amount / 50000)) if amount > 50000 else 1.0
        month_end_factor = 1.3 if j["month_end"] else 1.0
        u_issue, u_ret = prng.random(), prng.random()
        if ctype == "FI":
            rcause = pick(prng, {"RFI_PURPOSE": .35, "RFI_ADDRESS": .35, "RFI_NAME": .30})
        else:
            rcause = pick(prng, {"RFI_DOCS": .35, "RFI_PURPOSE": .30, "RFI_NAME": .20, "RFI_ADDRESS": .15})
        rhours = min(48, max(1.5, prng.lognormvariate(math.log(6), 0.6)))
        ret_cause = pick(prng, {"RET_ACCOUNT_CLOSED": .40, "RET_NAME_MISMATCH": .35, "RET_INVALID_ACCOUNT": .25})
        ret_extra = prng.uniform(48, 120)
        if s and s.get("repair"):
            u_issue, rcause, rhours = -1.0, s["repair"][0], s["repair"][1]
        if s:
            u_ret = 1.0

        # --- every candidate route
        cands = []
        for tid in R.CORRIDORS[corridor][6]:
            tpl = R.TEMPLATES[tid]
            hops = resolve_hops(tid, corridor, bene_bank)
            fees = list(tpl[3])
            if ctype == "FI":
                fees[0] = min(fees[0], R.FI_FIRST_HOP_FEE_CAP)
            bps = tpl[5] if amount >= R.LARGE_TICKET_USD else tpl[4]
            fx_cost = amount * bps / 1e4
            nominal = list(tpl[6])  # the simulator only knows template times; bank speed is hidden ground truth
            est = simulate(hops, tid, t0, cal, nominal)
            naive = naive_hours(tid, t0, cal, nominal)
            conflict = calendar_conflict(hops, vd, cal)
            reasons = []
            if tpl[8] and amount > tpl[8]:
                reasons.append(f"Above the ${tpl[8]:,.0f} per-transfer cap")
            if tpl[9] and ctype == "FI":
                reasons.append("Available to retail clients only")
            max_risk = max([hp.risk for hp in hops] + [R.CORRIDORS[corridor][3]])

            def real_proc(i, hp, st, nominal=nominal):
                f = hp.speed * bank_noise(hp.bank_id) * congestion_factor(args.seed, hp.bank_id, st.astimezone(TZ[hp.country]).date())
                if hp.intermediary and not hp.nonbank:
                    f *= large_factor                    # extra screening on large tickets
                return nominal[i] * f * month_end_factor

            p_repair = (1 - tpl[7]) * (1.5 if amount >= 100000 else 1.0) * (1.25 if max_risk >= 2 else 1.0)
            repaired = u_issue < p_repair
            rep = None
            if repaired:
                ri = min(R.REPAIR_HOP[tid], len(hops) - 1)
                label = next(c[2] for c in R.CAUSE_CODES if c[0] == rcause).split(": ", 1)[-1]
                rep = (ri, rhours, label)
            real = simulate(hops, tid, t0, cal, real_proc, repair=rep)
            for hp in hops:
                bank_noise(hp.bank_id)
            real_fees = [f + (extra_fee[hp.bank_id] if hp.intermediary and not hp.nonbank else 0.0) for f, hp in zip(fees, hops)]
            p_ret = 0.006 * (1.5 if tpl[10] else 1.0) * (1.3 if corridor == "USD-INR" else 1.0)
            returned = u_ret < p_ret
            real_cost = sum(real_fees) + fx_cost
            if returned:
                outcome, rh = "Returned", real.hours + ret_extra + 72
                real_cost += 25 + fx_cost
            else:
                outcome, rh = ("Repaired" if repaired else "Credited"), real.hours
            first = hops[0]
            cut = datetime.combine(vd, time(first.cutoff), tzinfo=TZ[first.country]).astimezone(UTC)
            cands.append({
                "template_id": tid, "hops": hops, "banks": [hp.bank_id for hp in hops], "fees": fees, "real_fees": real_fees,
                "bps": bps, "fx_cost": fx_cost, "est_cost": sum(fees) + fx_cost, "est_hours": est.hours,
                "naive_hours": naive, "est_cal": est.calendar_days, "conflict": int(conflict),
                "cutoff_slack": (cut - t0).total_seconds() / 3600, "max_risk": max_risk, "stp": tpl[7],
                "eligible": not reasons, "reasons": "; ".join(reasons) or None, "real": real, "realized_hours": rh,
                "realized_cost": real_cost, "realized_outcome": outcome, "realized_end": real.end,
                "returned": returned, "repaired": repaired, "utility": None, "relevance": 0,
            })
        utility_labels(cands, priority, deadline_end)
        chosen = next(c for c in cands if c["template_id"] == s["tpl"]) if s else ops_choice(prng, cands, ctype, amount, priority)

        # --- the payment as it happened on the chosen route
        real = chosen["real"]
        fee_total = sum(chosen["real_fees"])
        mid = fx_table[(ccy, vd)]
        rate = mid * (1 - chosen["bps"] / 1e4)
        intermediary_fees = fee_total - chosen["real_fees"][0]
        converted = amount if charges == "OUR" else amount - intermediary_fees if charges == "SHA" else amount - fee_total
        dp = next(c[2] for c in R.CURRENCIES if c[0] == ccy)
        credited = None if chosen["returned"] else real.end
        returned_at = real.end + timedelta(hours=ret_extra) if chosen["returned"] else None
        dl_met = None
        if deadline_end is not None:
            dl_met = int(credited is not None and credited <= deadline_end)
        ymd = vd.strftime("%y%m%d")
        seq_by_day[ymd] = seq_by_day.get(ymd, 0) + 1
        payments.append((
            pid, str(uuid.UUID(int=prng.getrandbits(128), version=4)), f"LKSB{ymd}{seq_by_day[ymd]:05d}", cid, ctype, corridor,
            amount, charges, purpose, priority, vd.isoformat(), deadline.isoformat() if deadline else None, iso(t0), round(h, 2),
            bene_name, bene_bank, chosen["template_id"], "ops", round(chosen["est_hours"], 2),
            round(fee_total, 2), chosen["bps"], round(chosen["fx_cost"], 2), round(fee_total + chosen["fx_cost"], 2),
            mid, round(rate, 6 if dp else 4), round(converted, 2), round(converted * rate, dp), chosen["realized_outcome"],
            int(chosen["realized_outcome"] == "Credited"), iso(credited) if credited else None, iso(returned_at) if returned_at else None,
            round(real.hours, 2) if credited else None, real.calendar_days, dl_met,
        ))

        last = len(real.steps) - 1
        for i, st in enumerate(real.steps):
            status = ("RJCT" if chosen["returned"] else "ACCC") if i == last else "ACSP"
            if st.holds:
                big = max(st.holds, key=lambda x: x.hours)
                parts = []
                for hd in st.holds:
                    if hd.kind == "calendar":
                        parts.append(f"Closed {day_label(hd.day)}: {hd.reason}")
                    elif hd.kind == "cutoff":
                        parts.append(f"Held {day_label(hd.day)}: {hd.reason}")
                    elif hd.kind == "batch":
                        parts.append("Held for Thursday netting batch")
                    else:
                        parts.append(f"Information request: {hd.reason}")
                hk, hr_, hh = big.kind, "; ".join(parts), round(sum(x.hours for x in st.holds), 2)
            else:
                hk, hr_, hh = None, None, 0.0
            hops_rows.append((pid, i, st.hop.bank_id, iso(st.arrive), iso(st.start), iso(st.leave),
                              round(chosen["real_fees"][i], 2), status, hk, hr_, hh))

        for c in cands:
            cand_rows.append((
                pid, c["template_id"], int(c["eligible"]), c["reasons"], len(c["hops"]), len(c["hops"]) - 2,
                round(sum(c["fees"]), 2), c["bps"], round(c["est_cost"], 2), round(c["est_hours"], 2), round(c["naive_hours"], 2),
                c["est_cal"], c["conflict"], round(c["cutoff_slack"], 2), c["max_risk"], c["stp"],
                round(c["realized_hours"], 2), round(c["realized_cost"], 2), c["realized_outcome"],
                c["utility"], c["relevance"], int(c is chosen),
            ))

        # --- ops annotations
        note = cause = None
        end_t = credited or returned_at or real.end
        if chosen["returned"]:
            cause = ret_cause
            label = next(c[2] for c in R.CAUSE_CODES if c[0] == cause).split(": ", 1)[-1]
            note = f"{real.steps[-1].hop.name} returned the payment: {label}. Funds back {fmt_h(real.hours + ret_extra)} after release."
        elif chosen["repaired"]:
            cause = rcause
            st = next(st for st in real.steps if any(hd.kind == "repair" for hd in st.holds))
            label = next(c[2] for c in R.CAUSE_CODES if c[0] == cause).split(": ", 1)[-1].lower()
            note = f"{st.hop.name} raised an information request ({label}). Repaired after {rhours:.0f} h; credited after {fmt_h(real.hours)}."
        else:
            routine = ("Saturday", "Sunday", "Saturday · Gulf weekend")  # closures the US shares are not news
            holds = [(st, hd) for st in real.steps[1:] for hd in st.holds if not (hd.kind == "calendar" and hd.reason in routine)]
            if holds:
                st, hd = max(holds, key=lambda x: x[1].hours)
                p_note = 1.0 if s else {"calendar": 0.35, "cutoff": 0.12, "batch": 0.30}.get(hd.kind, 0)
                big_enough = hd.hours >= {"calendar": 20, "cutoff": 12, "batch": 96}.get(hd.kind, 1e9)
                if (s or big_enough) and prng.random() < p_note:
                    if hd.kind == "calendar":
                        weekend = hd.reason in ("Saturday", "Sunday") or "Gulf weekend" in hd.reason
                        cause = "CAL_WEEKEND" if weekend else "CAL_HOLIDAY"
                        city = R.BANKS[st.hop.bank_id][2]
                        note = (f"{st.hop.name}, {city}, closed {day_label(hd.day)} ({hd.reason}). "
                                f"Credited {fmt_local(real.end, dest_cc)} local, {fmt_h(real.hours)} after release.")
                    elif hd.kind == "cutoff":
                        cause = "CUTOFF_MISSED"
                        note = (f"Arrived at {st.hop.name} after its {st.hop.cutoff:02d}:00 cut-off; processed the next business day. "
                                f"Credited after {fmt_h(real.hours)}.")
                    elif hd.kind == "batch":
                        cause = "BATCH_WAIT"
                        note = f"Missed the Thursday netting batch at {st.hop.name}; waited {hd.hours / 24:.1f} days for the next run."
        if note:
            annot_t = end_t + timedelta(hours=prng.uniform(2, 60))
            incidents.append((pid, cause, note, prng.choice(R.ANNOTATORS), iso(annot_t)))

        if pid % 5000 == 0:
            print(f"  {pid:,} payments  ({_time.time() - t_start:.0f}s)", flush=True)

    return {
        "hol_rows": hol_rows, "fx_rows": fx_rows, "clients": clients, "payments": payments,
        "hops": hops_rows, "cands": cand_rows, "incidents": incidents,
    }


# ------------------------------------------------------------------ write
def write_db(path: Path, data, args):
    if path.exists():
        path.unlink()
    con = sqlite3.connect(path)
    con.executescript((DATA_DIR / "schema.sql").read_text())
    cur = con.cursor()
    cur.executemany("INSERT INTO currency VALUES (?,?,?,?)", R.CURRENCIES)
    cur.executemany("INSERT INTO country VALUES (?,?,?,?,?,?)",
                    [(cc, v[0], v[1], ",".join(map(str, v[2])), v[3], v[4]) for cc, v in R.COUNTRIES.items()])
    cur.executemany("INSERT INTO holiday VALUES (?,?,?,?)", data["hol_rows"])
    cur.executemany("INSERT INTO bank VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", [
        (bid, b[0], b[1], b[2], b[3], b[4], "09:00", f"{b[5]:02d}:00" if b[5] is not None else None, b[6], b[7], b[8], b[9])
        for bid, b in R.BANKS.items()])
    cur.executemany("INSERT INTO bank_closure VALUES (?,?,?)", [(b, d.isoformat(), r) for (b, d), r in CLOSURES.items()])
    cur.executemany("INSERT INTO corridor VALUES (?,?,?,?,?,?,?)",
                    [(cid, "US", c[0], c[1], c[2], c[3], c[4]) for cid, c in R.CORRIDORS.items()])
    cur.executemany("INSERT INTO beneficiary_bank VALUES (?,?,?)",
                    [(cid, b, w) for cid, c in R.CORRIDORS.items() for b, w in c[5]])
    cur.executemany("INSERT INTO route_template VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", [
        (tid, t[0], t[1], t[13], t[7], t[8], t[9], t[10], t[11], t[12], t[4], t[5], t[14]) for tid, t in R.TEMPLATES.items()])
    cur.executemany("INSERT INTO route_template_hop VALUES (?,?,?,?,?)",
                    [(tid, i, ref, t[3][i], t[6][i]) for tid, t in R.TEMPLATES.items() for i, ref in enumerate(t[2])])
    cur.executemany("INSERT INTO corridor_route VALUES (?,?)", [(cid, t) for cid, c in R.CORRIDORS.items() for t in c[6]])
    cur.executemany("INSERT INTO fx_rate_daily VALUES (?,?,?,?)", data["fx_rows"])
    cur.executemany("INSERT INTO cause_code VALUES (?,?,?)", R.CAUSE_CODES)
    cur.executemany("INSERT INTO client VALUES (?,?,?,?,?)", data["clients"])
    cur.executemany("INSERT INTO payment VALUES (" + ",".join("?" * 34) + ")", data["payments"])
    cur.executemany("INSERT INTO payment_hop VALUES (?,?,?,?,?,?,?,?,?,?,?)", data["hops"])
    cur.executemany("INSERT INTO route_candidate VALUES (" + ",".join("?" * 22) + ")", data["cands"])
    cur.executemany("INSERT INTO incident (payment_id, cause_code, note, annotated_by, annotated_at_utc) VALUES (?,?,?,?,?)", data["incidents"])
    meta = {
        "generator_version": GEN_VERSION, "seed": str(args.seed), "window_start": args.start, "window_end": args.end,
        "payments": str(len(data["payments"])), "synthetic": "yes - all banks, clients, rates and outcomes are test data",
        "holidays_library": f"holidays {getattr(holidays_lib, '__version__', '?')} (Islamic holidays are estimated)",
        "large_ticket_usd": str(R.LARGE_TICKET_USD), "ordering_bank": "ORIG",
        "label_definition": ("route_candidate.utility = w*norm(log realized_hours) + (1-w)*norm(log realized_cost) within the payment, "
                             "w = priority/100; +1.0 if returned; +0.5 if credited after the deadline. relevance = 4,3,2,1 by ascending utility, else 0."),
        "created_at_utc": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    cur.executemany("INSERT INTO meta VALUES (?,?)", list(meta.items()))
    con.commit()
    con.execute("ANALYZE")
    con.execute("VACUUM")
    con.close()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(DATA_DIR / "corridoriq_history.db"))
    ap.add_argument("--payments", type=int, default=40000)
    ap.add_argument("--start", default="2024-04-01")
    ap.add_argument("--end", default="2026-09-30")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    t = _time.time()
    print(f"Generating {args.payments:,} payments, {args.start} to {args.end}, seed {args.seed}")
    data = generate(args)
    write_db(Path(args.out), data, args)
    print(f"Wrote {args.out}: {len(data['payments']):,} payments, {len(data['hops']):,} hops, "
          f"{len(data['cands']):,} candidate routes, {len(data['incidents']):,} incidents in {_time.time() - t:.0f}s")


if __name__ == "__main__":
    main()
