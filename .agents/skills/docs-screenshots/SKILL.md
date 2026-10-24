---
name: docs-screenshots
description: Re-capture the screenshots and social image of the docs/ website, or run the AMS1-sized emulator, for
  Mini mPOS
---

# Screenshots for `docs/images/` and the AMS1 emulator

## Output format

- Capture English, Mexican Spanish, French, Japanese, Brazilian Portuguese, Simplified Chinese and Traditional Chinese
  using `AppSettings.languageTag` (`en`, `es-MX`, `fr-FR`, `ja`, `pt-BR`, `zh-CN`, `zh-Hant`), not translated overlays.
  English assets live directly in `docs/images/`; the other sets live in matching language subdirectories. Localized
  pages and social metadata must use their own set. Use the
  separate localized café fixtures in `app/src/main/assets/sample-data.json`, including native merchant details,
  products, categories, receipt text, currency and prices. Never translate or reprice saved merchant data. Adyen receipt
  fields remain verbatim in the app; only synthetic fixture card receipts are regenerated for the chosen demo currency.
- The site shows every screenshot inside a terminal frame, so each must have its frame's screen ratio
  (`WebsiteTest`'s "screenshots match their terminal frames" checks the PNG sizes, the `width`/`height` attributes and
  the white screen `rect` of each SVG):
  - S1F2 (9:16): laid out at the S1F2's 720×1280 xhdpi, saved as 540×960. All screenshots except the hero's AMS1 one.
  - AMS1 (3:5): `sale-ams1.png`, laid out and saved at the AMS1's 480×800 hdpi (`wm density 240` on the S1F2 AVD).
- No simulator banner: `scripts/docs-capture launch` creates the `files/docs-capture` marker, which only debug builds
  read (`AndroidDeviceInfo.docsCapture`), and `finish` removes it. The site discloses the screenshots as simulator
  demos instead; receipts' own demo notices stay.
- PNGs with a 96-colour palette (median cut, no dither), made by `scripts/docs-capture process`; see Capture script.
- Full screen, no system bars: Adyen terminals show no clock or gesture bar, so the app fills their whole screen. The
  emulator cannot hide its bars (SystemUI is persistent, and `policy_control` is gone since Android 11), so captures use
  a logical display taller than the terminal's by exactly one status bar and one gesture bar (24 dp each): the app
  lays out between them as it would on the terminal, and `process` crops the bars off. S1F2: `wm size 720x1376`, bars
  48 px each (raw 720×1376 → 720×1280 → 540×960). AMS1: `wm size 480x872` at `wm density 240`, bars 36 px each (raw
  480×872 → 480×800). Never paint over the bars or stretch: the crop keeps the layout the app draws. `scripts/docs-capture`
  sets the sizes; check the bars with `dumpsys window displays | grep InsetsSource`.
- Size classes follow the window between the bars (`Dimens.forWindow`). The S1F2 frame's full 640 dp gives `Regular`,
  where an S1F2 that keeps its own bars (about 568 dp, `DimensTest`) gets `Medium`; the AMS1 is `Compact` either way
  (under 360 dp wide). S1F2 screenshots therefore show the Regular layout, filling the frame.
- If a frame's screen `rect` changes, update `.terminal-* img` in `docs/styles.css` (percentages of the viewBox) and
  the social page to match.

## Capture script

`scripts/docs-capture` (Bash, checked by shellcheck) drives the docs emulator by visible text, so one sequence works
in every language: `prepare`, `locale LANG`, `snapshot`/`restore NAME.tar` (app data in `/data/local/tmp`), `launch
  LANG`,
`tap LANG KEY` (a string resource, or `=literal text`), `scroll LANG KEY`, `shot LANG NAME` and `finish`. Raw
Captures collect in `/tmp/minimpos-shots` as `LANG-NAME.png`; `process` then crops the system bars, scales S1F2 to
540×960 (AMS1 stays 480×800) and quantizes them into `docs/images[/LANG]/` through `:tooling`'s `screenshots` command
(`DocsScreenshots`). Keep per-language fixtures as on-device snapshots (`docs-<set>-<lang>.tar`); `flow LANG NAME...`
restores one before each named flow:

- `home`: home only; `overview`: home, history, history-search; `catalog`: products; `preferences`: settings,
  capabilities.
- `cart`: sale; `checkout`: sale, checkout, wallets, wallet-scan, processing, approved, receipt, refund.
- `authorize`: pre-auth; `preauth`: pre-auth-detail; `terminal_setup`: terminal-setup;
  `settings`: terminal-setup, tax, terminal-tipping.
- `transfer`: transfer, export; `partial`: partial-payment; `gratuity`: tip-receipt, tip; `tax_dialog`: tax-dialog.
- `offline`: offline-payment, reconciliation.
- `ams1`: sale and the sale-ams1 image (raw 480×872). Both terminal sizes share the raw processing directory.

`settings LANG KEY` opens a Settings section past the admin PIN; `expand_receipt` expands the open simulated printer.
Add a named flow there for each screenshot you
recapture by hand twice, rather than a temporary script. Do not edit the running shell script during a capture.
Launch waits for localized Home controls; a Home capture checks them again and refuses splash screens.
For cloning, approved reset opt-in, batch capture, image processing/review and shutdown, use
[CONTRIBUTING's repeatable workflow](../../../CONTRIBUTING.md#repeatable-screenshot-refresh). Do not recreate those
steps with temporary Python, Node or shell scripts. Fixture resets refuse non-emulators, unowned AVDs and missing
  archives.

## Emulator

- AVD `MiniMpos_Docs_S1F2`: a clone (`cp -c -R`) of `MiniMpos_Docs_Pixel4a` (API 33 google_apis, gesture nav,
  `hw.keyboard=no`) with `hw.lcd.width/height/density` = 720/1280/320, so it keeps the demo data. Use these separate
  AVDs as fixture sources so existing emulator data is untouched. For a refresh, use `scripts/docs-emulator clone`
  on a stopped source, then `start` on the new owned disposable clone; never reset another emulator's data. Headless:
  `-no-window -no-snapshot -gpu swiftshader_indirect`. A
  `-read-only` boot of the Pixel 4a AVD from its snapshot stayed `offline` in adb.
- Disable the Pixel 4a emulation overlays (`cmd overlay disable com.android.internal.emulation.pixel_4a` and
  `com.android.systemui.emulation.pixel_4a`, then `stop`/`start`): they add a hole-punch cutout, a 68 dp status bar
  and rounded corners that clip the clock. Wait for Package Manager after restarting the framework before launching
  the app; an early launch can report that the activity does not exist.
- Setup: `adb root`, set `persist.sys.locale` to the fixture's `deviceLocale`, then `stop`/`start`; wait until Package
  Manager resolves the app's activity, not just its APK path. After restarting, disable automatic time zone selection
  with `settings put global auto_time_zone 0` and apply the fixture's `timeZone` with `cmd alarm set-timezone
  <timeZone>`.
  Verify `getprop persist.sys.timezone`; a numeric Binder call or setprop alone is not reliable. Window, transition
  and animator animations off. The status bar's clock and icons are cropped away, so no demo mode is needed.
- The frames show terminals, so make the app behave as on one: `settings put global device_name S1F2-000158213605014`
  and `"terminal": {"mode": "SIMULATOR"}` in the seeded `settings.json` (Automatic would pick the local terminal).
  Off a terminal, results and history add Share receipt, which terminals never show. `settings delete global
  device_name` afterwards.
- History groups by day: with `settings put global auto_time 0` and `date MMDDhhmmYYYY.ss` set the clock to the
  afternoon of the seeded day, so "Today" stays and new sales sort above the seeded ones. Turn `auto_time` back on.
  Or move the seeded sales to today instead (`createdAt` of `sales` and `refunds`, and the `yyMMdd` in their
  merchant references) and capture in the afternoon.
- To re-capture a flow without leaving extra sales in History, snapshot the app's data as root
  (`cd /data/data/app.minimpos && tar -cf /data/local/tmp/minimpos-base.tar .`) and restore it between
  flows (force-stop, delete, untar, `chown` to the app's uid, `restorecon -R`); the Keystore keys stay valid because
  the app is not reinstalled. Install a fresh debug build with `adb install -r` first (the data survives).
- Shut emulators down with `adb shell reboot -p`: `adb emu kill` can leave a freshly installed APK corrupt.

## Demo data

Seeded before the first launch (debug builds only), piped in with
`adb shell "cat … | run-as app.minimpos sh -c 'cat > …'"`:

- A Room DB built from the latest `app/schemas/app.minimpos.app.data.db.AppDatabase/<version>.json`
  (the `sqlite3` command-line tool, `PRAGMA user_version`). Do not add Python to the project.
- `files/datastore/settings.json`: auto-lock 10 min, simulator delay 6 s (so the "waiting" screen can be captured).
  Explicitly set `payment.askTransactionReference = false`, `payment.shopperReferenceSource = CUSTOMER_REFERENCE`,
  `payment.offerCardSaving = true` and `receipt.autoPrint = false` for these demos. Omitted keys take the current
  constructor defaults: customer reference on, extra merchant reference off, automatic printing on. Card saving still
  needs an entered shopper reference and consent. Keep the extra merchant reference off in checkout captures so the
  saving switch and its consent explanation can fit.
- Café "Harbour Coffee Co.", 1 Wharf Street Fremantle, ABN; GST 10% default and GST-free; AUD; 10 products in
  Coffee/Food/Retail, the beans GST-free with a barcode; plus "Catering deposit" $200.00 GST 10% in a Bookings category
  as a pre-authorisation product. This is the English fixture; use `app/src/main/assets/sample-data.json` for each
  other language's native café, realistic prices and tax examples. JPY minor units are whole yen, not hundredths.
- The admin PIN (1357) is set in the app.
- Back up the database through SQLite, including committed WAL contents, before preparing localized copies. Recompute
  line amounts, net/tax totals, tips, authorizations, captures, tenders, refunds and synthetic card-receipt totals in
  the
  selected currency. Validate foreign keys, integrity and summed line totals before installing a disposable fixture.

| Page language | Device locale | Currency | Main sale | Item refund | Tip sale + tip | Deposit | Partial sale |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| English | en-AU | AUD | 34.00 | 11.00 | 38.00 + 7.00 | 200.00 | 21.39 |
| Mexican Spanish | es-MX | MXN | 335.00 | 100.00 | 330.00 + 50.00 | 2,000.00 | 21.39 |
| French | fr-FR | EUR | 27.00 | 9.00 | 24.00 + 5.00 | 200.00 | 21.39 |
| Japanese | ja-JP | JPY | 3,050 | 1,000 | 3,000 + 500 | 20,000 | 2,139 |
| Brazilian Portuguese | pt-BR | BRL | 87.00 | 30.00 | 80.00 + 10.00 | 200.00 | 21.39 |
| Simplified Chinese | zh-SG | SGD | 33.50 | 10.00 | 35.00 + 5.00 | 200.00 | 21.39 |
| Traditional Chinese | zh-HK | HKD | 232.00 | 66.00 | 192.00 + 18.00 | 2,000.00 | 121.39 |

## Flows

The amounts and customer references below describe the English fixture. Use the table above and the JSON fixture for
the other languages. CJK fixtures use numeric customer references so input methods do not autocorrect ASCII acronyms.
In Japanese, send `KEYCODE_EISU` before typing to keep digits half-width. Verify the actual field text after the
  keyboard
closes. Always restore the market's device locale when recapturing only part of a flow.

1. Sale of 2 flat whites, banana bread and beans ($34.00, CUST-1042, save card; `checkout.png` shows Save card on,
   with `payment.receiptTipping = DISABLED`, the default, so there is no tipping switch) → printed receipt (simulated
   printer sheet, expanded to show the merchant header, references and items) → `refund.png`
   from that sale's history detail (Refund › Items, one flat white and the banana bread, $11.00). Use a fixture from
   before that refund, and press each item's increase button; tapping its label does not select a quantity. The same
   cart on the AMS1-sized screen is `sale-ams1.png`.
2. Sales of $11.50 and $29.50, then an item refund of the first sale ($11.00). These four can also be seeded into the
   DB to re-capture later screens only. In the DB the $11.50 sale is `visa_applepay` and the $29.50 one `mc_googlepay`
   (the others plain, matching `receipt.png`/`approved.png`), so `history.png` shows "VISA Apple Pay" and
   "MC Google Pay".
3. `history-search.png`: History searched for `CUST-1042` (typed with `adb shell input text`, keyboard closed with
   Back), showing that sale and its refund.
4. In Settings › Tips and donations, set Tip on the receipt to Enabled (default off)
   (`payment.receiptTipping = DEFAULT_OFF`)
   before this flow; restore Disabled afterward. Tip on the receipt sale of 2 avocado toast and 2 flat whites ($38.00):
   `tip-receipt.png` is its merchant copy with
   the blank TIP, TOTAL and SIGNATURE lines in the expanded printer sheet, scrolled past the business header without
   clipping the receipt title. `tip.png` is the Enter tip screen with a $7.00
   tip and $45.00 total. The main History fixture shows the accepted capture and Tips $7.00.
5. Pre-authorisation of the catering deposit (CUST-2077): `pre-auth.png` is its checkout (tapping the deposit opens it)
   with CUST-2077 typed, before tapping Pre-authorize $200.00, `pre-auth-detail.png` its history detail with Capture,
   Adjust amount and Cancel pre-authorisation; left open so Home shows the split tile.
6. `transfer.png` and `export.png`: Settings › Data and transfer › Share with another device (after the PIN is set,
   so passwords and keys are offered) before and after Show QR codes, paused on a complete code. Keep the transfer
   code and its explanation visible; these are disposable demo transfers with no real credentials.
7. `capabilities.png`: Settings › Adyen capabilities, showing the simulator matrix. Never enter real credentials or
   imply that simulator availability verifies a real terminal.
8. `terminal-tipping.png`: Settings › Tips and donations with terminal tipping Enabled (default on), Dynamic basket
   tip suggestions on, percentage tips before tax off, receipt tipping and Giving Disabled. Restore the defaults
   after this capture.
9. `partial-payment.png`: with Allow partial authorizations on and tipping off, take a custom-item sale of $21.39.
   The simulator's minor-unit `139` suffix approves half, rounded up: $10.70 paid and $10.69 remaining. Capture the
   result with Pay remaining visible; turn the simulated printer off for this capture so Show receipt also fits.
   Restore the printer afterward; do not add this payment to the main history screenshot.
10. `offline-payment.png` and `reconciliation.png`: use a separate empty-history demo fixture with the same products.
    Enable Settings › Simulator › Offline approvals, then approve the $34.00 sale. In its History
    detail, scroll past the receipt buttons to show the full Offline processing and Unconfirmed offline batches rows,
    followed by the receipt preview. Go Home, open Reconciliation and tap Check terminal totals without restarting the
    app: the simulator ledger is in memory. Capture matching $34.00 totals together with the offline-sale and
    one-unconfirmed-batch warning. Restore the main fixture afterward.
11. `checkout.png` shows Charge token: the main fixture's first sale saved a token under its customer reference, so
    the button appears once the lookup answers. Localized fixtures need that sale's references in the language's
    numeric form (`1042`, `2077`).

## Driving the UI

- Find nodes by text in `uiautomator dump` (dialogs are separate windows; tap keypad keys by their labels). A dump
  takes about 2 s: tap known coordinates where the layout is fixed.
- Switches have no text in the dump: tap the n-th `checkable` node.
- The PIN pad needs OK after the digits.
- Expand the printer sheet by dragging its handle upward, not by scrolling the receipt; this keeps the business
  details at the top. Find the handle's bounds first: starting above it taps the scrim and dismisses the sheet.
  Press Back twice from the expanded sheet; the first only collapses it. A merchant copy comes
  from the sale's history detail (Print merchant copy); the result screen's Print receipt prints the cardholder copy.
- Focusing a text field scrolls Checkout to its end; swipe to show Save card and its full consent explanation,
  with the cart totals above. Avoid stopping halfway through a text row or leaving a switch behind fixed actions.
  Recheck this in every language: Japanese consent is longer and needs a smaller scroll adjustment.
- The simulator picks the card brand and wallet at random, so a new capture may show another brand than History's
  seeded sales. Keep the approved sale, receipt and refund on the same payment; keep image descriptions accurate
  about the visible portion of a long receipt. Review a contact sheet, PNG dimensions and palette sizes before
  running website checks. When a referenced image changes, regenerate `social.png` too.

## Social image and site rendering

- `scripts/docs-assets social` generates `social.png` (1200×630) from the checked-in `social.html` template and
  `social-copy.json` beside this skill. Keep their original design, frame insets and demo disclosures. It renders all
  seven languages with local assets only; no host paths or browser versions are embedded in a temporary generator.
- The site's terminal frames are original SVG illustrations: keep their bottom bezels blank (no model labels) and the
  AMS1's top free of an NFC symbol; `./gradlew :website-test:check` checks this.
- Use `MINIMPOS_CHROME` for Playwright's installed `chrome-headless-shell` if regular Chrome crashes in the sandbox.
  The existing JDK browser driver now performs the desktop/phone layout audit in `GuideLayoutTest`; inspect its report
  screenshots rather than recreating a DevTools driver. Guide frames are up to 300 px wide, with captions up to 420 px;
  paired wallet screenshots stack on phones. Check that images load, captions stay readable and pages do not overflow.
- Let wallet logos finish loading from Adyen's public CDN before capturing. They are decorative and cached; unavailable
  images fall back to a wallet icon without hiding the name or blocking selection.

## AMS1-sized emulator

- AVD `MiniMpos_AMS1_480x800` (API 33 google_apis arm64, "Nexus S" profile edited to 480×800 /
  `hw.lcd.density=240`, `hw.keyboard=no`, `hw.mainKeys = no`). Headless:
  `emulator -avd MiniMpos_AMS1_480x800 -no-window -no-snapshot -gpu swiftshader_indirect -port 5560`.
- For a realistic 48 dp nav bar: `adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton` and
  `... disable com.android.internal.systemui.navbar.gestural` (with both enabled the insets stay at 24 dp).
- `adb shell settings put global device_name AMS1-000168223606144` makes the debug app behave as on a terminal.
