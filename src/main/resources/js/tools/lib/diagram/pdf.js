/**
 * diagram/pdf - the PDF backend: a scene becomes a list of pdfedit operations.
 *
 * This is the one backend that does not produce a string: it translates the scene items into the
 * ops {@code pdftext} already uses ({@code draw.rect}, {@code draw.line}, {@code text.put},
 * {@code content.append}), so the same diagram that is an SVG comes out of the same scene as a PDF.
 * The unit of the scene is a point and its origin is top-left, exactly what {@code space:'page'}
 * (and the mirrored CTM of {@code content.append}) already expect, so no axis has to be flipped
 * here - {@code pdftext} places the block and hands the page offsets in.
 *
 *     var ops = require('./diagram/pdf.js').ops(scene, {
 *       offsetX: 57, offsetY: 120,
 *       widthOf: function (text, size) { return 42.5; },
 *       font: 'Helvetica', color: '#111111'
 *     });
 *
 * Nothing here writes a file or knows about a page: {@code pdftext} owns the page, the measuring
 * cache and the {@code draw}/{@code text} ops' {@code page} member (docs/js/diagram-plan.md §3.1).
 */

'use strict';

var sceneLib = require('./scene.js');

/** The bezier constant for a quarter circle, k = 4 * (sqrt(2) - 1) / 3. */
var KAPPA = 0.5522847498;

/**
 * Translates a scene into pdfedit ops (the {@code page} member is filled in by the caller).
 * @param {object} scene the scene
 * @param {object} ctx {offsetX, offsetY, widthOf, font, color}
 * @returns {Array<object>} the ops, in drawing order
 */
function ops(scene, ctx) {
  var context = ctx || {};
  var offsetX = numberOr(context.offsetX, 0);
  var offsetY = numberOr(context.offsetY, 0);
  var font = context.font || 'Helvetica';
  var defaultColor = context.color || '#111111';
  var widthOf = typeof context.widthOf === 'function' ? context.widthOf : estimateWidth;
  var out = [];
  var items = scene.items || [];
  for (var i = 0; i < items.length; i++) {
    emitItem(items[i], offsetX, offsetY, font, defaultColor, widthOf, out);
  }
  return out;
}

/**
 * The texts a scene draws, so a caller can measure them before laying out.
 * @param {object} scene the scene
 * @returns {Array<object>} {text, size} per text (and group label)
 */
function measureTexts(scene) {
  var out = [];
  var seen = {};
  var items = scene.items || [];
  for (var i = 0; i < items.length; i++) {
    var item = items[i];
    if (item.kind === 'text') {
      addText(out, seen, item.text, item.size);
    } else if (item.kind === 'group') {
      addText(out, seen, item.label, item.size || 9);
    }
  }
  return out;
}

/**
 * Appends a text/size pair when it is new.
 * @param {Array<object>} out the list
 * @param {object} seen the seen-set by key
 * @param {string} text the text
 * @param {number} size the size
 */
function addText(out, seen, text, size) {
  if (text === undefined || text === null) {
    return;
  }
  var key = size + '|' + text;
  if (!seen[key]) {
    seen[key] = true;
    out.push({text: String(text), size: numberOr(size, 10)});
  }
}

/**
 * The bounding box of a scene in its own coordinates.
 * @param {object} scene the scene
 * @returns {object} {originX, originY, width, height}
 */
function bounds(scene) {
  return {
    originX: numberOr(scene.originX, 0),
    originY: numberOr(scene.originY, 0),
    width: numberOr(scene.width, 0),
    height: numberOr(scene.height, 0)
  };
}

/**
 * Emits one scene item.
 * @param {object} item the item
 * @param {number} dx the page offset x
 * @param {number} dy the page offset y
 * @param {string|object} font the font
 * @param {string} defaultColor the ink of a text without a fill
 * @param {Function} widthOf the width source
 * @param {Array<object>} out the op list
 */
function emitItem(item, dx, dy, font, defaultColor, widthOf, out) {
  if (item.kind === 'rect') {
    emitRect(item, dx, dy, out);
  } else if (item.kind === 'ellipse') {
    emitEllipse(item, dx, dy, out);
  } else if (item.kind === 'polygon') {
    emitPolygon(item, dx, dy, out);
  } else if (item.kind === 'path') {
    emitPath(item, dx, dy, out);
  } else if (item.kind === 'group') {
    emitGroup(item, dx, dy, font, widthOf, out);
  } else if (item.kind === 'text') {
    emitText(item, dx, dy, font, defaultColor, widthOf, out);
  }
}

/**
 * Emits a rectangle (sharp or rounded).
 * @param {object} item the item
 * @param {number} dx the offset x
 * @param {number} dy the offset y
 * @param {Array<object>} out the op list
 */
function emitRect(item, dx, dy, out) {
  var x = item.x + dx;
  var y = item.y + dy;
  var rx = item.rx || 0;
  if (rx > 0) {
    var radius = Math.min(rx, Math.min(item.w, item.h) / 2);
    var path = roundedRect(x, y, item.w, item.h, radius);
    out.push(contentOp(path, item.fill, item.stroke, item.lineWidth, item.dash));
    return;
  }
  out.push({
    op: 'draw.rect',
    space: 'page',
    x: round(x),
    y: round(y),
    width: round(item.w),
    height: round(item.h),
    fill: item.fill || null,
    stroke: item.stroke || null,
    lineWidth: item.lineWidth === undefined ? 1 : item.lineWidth
  });
}

/**
 * Emits an ellipse (a circle when rx === ry) as four bezier arcs.
 * @param {object} item the item
 * @param {number} dx the offset x
 * @param {number} dy the offset y
 * @param {Array<object>} out the op list
 */
function emitEllipse(item, dx, dy, out) {
  var cx = item.cx + dx;
  var cy = item.cy + dy;
  var rx = item.rx;
  var ry = item.ry;
  var kx = rx * KAPPA;
  var ky = ry * KAPPA;
  var path = [
    move(cx + rx, cy),
    curve(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry),
    curve(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy),
    curve(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry),
    curve(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy),
    'h'
  ].join(' ');
  out.push(contentOp(path, item.fill, item.stroke, item.lineWidth, item.dash));
}

/**
 * Emits a closed polygon.
 * @param {object} item the item
 * @param {number} dx the offset x
 * @param {number} dy the offset y
 * @param {Array<object>} out the op list
 */
function emitPolygon(item, dx, dy, out) {
  if (!item.points || item.points.length < 2) {
    return;
  }
  var parts = [move(item.points[0][0] + dx, item.points[0][1] + dy)];
  for (var i = 1; i < item.points.length; i++) {
    parts.push(lineTo(item.points[i][0] + dx, item.points[i][1] + dy));
  }
  parts.push('h');
  out.push(contentOp(parts.join(' '), item.fill, item.stroke, item.lineWidth, item.dash));
}

/**
 * Emits an open polyline.
 * <p>
 * A path with fewer than three support points has no corner and stays one {@code draw.line} per
 * segment (that op carries colour, width and dash, and the geometry is identical). A smoothed line
 * ({@code item.curve}) becomes a single {@code content.append} with
 * {@code m}/{@code l}/{@code c} operators: a curve has to be one path, and the operators are the
 * ones the ellipse and the rounded box already use - the segments come from
 * {@code scene.curveSegments}, the same call the SVG backend makes, so both draw the same curve.
 * </p>
 * @param {object} item the item
 * @param {number} dx the offset x
 * @param {number} dy the offset y
 * @param {Array<object>} out the op list
 */
function emitPath(item, dx, dy, out) {
  var points = item.points || [];
  if (item.curve && points.length > 2) {
    emitCurvedPath(item, dx, dy, out);
    return;
  }
  for (var i = 1; i < points.length; i++) {
    out.push({
      op: 'draw.line',
      space: 'page',
      x1: round(points[i - 1][0] + dx),
      y1: round(points[i - 1][1] + dy),
      x2: round(points[i][0] + dx),
      y2: round(points[i][1] + dy),
      color: item.stroke || '#333333',
      lineWidth: item.lineWidth === undefined ? 1 : item.lineWidth,
      dash: item.dash && item.dash.length ? item.dash : null
    });
  }
}

/**
 * Emits a smoothed path as one content stream path.
 * @param {object} item the path item with a curve
 * @param {number} dx the offset x
 * @param {number} dy the offset y
 * @param {Array<object>} out the op list
 */
function emitCurvedPath(item, dx, dy, out) {
  var points = item.points;
  var parts = [move(points[0][0] + dx, points[0][1] + dy)];
  var segments = sceneLib.curveSegments(points, item.curve);
  for (var i = 0; i < segments.length; i++) {
    var segment = segments[i];
    if (segment.type === 'C') {
      parts.push(curve(segment.c1[0] + dx, segment.c1[1] + dy, segment.c2[0] + dx,
        segment.c2[1] + dy, segment.to[0] + dx, segment.to[1] + dy));
    } else {
      parts.push(lineTo(segment.to[0] + dx, segment.to[1] + dy));
    }
  }
  out.push(contentOp(parts.join(' '), null, item.stroke || '#333333',
    item.lineWidth === undefined ? 1 : item.lineWidth, item.dash));
}

/**
 * Emits a group band (a dashed rounded rectangle plus its title).
 * @param {object} item the item
 * @param {number} dx the offset x
 * @param {number} dy the offset y
 * @param {string|object} font the font
 * @param {Function} widthOf the width source (unused for a start-anchored title)
 * @param {Array<object>} out the op list
 */
function emitGroup(item, dx, dy, font, widthOf, out) {
  var path = roundedRect(item.x + dx, item.y + dy, item.w, item.h, Math.min(4, item.h / 2));
  out.push(contentOp(path, '#f6f7f9', '#b9bec7', 1, [4, 3]));
  var size = item.size || 9;
  out.push({
    op: 'text.put',
    space: 'page',
    x: round(item.x + dx + 6),
    y: round(item.y + dy + size + 2 + size * 0.35),
    text: item.label,
    font: font,
    size: size,
    color: '#555b66'
  });
}

/**
 * Emits a text item with its anchor honoured and the y as a baseline.
 * @param {object} item the item
 * @param {number} dx the offset x
 * @param {number} dy the offset y
 * @param {string|object} font the font
 * @param {string} defaultColor the ink when the item has none
 * @param {Function} widthOf the width source
 * @param {Array<object>} out the op list
 */
function emitText(item, dx, dy, font, defaultColor, widthOf, out) {
  var size = item.size || 10;
  var width = widthOf(item.text, size);
  var x = item.x + dx;
  if (item.anchor === 'middle') {
    x -= width / 2;
  } else if (item.anchor === 'end') {
    x -= width;
  }
  var y = item.y + dy;
  if (item.baseline !== 'alphabetic') {
    // The scene places a middle-anchored line by its vertical centre; text.put wants a baseline.
    y += size * 0.35;
  }
  out.push({
    op: 'text.put',
    space: 'page',
    x: round(x),
    y: round(y),
    text: item.text,
    font: font,
    size: size,
    color: item.fill || defaultColor
  });
}

/**
 * A content.append op that draws a path with fill and/or stroke, isolated by q/Q.
 * @param {string} path the path operators
 * @param {string|null} fill the fill colour
 * @param {string|null} stroke the stroke colour
 * @param {number} lineWidth the stroke width
 * @param {Array<number>|null} dash the dash pattern
 * @returns {object} the op
 */
function contentOp(path, fill, stroke, lineWidth, dash) {
  var parts = ['q'];
  var fillRgb = rgb(fill);
  var strokeRgb = rgb(stroke);
  if (fillRgb) {
    parts.push(colorCommand(fillRgb, 'rg'));
  }
  if (strokeRgb) {
    parts.push(colorCommand(strokeRgb, 'RG'));
    parts.push(numberOr(lineWidth, 1) + ' w');
    parts.push('1 J 1 j');
    if (dash && dash.length) {
      parts.push('[' + dash.join(' ') + '] 0 d');
    }
  }
  parts.push(path);
  if (fillRgb && strokeRgb) {
    parts.push('B');
  } else if (fillRgb) {
    parts.push('f');
  } else if (strokeRgb) {
    parts.push('S');
  }
  parts.push('Q');
  return {op: 'content.append', text: parts.join(' ')};
}

/**
 * A rounded rectangle as path operators.
 * @param {number} x left
 * @param {number} y top
 * @param {number} w width
 * @param {number} h height
 * @param {number} r radius
 * @returns {string} the path
 */
function roundedRect(x, y, w, h, r) {
  var k = r * KAPPA;
  return [
    move(x + r, y),
    lineTo(x + w - r, y),
    curve(x + w - r + k, y, x + w, y + r - k, x + w, y + r),
    lineTo(x + w, y + h - r),
    curve(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h),
    lineTo(x + r, y + h),
    curve(x + r - k, y + h, x, y + h - r + k, x, y + h - r),
    lineTo(x, y + r),
    curve(x, y + r - k, x + r - k, y, x + r, y),
    'h'
  ].join(' ');
}

/**
 * A moveto operator.
 * @param {number} x the x
 * @param {number} y the y
 * @returns {string} the operator
 */
function move(x, y) {
  return round(x) + ' ' + round(y) + ' m';
}

/**
 * A lineto operator.
 * @param {number} x the x
 * @param {number} y the y
 * @returns {string} the operator
 */
function lineTo(x, y) {
  return round(x) + ' ' + round(y) + ' l';
}

/**
 * A cubic bezier operator.
 * @param {number} x1 control 1 x
 * @param {number} y1 control 1 y
 * @param {number} x2 control 2 x
 * @param {number} y2 control 2 y
 * @param {number} x3 end x
 * @param {number} y3 end y
 * @returns {string} the operator
 */
function curve(x1, y1, x2, y2, x3, y3) {
  return round(x1) + ' ' + round(y1) + ' ' + round(x2) + ' ' + round(y2) + ' ' + round(x3)
    + ' ' + round(y3) + ' c';
}

/**
 * A colour command ({@code r g b rg} or {@code r g b RG}).
 * @param {Array<number>} rgbValue the colour as [r, g, b] in 0..1
 * @param {string} operator the operator
 * @returns {string} the command
 */
function colorCommand(rgbValue, operator) {
  return round(rgbValue[0]) + ' ' + round(rgbValue[1]) + ' ' + round(rgbValue[2]) + ' '
    + operator;
}

/** Named colours a scene may use. */
var NAMED = {
  black: [0, 0, 0],
  white: [1, 1, 1],
  gray: [0.5, 0.5, 0.5],
  grey: [0.5, 0.5, 0.5],
  lightgray: [0.83, 0.83, 0.83],
  lightgrey: [0.83, 0.83, 0.83],
  darkgray: [0.33, 0.33, 0.33],
  red: [0.8, 0, 0],
  green: [0, 0.5, 0],
  blue: [0, 0, 0.8],
  orange: [0.9, 0.5, 0]
};

/**
 * Converts a colour to [r, g, b] in 0..1, or null for "no colour".
 * @param {*} color a name, '#rrggbb', [gray] or [r, g, b] (or a CMYK array of four)
 * @returns {Array<number>|null} the colour
 */
function rgb(color) {
  if (color === null || color === undefined || color === '') {
    return null;
  }
  if (Array.isArray(color)) {
    if (color.length === 1) {
      return [color[0], color[0], color[0]];
    }
    if (color.length >= 3) {
      return [color[0], color[1], color[2]];
    }
    return null;
  }
  var value = String(color).trim().toLowerCase();
  if (Object.prototype.hasOwnProperty.call(NAMED, value)) {
    return NAMED[value];
  }
  if (value.charAt(0) === '#') {
    return hexRgb(value);
  }
  return [0, 0, 0];
}

/**
 * Converts '#rrggbb' or '#rgb' to [r, g, b].
 * @param {string} value the hex colour
 * @returns {Array<number>} the colour
 */
function hexRgb(value) {
  var hex = value.substring(1);
  if (hex.length === 3) {
    hex = hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1)
      + hex.charAt(2) + hex.charAt(2);
  }
  if (hex.length !== 6 || !/^[0-9a-f]{6}$/.test(hex)) {
    return [0, 0, 0];
  }
  return [parseInt(hex.substring(0, 2), 16) / 255,
    parseInt(hex.substring(2, 4), 16) / 255,
    parseInt(hex.substring(4, 6), 16) / 255];
}

/**
 * A rough width when no measure function is given (the caller always passes the exact one).
 * @param {string} text the text
 * @param {number} size the size
 * @returns {number} the width
 */
function estimateWidth(text, size) {
  return String(text).length * size * 0.55;
}

/**
 * A number, or a fallback for a non-number.
 * @param {*} value the value
 * @param {number} fallback the fallback
 * @returns {number} the number
 */
function numberOr(value, fallback) {
  return (typeof value === 'number' && isFinite(value)) ? value : fallback;
}

/**
 * Rounds to two decimals for a content stream (and to keep determinism).
 * @param {number} value the value
 * @returns {number} the rounded value
 */
function round(value) {
  return Math.round(value * 100) / 100;
}

module.exports = {
  ops: ops,
  measureTexts: measureTexts,
  bounds: bounds
};
