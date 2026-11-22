/**
 * diagram/fit - does a scene fit onto a page, and at which scale?
 *
 * The arithmetic is small (one `min`), but three things around it are not obvious, and both
 * callers that need it - a caller of `diagram.fit()` and `pdftext` when it places a diagram - must
 * agree on them:
 *
 *   * the page is a preset (portrait as defined here) or an explicit `{width, height}` in points,
 *   * the text area is the page minus its margins minus the space a header and a footer take,
 *   * scaling only ever shrinks: a small drawing keeps its size, and a fixed `scale` wins over `fit`.
 *
 * `pdftext` reserves `2.2 x size` per header/footer line (see its contentHeight()); it passes the
 * two spaces in here, so the two modules compute the same text area without sharing the rule twice.
 *
 *     var fit = require('./diagram/fit.js');
 *     fit.fit(scene, {page: 'A4', margin: 15, orientation: 'auto'});
 *     // {scale: 0.452, orientation: 'portrait', width: 565, height: 542,
 *     //  smallestFont: 4.52, legible: false, fits: true, warning: '…'}
 *
 * The result is a statement about the drawing, not about a layout: it changes nothing in the scene.
 */

'use strict';

var sceneLib = require('./scene.js');
var metricLib = require('./metric.js');

/** Page size presets in points - portrait, as a page dictionary holds them. */
var PAGES = {
  A4: {width: 595.28, height: 841.89},
  A3: {width: 841.89, height: 1190.55},
  A5: {width: 419.53, height: 595.28},
  LETTER: {width: 612, height: 792},
  LEGAL: {width: 612, height: 1008},
  TABLOID: {width: 792, height: 1224}
};

/** The margin a report uses when the options say nothing (the pdftext default, 2 cm). */
var DEFAULT_MARGIN = 57;

/** The `fit` modes, in the order the help lists them. */
var FIT_MODES = ['page', 'width', 'none'];

/** The orientations `fit()` understands. */
var ORIENTATIONS = ['portrait', 'landscape', 'auto'];

/**
 * Rounds to two decimals - the module's precision for points.
 * @param {number} value the value
 * @returns {number} the rounded value
 */
function round(value) {
  return Math.round(value * 100) / 100;
}

/**
 * Rounds a number of a request, refusing anything that is not a positive number.
 * @param {string} what the field name, for the message
 * @param {*} value the value
 * @param {boolean} [allowZero] accept 0 as well
 * @returns {number} the number
 */
function positive(what, value, allowZero) {
  var number = Number(value);
  if (!isFinite(number) || (allowZero ? number < 0 : number <= 0)) {
    throw new Error('diagram.fit: ' + what + ' must be a ' + (allowZero ? 'non-negative'
      : 'positive') + ' number of points, got ' + JSON.stringify(value));
  }
  return number;
}

/**
 * Resolves a page option into points.
 * <p>
 * A preset name is used as it is defined (portrait); `orientation` turns it by swapping width and
 * height - the table stays one table. An explicit `{width, height}` is taken literally unless an
 * orientation is asked for, which then swaps it too.
 * </p>
 * @param {string|object} page a preset name or {width, height} in points
 * @param {string|null} [orientation] 'portrait', 'landscape' or null (as defined)
 * @returns {{width: number, height: number, orientation: string, preset: string|null,
 *           explicit: boolean}} the page in points
 */
function resolvePage(page, orientation) {
  var width;
  var height;
  var preset = null;
  var explicit = false;
  if (typeof page === 'string') {
    preset = presetName(page);
    var entry = PAGES[preset];
    if (!entry) {
      throw new Error("diagram.fit: unknown page '" + page + "' - known are "
        + Object.keys(PAGES).join(', ') + ' or {width, height} in points');
    }
    width = entry.width;
    height = entry.height;
  } else if (page && Number(page.width) > 0 && Number(page.height) > 0) {
    width = Number(page.width);
    height = Number(page.height);
    explicit = true;
  } else {
    throw new Error('diagram.fit: page is a preset name ('
      + Object.keys(PAGES).join(', ') + ') or {width, height} in points, got '
      + JSON.stringify(page));
  }
  var wanted = orientation === undefined || orientation === null ? null : String(orientation);
  if (wanted !== null && ORIENTATIONS.indexOf(wanted) < 0) {
    throw new Error("diagram.fit: orientation is 'portrait', 'landscape' or 'auto', got '"
      + wanted + "'");
  }
  if (wanted === 'auto') {
    // 'auto' is a decision about a drawing, and this call does not have one - fit() resolves it
    // before it comes here.
    throw new Error("diagram.fit: orientation 'auto' needs a drawing - fit() resolves it;"
      + ' resolvePage() takes \'portrait\', \'landscape\' or null');
  }
  var isLandscape = width > height;
  if (wanted === 'landscape' && !isLandscape) {
    return {width: height, height: width, orientation: 'landscape', preset: preset,
      explicit: explicit};
  }
  if (wanted === 'portrait' && isLandscape) {
    return {width: height, height: width, orientation: 'portrait', preset: preset,
      explicit: explicit};
  }
  return {width: width, height: height, orientation: isLandscape ? 'landscape' : 'portrait',
    preset: preset, explicit: explicit};
}

/**
 * The preset name of a page argument, upper-cased.
 * @param {string} page the name as written
 * @returns {string} the name as the table holds it
 */
function presetName(page) {
  return String(page).trim().toUpperCase();
}

/**
 * Resolves a margin option.
 * @param {number|object} margin one number or {top, right, bottom, left}
 * @returns {{top: number, right: number, bottom: number, left: number}} the margins in points
 */
function resolveMargin(margin) {
  if (margin === undefined || margin === null) {
    var d = DEFAULT_MARGIN;
    return {top: d, right: d, bottom: d, left: d};
  }
  if (typeof margin === 'number') {
    positive('margin', margin, true);
    return {top: margin, right: margin, bottom: margin, left: margin};
  }
  return {
    top: positive('margin.top', margin.top === undefined ? 0 : margin.top, true),
    right: positive('margin.right', margin.right === undefined ? 0 : margin.right, true),
    bottom: positive('margin.bottom', margin.bottom === undefined ? 0 : margin.bottom, true),
    left: positive('margin.left', margin.left === undefined ? 0 : margin.left, true)
  };
}

/**
 * The text area of a page: the page minus the margins minus the header and the footer space.
 * @param {{width: number, height: number}} pageSize the page in points
 * @param {number|object} [margin] one number or {top, right, bottom, left}
 * @param {number} [headerSpace] points reserved at the top for a header line
 * @param {number} [footerSpace] points reserved at the bottom for a footer line
 * @returns {{width: number, height: number, left: number, top: number,
 *           margin: object, headerSpace: number, footerSpace: number}} the text area
 */
function textArea(pageSize, margin, headerSpace, footerSpace) {
  var m = resolveMargin(margin);
  var header = headerSpace === undefined || headerSpace === null ? 0
    : positive('headerSpace', headerSpace, true);
  var footer = footerSpace === undefined || footerSpace === null ? 0
    : positive('footerSpace', footerSpace, true);
  var width = pageSize.width - m.left - m.right;
  var height = pageSize.height - m.top - m.bottom - header - footer;
  if (width <= 0 || height <= 0) {
    throw new Error('diagram.fit: the margins (' + m.left + '/' + m.right + '/' + m.top + '/'
      + m.bottom + ' pt) and the header/footer space (' + header + '/' + footer
      + ' pt) leave no text area on a ' + round(pageSize.width) + ' x '
      + round(pageSize.height) + ' pt page');
  }
  return {width: width, height: height, left: m.left, top: m.top + header,
    margin: m, headerSpace: header, footerSpace: footer};
}

/**
 * The factor a drawing is scaled with - the one formula, used by {@code fit()} and by
 * {@code pdftext}.
 * @param {{width: number, height: number}} drawing the drawing's own extent in points
 * @param {{width: number, height: number}} area the text area in points
 * @param {string} [mode] 'page' (default), 'width' or 'none'
 * @param {number} [requested] a fixed factor, which wins over {@code mode}
 * @returns {number} the factor (0 &lt; f, at most 1 for the fit modes)
 */
function scaleFor(drawing, area, mode, requested) {
  if (requested !== undefined && requested !== null) {
    var fixed = Number(requested);
    if (!isFinite(fixed) || fixed <= 0) {
      throw new Error('diagram.fit: scale must be a positive number, got '
        + JSON.stringify(requested));
    }
    return fixed;
  }
  var m = mode === undefined || mode === null ? 'page' : String(mode);
  if (FIT_MODES.indexOf(m) < 0) {
    throw new Error("diagram.fit: fit is 'page', 'width' or 'none', got '" + m + "'");
  }
  var width = Math.max(drawing.width, 0.0001);
  var height = Math.max(drawing.height, 0.0001);
  if (m === 'page') {
    return Math.min(1, area.width / width, area.height / height);
  }
  if (m === 'width') {
    return Math.min(1, area.width / width);
  }
  return 1;
}

/**
 * The smallest text size of a scene, in the scene's own points.
 * @param {Array<object>} items the scene items
 * @returns {number|null} the smallest size, or null when the scene holds no text
 */
function smallestTextSize(items) {
  var smallest = null;
  for (var i = 0; i < items.length; i++) {
    var item = items[i];
    if (item.kind !== 'text' && item.kind !== 'group') {
      continue;
    }
    var size = Number(item.size);
    if (!isFinite(size) || size <= 0) {
      continue;
    }
    if (smallest === null || size < smallest) {
      smallest = size;
    }
  }
  return smallest;
}

/**
 * How a scene fits onto a page - the calculation {@code diagram.fit()} answers.
 * <p>
 * Two ways in: either the page as the caller knows it ({@code page}, {@code margin},
 * {@code orientation}), or the text area directly ({@code textArea}), which is what {@code pdftext}
 * uses because it has already computed it.
 * </p>
 * @param {object} scene the scene ({kind:'scene', items:[…]})
 * @param {object} [options] {page, margin, orientation, headerSpace, footerSpace, fit, scale,
 *        textArea, pageSize}
 * @returns {object} the fit answer
 */
function fit(scene, options) {
  var opts = options || {};
  var items = (scene && scene.items) || [];
  var box = sceneLib.bounds(items);
  // The drawing's extent is the scene's own width/height when it declares them: that is the box
  // both backends draw (the SVG viewBox, the PDF block) and it includes the normalisation padding.
  // Without them the tight box of the items is the best answer there is.
  var drawing;
  if (scene && typeof scene.width === 'number' && typeof scene.height === 'number'
      && scene.width > 0 && scene.height > 0) {
    drawing = {width: round(scene.width), height: round(scene.height)};
  } else {
    drawing = box.empty ? {width: 0, height: 0}
      : {width: round(box.maxX - box.minX), height: round(box.maxY - box.minY)};
  }
  var orientation = opts.orientation === undefined || opts.orientation === null ? null
    : String(opts.orientation);
  if (orientation !== null && ORIENTATIONS.indexOf(orientation) < 0) {
    throw new Error("diagram.fit: orientation is 'portrait', 'landscape' or 'auto', got '"
      + orientation + "'");
  }
  var pageSize;
  var area;
  var resolved;
  if (opts.textArea) {
    // The caller already holds the page (pdftext does): take its numbers as they are.
    area = {width: positive('textArea.width', opts.textArea.width),
      height: positive('textArea.height', opts.textArea.height)};
    pageSize = opts.pageSize
      ? {width: positive('pageSize.width', opts.pageSize.width),
        height: positive('pageSize.height', opts.pageSize.height)}
      : null;
    resolved = {preset: typeof opts.page === 'string' ? presetName(opts.page) : null,
      orientation: orientation};
  } else {
    if (orientation === 'auto') {
      // "auto" is a decision about this drawing, so it can only be taken here.
      orientation = drawing.width > drawing.height ? 'landscape' : 'portrait';
    }
    resolved = resolvePage(opts.page === undefined || opts.page === null ? 'A4' : opts.page,
      orientation === 'portrait' || orientation === 'landscape' ? orientation : null);
    pageSize = {width: resolved.width, height: resolved.height};
    area = textArea(pageSize, opts.margin, opts.headerSpace, opts.footerSpace);
  }
  var scale = scaleFor(drawing, area, opts.fit, opts.scale);
  var width = round(drawing.width * scale);
  var height = round(drawing.height * scale);
  var smallest = smallestTextSize(items);
  var smallestFont = smallest === null ? null : round(smallest * scale);
  var legible = smallestFont === null ? null : smallestFont >= metricLib.MIN_LEGIBLE_SIZE;
  var fits = width <= area.width + 0.5 && height <= area.height + 0.5;
  var answer = {
    page: resolved.preset === undefined ? null : resolved.preset,
    pageSize: pageSize,
    orientation: resolved.orientation,
    textArea: {width: round(area.width), height: round(area.height)},
    drawing: drawing,
    scale: round(scale),
    width: width,
    height: height,
    smallestFont: smallestFont,
    legible: legible,
    fits: fits,
    warning: null
  };
  answer.warning = fitWarning(answer, scale, opts);
  return answer;
}

/**
 * One sentence about what a fit answer means for the reader, or null when there is nothing to say.
 * @param {object} answer the fit answer
 * @param {number} scale the unrounded factor
 * @param {object} opts the options of the call
 * @returns {string|null} the sentence
 */
function fitWarning(answer, scale, opts) {
  var sentences = [];
  if (opts.scale === undefined || opts.scale === null) {
    if (scale < 0.999) {
      sentences.push('the drawing is ' + answer.drawing.width + ' x ' + answer.drawing.height
        + ' pt and is scaled to ' + Math.round(scale * 1000) / 10 + ' % ('
        + answer.width + ' x ' + answer.height + ' pt) to fit the text area');
    }
  }
  if (answer.legible === false) {
    sentences.push('the smallest text would be ' + answer.smallestFont + ' pt - below about '
      + metricLib.MIN_LEGIBLE_SIZE + ' pt a label is hard to read; a larger page, orientation'
      + ' landscape, a smaller margin, a split diagram or diagram.rotate(scene, 90) helps');
  }
  if (!answer.fits) {
    sentences.push('the drawing is ' + answer.width + ' x ' + answer.height + ' pt, the text area'
      + ' only ' + answer.textArea.width + ' x ' + answer.textArea.height + ' pt - it runs over'
      + " the page (" + (opts.scale !== undefined && opts.scale !== null
        ? 'scale ' + opts.scale + ' is fixed' : "fit: 'none' keeps the size") + ')');
  }
  return sentences.length === 0 ? null : sentences.join('; ');
}

module.exports = {
  PAGES: PAGES,
  DEFAULT_MARGIN: DEFAULT_MARGIN,
  FIT_MODES: FIT_MODES,
  ORIENTATIONS: ORIENTATIONS,
  resolvePage: resolvePage,
  resolveMargin: resolveMargin,
  textArea: textArea,
  scaleFor: scaleFor,
  smallestTextSize: smallestTextSize,
  fit: fit,
  round: round
};
