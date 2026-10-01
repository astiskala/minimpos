---
name: docs-screenshots
description: Re-capture the screenshots and social image of the docs/ website, or run the AMS1-sized emulator, for Mini mPOS
---

# Screenshots for `docs/images/` and the AMS1 emulator

## Output format
- 540×1170 PNGs with a 96-colour palette (Pillow: Lanczos resize, median-cut, no dither; this ImageMagick has no PNG
  delegate), captured at 1080×2340.

## Emulator
- AVD `MiniMpos_Docs_Pixel4a` (API 33 google_apis, `pixel_4a` profile, gesture nav, `hw.keyboard=no`). Use this
  separate AVD so existing emulator data is untouched.
- Setup: `adb root`, `setprop persist.sys.locale en-AU` and Australia/Perth (`settings put global auto_time_zone 0` and
  `service call alarm 3 s16 Australia/Perth`; setprop alone does not stick), then `stop`/`start`; window, transition
  and animator animations off; SystemUI demo mode (clock 09:30, full Wi-Fi and battery, no notifications).
- Shut emulators down with `adb shell reboot -p`: `adb emu kill` can leave a freshly installed APK corrupt.

## Demo data
Seeded before the first launch (debug builds only), piped in with
`adb shell "cat … | run-as io.github.astiskala.minimpos sh -c 'cat > …'"`:
- A Room DB built from the latest `app/schemas/io.minimpos.app.data.db.AppDatabase/<version>.json` (Python sqlite3,
  `PRAGMA user_version`).
- `files/datastore/settings.json`: auto-lock 10 min, simulator delay 6 s (so the "waiting" screen can be captured).
- Café "Harbour Coffee Co.", 1 Wharf Street Fremantle, ABN; GST 10% default and GST-free; AUD; 10 products in
  Coffee/Food/Retail, the beans GST-free with a barcode; plus "Catering deposit" $200.00 GST 10% in a Bookings category
  as a pre-authorisation product.
- The admin PIN (1357) is set in the app.

## Flows
1. Sale of 2 flat whites, banana bread and beans ($34.00, CUST-1042, save card; `checkout.png` shows Save card on and
   Tip on the receipt off) → printed receipt (simulated printer sheet, expanded and scrolled to the items).
2. Sales of $11.50 and $29.50, then an item refund of the first sale ($11.00). These four can also be seeded into the
   DB to re-capture later screens only. In the DB the $11.50 sale is `visa_applepay` and the $29.50 one `mc_googlepay`
   (the others plain, matching `receipt.png`/`approved.png`), so `history.png` shows "VISA Apple Pay" and
   "MC Google Pay".
3. `history-search.png`: History searched for `CUST-1042` (typed with `adb shell input text`, keyboard closed with
   Back), showing that sale and its refund.
4. Tip on the receipt sale of 2 avocado toast and 2 flat whites ($38.00): `tip-receipt.png` is its merchant copy with
   the blank TIP, TOTAL and SIGNATURE lines in the expanded printer sheet, `tip.png` the Enter tip screen with Total
   $45.00 typed; confirmed, so History shows Capture requested and Tips $7.00.
5. Pre-authorisation of the catering deposit (CUST-2077): `pre-auth.png` is the Pre-authorize screen before tapping
   it, `pre-auth-detail.png` its history detail with Capture, Adjust amount and Cancel pre-authorisation; left open so
   Home shows the split tile.
6. `transfer.png` and `export.png`: Products › More › Share with another terminal (after the PIN is set, so passwords
   and keys are offered) before and after Show QR codes, paused on a code.

## Driving the UI
- Find nodes by text in `uiautomator dump` (dialogs are separate windows; tap keypad keys by their labels).
- Switches have no text in the dump: tap the n-th `checkable` node.
- Press Back twice to close the printer sheet; the first only collapses it.

## Social image and site rendering
- `social.png` (1200×630) is an HTML page (light green-white background, favicon + "Mini mPOS", "The whole checkout.
  One payment terminal.", a lead line, a navy "Free and open source" pill, a grey disclosure footer, and
  `sale.png`/`receipt.png` in the blank `terminal-ams1.svg`/`terminal-s1f2.svg` frames, tilted left and right).
- The site's terminal frames are original SVG illustrations: keep their bottom bezels blank (no model labels) and the
  AMS1's top free of an NFC symbol; `docs/tests/test_site.py` checks this.
- Render pages with Playwright's `chrome-headless-shell` (`~/Library/Caches/ms-playwright/`) and
  `--screenshot --window-size=W,H`; full Chrome headless and the Playwright MCP browser crashed in this sandbox.

## AMS1-sized emulator
- AVD `MiniMpos_AMS1_480x800` (API 33 google_apis arm64, "Nexus S" profile edited to 480×800 /
  `hw.lcd.density=240`, `hw.keyboard=no`, `hw.mainKeys = no`). Headless:
  `emulator -avd MiniMpos_AMS1_480x800 -no-window -no-snapshot -gpu swiftshader_indirect -port 5560`.
- For a realistic 48 dp nav bar: `adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton` and
  `... disable com.android.internal.systemui.navbar.gestural` (with both enabled the insets stay at 24 dp).
- `adb shell settings put global device_name AMS1-000168223606144` makes the debug app behave as on a terminal.
