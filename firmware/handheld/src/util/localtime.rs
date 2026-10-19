//! Local time: a fixed UTC offset plus an optional daylight saving rule.
//!
//! The device clock stays in UTC; these helpers convert for display, for setting the clock
//! and for game clocks that expect the wall-clock time.

use time::{Date, Duration, Month, OffsetDateTime, PrimitiveDateTime, Time, UtcOffset, Weekday};

use crate::kvs::keys;

/// Time zone choices, as shown in Settings, and their offsets from UTC in minutes.
pub const UTC_OFFSET_CHOICES: &[&str] = &[
    "UTC-12:00", "UTC-11:00", "UTC-10:00", "UTC-09:30", "UTC-09:00", "UTC-08:00", "UTC-07:00",
    "UTC-06:00", "UTC-05:00", "UTC-04:00", "UTC-03:30", "UTC-03:00", "UTC-02:00", "UTC-01:00",
    "UTC+00:00", "UTC+01:00", "UTC+02:00", "UTC+03:00", "UTC+03:30", "UTC+04:00", "UTC+04:30",
    "UTC+05:00", "UTC+05:30", "UTC+05:45", "UTC+06:00", "UTC+06:30", "UTC+07:00", "UTC+08:00",
    "UTC+08:45", "UTC+09:00", "UTC+09:30", "UTC+10:00", "UTC+10:30", "UTC+11:00", "UTC+12:00",
    "UTC+12:45", "UTC+13:00", "UTC+14:00",
];
const UTC_OFFSET_MINUTES: &[i32] = &[
    -720, -660, -600, -570, -540, -480, -420, -360, -300, -240, -210, -180, -120, -60, 0, 60, 120,
    180, 210, 240, 270, 300, 330, 345, 360, 390, 420, 480, 525, 540, 570, 600, 630, 660, 720, 765,
    780, 840,
];
/// Index of "UTC+00:00" in `UTC_OFFSET_CHOICES`.
pub const UTC_OFFSET_DEFAULT: i32 = 14;

/// Daylight saving rule choices, as shown in Settings.
pub const DST_CHOICES: &[&str] = &["Off", "Europe", "North America"];
const DST_EUROPE: i32 = 1;
const DST_NORTH_AMERICA: i32 = 2;

fn base_offset_minutes() -> i32 {
    let index = keys::UTC_OFFSET.get().unwrap_or(UTC_OFFSET_DEFAULT);
    UTC_OFFSET_MINUTES
        .get(index as usize)
        .copied()
        .unwrap_or(0)
}

fn last_sunday(year: i32, month: Month) -> Date {
    let mut date = Date::from_calendar_date(year, month, time::util::days_in_month(month, year))
        .unwrap();
    while date.weekday() != Weekday::Sunday {
        date = date.previous_day().unwrap();
    }
    date
}

fn nth_sunday(year: i32, month: Month, n: u8) -> Date {
    let mut date = Date::from_calendar_date(year, month, 1).unwrap();
    while date.weekday() != Weekday::Sunday {
        date = date.next_day().unwrap();
    }
    date + Duration::weeks((n - 1) as i64)
}

/// Whether daylight saving time is in effect at `utc`, for a zone whose standard offset is `base`.
fn is_dst(rule: i32, utc: OffsetDateTime, base: i32) -> bool {
    let year = utc.year();
    let (start, end) = match rule {
        // EU: last Sunday of March to last Sunday of October, at 01:00 UTC.
        DST_EUROPE => (
            last_sunday(year, Month::March).with_time(Time::from_hms(1, 0, 0).unwrap()),
            last_sunday(year, Month::October).with_time(Time::from_hms(1, 0, 0).unwrap()),
        ),
        // US / Canada: second Sunday of March at 02:00 standard time to first Sunday of
        // November at 02:00 daylight time.
        DST_NORTH_AMERICA => (
            nth_sunday(year, Month::March, 2).with_time(Time::from_hms(2, 0, 0).unwrap())
                - Duration::minutes(base as i64),
            nth_sunday(year, Month::November, 1).with_time(Time::from_hms(2, 0, 0).unwrap())
                - Duration::minutes((base + 60) as i64),
        ),
        _ => return false,
    };
    let now = PrimitiveDateTime::new(utc.date(), utc.time());
    now >= start && now < end
}

/// The offset from UTC, in minutes, at the given UTC instant.
pub fn offset_minutes_at(utc: OffsetDateTime) -> i32 {
    let base = base_offset_minutes();
    let rule = keys::DST_RULE.get().unwrap_or(0);
    if is_dst(rule, utc, base) {
        base + 60
    } else {
        base
    }
}

/// Convert a UTC instant to local time.
pub fn to_local(utc: OffsetDateTime) -> OffsetDateTime {
    let offset = UtcOffset::from_whole_seconds(offset_minutes_at(utc) * 60).unwrap_or(UtcOffset::UTC);
    utc.to_offset(offset)
}

/// Convert a local wall-clock time to a UTC instant. Around a daylight saving change the
/// result may be off by an hour for the times that are skipped or repeated.
pub fn local_to_utc(local: PrimitiveDateTime) -> OffsetDateTime {
    let base = base_offset_minutes();
    let guess = local.assume_utc() - Duration::minutes(base as i64);
    let offset = offset_minutes_at(guess);
    local.assume_utc() - Duration::minutes(offset as i64)
}

/// Format the time of day for the status bar clock.
pub fn format_clock(local: OffsetDateTime, use_24h: bool) -> String {
    if use_24h {
        format!("{:02}:{:02}", local.hour(), local.minute())
    } else {
        let hour = match local.hour() % 12 {
            0 => 12,
            h => h,
        };
        let suffix = if local.hour() < 12 { "AM" } else { "PM" };
        format!("{}:{:02} {}", hour, local.minute(), suffix)
    }
}
