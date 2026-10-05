"use strict";

const assert = require("node:assert/strict");
const { createDecipheriv, pbkdf2Sync, webcrypto } = require("node:crypto");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const { test } = require("node:test");
const { setImmediate: tick } = require("node:timers/promises");
const { runInNewContext } = require("node:vm");
const { inflateRawSync } = require("node:zlib");

const source = readFileSync(resolve(__dirname, "../../../../docs/js/setup.js"), "utf8");

/** The helper's DOM and deferred crypto, so tests control completion without a browser or network. */
const helper = ({ realCrypto = false } = {}) => {
  const element = () => ({
    hidden: true,
    textContent: "",
    listeners: new Map(),
    addEventListener(name, listener) { this.listeners.set(name, listener); },
    querySelectorAll() { return []; },
    setAttribute() {},
    append() {},
    replaceChildren() {},
    scrollIntoView() {},
  });
  const ids = [
    "setup-form", "setup-codes", "setup-status", "setup-qr", "setup-position",
    "setup-code-card", "setup-code", "setup-pause", "setup-previous", "setup-next",
  ];
  const nodes = Object.fromEntries(ids.map((id) => [id, element()]));
  const form = nodes["setup-form"];
  form.elements = {
    destination: { value: "network" }, environment: { value: "test" },
    setupMode: { value: "manual" }, includeSmtp: { value: "no" },
  };
  const smtp = { ...element(), dataset: { email: "true" }, disabled: true };
  smtp.names = ["smtpHost", "smtpPort", "smtpSecurity", "smtpUsername", "smtpPassword", "smtpFromAddress", "smtpFromName"];
  const account = {
    ...element(), dataset: { for: "thisTerminal network cloud tapToPay", mode: "manual" },
    names: ["merchantAccount"],
  };
  const sharedKey = {
    ...element(), dataset: { for: "thisTerminal network tapToPay", mode: "manual" },
    names: ["keyIdentifier", "keyVersion", "passphrase"],
  };
  const boarding = { ...element(), dataset: { for: "tapToPay" }, names: ["paymentsAppApiKey", "storeId"] };
  const mode = { ...element(), dataset: { for: "thisTerminal network cloud" }, names: ["setupMode"] };
  const groups = [smtp, account, sharedKey, boarding, mode];
  const requiredFields = sharedKey.names.map((name) => ({
    name, required: true, dataset: { requiredFor: "thisTerminal network" },
  }));
  form.querySelectorAll = (selector) => selector.includes("fieldset") ? groups :
    selector === "[data-required-for]" ? requiredFields : [];
  form.dataset = {
    msgCodeOf: "{n}/{total}", msgPause: "Pause", msgPlay: "Play",
    msgMaking: "Making", msgReady: "Ready {total}", msgUnsupported: "Unsupported",
  };
  form.values = new Map([["apiKey", "demo-key"]]);
  const pending = [];
  const drawn = [];
  const crypto = {
    getRandomValues(bytes) { return bytes.fill(0); },
    subtle: realCrypto ? webcrypto.subtle : {
      async importKey() { return {}; },
      async deriveKey() { return {}; },
      encrypt(_algorithm, _key, plaintext) {
        return new Promise((resolve, reject) => pending.push({ resolve: () => resolve(plaintext), reject }));
      },
    },
  };
  runInNewContext(source, {
    document: {
      getElementById(id) { return nodes[id]; },
      querySelectorAll() { return []; },
      createElementNS: element,
    },
    window: { crypto },
    crypto,
    TextEncoder,
    FormData: class {
      constructor(form) {
        this.values = new Map([...form.values].filter(([name]) => {
          const owners = groups.filter((group) => group.names.includes(name));
          return owners.length === 0 || owners.some((group) => !group.disabled);
        }));
      }
      get(name) { return this.values.get(name); }
    },
    qrcodegen: {
      QrCode: {
        Ecc: { MEDIUM: 0 },
        encodeText(text) {
          drawn.push(text);
          return { size: 1, getModule: () => false };
        },
      },
    },
    setTimeout(callback) { callback(); },
    setInterval() { return 1; },
    clearInterval() {},
  });
  return {
    form, pending, drawn, smtp, account, sharedKey, mode, requiredFields,
    results: nodes["setup-codes"],
    status: nodes["setup-status"],
    code: nodes["setup-code"],
    submit: () => form.listeners.get("submit")({ preventDefault() {} }),
    invalidate: (event) => form.listeners.get(event)(),
    next: () => nodes["setup-next"].listeners.get("click")(),
  };
};

/** Reassembles every code produced by the helper, preserving its actual transfer framing. */
const transferBytes = (page) => {
  const total = Number(page.drawn[0].split(":")[2].split("/")[1]);
  while (page.drawn.length < total) page.next();
  const payload = page.drawn.slice(0, total).map((chunk) => chunk.split(":").slice(3).join(":")).join("");
  const alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
  const bytes = [];
  for (let i = 0; i < payload.length; i += 3) {
    const n = alphabet.indexOf(payload[i]) + 45 * alphabet.indexOf(payload[i + 1]) +
      (i + 2 < payload.length ? 2025 * alphabet.indexOf(payload[i + 2]) : 0);
    if (i + 2 < payload.length) bytes.push(n >> 8, n & 255);
    else bytes.push(n);
  }
  return Buffer.from(bytes);
};

/** Reads the current connection and sealed secrets with independent Node crypto and DEFLATE implementations. */
const readTransfer = (page) => {
  const bytes = transferBytes(page);
  assert.equal(bytes[0], 5);
  const body = inflateRawSync(bytes.subarray(5));
  let offset = 0;
  const varint = () => {
    let value = 0;
    let shift = 0;
    let byte;
    do {
      byte = body[offset++];
      value |= (byte & 127) << shift;
      shift += 7;
    } while (byte & 128);
    return value;
  };
  const section = () => {
    const size = varint();
    const data = body.subarray(offset, offset + size);
    offset += size;
    return data;
  };
  const flags = varint();
  assert.equal(flags, 12);
  const sealed = section();
  const connection = JSON.parse(section());
  assert.equal(offset, body.length);
  assert.equal(sealed[0], 1);
  const key = pbkdf2Sync(page.code.textContent.replaceAll("-", ""), sealed.subarray(5, 21), sealed.readUInt32BE(1), 32, "sha256");
  const decipher = createDecipheriv("aes-256-gcm", key, sealed.subarray(21, 33));
  decipher.setAuthTag(sealed.subarray(-16));
  const secrets = JSON.parse(Buffer.concat([decipher.update(sealed.subarray(33, -16)), decipher.final()]));
  return { connection, secrets };
};

for (const event of ["input", "change", "reset"]) {
  test(`${event} discards an in-flight generation and its transfer code`, async () => {
    const page = helper();
    const submission = page.submit();
    await tick();
    assert.equal(page.pending.length, 1);
    page.invalidate(event);
    page.pending[0].resolve();
    await submission;
    assert.equal(page.results.hidden, true);
    assert.equal(page.code.textContent, "");
    assert.equal(page.status.textContent, "");
    assert.equal(page.drawn.length, 0);
  });
}

test("a later submission wins even when the first completes last", async () => {
  const page = helper();
  const first = page.submit();
  await tick();
  page.form.values.set("apiKey", "another-demo-key");
  const second = page.submit();
  await tick();
  assert.equal(page.pending.length, 2);
  page.pending[1].resolve();
  await second;
  assert.equal(page.results.hidden, false);
  assert.match(page.status.textContent, /^Ready /);
  assert.match(page.code.textContent, /^2222-2222-2222$/);
  const latest = page.drawn.slice();
  assert.equal(latest.length, 1);
  page.pending[0].resolve();
  await first;
  assert.deepEqual(page.drawn, latest);
  assert.equal(page.results.hidden, false);
});

test("a superseded crypto failure cannot overwrite a newer success", async () => {
  const page = helper();
  const first = page.submit();
  await tick();
  const second = page.submit();
  await tick();
  page.pending[1].resolve();
  await second;
  const ready = page.status.textContent;
  page.pending[0].reject(new Error("old crypto failure"));
  await first;
  assert.equal(page.status.textContent, ready);
  assert.equal(page.results.hidden, false);
});

test("a current crypto failure is reported without showing codes", async () => {
  const page = helper();
  const submission = page.submit();
  await tick();
  page.pending[0].reject(new Error("crypto unavailable"));
  await submission;
  assert.equal(page.status.textContent, "Unsupported");
  assert.equal(page.results.hidden, true);
  assert.equal(page.code.textContent, "");
});

test("SMTP fields are disabled until opted in and opting out omits even previously typed values", async () => {
  const page = helper({ realCrypto: true });
  assert.equal(page.smtp.disabled, true);
  assert.equal(page.smtp.hidden, true);
  page.form.elements.includeSmtp.value = "yes";
  page.invalidate("change");
  assert.equal(page.smtp.disabled, false);
  assert.equal(page.smtp.hidden, false);
  page.form.values.set("smtpHost", "smtp.example.com");
  page.form.values.set("smtpPassword", "demo-smtp-password");
  page.form.elements.includeSmtp.value = "no";
  page.invalidate("change");
  assert.equal(page.smtp.disabled, true);
  assert.equal(page.smtp.hidden, true);
  await page.submit();
  const { connection, secrets } = readTransfer(page);
  assert.deepEqual(connection, { destination: "network", environment: "TEST" });
  assert.deepEqual(secrets, { ADYEN_API_KEY: "demo-key" });
});

for (const destination of ["thisTerminal", "network", "cloud", "tapToPay"]) {
  for (const mode of ["automatic", "manual"]) {
    test(`SMTP travels with ${destination} ${mode}, with the password sealed and whitespace preserved`, async () => {
      const page = helper({ realCrypto: true });
      page.form.elements.destination.value = destination;
      page.form.elements.setupMode.value = mode;
      page.form.elements.includeSmtp.value = "yes";
      page.invalidate("change");
      for (const [name, value] of Object.entries({
        smtpHost: " smtp.example.com ", smtpPort: "465", smtpSecurity: "SSL",
        smtpUsername: " shop@example.com ", smtpPassword: " demo-smtp-password ",
        smtpFromAddress: " receipts@example.com ", smtpFromName: " Example shop ",
      })) page.form.values.set(name, value);
      await page.submit();
      const { connection, secrets } = readTransfer(page);
      assert.deepEqual(connection, {
        destination,
        ...(mode === "automatic" && destination !== "tapToPay" ? { automatic: true } : {}),
        ...(["network", "cloud"].includes(destination) ? { environment: "TEST" } : {}),
        smtpHost: "smtp.example.com", smtpPort: 465, smtpSecurity: "SSL",
        smtpUsername: "shop@example.com", smtpFromAddress: "receipts@example.com", smtpFromName: "Example shop",
      });
      assert.deepEqual(secrets, { ADYEN_API_KEY: "demo-key", SMTP_PASSWORD: " demo-smtp-password " });
      assert.equal(transferBytes(page).includes(Buffer.from("demo-smtp-password")), false);
    });
  }
}

test("blank optional SMTP fields are omitted rather than clearing saved values", async () => {
  const page = helper({ realCrypto: true });
  page.form.elements.includeSmtp.value = "yes";
  page.invalidate("change");
  for (const name of ["smtpUsername", "smtpPassword", "smtpFromName"]) page.form.values.set(name, " ");
  await page.submit();
  const { connection, secrets } = readTransfer(page);
  assert.deepEqual(connection, { destination: "network", environment: "TEST" });
  assert.deepEqual(secrets, { ADYEN_API_KEY: "demo-key" });
});

for (const mode of ["automatic", "manual"]) {
  test(`Tap to Pay always uses manual key entry even with ${mode} selected before switching destination`, async () => {
    const page = helper({ realCrypto: true });
    page.form.elements.destination.value = "tapToPay";
    page.form.elements.setupMode.value = mode;
    page.invalidate("change");
    assert.equal(page.account.disabled, false);
    assert.equal(page.sharedKey.disabled, false);
    assert.equal(page.mode.hidden, true);
    assert.equal(page.requiredFields.every((field) => !field.required), true);
    for (const [name, value] of Object.entries({
      merchantAccount: " Merchant ", paymentsAppApiKey: " boarding-key ", storeId: " ST1 ",
      keyIdentifier: "manual-key", keyVersion: "2", passphrase: " manual secret ",
    })) page.form.values.set(name, value);
    await page.submit();
    const { connection, secrets } = readTransfer(page);
    assert.deepEqual(connection, {
      destination: "tapToPay",
      merchantAccount: "Merchant", storeId: "ST1",
      keyIdentifier: "manual-key", keyVersion: 2,
    });
    assert.deepEqual(secrets, {
      ADYEN_API_KEY: "demo-key", PAYMENTS_APP_API_KEY: "boarding-key",
      TERMINAL_PASSPHRASE: " manual secret ",
    });
  });
}

test("Tap to Pay can omit the key for device entry; physical Manual setup still requires it", async () => {
  const page = helper({ realCrypto: true });
  page.form.elements.destination.value = "tapToPay";
  page.form.values.set("keyVersion", "1");
  page.invalidate("change");
  await page.submit();
  const { connection, secrets } = readTransfer(page);
  assert.deepEqual(connection, { destination: "tapToPay" });
  assert.deepEqual(secrets, { ADYEN_API_KEY: "demo-key" });
  assert.equal(page.requiredFields.every((field) => !field.required), true);
  page.form.elements.destination.value = "network";
  page.invalidate("change");
  assert.equal(page.requiredFields.every((field) => field.required), true);
  assert.equal(page.mode.hidden, false);
});
