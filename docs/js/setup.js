/*
 * The setup helper: turns connection and optional SMTP details typed into the form into the QR codes Mini mPOS reads under
 * "Set up from another device", entirely in the browser. The format is the app's (see TransferCodec, QrChunks and
 * TransferSeal in the repository): a version 5 transfer with the connection (JSON) and the secrets, sealed with a
 * 12-character transfer code that the page shows and the operator types on the device.
 *
 * Transfer: Base45(version 5 | CRC-32 of the body | raw DEFLATE of the body), where the body is a varint of the
 * sections (4: sealed secrets, 8: connection) followed by each as a varint length and its bytes. The body is stored
 * in uncompressed DEFLATE blocks: sealed secrets do not compress, and any inflater reads stored blocks.
 * Sealed secrets: version 2 | PBKDF2 iterations (4) | salt (16) | IV (12) | AES-256-GCM ciphertext and tag, with the
 * key PBKDF2-HMAC-SHA256 of the code without separators. Every transfer has a code and seal, even without secrets.
 * GCM authenticates the uncompressed public body: connection section flag, varint JSON length, exact UTF-8 JSON.
 * QR codes: "MPC1:<set>:<n>/<total>:<data>".
 */
"use strict";
(() => {
  const form = document.getElementById("setup-form");
  if (!form) return;

  const CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
  const SET_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  const BASE45 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
  const CODE_LENGTH = 12;
  const ITERATIONS = 150000;
  const CHUNK_CHARS = 480;
  const ADVANCE_MILLIS = 1800;
  const TRANSFER_VERSION = 5;
  const SEAL_VERSION = 2;
  const SECTION_SECRETS = 4;
  const SECTION_CONNECTION = 8;
  const STORED_BLOCK = 0xffff;
  const CUSTOMER_AREA = { test: "https://ca-test.adyen.com/ca/ui/", live: "https://ca-live.adyen.com/ca/ui/" };
  const CONNECTION_FIELDS = [
    "host", "poiId", "keyIdentifier", "merchantAccount", "liveUrlPrefix", "storeId",
    "smtpHost", "smtpSecurity", "smtpUsername", "smtpFromAddress", "smtpFromName",
    "receiptBusinessName", "receiptAddressLines", "receiptPhone", "receiptTaxId", "receiptTitle", "receiptFooter",
  ];
  const SECRET_FIELDS = {
    passphrase: "TERMINAL_PASSPHRASE", apiKey: "ADYEN_API_KEY",
    paymentsAppApiKey: "PAYMENTS_APP_API_KEY", smtpPassword: "SMTP_PASSWORD",
  };

  const results = document.getElementById("setup-codes");
  const status = document.getElementById("setup-status");
  const qrBox = document.getElementById("setup-qr");
  const position = document.getElementById("setup-position");
  const codeCard = document.getElementById("setup-code-card");
  const codeText = document.getElementById("setup-code");
  const pauseButton = document.getElementById("setup-pause");
  const encoder = new TextEncoder();
  let codes = [];
  let shown = 0;
  let timer = null;
  let generation = 0;

  /** The text of the form's data-[key] attribute, with {name} placeholders filled in from values. */
  const message = (key, values = {}) => form.dataset[key].replace(/\{(\w+)\}/g, (_, name) => String(values[name]));

  const randomIndex = (size) => {
    const limit = 256 - (256 % size);
    const byte = new Uint8Array(1);
    do crypto.getRandomValues(byte); while (byte[0] >= limit);
    return byte[0] % size;
  };

  const randomString = (alphabet, length) => Array.from({ length }, () => alphabet[randomIndex(alphabet.length)]).join("");

  const concat = (...parts) => {
    const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
    let offset = 0;
    for (const part of parts) {
      out.set(part, offset);
      offset += part.length;
    }
    return out;
  };

  const varint = (value) => {
    const out = [];
    let rest = value;
    while (rest >= 0x80) {
      out.push((rest & 0x7f) | 0x80);
      rest >>>= 7;
    }
    out.push(rest);
    return Uint8Array.from(out);
  };

  const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c >>> 0;
  });

  const crc32 = (bytes) => {
    let crc = 0xffffffff;
    for (const byte of bytes) crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
    return (crc ^ 0xffffffff) >>> 0;
  };

  const uint32 = (value) => {
    const out = new Uint8Array(4);
    new DataView(out.buffer).setUint32(0, value);
    return out;
  };

  /** Raw DEFLATE of bytes in stored (uncompressed) blocks. */
  const deflateStored = (bytes) => {
    const blocks = [];
    let offset = 0;
    do {
      const part = bytes.subarray(offset, offset + STORED_BLOCK);
      offset += part.length;
      const last = offset >= bytes.length;
      blocks.push(Uint8Array.of(last ? 1 : 0, part.length & 0xff, part.length >>> 8, ~part.length & 0xff, (~part.length >>> 8) & 0xff), part);
    } while (offset < bytes.length);
    return concat(...blocks);
  };

  const base45 = (bytes) => {
    let out = "";
    for (let i = 0; i < bytes.length; i += 2) {
      if (i + 1 < bytes.length) {
        let n = (bytes[i] << 8) | bytes[i + 1];
        const c = n % 45;
        n = Math.floor(n / 45);
        out += BASE45[c] + BASE45[n % 45] + BASE45[Math.floor(n / 45)];
      } else {
        out += BASE45[bytes[i] % 45] + BASE45[Math.floor(bytes[i] / 45)];
      }
    }
    return out;
  };

  const seal = async (plaintext, code, connection) => {
    const json = encoder.encode(JSON.stringify(connection));
    const additionalData = concat(varint(SECTION_CONNECTION), varint(json.length), json);
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const material = await crypto.subtle.importKey("raw", encoder.encode(code.replace(/-/g, "")), "PBKDF2", false, ["deriveKey"]);
    const key = await crypto.subtle.deriveKey(
      { name: "PBKDF2", hash: "SHA-256", salt, iterations: ITERATIONS },
      material,
      { name: "AES-GCM", length: 256 },
      false,
      ["encrypt"],
    );
    const ciphertext = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData, tagLength: 128 }, key, plaintext));
    return concat(Uint8Array.of(SEAL_VERSION), uint32(ITERATIONS), salt, iv, ciphertext);
  };

  const transfer = (sealed, connection) => {
    const json = encoder.encode(JSON.stringify(connection));
    const parts = [varint((sealed ? SECTION_SECRETS : 0) | SECTION_CONNECTION)];
    if (sealed) parts.push(varint(sealed.length), sealed);
    parts.push(varint(json.length), json);
    const body = concat(...parts);
    return base45(concat(Uint8Array.of(TRANSFER_VERSION), uint32(crc32(body)), deflateStored(body)));
  };

  const chunks = (payload) => {
    const set = randomString(SET_ALPHABET, 4);
    const parts = payload.match(new RegExp(`[^]{1,${CHUNK_CHARS}}`, "g")) || [""];
    return parts.map((part, i) => `MPC1:${set}:${i + 1}/${parts.length}:${part}`);
  };

  const qrSvg = (text, label) => {
    const qr = qrcodegen.QrCode.encodeText(text, qrcodegen.QrCode.Ecc.MEDIUM);
    const border = 4;
    const size = qr.size + border * 2;
    let path = "";
    for (let y = 0; y < qr.size; y++) {
      for (let x = 0; x < qr.size; x++) if (qr.getModule(x, y)) path += `M${x + border},${y + border}h1v1h-1z`;
    }
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", `0 0 ${size} ${size}`);
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", label);
    svg.setAttribute("shape-rendering", "crispEdges");
    const background = document.createElementNS(ns, "rect");
    background.setAttribute("width", "100%");
    background.setAttribute("height", "100%");
    background.setAttribute("fill", "#fff");
    const modules = document.createElementNS(ns, "path");
    modules.setAttribute("d", path);
    modules.setAttribute("fill", "#000");
    svg.append(background, modules);
    return svg;
  };

  const destination = () => form.elements.destination.value;
  const environment = () => form.elements.environment.value;
  const setupMode = () => destination() === "tapToPay" ? "manual" : form.elements.setupMode.value;

  /** Hidden destination, environment and mode fields are disabled, so they are neither checked nor read. */
  const refresh = () => {
    for (const group of form.querySelectorAll("fieldset[data-for], fieldset[data-mode], fieldset[data-email], fieldset[data-receipt-settings]")) {
      const wanted = (!group.dataset.for || group.dataset.for.split(" ").includes(destination())) &&
        (!group.dataset.env || group.dataset.env === environment()) &&
        (!group.dataset.mode || group.dataset.mode === setupMode()) &&
        (!group.dataset.email || form.elements.includeSmtp.value === "yes") &&
        (!group.dataset.receiptSettings || form.elements.includeReceipt.value === "yes");
      group.hidden = !wanted;
      group.disabled = !wanted;
    }
    for (const group of form.querySelectorAll("[data-receipt]")) {
      const manual = form.elements.includeReceipt.value === "yes" && form.elements[group.dataset.receipt].value === "manual";
      group.hidden = !manual;
      for (const input of group.querySelectorAll("input, textarea")) input.disabled = !manual;
    }
    for (const input of form.querySelectorAll("[data-required-for]")) {
      input.required = input.dataset.requiredFor.split(" ").includes(destination());
    }
    for (const text of form.querySelectorAll("[data-only]")) text.hidden = !text.dataset.only.split(" ").includes(destination());
    for (const link of document.querySelectorAll("a[data-ca]")) link.href = CUSTOMER_AREA[environment()] + link.dataset.ca;
  };

  const show = (index) => {
    shown = (index + codes.length) % codes.length;
    qrBox.replaceChildren(qrSvg(codes[shown], message("msgCodeOf", { n: shown + 1, total: codes.length })));
    position.textContent = message("msgCodeOf", { n: shown + 1, total: codes.length });
  };

  const play = (playing) => {
    clearInterval(timer);
    timer = playing && codes.length > 1 ? setInterval(() => show(shown + 1), ADVANCE_MILLIS) : null;
    pauseButton.textContent = message(timer ? "msgPause" : "msgPlay");
  };

  const clear = () => {
    generation++;
    play(false);
    codes = [];
    qrBox.replaceChildren();
    codeText.textContent = "";
    status.textContent = "";
    results.hidden = true;
  };

  form.addEventListener("change", () => {
    refresh();
    clear();
  });
  form.addEventListener("input", clear);
  form.addEventListener("reset", () => {
    clear();
    setTimeout(refresh);
  });
  document.getElementById("setup-previous").addEventListener("click", () => {
    play(false);
    show(shown - 1);
  });
  document.getElementById("setup-next").addEventListener("click", () => {
    play(false);
    show(shown + 1);
  });
  pauseButton.addEventListener("click", () => play(!timer));

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!window.crypto?.subtle || typeof qrcodegen === "undefined") {
      status.textContent = message("msgUnsupported");
      return;
    }
    clear();
    const started = generation;
    status.textContent = message("msgMaking");
    const data = new FormData(form);
    const connection = { destination: destination() };
    if (setupMode() === "automatic") connection.automatic = true;
    if (["network", "cloud"].includes(destination())) connection.environment = environment().toUpperCase();
    for (const name of CONNECTION_FIELDS) {
      const value = (data.get(name) || "").trim();
      if (value) connection[name] = value;
    }
    for (const [source, flag] of [
      ["receiptNameSource", "importReceiptName"],
      ["receiptAddressSource", "importReceiptAddress"],
      ["receiptPhoneSource", "importReceiptPhone"],
    ]) {
      if (data.get(source) !== "adyen") connection[flag] = false;
    }
    const version = Number.parseInt(data.get("keyVersion") || "", 10);
    if (Number.isInteger(version) && (connection.keyIdentifier || (data.get("passphrase") || "").trim())) {
      connection.keyVersion = version;
    }
    const port = Number.parseInt(data.get("smtpPort") || "", 10);
    if (Number.isInteger(port)) connection.smtpPort = port;
    const secrets = {};
    for (const [name, secret] of Object.entries(SECRET_FIELDS)) {
      const value = data.get(name) || "";
      if (value.trim()) secrets[secret] = ["passphrase", "smtpPassword"].includes(name) ? value : value.trim();
    }
    const code = randomString(CODE_ALPHABET, CODE_LENGTH).match(/.{4}/g).join("-");
    try {
      const sealed = await seal(encoder.encode(JSON.stringify(secrets)), code, connection);
      if (started !== generation) return;
      codes = chunks(transfer(sealed, connection));
    } catch (error) {
      if (started === generation) status.textContent = message("msgUnsupported");
      return;
    }
    codeCard.hidden = !code;
    codeText.textContent = code || "";
    position.hidden = codes.length < 2;
    for (const control of results.querySelectorAll(".setup-controls button")) control.hidden = codes.length < 2;
    results.hidden = false;
    status.textContent = message("msgReady", { total: codes.length });
    show(0);
    play(true);
    results.scrollIntoView({ block: "start" });
  });

  form.hidden = false;
  document.getElementById("setup-noscript")?.remove();
  refresh();
})();
