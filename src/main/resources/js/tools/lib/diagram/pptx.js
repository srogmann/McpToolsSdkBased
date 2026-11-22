/**
 * diagram/pptx - the PPTX backend: a scene becomes editable shapes on a PowerPoint slide.
 *
 * SVG and PDF draw a picture; a slide wants something a human can still move afterwards. So this
 * backend does not rasterize: every node box becomes an auto shape, every routed edge a chain of
 * line shapes, every label a transparent shape with a centred paragraph - and the arrowheads a
 * filled triangle each. Nothing of the scene is lost, and a reader of the deck can drag a box.
 *
 *     var pptx = require('./diagram/pptx.js');
 *     pptx.write(scene, {file: 'target/diagram.pptx'});                  // a new file
 *     pptx.write(scene, {file: 'deck.pptx', mode: 'append'});            // a new slide in it
 *     pptx.write(scene, {file: 'deck.pptx', mode: 'slide', slide: 2});   // onto slide 2
 *
 * Two layers, like the PDF backend (docs/js/diagram-plan.md §3.1):
 *
 *   * {@link place} and {@link ops} are pure: a scene plus the slide size becomes the numbers and
 *     then the list of drawing operations ({@code {op: 'shape'|'line'|'text'}}). No bridge call, no
 *     file - that is the part a test can check, and the part {@code diagram.pptxOps()} exposes.
 *   * {@link draw} and {@link write} perform it: they call the {@code pptx} namespace of the
 *     JavaScript sandbox ({@code pptx.createShape}, {@code pptx.createLine},
 *     {@code pptx.setShapeStyle}, …) and never see a coordinate of their own.
 *
 * What a pptx cannot express, and what this backend therefore does instead (each case is counted in
 * the report, never silent):
 *
 *   * a <b>curve</b> - a polygon of segments, sampled from {@code scene.curveSegments()} (the same
 *     control points the other two backends draw, so the line takes the same way),
 *   * an <b>arbitrary outline</b> - a pptx shape is a preset geometry: a rectangle, an oval, a
 *     diamond, a hexagon, a triangle. A rounded box keeps PowerPoint's corner radius, a triangle is
 *     turned into its direction,
 *   * a <b>dash pattern</b> - the closest of the presets (solid, dot, dash).
 *
 * The slide is measured with the same arithmetic as a page ({@code diagram/fit.js}): the drawing is
 * scaled down until it fits the slide minus the margin, and never enlarged. The scene is not
 * changed - a caller may draw it twice, once onto a page and once onto a slide.
 */

'use strict';

var sceneLib = require('./scene.js');
var fitLib = require('./fit.js');
var metricLib = require('./metric.js');

/** The margin of a slide drawing when the options say nothing (half an inch). */
var DEFAULT_MARGIN = 36;

/** The font a label is set in - metric-compatible with the layout's measuring font. */
var DEFAULT_FONT = 'Arial';

/** The font whose advance widths size a label box (the font the layout measures with). */
var MEASURE_FONT = 'Helvetica';

/** The length of a curve sample in points: how coarse a cubic becomes a chain of lines. */
var CURVE_STEP = 6;

/** How many samples one cubic segment gets at most (a long swing stays smooth). */
var MAX_CURVE_SAMPLES = 16;

/**
 * The three ways a scene becomes part of a presentation.
 * @type {Array<string>}
 */
var MODES = ['new', 'append', 'slide'];

/**
 * Resolves the scene argument.
 * @param {object|string} scene the scene or its JSON
 * @returns {object} the scene
 * @throws {Error} when the value is not a scene
 */
function sceneOf(scene) {
  var resolved = typeof scene === 'string' ? JSON.parse(scene) : scene;
  if (!resolved || typeof resolved !== 'object' || !Array.isArray(resolved.items)) {
    throw new Error('diagram.pptx: the scene must be a scene object with an items array (or its'
      + ' JSON), but was: ' + (resolved === null ? 'null' : typeof resolved));
  }
  return resolved;
}

/**
 * The {@code pptx} namespace of the sandbox, checked.
 * <p>
 * The call is lazy: {@link place} and {@link ops} must work in an environment that has no
 * PowerPoint bridge (a test, a preview), so nothing here touches the namespace at load time.
 * </p>
 * @returns {object} the namespace
 * @throws {Error} when the sandbox carries no pptx bridge
 */
function pptxApi() {
  if (typeof pptx === 'undefined' || pptx === null) {
    throw new Error('diagram.pptx: the pptx namespace is not available in this sandbox - the PPTX'
      + ' backend needs the PowerPoint bridge (pptx.help()); pptxPlace() and pptxOps() work'
      + ' without it');
  }
  return pptx;
}

/**
 * A number, or a fallback for anything else.
 * @param {*} value the value
 * @param {number} fallback the fallback
 * @returns {number} the number
 */
function numberOr(value, fallback) {
  return (typeof value === 'number' && isFinite(value)) ? value : fallback;
}

/**
 * Rounds to a hundredth of a point - the precision of the emitted operations.
 * @param {number} value the value
 * @returns {number} the rounded value
 */
function round(value) {
  return Math.round(value * 100) / 100;
}

/**
 * How a scene is placed on a slide: the factor, the offset and the numbers behind them.
 * <p>
 * The page arithmetic is {@code diagram/fit.js} - the same table and the same {@code min} a PDF
 * page uses, so a slide and a page answer "does it fit" the same way. A slide is smaller than an
 * A4 page (720 x 540 pt by default), so a diagram is scaled more often than not; the answer says
 * at which size the smallest label ends up, which is the number to look at.
 * </p>
 * <pre>{@code
 * pptx.place(scene, {page: {width: 720, height: 540}, margin: 36})
 * // {scale: 0.62, width: 428, height: 302, offsetX: 146, offsetY: 119, smallestFont: 5.6,
 * //  legible: false, fits: true, warning: 'the drawing is …'}
 * }</pre>
 * @param {object|string} scene the scene (or its JSON)
 * @param {object} [options] {page|pageSize, margin, fit, scale, align, valign, headerSpace,
 *        footerSpace}
 * @returns {object} {page, textArea, margin, scale, width, height, offsetX, offsetY, originX,
 *   originY, align, valign, fits, smallestFont, legible, warning}
 */
function place(scene, options) {
  var resolved = sceneOf(scene);
  var opts = options || {};
  var page = opts.page || opts.pageSize || opts.slideSize;
  if (!page || !(Number(page.width) > 0) || !(Number(page.height) > 0)) {
    throw new Error('diagram.pptx: place() needs the slide size as {page: {width, height}} in'
      + ' points (pptx.getPageSize(show) answers it)');
  }
  var margin = opts.margin === undefined || opts.margin === null ? DEFAULT_MARGIN : opts.margin;
  var answer = fitLib.fit(resolved, {
    page: {width: Number(page.width), height: Number(page.height)},
    margin: margin,
    fit: opts.fit,
    scale: opts.scale,
    headerSpace: opts.headerSpace,
    footerSpace: opts.footerSpace
  });
  var area = fitLib.textArea(answer.pageSize, margin, opts.headerSpace, opts.footerSpace);
  var align = opts.align === undefined || opts.align === null ? 'center' : String(opts.align);
  if (align !== 'left' && align !== 'center' && align !== 'right') {
    throw new Error("diagram.pptx: option 'align' is 'left', 'center' or 'right', but was: '"
      + align + "'");
  }
  var valign = opts.valign === undefined || opts.valign === null ? 'middle' : String(opts.valign);
  if (valign !== 'top' && valign !== 'middle' && valign !== 'bottom') {
    throw new Error("diagram.pptx: option 'valign' is 'top', 'middle' or 'bottom', but was: '"
      + valign + "'");
  }
  var freeX = area.width - answer.width;
  var freeY = area.height - answer.height;
  var dx = align === 'left' ? 0 : (align === 'center' ? freeX / 2 : freeX);
  var dy = valign === 'top' ? 0 : (valign === 'middle' ? freeY / 2 : freeY);
  // A drawing that does not fit stays at the top-left corner of the text area instead of hanging
  // out of it; fit() reports the overflow (fits:false and a warning), nothing is clipped.
  return {
    page: {width: round(answer.pageSize.width), height: round(answer.pageSize.height)},
    textArea: {left: round(area.left), top: round(area.top), width: round(area.width),
      height: round(area.height)},
    margin: area.margin,
    scale: answer.scale,
    width: answer.width,
    height: answer.height,
    offsetX: round(area.left + Math.max(0, dx)),
    offsetY: round(area.top + Math.max(0, dy)),
    originX: numberOr(resolved.originX, 0),
    originY: numberOr(resolved.originY, 0),
    align: align,
    valign: valign,
    fits: answer.fits,
    smallestFont: answer.smallestFont,
    legible: answer.legible,
    warning: answer.warning
  };
}

/**
 * A scene x in slide coordinates.
 * @param {object} placed the place() answer
 * @param {number} x the scene x
 * @returns {number} the slide x
 */
function mapX(placed, x) {
  return round(placed.offsetX + (x - placed.originX) * placed.scale);
}

/**
 * A scene y in slide coordinates (both spaces count downwards, so no flip).
 * @param {object} placed the place() answer
 * @param {number} y the scene y
 * @returns {number} the slide y
 */
function mapY(placed, y) {
  return round(placed.offsetY + (y - placed.originY) * placed.scale);
}

/**
 * A scene length in slide points, with a floor so a scaled stroke stays visible.
 * @param {object} placed the place() answer
 * @param {number} value the length in scene points
 * @returns {number} the length in slide points
 */
function mapLen(placed, value) {
  return round(Math.max(0.25, numberOr(value, 1) * placed.scale));
}

/**
 * The dash preset of a scene dash pattern.
 * <p>
 * A pptx line knows presets, not a pattern: a fine pattern becomes {@code dot}, a coarse one
 * {@code dash}. The report counts the cases under "dash", so a caller can see that the pattern was
 * not taken literally.
 * </p>
 * @param {Array<number>|null} dash the scene dash pattern
 * @returns {string} 'solid', 'dot' or 'dash'
 */
function dashOf(dash) {
  if (!dash || !dash.length) {
    return 'solid';
  }
  var sum = 0;
  for (var i = 0; i < dash.length; i++) {
    sum += numberOr(dash[i], 0);
  }
  return (sum / dash.length) <= 2.5 ? 'dot' : 'dash';
}

/**
 * The width of a label with the layout's measuring font.
 * @param {string} text the text
 * @param {number} size the size in points
 * @returns {number} the width in points
 */
function defaultWidth(text, size) {
  return metricLib.width(MEASURE_FONT, numberOr(size, 10), text);
}

/**
 * Translates a scene into drawing operations - the pure half of the backend.
 * <p>
 * Every op carries slide coordinates; the list is JSON, deterministic and free of bridge calls, so
 * it can be inspected (and asserted on) without opening a presentation:
 * </p>
 * <pre>{@code
 * pptx.ops(scene, {page: {width: 720, height: 540}})
 * // [{op:'shape', shape:'ROUND_RECT', x:146, y:119, w:92, h:28, fill:'#ffffff', line:'#333333',
 * //   lineWidth:1, dash:'solid', rotation:0},
 * //  {op:'text', text:'Modul A', x:151, y:123, w:82, h:20, size:9, bold:false, rotation:0},
 * //  {op:'line', x1:238, y1:133, x2:270, y2:133, line:'#333333', lineWidth:1, dash:'solid'}]
 * }</pre>
 * @param {object|string} scene the scene (or its JSON)
 * @param {object} [options] {place, page, margin, fit, scale, align, valign, widthOf, font}
 * @returns {Array<object>} the operations in drawing order
 */
function ops(scene, options) {
  var resolved = sceneOf(scene);
  var opts = options || {};
  var placed = opts.place || place(resolved, opts);
  var ctx = {
    place: placed,
    widthOf: typeof opts.widthOf === 'function' ? opts.widthOf : defaultWidth,
    out: []
  };
  var items = resolved.items || [];
  for (var i = 0; i < items.length; i++) {
    emitItem(items[i], ctx);
  }
  return ctx.out;
}

/**
 * Emits one scene item.
 * @param {object} item the item
 * @param {object} ctx {place, widthOf, out}
 */
function emitItem(item, ctx) {
  if (item === null || typeof item !== 'object') {
    return;
  }
  if (item.kind === 'rect') {
    emitRect(item, ctx);
  } else if (item.kind === 'ellipse') {
    emitEllipse(item, ctx);
  } else if (item.kind === 'polygon') {
    emitPolygon(item, ctx);
  } else if (item.kind === 'path') {
    emitPath(item, ctx);
  } else if (item.kind === 'group') {
    emitGroup(item, ctx);
  } else if (item.kind === 'text') {
    emitLabel(ctx, item);
  } else {
    ctx.out.push({op: 'skip', reason: "a scene item of kind '" + String(item.kind) + "' has no"
      + ' pptx counterpart'});
  }
}

/**
 * Emits a rectangle (a node box, a frame).
 * @param {object} item the item
 * @param {object} ctx the context
 */
function emitRect(item, ctx) {
  var placed = ctx.place;
  var op = {
    op: 'shape',
    shape: item.rx > 0 ? 'ROUND_RECT' : 'RECT',
    x: mapX(placed, item.x),
    y: mapY(placed, item.y),
    w: round(numberOr(item.w, 0) * placed.scale),
    h: round(numberOr(item.h, 0) * placed.scale),
    rotation: 0,
    fill: item.fill ? String(item.fill) : '',
    line: item.stroke ? String(item.stroke) : '',
    lineWidth: mapLen(placed, item.lineWidth),
    dash: dashOf(item.dash),
    name: item.nodeGroup || item.nodeId || null
  };
  if (item.rx > 0) {
    op.approximation = 'rounded corner';
  }
  ctx.out.push(op);
}

/**
 * Emits an ellipse (a circle, a start/end pseudo state).
 * @param {object} item the item
 * @param {object} ctx the context
 */
function emitEllipse(item, ctx) {
  var placed = ctx.place;
  ctx.out.push({
    op: 'shape',
    shape: 'ELLIPSE',
    x: mapX(placed, numberOr(item.cx, 0) - numberOr(item.rx, 0)),
    y: mapY(placed, numberOr(item.cy, 0) - numberOr(item.ry, 0)),
    w: round(2 * numberOr(item.rx, 0) * placed.scale),
    h: round(2 * numberOr(item.ry, 0) * placed.scale),
    rotation: 0,
    fill: item.fill ? String(item.fill) : '',
    line: item.stroke ? String(item.stroke) : '',
    lineWidth: mapLen(placed, item.lineWidth),
    dash: dashOf(item.dash),
    name: item.nodeGroup || item.nodeId || null
  });
}

/**
 * Emits a closed polygon.
 * <p>
 * Three cases matter: an arrowhead (three points, filled), a diamond (four points) and a hexagon
 * (six points). A pptx shape is a preset geometry, so those three become a triangle turned into
 * its direction, a diamond and a hexagon - and the report says that they are approximations
 * (the triangle is the only one where it can show: its base/height ratio is the preset's).
 * </p>
 * @param {object} item the item
 * @param {object} ctx the context
 */
function emitPolygon(item, ctx) {
  var points = item.points || [];
  if (points.length < 3) {
    ctx.out.push({op: 'skip', reason: 'a polygon with ' + points.length + ' points has no area'});
    return;
  }
  if (points.length === 3) {
    emitTriangle(points, item, ctx);
    return;
  }
  var placed = ctx.place;
  var box = pointBox(points);
  var shape = points.length === 4 ? 'DIAMOND' : (points.length === 6 ? 'HEXAGON' : null);
  if (shape === null) {
    ctx.out.push({op: 'skip', reason: 'a polygon with ' + points.length + ' points has no pptx'
      + ' preset geometry'});
    return;
  }
  ctx.out.push({
    op: 'shape',
    shape: shape,
    x: mapX(placed, box[0]),
    y: mapY(placed, box[1]),
    w: round((box[2] - box[0]) * placed.scale),
    h: round((box[3] - box[1]) * placed.scale),
    rotation: 0,
    fill: item.fill ? String(item.fill) : '',
    line: item.stroke ? String(item.stroke) : '',
    lineWidth: mapLen(placed, item.lineWidth),
    dash: dashOf(item.dash),
    name: item.nodeGroup || item.nodeId || null,
    approximation: shape.toLowerCase()
  });
}

/**
 * Emits an arrowhead as a rotated triangle.
 * <p>
 * The scene draws a head as an explicit polygon ({@code tip}, two base corners), so the direction
 * and the size are read from the points: the preset triangle (apex at the middle of the top side)
 * is given the length as its height, the base width as its width, and is turned by the angle of the
 * head. PowerPoint turns a shape about the centre of its anchor, and the centre of a triangle's
 * anchor is not its centroid - so the anchor is placed at {@code centroid + off}, where {@code off}
 * is the small distance between the two (a sixth of the length, turned with the head). Without that
 * correction every head would sit a third of its length too far forward.
 * </p>
 * @param {Array<Array<number>>} points the three points (tip first)
 * @param {object} item the item (for fill and stroke)
 * @param {object} ctx the context
 */
function emitTriangle(points, item, ctx) {
  var placed = ctx.place;
  var tip = points[0];
  var baseMid = [(points[1][0] + points[2][0]) / 2, (points[1][1] + points[2][1]) / 2];
  var dx = tip[0] - baseMid[0];
  var dy = tip[1] - baseMid[1];
  var length = Math.sqrt(dx * dx + dy * dy);
  if (length < 0.01) {
    ctx.out.push({op: 'skip', reason: 'an arrowhead without a direction is not drawn'});
    return;
  }
  var baseWidth = distance(points[1], points[2]);
  // The angle of the head, counted from "pointing up" and clockwise (which is the sense of a pptx
  // rotation in a coordinate system whose y grows downwards).
  var angle = Math.atan2(dx / length, -dy / length) * 180 / Math.PI;
  var centroid = [(points[0][0] + points[1][0] + points[2][0]) / 3,
    (points[0][1] + points[1][1] + points[2][1]) / 3];
  var offset = length / 6;
  var radians = angle * Math.PI / 180;
  var centreX = centroid[0] + offset * Math.sin(radians);
  var centreY = centroid[1] - offset * Math.cos(radians);
  var w = Math.max(0.5, baseWidth * placed.scale);
  var h = Math.max(0.5, length * placed.scale);
  ctx.out.push({
    op: 'shape',
    shape: 'TRIANGLE',
    x: round(mapX(placed, centreX) - w / 2),
    y: round(mapY(placed, centreY) - h / 2),
    w: round(w),
    h: round(h),
    rotation: round(angle),
    fill: item.fill ? String(item.fill) : '',
    line: item.stroke ? String(item.stroke) : '',
    lineWidth: mapLen(placed, item.lineWidth),
    dash: 'solid',
    name: null,
    approximation: 'arrowhead'
  });
}

/**
 * Emits an open polyline (a routed edge) as a chain of line shapes.
 * <p>
 * A smoothed line ({@code item.curve}) becomes one line per sample: the samples come from
 * {@code scene.curveSegments()}, the same function SVG and PDF draw, so the line takes the same way
 * - only coarser, by {@link CURVE_STEP} points. The op list stays countable (one op per segment),
 * which is what a caller checks a drawing for.
 * </p>
 * @param {object} item the item
 * @param {object} ctx the context
 */
function emitPath(item, ctx) {
  var points = item.points || [];
  if (points.length < 2) {
    return;
  }
  var style = {
    line: item.stroke ? String(item.stroke) : '#333333',
    lineWidth: mapLen(ctx.place, item.lineWidth),
    dash: dashOf(item.dash)
  };
  var firstOp = ctx.out.length;
  var segments = sceneLib.curveSegments(points, item.curve);
  var current = points[0];
  for (var i = 0; i < segments.length; i++) {
    var segment = segments[i];
    if (segment.type === 'C') {
      var samples = cubicSamples(current, segment);
      for (var s = 0; s < samples.length; s++) {
        pushLine(ctx, current, samples[s], style);
        current = samples[s];
      }
      current = segment.to;
    } else {
      pushLine(ctx, current, segment.to, style);
      current = segment.to;
    }
  }
  if (item.curve && ctx.out.length > firstOp) {
    // The mark sits on the first segment of a smoothed line, so the op list keeps one entry per
    // drawn line and the report still counts the item once.
    ctx.out[firstOp].approximation = 'curve (drawn as segments)';
  }
}

/**
 * Appends one line op unless the two points coincide.
 * @param {object} ctx the context
 * @param {Array<number>} from the start point (scene coordinates)
 * @param {Array<number>} to the end point (scene coordinates)
 * @param {object} style {line, lineWidth, dash}
 */
function pushLine(ctx, from, to, style) {
  var x1 = mapX(ctx.place, from[0]);
  var y1 = mapY(ctx.place, from[1]);
  var x2 = mapX(ctx.place, to[0]);
  var y2 = mapY(ctx.place, to[1]);
  if (Math.abs(x1 - x2) < 0.01 && Math.abs(y1 - y2) < 0.01) {
    return;
  }
  ctx.out.push({op: 'line', x1: x1, y1: y1, x2: x2, y2: y2, line: style.line,
    lineWidth: style.lineWidth, dash: style.dash});
}

/**
 * The intermediate points of one cubic segment (the last point is the segment's end).
 * @param {Array<number>} from the segment start
 * @param {object} segment {c1, c2, to}
 * @returns {Array<Array<number>>} the sampled points
 */
function cubicSamples(from, segment) {
  var length = distance(from, segment.c1) + distance(segment.c1, segment.c2)
    + distance(segment.c2, segment.to);
  var count = Math.min(MAX_CURVE_SAMPLES, Math.max(2, Math.ceil(length / CURVE_STEP)));
  var points = [];
  for (var i = 1; i <= count; i++) {
    points.push(cubicAt(from, segment.c1, segment.c2, segment.to, i / count));
  }
  return points;
}

/**
 * One point of a cubic Bézier.
 * @param {Array<number>} p0 the start
 * @param {Array<number>} c1 control 1
 * @param {Array<number>} c2 control 2
 * @param {Array<number>} p3 the end
 * @param {number} t the parameter, 0..1
 * @returns {Array<number>} the point
 */
function cubicAt(p0, c1, c2, p3, t) {
  var u = 1 - t;
  var a = u * u * u;
  var b = 3 * u * u * t;
  var c = 3 * u * t * t;
  var d = t * t * t;
  return [a * p0[0] + b * c1[0] + c * c2[0] + d * p3[0],
    a * p0[1] + b * c1[1] + c * c2[1] + d * p3[1]];
}

/**
 * Emits a group band and its title.
 * @param {object} item the group item
 * @param {object} ctx the context
 */
function emitGroup(item, ctx) {
  var placed = ctx.place;
  ctx.out.push({
    op: 'shape',
    shape: 'ROUND_RECT',
    x: mapX(placed, item.x),
    y: mapY(placed, item.y),
    w: round(numberOr(item.w, 0) * placed.scale),
    h: round(numberOr(item.h, 0) * placed.scale),
    rotation: 0,
    fill: '#f6f7f9',
    line: '#b9bec7',
    lineWidth: mapLen(placed, 1),
    dash: 'dash',
    name: null,
    approximation: 'rounded corner'
  });
  var size = numberOr(item.size, 9);
  emitLabel(ctx, {
    kind: 'text',
    text: item.label === undefined || item.label === null ? '' : String(item.label),
    x: item.x + 6,
    y: item.y + size + 2,
    size: size,
    anchor: 'start',
    baseline: 'middle',
    bold: false,
    fill: '#555b66'
  });
}

/**
 * Emits a text item as a centred shape.
 * <p>
 * The box is placed around the <b>centre of the string</b> and the text is centred in it
 * horizontally and vertically, so the anchor of the scene is honoured without further rules:
 * {@code middle} anchors the centre itself, {@code start} the left edge and {@code end} the right
 * one - each is turned into a centre here, because the box is what PowerPoint positions. A
 * baseline-anchored label gets its centre a third of the size above the baseline (the same
 * convention the PDF backend uses). A rotated label turns about the same point it turns about in
 * SVG, because the box turns about its centre.
 * </p>
 * @param {object} ctx the context
 * @param {object} label {text, x, y, size, anchor, baseline, bold, fill, rotation}
 */
function emitLabel(ctx, label) {
  var placed = ctx.place;
  var text = label.text === undefined || label.text === null ? '' : String(label.text);
  var size = numberOr(label.size, 10);
  var width = ctx.widthOf(text, size) * placed.scale;
  var scaledSize = Math.max(1, size * placed.scale);
  // The box is only a carrier for the paragraph: the insets are set to zero and the wrapping off
  // when it is drawn, so a hair of slack is enough and a slightly narrow box cannot wrap the text.
  var pad = Math.max(3, scaledSize * 0.4);
  var w = round(Math.max(width + 2 * pad, scaledSize * 1.5));
  var h = round(Math.max(scaledSize * 2.2, 8));
  // Where the anchor sits relative to the centre of the string: 'middle' IS the centre.
  var shiftX = label.anchor === 'middle' ? 0 : (label.anchor === 'end' ? -width / 2 : width / 2);
  var centreX = mapX(placed, label.x) + shiftX;
  var centreY = mapY(placed, label.y)
    - (label.baseline === 'alphabetic' ? scaledSize * 0.35 : 0);
  ctx.out.push({
    op: 'text',
    text: text,
    x: round(centreX - w / 2),
    y: round(centreY - h / 2),
    w: w,
    h: h,
    size: round(scaledSize),
    bold: Boolean(label.bold),
    color: label.fill ? String(label.fill) : '#111111',
    rotation: numberOr(label.rotation, 0)
  });
}

/**
 * The bounding box of a point list as [minX, minY, maxX, maxY].
 * @param {Array<Array<number>>} points the points
 * @returns {Array<number>} the box
 */
function pointBox(points) {
  var minX = Infinity;
  var minY = Infinity;
  var maxX = -Infinity;
  var maxY = -Infinity;
  for (var i = 0; i < points.length; i++) {
    minX = Math.min(minX, points[i][0]);
    minY = Math.min(minY, points[i][1]);
    maxX = Math.max(maxX, points[i][0]);
    maxY = Math.max(maxY, points[i][1]);
  }
  return [minX, minY, maxX, maxY];
}

/**
 * The Euclidean distance between two points.
 * @param {Array<number>} a the first point
 * @param {Array<number>} b the second point
 * @returns {number} the distance
 */
function distance(a, b) {
  var dx = a[0] - b[0];
  var dy = a[1] - b[1];
  return Math.sqrt(dx * dx + dy * dy);
}

/**
 * Draws a scene onto a slide the caller already holds - the bridge half of the backend.
 * <p>
 * This is the call for a caller that owns the presentation: it draws, it does not open, save or
 * close anything.
 * </p>
 * <pre>{@code
 * var show = pptx.createPresentation();
 * var slide = pptx.createBlankSlide(show);
 * var report = pptx.draw(slide, scene, {page: pptx.getPageSize(show), margin: 24});
 * pptx.save(show, 'out.pptx');
 * }</pre>
 * @param {string} slideHandle the slide handle
 * @param {object|string} scene the scene (or its JSON)
 * @param {object} [options] {page|pageSize, show, margin, fit, scale, align, valign, widthOf, font}
 * @returns {object} the report {slide, page, scale, width, height, offsetX, offsetY, fits,
 *   smallestFont, legible, warning, shapes, lines, labels, skipped, items, approximations, notes}
 */
function draw(slideHandle, scene, options) {
  var resolved = sceneOf(scene);
  var opts = options || {};
  var api = pptxApi();
  if (typeof slideHandle !== 'string' || slideHandle === '') {
    throw new Error('diagram.pptx: draw() needs a slide handle (pptx.createBlankSlide(show)'
      + ' returns one), but was: ' + String(slideHandle));
  }
  var page = opts.page || opts.pageSize || opts.slideSize;
  if (!page && opts.show) {
    page = api.getPageSize(opts.show);
  }
  if (!page) {
    throw new Error('diagram.pptx: draw() needs the slide size - pass'
      + ' {page: pptx.getPageSize(show)} or {show: showHandle}');
  }
  var placed = place(resolved, {page: page, margin: opts.margin, fit: opts.fit, scale: opts.scale,
    align: opts.align, valign: opts.valign, headerSpace: opts.headerSpace,
    footerSpace: opts.footerSpace});
  var list = ops(resolved, {place: placed, widthOf: opts.widthOf});
  var font = opts.font || DEFAULT_FONT;
  var report = {
    slide: slideHandle,
    page: placed.page,
    scale: placed.scale,
    width: placed.width,
    height: placed.height,
    offsetX: placed.offsetX,
    offsetY: placed.offsetY,
    fits: placed.fits,
    smallestFont: placed.smallestFont,
    legible: placed.legible,
    warning: placed.warning,
    items: list.length,
    shapes: 0,
    lines: 0,
    labels: 0,
    skipped: 0,
    approximations: {},
    notes: []
  };
  for (var i = 0; i < list.length; i++) {
    var op = list[i];
    if (op.approximation) {
      report.approximations[op.approximation] = (report.approximations[op.approximation] || 0) + 1;
    }
    if (op.op === 'shape') {
      var shape = api.createShape(slideHandle, '', op.x, op.y, op.w, op.h);
      api.setShapeGeometry(shape, op.shape);
      if (op.rotation) {
        api.setShapeRotation(shape, op.rotation);
      }
      api.setShapeStyle(shape, op.fill || '', op.line || '', op.lineWidth || 0, op.dash);
      report.shapes++;
    } else if (op.op === 'line') {
      var line = api.createLine(slideHandle, op.x1, op.y1, op.x2, op.y2, false);
      api.setShapeStyle(line, '', op.line || '', op.lineWidth || 0, op.dash);
      report.lines++;
    } else if (op.op === 'text') {
      // A text box would do, but POI's XSLFTextBox prototype carries an auto-fit that resizes the
      // shape to the string - the box would then no longer be the box this op describes. A
      // text-less rectangle with a centred paragraph is a plain shape: transparent, no outline,
      // and the text sits exactly in the middle of the rectangle the op names.
      var box = api.createShape(slideHandle, op.text, op.x, op.y, op.w, op.h);
      api.setShapeGeometry(box, 'RECT');
      api.setShapeStyle(box, '', '', 0, 'solid');
      api.setShapeTextAlign(box, 'center');
      api.setShapeVerticalAlign(box, 'middle');
      // No inner distance and no wrapping: the box IS the text area, so the centre computed in
      // emitLabel() is the centre on the slide. With DrawingML's defaults (7.2 pt left and right)
      // the text area would be narrower than the string and PowerPoint would break a label that
      // has room for one line ("Web" / "UI").
      api.setShapeTextInsets(box, 0, 0, 0, 0);
      api.setShapeWordWrap(box, false);
      api.setShapeFont(box, op.size, op.bold, op.color, font);
      if (op.rotation) {
        api.setShapeRotation(box, op.rotation);
      }
      report.labels++;
    } else if (op.op === 'skip') {
      report.skipped++;
      report.notes.push('drawing: ' + op.reason);
    }
  }
  report.notes = report.notes.concat(approximationNotes(report.approximations));
  if (placed.warning) {
    report.notes.push('slide: ' + placed.warning);
  }
  return report;
}

/**
 * One note per approximated kind, in a stable order.
 * @param {object} approximations {kind: count}
 * @returns {Array<string>} the notes
 */
function approximationNotes(approximations) {
  var kinds = Object.keys(approximations).sort();
  var notes = [];
  for (var i = 0; i < kinds.length; i++) {
    notes.push('drawing: ' + approximations[kinds[i]] + ' x ' + kinds[i] + ' is drawn as the'
      + ' closest pptx preset (a slide shape knows a preset geometry, not an arbitrary outline)');
  }
  return notes;
}

/**
 * Writes a scene into a presentation - a new file, a new slide or an existing slide.
 * <p>
 * The three modes are the three ways a diagram gets into a deck, and the mode is never guessed: an
 * open file that turns out to be unreadable must not silently become a new presentation that
 * overwrites it.
 * </p>
 * <pre>{@code
 * pptx.write(scene, {file: 'target/diagram.pptx'});                  // new file, new slide
 * pptx.write(scene, {file: 'target/bericht.pptx', mode: 'append'});  // one more slide
 * pptx.write(scene, {file: 'target/bericht.pptx', mode: 'slide', slide: 3});  // onto slide 3
 * pptx.write(scene, {file: 'vorlage.pptx', mode: 'slide', slide: 1,
 *                    saveAs: 'target/bericht.pptx'});                // read A, write B
 * }</pre>
 * @param {object|string} scene the scene (or its JSON)
 * @param {object} options {file, mode, slide, saveAs, margin, fit, scale, align, valign, widthOf,
 *        font}
 * @returns {object} the report of {@link draw} plus {file, mode, slideNumber, presentation}
 */
function write(scene, options) {
  var resolved = sceneOf(scene);
  var opts = options || {};
  var api = pptxApi();
  var file = opts.file;
  if (typeof file !== 'string' || file.trim() === '') {
    throw new Error("diagram.pptx: option 'file' must be the path of the .pptx file, but was: "
      + String(file));
  }
  var mode = opts.mode === undefined || opts.mode === null ? 'new' : String(opts.mode);
  if (MODES.indexOf(mode) < 0) {
    throw new Error("diagram.pptx: option 'mode' is 'new' (a new file), 'append' (a new slide in"
      + " an existing file) or 'slide' (an existing slide), but was: '" + mode + "'");
  }
  var show;
  var slide;
  var slideNumber;
  if (mode === 'new') {
    show = api.createPresentation();
    slide = api.createBlankSlide(show);
    slideNumber = 1;
  } else {
    show = api.openFile(file);
    var slides = api.getSlides(show).slides;
    if (mode === 'append') {
      slide = api.createBlankSlide(show);
      slideNumber = slides.length + 1;
    } else {
      var wanted = Number(opts.slide);
      if (!(wanted >= 1) || wanted !== Math.floor(wanted)) {
        throw new Error("diagram.pptx: mode 'slide' needs option 'slide' as a whole slide number"
          + ' (1-based), but was: ' + String(opts.slide));
      }
      if (wanted > slides.length) {
        throw new Error("diagram.pptx: slide " + wanted + ' does not exist - the file has '
          + slides.length + ' slide(s)');
      }
      slide = slides[wanted - 1].h;
      slideNumber = slides[wanted - 1].slideNumber;
    }
  }
  var report = draw(slide, resolved, {page: api.getPageSize(show), margin: opts.margin,
    fit: opts.fit, scale: opts.scale, align: opts.align, valign: opts.valign,
    headerSpace: opts.headerSpace, footerSpace: opts.footerSpace, widthOf: opts.widthOf,
    font: opts.font});
  var target = typeof opts.saveAs === 'string' && opts.saveAs.trim() !== '' ? opts.saveAs : file;
  api.save(show, target);
  report.file = target;
  report.mode = mode;
  report.slideNumber = slideNumber;
  // The presentation stays open (the bridge closes it at the end of the run): a caller may add a
  // second diagram or a caption to the same slide.
  report.presentation = show;
  return report;
}

module.exports = {
  DEFAULT_MARGIN: DEFAULT_MARGIN,
  DEFAULT_FONT: DEFAULT_FONT,
  MODES: MODES,
  place: place,
  ops: ops,
  draw: draw,
  write: write
};
