"use strict";

const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const { test } = require("node:test");
const { setImmediate: tick } = require("node:timers/promises");
const { runInNewContext } = require("node:vm");

const source = readFileSync(resolve(__dirname, "../../../../docs/js/setup.js"), "utf8");

/** The helper's DOM and deferred crypto, so tests control completion without a browser or network. */
const helper = () => {
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
  form.elements = { destination: { value: "network" }, environment: { value: "test" }, setupMode: { value: "manual" } };
  form.dataset = {
    msgCodeOf: "{n}/{total}", msgPause: "Pause", msgPlay: "Play",
    msgMaking: "Making", msgReady: "Ready {total}", msgUnsupported: "Unsupported",
  };
  form.values = new Map([["apiKey", "demo-key"]]);
  const pending = [];
  const drawn = [];
  const crypto = {
    getRandomValues(bytes) { return bytes.fill(0); },
    subtle: {
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
      constructor(form) { this.values = new Map(form.values); }
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
    form, pending, drawn,
    results: nodes["setup-codes"],
    status: nodes["setup-status"],
    code: nodes["setup-code"],
    submit: () => form.listeners.get("submit")({ preventDefault() {} }),
    invalidate: (event) => form.listeners.get(event)(),
  };
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
