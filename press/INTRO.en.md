# ScreenBox — press & community kit (English)

## One-liner

**ScreenBox — Your screenshots, finally organized.**

## Short intro (GitHub / Reddit / V2EX, ~120 words)

My phone's screenshot folder had become a junk drawer: order confirmations, QR codes, chat snippets, game screenshots, expired coupons — all in one grid, impossible to search. So I built ScreenBox.

ScreenBox is a local-first Android app that scans your screenshots, reads the text inside them on-device, sorts them by what they actually show, and helps you safely clean up the ones that have outlived their purpose. Search a screenshot by a word that appears inside it. Filter by category or source. Get cleanup suggestions that each come with a reason and evidence — never a blind "delete because old."

It requests **no network permission** — it literally cannot upload anything. No root, no account, no cloud.

First public release is a Preview (0.10.0). Source is open. Feedback and compatibility reports welcome.

## Key points

- **Find by content.** On-device OCR + structured entities; search a date, amount, or order number that appears in a screenshot.
- **Auto-categorize.** chat / order / QR code / game / social. When unsure, it leaves the screenshot uncategorized instead of guessing.
- **Safe cleanup.** Exact duplicates, possibly-expired, near-blank, one-time codes, stale notifications, old game/social — each with evidence.
- **Protected deletion.** Favorites/protected items are excluded at three points; deletion goes through Android's system authorization.
- **Local-first.** No INTERNET permission. OCR model bundled in the APK.
- **Honest compatibility.** Verified on Android 15 real hardware; Android 10–14 not yet verified on device (documented as such).

## Suggested post copy (Reddit / V2EX title)

> ScreenBox — a local-first Android screenshot organizer that finally makes screenshots searchable (open source, no network permission)

## Links

- Repo: https://github.com/wlmosv-png/ScreenBox
- License: Apache-2.0
- Release: 0.10.0 (12) Preview
