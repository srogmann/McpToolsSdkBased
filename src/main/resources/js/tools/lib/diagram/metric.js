/**
 * diagram/metric - built-in advance widths of the standard fonts (Base 14).
 *
 * The layout needs one number per label: how wide is this string? Until now that number only came
 * from a caller-injected measure function, and without one the layout estimated
 * `length x size x 0.55` and said so in a note. The estimate is wrong exactly where it hurts - a
 * narrow label ("III") and a wide one ("WWW") get the same box - and the default font of the
 * layout (`Helvetica`, see layout.js) is a font whose widths are part of every PDF specification.
 *
 * So the numbers are here: the character widths of the standard fonts as the Adobe AFM files
 * declare them, in 1/1000 of the font size, for the printable ASCII range (32..126). That covers
 * what a diagram label is made of; anything else (accents, CJK, a symbol font) uses the font's
 * default width and is therefore an approximation - said in `source` of the metric.
 *
 *     var metric = require('./diagram/metric.js');
 *     metric.width('Helvetica', 10, 'Hallo')      // 24.46
 *     diagram.layout(model, {measure: metric.metric('Helvetica').measure})   // exact widths
 *
 * <p>
 * Courier is the easy one: every glyph advances 600 (a monospaced font), which is also why
 * `pdftext` documents the indent of a code line as `4 x 0.6 x size`.
 * </p>
 */

'use strict';

/** Index of the first table entry. */
var FIRST = 32;

/** Index of the last table entry. */
var LAST = 126;

/** The smallest font size a label is still readable at (used by fit.js). */
var MIN_LEGIBLE_SIZE = 6;

/**
 * Helvetica (Adobe AFM, WinAnsi == ASCII for 32..126).
 * <p>Index = charCode - 32; the rows are 16 entries, so the first column of a row is the character
 * named in the comment.</p>
 */
var HELVETICA = [
  278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, // ' ' ! " # $ % & ' ( ) * + , - . /
  556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, // 0-9 : ; < = > ?
  1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, // @ A-O
  667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, // P-Z [ \ ] ^ _
  333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, // ` a-o
  556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584 // p-z { | } ~
];

/** Helvetica-Bold (Adobe AFM). */
var HELVETICA_BOLD = [
  278, 333, 474, 556, 556, 889, 722, 278, 333, 333, 389, 584, 278, 333, 278, 278, // ' ' ! " # $ % & ' ( ) * + , - . /
  556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611, // 0-9 : ; < = > ?
  975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778, // @ A-O
  667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556, // P-Z [ \ ] ^ _
  333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611, // ` a-o
  611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584 // p-z { | } ~
];

/** Times-Roman (Adobe AFM). */
var TIMES_ROMAN = [
  250, 333, 408, 500, 500, 833, 778, 180, 333, 333, 500, 564, 250, 333, 250, 278, // ' ' ! " # $ % & ' ( ) * + , - . /
  500, 500, 500, 500, 500, 500, 500, 500, 500, 500, 278, 278, 564, 564, 564, 444, // 0-9 : ; < = > ?
  921, 722, 667, 667, 722, 611, 556, 722, 722, 333, 389, 722, 611, 889, 722, 722, // @ A-O
  556, 722, 667, 556, 611, 722, 722, 944, 722, 722, 611, 333, 278, 333, 469, 500, // P-Z [ \ ] ^ _
  333, 444, 500, 444, 500, 444, 333, 500, 500, 278, 278, 500, 278, 778, 500, 500, // ` a-o
  500, 500, 333, 389, 278, 500, 500, 722, 500, 500, 444, 480, 200, 480, 541 // p-z { | } ~
];

/** Times-Bold (Adobe AFM). */
var TIMES_BOLD = [
  250, 333, 555, 500, 500, 1000, 833, 278, 333, 333, 500, 570, 250, 333, 250, 278, // ' ' ! " # $ % & ' ( ) * + , - . /
  500, 500, 500, 500, 500, 500, 500, 500, 500, 500, 333, 333, 570, 570, 570, 500, // 0-9 : ; < = > ?
  930, 722, 667, 722, 722, 667, 611, 778, 778, 389, 500, 778, 667, 944, 722, 778, // @ A-O
  611, 778, 722, 556, 667, 722, 722, 1000, 722, 722, 667, 333, 278, 333, 581, 500, // P-Z [ \ ] ^ _
  333, 500, 556, 444, 556, 444, 333, 500, 556, 278, 333, 556, 278, 833, 556, 500, // ` a-o
  556, 556, 444, 389, 333, 556, 500, 722, 500, 500, 444, 394, 220, 394, 520 // p-z { | } ~
];

/** Courier: monospaced, every glyph 600 (Adobe AFM). */
var COURIER_WIDTH = 600;

/**
 * The known fonts: table, the width of a character the table does not hold, and the names that
 * mean this font. The standard names come first; the second spelling is what a document or a
 * caller usually writes (Helvetica and Arial are metrically compatible, and so are Times New Roman
 * and Times).
 */
var FONTS = {
  'Helvetica': {table: HELVETICA, fallback: 556, aliases: ['Arial', 'Helvetica-Regular']},
  'Helvetica-Bold': {table: HELVETICA_BOLD, fallback: 611,
    aliases: ['Arial-Bold', 'Helvetica-BoldOblique', 'Arial-BoldMT']},
  'Courier': {table: null, fallback: COURIER_WIDTH,
    aliases: ['CourierNew', 'Courier New', 'Courier-Regular', 'monospace']},
  'Times-Roman': {table: TIMES_ROMAN, fallback: 500,
    aliases: ['Times', 'TimesNewRoman', 'Times New Roman', 'serif']},
  'Times-Bold': {table: TIMES_BOLD, fallback: 500,
    aliases: ['Times-Bold', 'TimesNewRoman-Bold']}
};

/** Lookup by lower-case name, built once. */
var BY_NAME = (function () {
  var map = {};
  var names = Object.keys(FONTS);
  for (var i = 0; i < names.length; i++) {
    map[names[i].toLowerCase()] = names[i];
    var aliases = FONTS[names[i]].aliases;
    for (var a = 0; a < aliases.length; a++) {
      map[String(aliases[a]).toLowerCase()] = names[i];
    }
  }
  return map;
})();

/**
 * Resolves a font name to a key of {@link FONTS}.
 * @param {string} font the font name (any spelling)
 * @returns {string|null} the standard name, or null when the font is not known
 */
function fontKey(font) {
  if (!font) {
    return null;
  }
  var key = BY_NAME[String(font).trim().toLowerCase()];
  return key === undefined ? null : key;
}

/**
 * The advance width of one character, in 1/1000 of the font size.
 * @param {string} font the font name
 * @param {number} code the character code
 * @returns {number} the width, or -1 when the font is not known
 */
function widthUnit(font, code) {
  var key = fontKey(font);
  if (key === null) {
    return -1;
  }
  var entry = FONTS[key];
  if (entry.table === null) {
    return COURIER_WIDTH;
  }
  if (code < FIRST || code > LAST) {
    return entry.fallback;
  }
  return entry.table[code - FIRST];
}

/**
 * The width of a string in points.
 * @param {string} font the font name
 * @param {number} size the font size in points
 * @param {string} text the string
 * @returns {number} the width in points
 */
function width(font, size, text) {
  var units = 0;
  var value = String(text);
  for (var i = 0; i < value.length; i++) {
    var unit = widthUnit(font, value.charCodeAt(i));
    if (unit < 0) {
      // Unknown font: fall through to the estimate of the layout, per character.
      units += 550;
    } else {
      units += unit;
    }
  }
  return units * size / 1000;
}

/**
 * A metric: the measurement of one font, usable as the {@code measure} function of a layout.
 * <p>
 * The returned object answers two ways, because two callers ask two ways: {@code width(text)}
 * measures a string as it is, and {@code measure(request)} is the shape
 * {@code diagram.layout()} calls ({@code {font, size, text}}) - so it can be passed in directly:
 * </p>
 * <pre>{@code
 * var m = diagram.metric('Helvetica', 10);
 * m.width('Knoten');                       // 35.58
 * diagram.layout(model, {measure: m.measure});
 * }</pre>
 * @param {string} font the font name (default 'Helvetica')
 * @param {number} [size] the size in points (default 10; {@code measure} prefers the size of the
 *        request, so a layout with several sizes still gets the right number)
 * @returns {object} {font, size, source, width(text[, size]), measure(request)}
 */
function metric(font, size) {
  var name = font || 'Helvetica';
  var key = fontKey(name);
  var baseSize = size === undefined || size === null ? 10 : Number(size);
  if (!(baseSize > 0)) {
    throw new Error("diagram.metric: size must be a positive number, got "
      + JSON.stringify(size));
  }
  var source = key === null ? 'estimate' : 'builtin';
  return {
    font: key === null ? String(name) : key,
    size: baseSize,
    source: source,
    /**
     * The width of a string.
     * @param {string} text the string
     * @param {number} [atSize] the size (default: the metric's size)
     * @returns {number} the width in points
     */
    width: function (text, atSize) {
      var s = atSize === undefined || atSize === null ? baseSize : Number(atSize);
      if (key === null) {
        return String(text).length * s * 0.55;
      }
      return width(key, s, text);
    },
    /**
     * The measuring function of a layout.
     * @param {object} request {font, size, text}
     * @returns {number} the width in points
     */
    measure: function (request) {
      var req = request || {};
      var s = typeof req.size === 'number' && req.size > 0 ? req.size : baseSize;
      var f = req.font ? fontKey(req.font) : key;
      if (f === null) {
        return String(req.text === undefined ? '' : req.text).length * s * 0.55;
      }
      return width(f, s, req.text === undefined ? '' : req.text);
    }
  };
}

/**
 * Fills a request object with the {@code measure} of a font - the one-liner a caller needs.
 * @param {string} [font] the font name (default 'Helvetica')
 * @param {number} [size] the size in points (default 10)
 * @returns {Function} the measuring function
 */
function measurer(font, size) {
  return metric(font, size).measure;
}

module.exports = {
  FIRST: FIRST,
  LAST: LAST,
  MIN_LEGIBLE_SIZE: MIN_LEGIBLE_SIZE,
  names: function () {
    return Object.keys(FONTS);
  },
  fontKey: fontKey,
  width: width,
  widthUnit: widthUnit,
  metric: metric,
  measurer: measurer
};
