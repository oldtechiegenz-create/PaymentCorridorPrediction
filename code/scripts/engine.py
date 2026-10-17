"""Banking calendar and hop-by-hop route simulation (MVP 1 modules M3 and M6).

The same functions produce the deterministic estimate shown to operators (nominal processing
times) and, in the generator, the realised history (noisy processing, repairs, returns).
All datetimes are timezone-aware UTC.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone
from functools import lru_cache
from zoneinfo import ZoneInfo

import holidays as holidays_lib

from reference import BANKS, COUNTRIES, CORRIDORS, HOLIDAY_ARGS, TEMPLATES

UTC = timezone.utc
TZ = {cc: ZoneInfo(v[1]) for cc, v in COUNTRIES.items()}
WEEKEND = {cc: set(v[2]) for cc, v in COUNTRIES.items()}
DOW = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def portal_dow(d: date) -> int:
    """0 = Sunday ... 6 = Saturday."""
    return (d.weekday() + 1) % 7


def day_label(d: date) -> str:
    return f"{DOW[portal_dow(d)]} {d.day} {MON[d.month - 1]} {d.year}"


# ------------------------------------------------------------------ calendars
def build_holidays(years):
    """Return {country: {date: name}} and a list of rows (country, date, name, source)."""
    version = getattr(holidays_lib, "__version__", "?")
    src = f"holidays {version}"
    table, rows = {}, []
    for cc, kw in HOLIDAY_ARGS.items():
        out = {}
        if cc == "US":
            # Fedwire follows Federal Reserve holidays: a Sunday holiday is observed Monday;
            # a Saturday holiday is NOT moved to Friday (the Fed stays open on that Friday).
            base = holidays_lib.country_holidays("US", years=years, observed=False)
            for d, name in base.items():
                if portal_dow(d) == 0:
                    out[d + timedelta(days=1)] = f"{name} (observed) · Fedwire closed"
                elif portal_dow(d) != 6:
                    out[d] = f"{name} · Fedwire closed"
        else:
            try:
                h = holidays_lib.country_holidays(cc, years=years, language="en_US", **kw)
            except Exception:
                h = holidays_lib.country_holidays(cc, years=years, **kw)
            out.update({d: n for d, n in h.items()})
        rule_rows = []
        if cc == "JP":  # bank holidays that are not national holidays
            for y in years:
                for d, n in ((date(y, 1, 2), "Bank holiday (New Year)"), (date(y, 1, 3), "Bank holiday (New Year)"), (date(y, 12, 31), "Bank holiday (year end)")):
                    if d not in out:
                        out[d] = n
                        rule_rows.append(d)
        table[cc] = out
        for d, n in sorted(out.items()):
            rows.append((cc, d.isoformat(), n, "rule" if d in rule_rows else src))
    return table, rows


class Calendar:
    def __init__(self, holiday_table, closures=None):
        self.hol = holiday_table
        self.closures = closures or {}  # {(bank_id, date): reason}

    @lru_cache(maxsize=None)
    def non_business(self, cc: str, d: date):
        """Reason the day is closed for banks in `cc`, or None if it is a business day."""
        name = self.hol.get(cc, {}).get(d)
        if name:
            return name
        dow = portal_dow(d)
        if dow in WEEKEND[cc]:
            if cc in ("BH", "SA"):
                return ("Friday" if dow == 5 else "Saturday") + " · Gulf weekend"
            return "Sunday" if dow == 0 else "Saturday"
        if cc == "IN" and dow == 6:
            n = (d.day - 1) // 7 + 1
            if n in (2, 4):
                return ("2nd" if n == 2 else "4th") + " Saturday · Indian bank holiday"
        return None

    def bank_closed(self, bank_id: str, cc: str, d: date):
        return self.closures.get((bank_id, d)) or self.non_business(cc, d)


# ------------------------------------------------------------------ routes
@dataclass
class Hop:
    bank_id: str
    name: str
    country: str
    cutoff: float | None
    all_day: bool
    intermediary: bool
    nonbank: bool
    risk: int
    speed: float


def hop_of(bank_id: str) -> Hop:
    b = BANKS[bank_id]
    return Hop(bank_id, b[0], b[3], b[5], bool(b[6]), bool(b[7]), bool(b[8]), b[9], b[10])


def resolve_hops(template_id: str, corridor_id: str, bene_bank_id: str):
    clr = CORRIDORS[corridor_id][4]
    out = []
    for ref in TEMPLATES[template_id][2]:
        out.append(hop_of(clr if ref == "CLR" else bene_bank_id if ref == "BENE" else ref))
    return out


@dataclass
class Hold:
    kind: str      # calendar, cutoff, batch, repair
    reason: str
    day: date
    hours: float


@dataclass
class Step:
    hop: Hop
    arrive: datetime
    start: datetime
    leave: datetime
    holds: list = field(default_factory=list)


@dataclass
class SimResult:
    steps: list
    end: datetime
    hours: float
    calendar_days: int


def _next_open(t_local_date: date, tz, opens=9) -> datetime:
    return datetime.combine(t_local_date + timedelta(days=1), time(opens), tzinfo=tz).astimezone(UTC)


def simulate(hops, template_id, t0: datetime, cal: Calendar, proc_hours, repair=None) -> SimResult:
    """Walk the payment through each hop.

    proc_hours: processing hours per hop (nominal for estimates), or a callable
                f(hop_index, hop, start_utc) -> hours (noisy realisations that depend on the day).
    repair: optional (hop_index, hours, reason) — an information request raised at that hop.
    """
    tpl = TEMPLATES[template_id]
    instant, batch_hop = bool(tpl[11]), tpl[12]
    t = t0
    steps, cal_days = [], set()
    last = len(hops) - 1
    for i, h in enumerate(hops):
        tz = TZ[h.country]
        arrive, holds = t, []
        all_day = h.all_day or (instant and i == last)
        if not all_day:
            for _ in range(60):
                L = t.astimezone(tz)
                d = L.date()
                hr = L.hour + L.minute / 60
                closed = cal.bank_closed(h.bank_id, h.country, d)
                if closed:
                    nt = _next_open(d, tz)
                    holds.append(Hold("calendar", closed, d, (nt - t).total_seconds() / 3600))
                    cal_days.add((h.country, d))
                    t = nt
                    continue
                if h.cutoff is not None and hr >= h.cutoff:
                    nt = _next_open(d, tz)
                    holds.append(Hold("cutoff", f"after {h.cutoff:02d}:00 cut-off", d, (nt - t).total_seconds() / 3600))
                    t = nt
                    continue
                if hr < 9:
                    nt = datetime.combine(d, time(9), tzinfo=tz).astimezone(UTC)
                    t = nt
                    continue
                break
        if batch_hop == i:
            L = t.astimezone(tz)
            add = (4 - portal_dow(L.date()) + 7) % 7
            if add == 0 and L.hour >= 16:
                add = 7
            bt = datetime.combine(L.date() + timedelta(days=add), time(16), tzinfo=tz).astimezone(UTC)
            if bt > t:
                holds.append(Hold("batch", "held for Thursday netting batch", bt.astimezone(tz).date(), (bt - t).total_seconds() / 3600))
                t = bt
        start = t
        ph = proc_hours(i, h, start) if callable(proc_hours) else proc_hours[i]
        t = t + timedelta(hours=ph)
        if repair and repair[0] == i:
            holds.append(Hold("repair", repair[2], t.astimezone(tz).date(), repair[1]))
            t = t + timedelta(hours=repair[1])
        steps.append(Step(h, arrive, start, t, holds))
    return SimResult(steps, t, (t - t0).total_seconds() / 3600, len(cal_days))


def naive_hours(template_id: str, t0: datetime, cal: Calendar, nominal) -> float:
    """What operators estimated before CorridorIQ: processing time plus the US cut-off only."""
    tz = TZ["US"]
    L = t0.astimezone(tz)
    wait = 0.0
    cutoff = BANKS["ORIG"][5]
    if L.hour + L.minute / 60 >= cutoff or cal.non_business("US", L.date()):
        d = L.date() + timedelta(days=1)
        while cal.non_business("US", d):
            d += timedelta(days=1)
        wait = (datetime.combine(d, time(9), tzinfo=tz).astimezone(UTC) - t0).total_seconds() / 3600
    return wait + sum(nominal)


def calendar_conflict(hops, value_date: date, cal: Calendar) -> bool:
    """An intermediary is closed on the value date or the next day while US markets are open."""
    for h in hops:
        if not h.intermediary or h.all_day:
            continue
        for o in (0, 1):
            d = value_date + timedelta(days=o)
            if not cal.non_business("US", d) and cal.bank_closed(h.bank_id, h.country, d):
                return True
    return False
