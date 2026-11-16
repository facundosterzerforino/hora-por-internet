# Hora por Internet

A small free app for old **rooted** Android phones (2.3 Gingerbread and up)
with no SIM card: it sets the clock from internet time servers.

Android 2.3 can only set the time automatically from the mobile network
(NITZ). Without a SIM the clock drifts (this Motorola XT550 was 18 s fast
after a day). After a battery pull it can even go back to 2012, and then no
HTTPS page opens because every certificate looks invalid. Network time (NTP)
was added to Android in 4.0.

## What it does

- When the phone gets a connection (Wi-Fi), at boot, and in an hourly check
  that never wakes the phone, it syncs if the last good sync was more than
  12 hours ago. If the date is obviously wrong (before 2026-11-16, when the app
  was written), it always tries.
- It asks `pool.ntp.org`, `time.cloudflare.com` and `time.google.com` with
  plain SNTP (RFC 4330, `src/org/librehora/Sntp.java`). Plain SNTP needs no
  certificates, so it works even when the date is wrong.
  - Answers are checked: server mode, stratum, leap indicator, our own
    timestamp echoed back, and a round trip under 2 s.
  - It uses the median answer. With three answers, one that is more than 2 s
    away from the median is dropped.
- If the phone is off by 1 s or more, it runs the system's own
  `date -s YYYYMMDD.hhmmss` in a root shell, exactly when the right time
  reaches a whole second.
  - On Android this goes through `/dev/alarm` (`ANDROID_ALARM_SET_RTC`), so the
    hardware clock is set too and the system sends "time changed".
- One screen in Spanish: the current time, the last result and a
  "Poner en hora ahora" button.
- 13 KB. Permissions: internet, network state and run at boot. No trackers,
  no libraries, no notifications, no background service left running.

The first time, Superuser asks for root: check "Recordar" and tap
"Permitir". The answer is saved, so it is asked only once (not after each
restart).

## Build

Only the SDK tools are needed: aapt, javac, d8, zipalign and apksigner. No
Gradle.

```
python tools/make_icon.py      # draws res/drawable/icon.png (pure Python)
python build.py --jdk <jdk> --build-tools <build-tools> --android-jar <android.jar> \
    --keystore <ks> --storepass-file <file>
```

## Tested on a Motorola XT550 (Android 2.3.6)

- The phone was 18.3 s fast; the three servers agreed within 3 ms. After the
  fix it was "La hora estaba bien".
- Date set to 2012-07-24 with Wi-Fi off. When Wi-Fi was turned back on, the app
  fixed it with no user action ("estaba atrasada 5228 días"), hardware clock
  included.

License: GPL-3.0-or-later.
