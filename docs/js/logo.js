/* Integer-exact mirror of core/logo/LogoConverter. Keep pipeline and shared logo vectors in sync. */
"use strict";
(() => {
  const luma = (r, g, b) => (54 * r + 183 * g + 19 * b + 128) >> 8;
  const composite = (c, a, white) => Math.trunc((c * a + (white ? 255 * (255 - a) : 0) + 127) / 255);
  const grey = (rgba, offset, white) => {
    const alpha = rgba[offset + 3];
    return luma(composite(rgba[offset], alpha, white), composite(rgba[offset + 1], alpha, white), composite(rgba[offset + 2], alpha, white));
  };
  const otsu = pixels => {
    const histogram = new Int32Array(256);
    let sum = 0;
    for (const value of pixels) { histogram[value]++; sum += value; }
    let weight = 0, partial = 0, maximum = -1, threshold = 127;
    for (let t = 0; t < 255; t++) {
      weight += histogram[t]; partial += t * histogram[t];
      const foreground = pixels.length - weight;
      if (!weight || !foreground) continue;
      const difference = partial / weight - (sum - partial) / foreground;
      const between = weight * foreground * difference * difference;
      if (between > maximum) { maximum = between; threshold = t; }
    }
    return threshold;
  };
  // Prepared handles expose no mutable pixels; quantization always works on its own copy.
  const preparedPixels = new WeakMap();
  const prepare = (width, height, rgba, options = {}) => {
    if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1 || width > 4096 || height > 4096 || rgba.length !== width * height * 4) throw new RangeError("Invalid image dimensions");
    const total = width * height;
    let inverted = options.invert;
    if (inverted == null) {
      let transparent = 0, opaque = 0, ink = 0, border = 0, borderCount = 0;
      for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
        const offset = (y * width + x) * 4;
        if (rgba[offset + 3] < 128) transparent++;
        else { opaque++; ink += grey(rgba, offset, true); }
        if (x === 0 || y === 0 || x === width - 1 || y === height - 1) { border += grey(rgba, offset, true); borderCount++; }
      }
      inverted = transparent * 10 >= total ? opaque > 0 && Math.trunc(ink / opaque) >= 160 : Math.trunc(border / borderCount) < 96;
    }
    const source = new Uint8Array(total);
    let left = width, top = height, right = -1, bottom = -1;
    for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
      const index = y * width + x;
      const value = grey(rgba, index * 4, !inverted);
      source[index] = inverted ? 255 - value : value;
      if (source[index] < 240) { left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y); }
    }
    if (right < 0) return Object.freeze({kind: "blank"});
    if (options.trim === false) { left = 0; top = 0; right = width - 1; bottom = height - 1; }
    const w = right - left + 1, h = bottom - top + 1;
    const maxHeight = options.size === "small" ? 96 : 192;
    let tw, th;
    if (w * maxHeight >= h * 384) { tw = Math.min(w, 384); th = Math.max(1, Math.trunc((h * tw + Math.trunc(w / 2)) / w)); }
    else { th = Math.min(h, maxHeight); tw = Math.max(1, Math.trunc((w * th + Math.trunc(h / 2)) / h)); }
    const horizontal = new Float64Array(tw * h);
    for (let y = 0; y < h; y++) for (let x = 0; x < tw; x++) {
      const start = x * w, end = (x + 1) * w;
      let sum = 0;
      for (let i = Math.trunc(start / tw); i < Math.ceil(end / tw); i++) sum += (Math.min(end, (i + 1) * tw) - Math.max(start, i * tw)) * source[(top + y) * width + left + i];
      horizontal[y * tw + x] = sum;
    }
    const pixels = new Int32Array(tw * th);
    for (let y = 0; y < th; y++) for (let x = 0; x < tw; x++) {
      const start = y * h, end = (y + 1) * h;
      let sum = 0;
      for (let i = Math.trunc(start / th); i < Math.ceil(end / th); i++) sum += (Math.min(end, (i + 1) * th) - Math.max(start, i * th)) * horizontal[i * tw + x];
      pixels[y * tw + x] = Math.trunc((sum + Math.trunc(w * h / 2)) / (w * h));
    }
    const mid = pixels.reduce((count, value) => count + (value > 32 && value < 224 ? 1 : 0), 0);
    const prepared = Object.freeze({kind: "prepared", width: tw, height: th, inverted});
    preparedPixels.set(prepared, {pixels, mode: mid * 100 > pixels.length * 20 ? "photo" : "mark", threshold: otsu(pixels)});
    return prepared;
  };
  const quantize = (prepared, options = {}) => {
    if (prepared.kind === "blank") return {kind: "blank"};
    const source = preparedPixels.get(prepared);
    if (!source) throw new TypeError("Invalid prepared logo");
    const {width: tw, height: th, inverted} = prepared;
    const pixels = source.pixels.slice();
    const mode = !options.mode || options.mode === "auto" ? source.mode : options.mode;
    const threshold = options.threshold ?? (mode === "mark" ? source.threshold : 127);
    if (!Number.isInteger(threshold) || threshold < 0 || threshold > 255 || !["mark", "photo"].includes(mode)) throw new RangeError("Invalid logo options");
    const stride = Math.trunc((tw + 7) / 8), bits = new Uint8Array(stride * th);
    for (let y = 0; y < th; y++) for (let x = 0; x < tw; x++) {
      const value = pixels[y * tw + x], black = value <= threshold;
      if (black) bits[y * stride + (x >> 3)] |= 128 >> (x & 7);
      if (mode === "photo") {
        const error = Math.trunc((value - (black ? 0 : 255)) / 8);
        for (const [dx, dy] of [[1, 0], [2, 0], [-1, 1], [0, 1], [1, 1], [0, 2]]) if (x + dx >= 0 && x + dx < tw && y + dy < th) pixels[(y + dy) * tw + x + dx] += error;
      }
    }
    return {kind: "converted", width: tw, height: th, mode, threshold, inverted, bits};
  };
  const convert = (width, height, rgba, options = {}) => quantize(prepare(width, height, rgba, options), options);
  window.MiniMposLogo = Object.freeze({prepare, quantize, convert});
})();
