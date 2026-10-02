---
name: docs-screenshots
description: Re-capture the screenshots and social image of the docs/ website, or run the AMS1-sized emulator, for Mini mPOS
---

# Screenshots for `docs/images/` and the AMS1 emulator

## Output format

- The site shows every screenshot inside a terminal frame, so each must have its frame's screen ratio
  (`WebsiteTest`'s "screenshots match their terminal frames" checks the PNG sizes, the `width`/`height` attributes and
  the white screen `rect` of each SVG):
  - S1F2 (9:16): captured at the S1F2's 720×1280 xhdpi, saved as 540×960. All screenshots except the hero's AMS1 one.
  - AMS1 (3:5): `sale-ams1.png`, captured and saved at the AMS1's 480×800 hdpi (`wm size 480x800`, `wm density 240`
    on the S1F2 AVD, then `wm size reset`/`wm density reset`; `screencap` then returns 480×800).
- PNGs with a 96-colour palette (Pillow: Lanczos resize, median-cut, no dither; this ImageMagick has no PNG delegate).
- If a frame's screen `rect` changes, update `.terminal-* img` in `docs/styles.css` (percentages of the viewBox) and
  the social page to match.

## Emulator

- AVD `MiniMpos_Docs_S1F2`: a clone (`cp -c -R`) of `MiniMpos_Docs_Pixel4a` (API 33 google_apis, gesture nav,
  `hw.keyboard=no`) with `hw.lcd.width/height/density` = 720/1280/320, so it keeps the demo data. Use these separate
  AVDs so existing emulator data is untouched. Headless: `-no-window -no-snapshot -gpu swiftshader_indirect`. A
  `-read-only` boot of the Pixel 4a AVD from its snapshot stayed `offline` in adb.
- Disable the Pixel 4a emulation overlays (`cmd overlay disable com.android.internal.emulation.pixel_4a` and
  `com.android.systemui.emulation.pixel_4a`, then `stop`/`start`): they add a hole-punch cutout, a 68 dp status bar
  and rounded corners that clip the clock.
- Setup: `adb root`, `setprop persist.sys.locale en-AU` and Australia/Perth (`settings put global auto_time_zone 0` and
  `service call alarm 3 s16 Australia/Perth`; setprop alone does not stick), then `stop`/`start`; window, transition
  and animator animations off; SystemUI demo mode (clock 09:30, full Wi-Fi and battery, no notifications; re-send it
  after every `stop`/`start`).
- The frames show terminals, so make the app behave as on one: `settings put global device_name S1F2-000158213605014`
  and `"terminal": {"mode": "SIMULATOR"}` in the seeded `settings.json` (Automatic would pick the local terminal).
  Off a terminal, results and history add Share receipt, which terminals never show. `settings delete global
  device_name` afterwards.
- History groups by day: with `settings put global auto_time 0` and `date MMDDhhmmYYYY.ss` set the clock to the
  afternoon of the seeded day, so "Today" stays and new sales sort above the seeded ones. Turn `auto_time` back on.
  Or move the seeded sales to today instead (`createdAt` of `sales` and `refunds`, and the `yyMMdd` in their
  merchant references) and capture in the afternoon.
- To re-capture a flow without leaving extra sales in History, snapshot the app's data as root
  (`cd /data/data/io.github.astiskala.minimpos && tar -cf /data/local/tmp/minimpos-base.tar .`) and restore it between
  flows (force-stop, delete, untar, `chown` to the app's uid, `restorecon -R`); the Keystore keys stay valid because
  the app is not reinstalled. Install a fresh debug build with `adb install -r` first (the data survives).
- Shut emulators down with `adb shell reboot -p`: `adb emu kill` can leave a freshly installed APK corrupt.

## Demo data

Seeded before the first launch (debug builds only), piped in with
`adb shell "cat … | run-as io.github.astiskala.minimpos sh -c 'cat > …'"`:

- A Room DB built from the latest `app/schemas/io.minimpos.app.data.db.AppDatabase/<version>.json` (Python sqlite3,
  `PRAGMA user_version`).
- `files/datastore/settings.json`: auto-lock 10 min, simulator delay 6 s (so the "waiting" screen can be captured).
  The keys it leaves out take the constructor's defaults, not a new installation's
  (`AppSettings.forNewInstallation`): checkout asks for a transaction and a customer reference, offers Save card, and
  receipts print only when asked, as the flows below need.
- Café "Harbour Coffee Co.", 1 Wharf Street Fremantle, ABN; GST 10% default and GST-free; AUD; 10 products in
  Coffee/Food/Retail, the beans GST-free with a barcode; plus "Catering deposit" $200.00 GST 10% in a Bookings category
  as a pre-authorisation product.
- The admin PIN (1357) is set in the app.

## Flows

1. Sale of 2 flat whites, banana bread and beans ($34.00, CUST-1042, save card; `checkout.png` shows Save card on and
   Tip on the receipt off) → printed receipt (simulated printer sheet, expanded and scrolled to the items) → `refund.png`
   from that sale's history detail (Refund › Items, one flat white and the banana bread, $11.00). The same cart on the
   AMS1-sized screen is `sale-ams1.png`.
2. Sales of $11.50 and $29.50, then an item refund of the first sale ($11.00). These four can also be seeded into the
   DB to re-capture later screens only. In the DB the $11.50 sale is `visa_applepay` and the $29.50 one `mc_googlepay`
   (the others plain, matching `receipt.png`/`approved.png`), so `history.png` shows "VISA Apple Pay" and
   "MC Google Pay".
3. `history-search.png`: History searched for `CUST-1042` (typed with `adb shell input text`, keyboard closed with
   Back), showing that sale and its refund.
4. Tip on the receipt sale of 2 avocado toast and 2 flat whites ($38.00): `tip-receipt.png` is its merchant copy with
   the blank TIP, TOTAL and SIGNATURE lines in the expanded printer sheet, `tip.png` the Enter tip screen with Total
   $45.00 typed; confirmed, so History shows Capture requested and Tips $7.00.
5. Pre-authorisation of the catering deposit (CUST-2077): `pre-auth.png` is its checkout (tapping the deposit opens it)
   with CUST-2077 typed, before tapping Pre-authorize $200.00, `pre-auth-detail.png` its history detail with Capture,
   Adjust amount and Cancel pre-authorisation; left open so Home shows the split tile.
6. `transfer.png` and `export.png`: Products › More › Share with another device (after the PIN is set, so passwords
   and keys are offered) before and after Show QR codes, paused on a code.

## Driving the UI

- Find nodes by text in `uiautomator dump` (dialogs are separate windows; tap keypad keys by their labels). A dump
  takes about 2 s: tap known coordinates where the layout is fixed.
- Switches have no text in the dump: tap the n-th `checkable` node.
- The PIN pad needs OK after the digits.
- Press Back twice to close the printer sheet; the first only collapses it. A merchant copy comes from the sale's
  history detail (Print merchant copy); the result screen's Print receipt prints the cardholder copy.
- Focusing a text field scrolls Checkout to its end; swipe back a little so the items show above Save card.
- The simulator picks the card brand and wallet at random, so a new capture may show another brand than History's
  seeded sales.

## Social image and site rendering

- `social.png` (1200×630) is an HTML page (light green-white background, favicon + "Mini mPOS", "The whole checkout.
  One payment terminal.", a lead line, a navy "Free and open source" pill, a grey disclosure footer, and
  `sale-ams1.png`/`receipt.png` in the blank `terminal-ams1.svg`/`terminal-s1f2.svg` frames, rotated -6° and 6°, with
  the same screen insets as `docs/styles.css`). Render it with `--allow-file-access-from-files` and
  `--force-device-scale-factor=1`.
- The site's terminal frames are original SVG illustrations: keep their bottom bezels blank (no model labels) and the
  AMS1's top free of an NFC symbol; `./gradlew :website-test:check` checks this.
- Render pages with Playwright's `chrome-headless-shell` (`~/Library/Caches/ms-playwright/`) and
  `--screenshot --window-size=W,H`; full Chrome headless and the Playwright MCP browser crashed in this sandbox.

## AMS1-sized emulator

- AVD `MiniMpos_AMS1_480x800` (API 33 google_apis arm64, "Nexus S" profile edited to 480×800 /
  `hw.lcd.density=240`, `hw.keyboard=no`, `hw.mainKeys = no`). Headless:
  `emulator -avd MiniMpos_AMS1_480x800 -no-window -no-snapshot -gpu swiftshader_indirect -port 5560`.
- For a realistic 48 dp nav bar: `adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton` and
  `... disable com.android.internal.systemui.navbar.gestural` (with both enabled the insets stay at 24 dp).
- `adb shell settings put global device_name AMS1-000168223606144` makes the debug app behave as on a terminal.
