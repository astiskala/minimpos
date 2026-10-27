/*
 * The setup helper: turns connection and optional SMTP details typed into the form into the QR codes Mini mPOS reads under
 * "Set up from another device", entirely in the browser. The format is the app's (see TransferCodec, QrChunks and
 * TransferSeal in the repository): a version 6 transfer with the connection (JSON) and the secrets, sealed with a
 * 12-character transfer code that the page shows and the operator types on the device.
 *
 * Transfer: Base45(version 6 | CRC-32 of the body | raw DEFLATE of the body), where the body is a varint of the
 * sections (4: sealed secrets, 8: connection, 16: receipt logo) followed by each as a varint length and its bytes. The body is stored
 * in uncompressed DEFLATE blocks: sealed secrets do not compress, and any inflater reads stored blocks.
 * Sealed secrets: version 2 | PBKDF2 iterations (4) | salt (16) | IV (12) | AES-256-GCM ciphertext and tag, with the
 * key PBKDF2-HMAC-SHA256 of the code without separators. Complete setups have a code and seal, even without secrets; logo-only transfers are unsealed.
 * GCM authenticates the uncompressed public body: public section flags, length-prefixed connection JSON and logo record.
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
  const TRANSFER_VERSION = 6;
  const SEAL_VERSION = 2;
  const SECTION_SECRETS = 4;
  const SECTION_CONNECTION = 8;
  const SECTION_LOGO = 16;
  const STORED_BLOCK = 0xffff;
  const CUSTOMER_AREA = { test: "https://ca-test.adyen.com/ca/ui/", live: "https://ca-live.adyen.com/ca/ui/" };
  const CONNECTION_FIELDS = [
    "host", "poiId", "keyIdentifier", "merchantAccount", "liveUrlPrefix", "storeId",
    "smtpHost", "smtpSecurity", "smtpUsername", "smtpFromAddress", "smtpFromName",
    "receiptBusinessName", "receiptAddressLines", "receiptPhone", "receiptTaxId", "receiptTitle", "receiptFooter",
  ];
  const SECRET_FIELDS = {
    passphrase: "TERMINAL_PASSPHRASE", apiKey: "ADYEN_API_KEY",
    smtpPassword: "SMTP_PASSWORD",
  };

  const results = document.getElementById("setup-codes");
  const status = document.getElementById("setup-status");
  const qrBox = document.getElementById("setup-qr");
  const position = document.getElementById("setup-position");
  const codeCard = document.getElementById("setup-code-card");
  const codeText = document.getElementById("setup-code");
  const pauseButton = document.getElementById("setup-pause");
  const resultTitle = document.getElementById("setup-codes-title");
  const summary = {
    destination: document.getElementById("setup-summary-destination"),
    environment: document.getElementById("setup-summary-environment"),
    setupMode: document.getElementById("setup-summary-mode"),
    includeReceipt: document.getElementById("setup-summary-receipt"),
    includeSmtp: document.getElementById("setup-summary-email"),
  };
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

  const publicBody = (connection, logo) => {
    const parts = [varint((connection ? SECTION_CONNECTION : 0) | (logo ? SECTION_LOGO : 0))];
    if (connection) {
      const json = encoder.encode(JSON.stringify(connection));
      parts.push(varint(json.length), json);
    }
    if (logo) parts.push(varint(logo.length), logo);
    return concat(...parts);
  };

  const seal = async (plaintext, code, connection, logo) => {
    const additionalData = publicBody(connection, logo);
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

  const transfer = (sealed, connection, logo) => {
    const publicBytes = publicBody(connection, logo);
    const sections = publicBytes[0] | (sealed ? SECTION_SECRETS : 0);
    const body = concat(varint(sections), ...(sealed ? [varint(sealed.length), sealed] : []), publicBytes.subarray(1));
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
  const logoOnly = () => form.elements.make.value === "logo";
  const refresh = () => {
    document.getElementById("complete-fields").hidden = logoOnly();
    for (const group of document.querySelectorAll("#complete-fields > fieldset, #complete-fields > details > fieldset")) group.disabled = logoOnly();
    document.getElementById("logo-fields").hidden = !logoOnly() && form.elements.includeReceipt.value !== "yes";
    for (const group of form.querySelectorAll("fieldset[data-for], fieldset[data-mode], fieldset[data-email], fieldset[data-receipt-settings]")) {
      const wanted = !logoOnly() && (!group.dataset.for || group.dataset.for.split(" ").includes(destination())) &&
        (!group.dataset.env || group.dataset.env === environment()) &&
        (!group.dataset.mode || group.dataset.mode === setupMode()) &&
        (!group.dataset.email || form.elements.includeSmtp.value === "yes") &&
        (!group.dataset.receiptSettings || form.elements.includeReceipt.value === "yes");
      group.hidden = !wanted;
      group.disabled = !wanted;
    }
    for (const group of form.querySelectorAll("[data-receipt]")) {
      const manual = form.elements[group.dataset.receipt].value === "manual";
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
    for (const value of Object.values(summary)) value.textContent = "";
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

  let logoSource = null;
  let convertedLogo = null;
  let logoRevision = 0;
  let logoPrepared = null;
  let logoFrame = null;
  let logoResetThreshold = false;
  const cancelLogoConversion = () => {
    if (logoFrame !== null) cancelAnimationFrame(logoFrame);
    logoFrame = null;
    logoResetThreshold = false;
  };
  const logoStatus = document.getElementById("logo-status");
  const preview = document.getElementById("logo-preview");
  const logoRecord = () => convertedLogo && concat(varint(convertedLogo.width), varint(convertedLogo.height), convertedLogo.bits);
  const reconvert = (resetThreshold = false, detectInvert = false) => {
    clear();
    if (!logoSource) return;
    const invert = detectInvert ? null : form.elements.logoInvert.checked;
    const trim = form.elements.logoTrim.checked, size = form.elements.logoSize.value;
    if (!logoPrepared || logoPrepared.source !== logoSource || logoPrepared.invert !== invert ||
        logoPrepared.trim !== trim || logoPrepared.size !== size) {
      const value = MiniMposLogo.prepare(logoSource.width, logoSource.height, logoSource.data, {invert, trim, size});
      logoPrepared = {source: logoSource, invert: value.inverted ?? invert, trim, size, value};
    }
    convertedLogo = MiniMposLogo.quantize(logoPrepared.value, {
      mode: form.elements.logoMode.value,
      threshold: resetThreshold ? null : Number(form.elements.logoThreshold.value),
    });
    const ctx = preview.getContext("2d");
    ctx.fillStyle = "white"; ctx.fillRect(0, 0, preview.width, preview.height);
    if (convertedLogo.kind === "blank") {
      convertedLogo = null;
      logoStatus.textContent = message("msgLogoBlank");
      return;
    }
    logoStatus.textContent = "";
    if (resetThreshold) form.elements.logoThreshold.value = convertedLogo.threshold;
    if (detectInvert) form.elements.logoInvert.checked = convertedLogo.inverted;
    ctx.fillStyle = "black";
    const stride = Math.ceil(convertedLogo.width / 8), offset = Math.trunc((384 - convertedLogo.width) / 2);
    for (let y = 0; y < convertedLogo.height; y++) for (let x = 0; x < convertedLogo.width; x++) {
      if (convertedLogo.bits[y * stride + (x >> 3)] & (128 >> (x & 7))) ctx.fillRect(offset + x, y, 1, 1);
    }
  };
  const flushLogoConversion = () => {
    if (logoFrame === null) return;
    const resetThreshold = logoResetThreshold;
    cancelLogoConversion();
    reconvert(resetThreshold);
  };
  const removeLogo = () => {
    logoRevision++;
    cancelLogoConversion();
    logoPrepared = null;
    logoSource = null; convertedLogo = null;
    form.elements.logo.value = "";
    logoStatus.textContent = "";
    document.getElementById("logo-editor").hidden = true;
    clear();
  };
  document.getElementById("logo-remove").addEventListener("click", removeLogo);
  form.addEventListener("reset", removeLogo);
  for (const name of ["logoMode", "logoThreshold", "logoInvert", "logoTrim", "logoSize"]) {
    form.elements[name].addEventListener("input", () => {
      clear();
      convertedLogo = null;
      if (!logoSource) return;
      if (name === "logoThreshold") logoResetThreshold = false;
      else if (name === "logoMode") logoResetThreshold = true;
      if (logoFrame === null) logoFrame = requestAnimationFrame(flushLogoConversion);
    });
  }
  /** Read bounded raster metadata before asking the platform for a bitmap; never buffer the whole source file. */
  const rasterDimensions = async (file) => {
    const read = async (offset, count) => {
      if (offset < 0 || offset + count > file.size) throw new Error("Truncated image header");
      return new Uint8Array(await file.slice(offset, offset + count).arrayBuffer());
    };
    const first = await read(0, Math.min(file.size, 30));
    const matches = (offset, bytes) => bytes.every((value, index) => first[offset + index] === value);
    const uint32 = offset => new DataView(first.buffer).getUint32(offset);
    if (matches(0, [137, 80, 78, 71, 13, 10, 26, 10])) {
      if (first.length < 29 || uint32(8) !== 13 || !matches(12, [73, 72, 68, 82])) throw new Error("Invalid PNG header");
      return {width: uint32(16), height: uint32(20)};
    }
    if (matches(0, [82, 73, 70, 70]) && matches(8, [87, 69, 66, 80])) {
      if (first.length < 20) throw new Error("Invalid WebP header");
      const view = new DataView(first.buffer);
      const end = view.getUint32(4, true) + 8;
      const chunkSize = view.getUint32(16, true);
      if (end > file.size || end < 20 + chunkSize || 20 + chunkSize > file.size) throw new Error("Truncated WebP chunk");
      const uint24 = offset => first[offset] | (first[offset + 1] << 8) | (first[offset + 2] << 16);
      if (matches(12, [86, 80, 56, 88]) && chunkSize === 10 && first.length >= 30) {
        return {width: uint24(24) + 1, height: uint24(27) + 1};
      }
      if (matches(12, [86, 80, 56, 76]) && chunkSize >= 5 && first.length >= 25 && first[20] === 47 && (first[24] >>> 5) === 0) {
        return {width: 1 + (first[21] | ((first[22] & 63) << 8)), height: 1 + ((first[22] >>> 6) | (first[23] << 2) | ((first[24] & 15) << 10))};
      }
      if (matches(12, [86, 80, 56, 32]) && chunkSize >= 10 && first.length >= 30 && matches(23, [157, 1, 42]) && !(first[20] & 1)) {
        return {width: view.getUint16(26, true) & 16383, height: view.getUint16(28, true) & 16383};
      }
      throw new Error("Invalid WebP image header");
    }
    if (matches(0, [255, 216])) {
      let offset = 2;
      // JPEG metadata may be large. Limit both skipped metadata and marker work before the first frame header.
      for (let markers = 0; markers < 256 && offset <= 1024 * 1024; markers++) {
        const marker = await read(offset, 2);
        if (marker[0] !== 255 || marker[1] === 0) throw new Error("Invalid JPEG marker");
        if (marker[1] === 255) { offset++; continue; }
        if (marker[1] === 217 || marker[1] === 218 || marker[1] === 216) throw new Error("Missing JPEG frame");
        if (marker[1] === 1 || (marker[1] >= 208 && marker[1] <= 215)) { offset += 2; continue; }
        const lengthBytes = await read(offset + 2, 2);
        const length = (lengthBytes[0] << 8) | lengthBytes[1];
        if (length < 2 || offset + 2 + length > file.size) throw new Error("Truncated JPEG segment");
        if (marker[1] >= 192 && marker[1] <= 207 && ![196, 200, 204].includes(marker[1])) {
          if (length < 8) throw new Error("Invalid JPEG frame");
          const frame = await read(offset + 4, 5);
          return {width: (frame[3] << 8) | frame[4], height: (frame[1] << 8) | frame[2]};
        }
        offset += 2 + length;
      }
      throw new Error("JPEG metadata limit exceeded");
    }
    throw new Error("Unsupported image header");
  };

  /** EXIF orientation only affects oversize resize dimensions; the browser still applies the actual transform. */
  const rasterOrientation = async (file) => {
    let metadataBytes = 0;
    const read = async (offset, length) => {
      metadataBytes += length;
      if (length < 0 || length > 65536 || metadataBytes > 1024 * 1024 || offset + length > file.size) throw new Error("Image metadata limit exceeded");
      return new Uint8Array(await file.slice(offset, offset + length).arrayBuffer());
    };
    const tiffOrientation = bytes => {
      // Some WebP writers include JPEG's Exif prefix, while PNG eXIf stores TIFF directly.
      if (bytes.length >= 6 && bytes[0] === 69 && bytes[1] === 120 && bytes[2] === 105 && bytes[3] === 102 && bytes[4] === 0 && bytes[5] === 0) bytes = bytes.subarray(6);
      if (bytes.length < 8) return 1;
      const little = bytes[0] === 73 && bytes[1] === 73;
      if (!little && !(bytes[0] === 77 && bytes[1] === 77)) return 1;
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      if (view.getUint16(2, little) !== 42) return 1;
      const offset = view.getUint32(4, little);
      if (offset < 8 || offset + 2 > bytes.length) return 1;
      const count = view.getUint16(offset, little);
      if (count > 256 || offset + 2 + count * 12 > bytes.length) return 1;
      for (let index = 0; index < count; index++) {
        const entry = offset + 2 + index * 12;
        if (view.getUint16(entry, little) === 274 && view.getUint16(entry + 2, little) === 3 && view.getUint32(entry + 4, little) === 1) {
          const orientation = view.getUint16(entry + 8, little);
          return orientation >= 1 && orientation <= 8 ? orientation : 1;
        }
      }
      return 1;
    };
    const first = await read(0, Math.min(file.size, 12));
    const text = bytes => String.fromCharCode(...bytes);
    if (first[0] === 255 && first[1] === 216) {
      let offset = 2;
      for (let markers = 0; markers < 256 && offset <= 1024 * 1024; markers++) {
        const marker = await read(offset, 2);
        if (marker[0] !== 255 || marker[1] === 0) throw new Error("Invalid JPEG marker");
        if (marker[1] === 255) { offset++; continue; }
        if (marker[1] === 217 || marker[1] === 218) return 1;
        if (marker[1] === 1 || (marker[1] >= 208 && marker[1] <= 215)) { offset += 2; continue; }
        const size = await read(offset + 2, 2);
        const length = (size[0] << 8) | size[1];
        if (length < 2 || offset + 2 + length > file.size) throw new Error("Truncated JPEG segment");
        if (marker[1] === 225) {
          const bytes = await read(offset + 4, length - 2);
          if (text(bytes.subarray(0, 6)) === "Exif\0\0") return tiffOrientation(bytes);
        }
        offset += 2 + length;
      }
      throw new Error("JPEG metadata limit exceeded");
    }
    const png = first[0] === 137;
    let offset = png ? 8 : 12;
    for (let chunks = 0; chunks < 256 && offset + 8 <= file.size; chunks++) {
      const header = await read(offset, 8);
      const view = new DataView(header.buffer);
      const length = view.getUint32(png ? 0 : 4, !png);
      const name = text(header.subarray(png ? 4 : 0, png ? 8 : 4));
      const end = offset + 8 + length + (png ? 4 : length % 2);
      if (end > file.size) throw new Error("Truncated image chunk");
      if (name === (png ? "eXIf" : "EXIF")) return tiffOrientation(await read(offset + 8, length));
      if (name === "IEND") return 1;
      offset = end;
    }
    if (offset === file.size) return 1;
    throw new Error("Image metadata limit exceeded");
  };

  form.elements.logo.addEventListener("change", async () => {
    clear();
    logoStatus.textContent = "";
    const revision = ++logoRevision;
    cancelLogoConversion();
    logoPrepared = null;
    logoSource = null; convertedLogo = null;
    document.getElementById("logo-editor").hidden = true;
    const file = form.elements.logo.files[0];
    if (!file) return;
    let decoded, url;
    try {
      if (file.type === "image/svg+xml") {
        url = URL.createObjectURL(file);
        decoded = new Image(); decoded.src = url;
        await decoded.decode();
      } else {
        const dimensions = await rasterDimensions(file);
        if (revision !== logoRevision) return;
        if (!Number.isInteger(dimensions.width) || !Number.isInteger(dimensions.height) || dimensions.width < 1 || dimensions.height < 1) throw new Error("Invalid image dimensions");
        const ratio = Math.min(1, 4096 / Math.max(dimensions.width, dimensions.height));
        if (ratio < 1) {
          const orientation = await rasterOrientation(file);
          if (revision !== logoRevision) return;
          if (orientation >= 5) [dimensions.width, dimensions.height] = [dimensions.height, dimensions.width];
        }
        decoded = ratio < 1 ? await createImageBitmap(file, {
          resizeWidth: Math.max(1, Math.floor(dimensions.width * ratio)),
          resizeHeight: Math.max(1, Math.floor(dimensions.height * ratio)),
          resizeQuality: "high",
        }) : await createImageBitmap(file);
      }
      const width = decoded.width || decoded.naturalWidth, height = decoded.height || decoded.naturalHeight;
      if (!width || !height) throw new Error("Invalid image dimensions");
      const scale = Math.min(1, 4096 / Math.max(width, height));
      const canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.floor(width * scale)); canvas.height = Math.max(1, Math.floor(height * scale));
      const context = canvas.getContext("2d", {willReadFrequently: true});
      context.drawImage(decoded, 0, 0, canvas.width, canvas.height);
      if (revision !== logoRevision) return;
      logoSource = context.getImageData(0, 0, canvas.width, canvas.height);
      document.getElementById("logo-editor").hidden = false;
      reconvert(true, true);
    } catch (error) {
      if (revision === logoRevision) logoStatus.textContent = message("msgLogoUnreadable");
    } finally {
      decoded?.close?.();
      if (url) URL.revokeObjectURL(url);
    }
  });

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    flushLogoConversion();
    if (!window.crypto?.subtle || typeof qrcodegen === "undefined") {
      status.textContent = message("msgUnsupported");
      return;
    }
    clear();
    const logo = (logoOnly() || form.elements.includeReceipt.value === "yes") ? logoRecord() : null;
    if (logoOnly() && !logo) {
      status.textContent = message("msgLogoRequired");
      return;
    }
    const started = generation;
    status.textContent = message("msgMaking");
    const data = new FormData(form);
    const connection = { destination: destination() };
    if (setupMode() === "automatic") connection.automatic = true;
    connection.environment = environment().toUpperCase();
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
    const code = logoOnly() ? "" : randomString(CODE_ALPHABET, CODE_LENGTH).match(/.{4}/g).join("-");
    try {
      const sealed = logoOnly() ? null : await seal(encoder.encode(JSON.stringify(secrets)), code, connection, logo);
      if (started !== generation) return;
      codes = chunks(transfer(sealed, logoOnly() ? null : connection, logo));
    } catch (error) {
      if (started === generation) status.textContent = message("msgUnsupported");
      return;
    }
    codeCard.hidden = !code;
    codeText.textContent = code || "";
    position.hidden = codes.length < 2;
    for (const control of results.querySelectorAll(".setup-controls button")) control.hidden = codes.length < 2;
    results.hidden = false;
    document.querySelector(".setup-summary").hidden = logoOnly();
    for (const [name, value] of Object.entries(summary)) {
      value.textContent = form.querySelector(`input[name="${name}"]:checked`).closest("label").textContent.trim().replace(/\s+/g, " ");
    }
    summary.setupMode.parentElement.hidden = destination() === "tapToPay";
    status.textContent = message("msgReady", { total: codes.length });
    show(0);
    play(true);
    resultTitle.focus({ preventScroll: true });
    results.scrollIntoView({ block: "start" });
  });

  form.hidden = false;
  document.getElementById("setup-loading")?.remove();
  refresh();
})();
