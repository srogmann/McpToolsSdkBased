/**
 * diagram/scene - the drawing description every backend shares.
 *
 * A scene is plain JSON: a width, a height and a list of items in SVG coordinates (origin top-left,
 * y growing downwards). It is deliberately <b>not</b> a list of pdfedit operations: otherwise SVG
 * and PPTX would be impossible. {@code diagram/layout.js} produces it, {@code diagram/svg.js} turns
 * it into a string, and a PDF or PPTX backend walks the same items. See docs/js/diagram-plan.md §5.
 *
 *     var scene = require('./diagram/scene.js').create();
 *     scene.items.push(scene.rect(10, 20, 92, 28, {rx: 3}));
 *
 * All coordinates and sizes are points. The helpers here only build items and normalize the drawing
 * to the origin; no item knows about fonts or a page.
 */

'use strict';

/** The default ink of a stroke. */
var STROKE = '#333333';

/** The default ink of a label. */
var INK = '#111111';

/** The stroke width of a node outline. */
var LINE_WIDTH = 1;

/** The corner radius of a rounded edge ('round'), in points. */
var CURVE_RADIUS = 4;

/** The tension of a spline edge: 0.5 is a calm curve, 1 the classic Catmull-Rom. */
var CURVE_TENSION = 0.5;

/** The centripetal knot exponent - the value that keeps a spline free of cusps and loops. */
var CENTRIPETAL = 0.5;

/**
 * Creates an empty scene.
 * @returns {object} the scene
 */
function create() {
  return {kind: 'scene', width: 0, height: 0, originX: 0, originY: 0, items: [], phases: {}};
}

/**
 * Builds the style members an item may carry, dropping the absent ones.
 * @param {object} [style] {stroke, fill, lineWidth, dash}
 * @returns {object} the members to merge into the item
 */
function styleOf(style) {
  var s = style || {};
  return {
    stroke: s.stroke === undefined ? STROKE : s.stroke,
    fill: s.fill === undefined ? null : s.fill,
    lineWidth: s.lineWidth === undefined ? LINE_WIDTH : s.lineWidth,
    dash: s.dash === undefined ? null : s.dash
  };
}

/**
 * A rectangle (a node box, a group band, a border).
 * @param {number} x left
 * @param {number} y top
 * @param {number} w width
 * @param {number} h height
 * @param {object} [style] {rx, stroke, fill, lineWidth, dash}
 * @returns {object} the item
 */
function rect(x, y, w, h, style) {
  var item = {kind: 'rect', x: x, y: y, w: w, h: h, rx: (style && style.rx) || 0};
  return merge(item, styleOf(style));
}

/**
 * An ellipse (a circle when rx === ry).
 * @param {number} cx center x
 * @param {number} cy center y
 * @param {number} rx horizontal radius
 * @param {number} ry vertical radius
 * @param {object} [style] the style
 * @returns {object} the item
 */
function ellipse(cx, cy, rx, ry, style) {
  var item = {kind: 'ellipse', cx: cx, cy: cy, rx: rx, ry: ry};
  return merge(item, styleOf(style));
}

/**
 * A closed polygon.
 * @param {Array<Array<number>>} points the vertices
 * @param {object} [style] the style
 * @returns {object} the item
 */
function polygon(points, style) {
  var item = {kind: 'polygon', points: clonePoints(points)};
  return merge(item, styleOf(style));
}

/**
 * An open polyline.
 * <p>
 * The points are the <b>support points</b> of the line: with {@code curve} the backends draw a
 * smooth line through them (see {@link curveSegments}), without it they connect them with straight
 * segments. The support points stay in the item because every geometry check in the layout
 * (crossings, overlaps, labels, bounds) works on them, and because the orientation step of the
 * scene transforms one point list, not a set of control points.
 * </p>
 * @param {Array<Array<number>>} points the vertices
 * @param {object} [style] the style
 * @param {object} [curve] {kind: 'round', radius} or {kind: 'spline', tension}, or null
 * @returns {object} the item
 */
function path(points, style, curve) {
  var item = {kind: 'path', points: clonePoints(points), fill: null, curve: null};
  var spec = normalizeCurve(curve);
  if (spec !== null) {
    item.curve = spec;
  }
  return merge(item, styleOf(style));
}

/**
 * Validates a curve descriptor.
 * @param {object|string|null} curve {kind, radius|tension}, a kind, or null/undefined
 * @returns {object|null} the normalized descriptor, or null for "straight"
 */
function normalizeCurve(curve) {
  if (curve === null || curve === undefined || curve === '' || curve === false) {
    return null;
  }
  var kind = (typeof curve === 'string') ? curve : curve.kind;
  if (kind === 'round') {
    return {kind: 'round', radius: numberOr(curve.radius, CURVE_RADIUS)};
  }
  if (kind === 'spline') {
    return {kind: 'spline', tension: numberOr(curve.tension, CURVE_TENSION)};
  }
  throw new Error("diagram/scene: unknown curve '" + kind + "' (allowed: round, spline)");
}

/**
 * Turns the support points of a path into drawing segments.
 * <p>
 * Both backends call this one function, so SVG and PDF draw <b>the same</b> curve: no backend
 * derives control points of its own, and the scene carries the support points plus the curve kind
 * instead of a second, redundant coordinate list (docs/js/diagram-plan.md §13.8).
 * </p>
 * @param {Array<Array<number>>} points the support points
 * @param {object|string|null} curve the curve descriptor
 * @returns {Array<object>} the segments after the first point: {type: 'L', to} or
 *   {type: 'C', c1, c2, to}
 */
function curveSegments(points, curve) {
  var list = points || [];
  var spec = normalizeCurve(curve);
  if (spec === null || list.length < 3) {
    return straightSegments(list);
  }
  if (spec.kind === 'round') {
    return roundSegments(list, spec.radius);
  }
  return splineSegments(list, spec.tension);
}

/**
 * The polyline segments of a path.
 * @param {Array<Array<number>>} points the points
 * @returns {Array<object>} one {type: 'L', to} per point after the first
 */
function straightSegments(points) {
  var out = [];
  for (var i = 1; i < points.length; i++) {
    out.push({type: 'L', to: [points[i][0], points[i][1]]});
  }
  return out;
}

/**
 * Rounds the corners of a polyline (smoothstep / corner filleting): every interior vertex is cut
 * back by {@code radius} on both sides and bridged by one quadratic Bézier whose control point is
 * the corner itself - a quarter-arc look that keeps the direction at both ends of the path, so an
 * arrowhead stays aligned.
 * <p>
 * The quadratic is elevated to the exactly equivalent cubic, so SVG ({@code C}) and PDF ({@code c})
 * write the identical curve.
 * </p>
 * @param {Array<Array<number>>} points the support points
 * @param {number} radius the wanted radius (cut back to half of the shorter adjacent segment)
 * @returns {Array<object>} the segments
 */
function roundSegments(points, radius) {
  var out = [];
  for (var i = 1; i < points.length - 1; i++) {
    var prev = points[i - 1];
    var corner = points[i];
    var next = points[i + 1];
    var inLength = distance(prev, corner);
    var outLength = distance(corner, next);
    var r = Math.min(radius, inLength / 2, outLength / 2);
    if (r < 0.01) {
      out.push({type: 'L', to: [corner[0], corner[1]]});
      continue;
    }
    var a = pointTowards(corner, prev, r);
    var b = pointTowards(corner, next, r);
    out.push({type: 'L', to: a});
    out.push({
      type: 'C',
      c1: [a[0] + 2 / 3 * (corner[0] - a[0]), a[1] + 2 / 3 * (corner[1] - a[1])],
      c2: [b[0] + 2 / 3 * (corner[0] - b[0]), b[1] + 2 / 3 * (corner[1] - b[1])],
      to: b
    });
  }
  out.push({type: 'L', to: [points[points.length - 1][0], points[points.length - 1][1]]});
  return out;
}

/**
 * Fits a centripetal Catmull-Rom spline through the support points and returns it as cubic Béziers.
 * <p>
 * Centripetal (knot exponent ½) rather than uniform: uniform Catmull-Rom overshoots and can loop
 * when the segment lengths differ a lot, which is exactly the case here (a routed edge has short
 * port stubs next to long runs). The two phantom points are mirrored from their neighbour, so the
 * curve leaves and enters the path along the first and last segment - again what keeps an arrowhead
 * aligned. Every control point is clamped into the corridor of its support points (see
 * {@link cubicSegment}), so the curve stays where the routing reserved room for the edge.
 * </p>
 * @param {Array<Array<number>>} points the support points
 * @param {number} tension the tangent scale (1 = plain Catmull-Rom)
 * @returns {Array<object>} the cubic segments
 */
function splineSegments(points, tension) {
  var padded = [reflect(points[0], points[1])];
  for (var i = 0; i < points.length; i++) {
    padded.push([points[i][0], points[i][1]]);
  }
  padded.push(reflect(points[points.length - 1], points[points.length - 2]));
  var out = [];
  for (var j = 1; j + 2 < padded.length; j++) {
    out.push(cubicSegment(padded[j - 1], padded[j], padded[j + 1], padded[j + 2], tension));
  }
  return out;
}

/**
 * One centripetal Catmull-Rom span as a cubic Bézier (non-uniform Hermite, knot spacing
 * {@code |dp|^0.5}).
 * @param {Array<number>} p0 the point before the span
 * @param {Array<number>} p1 the span start
 * @param {Array<number>} p2 the span end
 * @param {Array<number>} p3 the point after the span
 * @param {number} tension the tangent scale
 * @returns {object} the cubic segment
 */
function cubicSegment(p0, p1, p2, p3, tension) {
  var s1 = knot(distance(p0, p1));
  var s2 = knot(distance(p1, p2));
  var s3 = knot(distance(p2, p3));
  var t0 = 0;
  var t1 = t0 + s1;
  var t2 = t1 + s2;
  var t3 = t2 + s3;
  var m1 = [
    (t2 - t1) * ((p1[0] - p0[0]) / (t1 - t0) - (p2[0] - p0[0]) / (t2 - t0)
      + (p2[0] - p1[0]) / (t2 - t1)),
    (t2 - t1) * ((p1[1] - p0[1]) / (t1 - t0) - (p2[1] - p0[1]) / (t2 - t0)
      + (p2[1] - p1[1]) / (t2 - t1))
  ];
  var m2 = [
    (t2 - t1) * ((p2[0] - p1[0]) / (t2 - t1) - (p3[0] - p1[0]) / (t3 - t1)
      + (p3[0] - p2[0]) / (t3 - t2)),
    (t2 - t1) * ((p2[1] - p1[1]) / (t2 - t1) - (p3[1] - p1[1]) / (t3 - t1)
      + (p3[1] - p2[1]) / (t3 - t2))
  ];
  var t = numberOr(tension, CURVE_TENSION);
  var c1 = [p1[0] + m1[0] * t / 3, p1[1] + m1[1] * t / 3];
  var c2 = [p2[0] - m2[0] * t / 3, p2[1] - m2[1] * t / 3];
  // Clamp both control points into the box of the four support points: the drawn curve is then
  // inside that corridor, which is exactly the area the routing already proved to be free of node
  // boxes. Without the clamp a spline may bulge out of the corridor where a short port stub meets a
  // long run, and the box checks of the layout would no longer see the bulge.
  var box = {minX: Math.min(p0[0], p1[0], p2[0], p3[0]),
    minY: Math.min(p0[1], p1[1], p2[1], p3[1]),
    maxX: Math.max(p0[0], p1[0], p2[0], p3[0]),
    maxY: Math.max(p0[1], p1[1], p2[1], p3[1])};
  return {
    type: 'C',
    c1: [clamp(c1[0], box.minX, box.maxX), clamp(c1[1], box.minY, box.maxY)],
    c2: [clamp(c2[0], box.minX, box.maxX), clamp(c2[1], box.minY, box.maxY)],
    to: [p2[0], p2[1]]
  };
}

/**
 * Restricts a value to an interval.
 * @param {number} value the value
 * @param {number} min the lower bound
 * @param {number} max the upper bound
 * @returns {number} the clamped value
 */
function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}

/**
 * The knot spacing of a centripetal Catmull-Rom spline, floored so two coincident points cannot
 * produce a division by zero.
 * @param {number} d the distance between two support points
 * @returns {number} the knot spacing
 */
function knot(d) {
  return Math.max(Math.pow(d, CENTRIPETAL), 1e-4);
}

/**
 * Mirrors a point about another one - the phantom point at the end of an open spline.
 * @param {Array<number>} point the end point
 * @param {Array<number>} neighbour its neighbour
 * @returns {Array<number>} the mirrored point
 */
function reflect(point, neighbour) {
  return [2 * point[0] - neighbour[0], 2 * point[1] - neighbour[1]];
}

/**
 * A point {@code distance} away from {@code from} in the direction of {@code to}.
 * @param {Array<number>} from the start
 * @param {Array<number>} to the target
 * @param {number} length the distance
 * @returns {Array<number>} the point
 */
function pointTowards(from, to, length) {
  var d = distance(from, to);
  if (d < 1e-9) {
    return [from[0], from[1]];
  }
  return [from[0] + (to[0] - from[0]) / d * length, from[1] + (to[1] - from[1]) / d * length];
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
 * A number, or a fallback for anything that is not a finite number.
 * @param {*} value the value
 * @param {number} fallback the fallback
 * @returns {number} the number
 */
function numberOr(value, fallback) {
  return (typeof value === 'number' && isFinite(value)) ? value : fallback;
}

/**
 * A text line. The anchor says where x is relative to the string; the baseline says where y is
 * relative to the glyphs.
 * @param {number} x the x position
 * @param {number} y the y position
 * @param {string} text the string
 * @param {object} [options] {size, anchor, baseline, bold, fill}
 * @returns {object} the item
 */
function text(x, y, text, options) {
  var o = options || {};
  return {
    kind: 'text',
    x: x,
    y: y,
    text: String(text),
    size: o.size === undefined ? 10 : o.size,
    anchor: o.anchor === undefined ? 'start' : o.anchor,
    baseline: o.baseline === undefined ? 'alphabetic' : o.baseline,
    bold: Boolean(o.bold),
    fill: o.fill === undefined ? INK : o.fill
  };
}

/**
 * A titled band around a set of nodes (a subgraph / cluster).
 * @param {string} id the group id
 * @param {number} x left
 * @param {number} y top
 * @param {number} w width
 * @param {number} h height
 * @param {string} label the title
 * @param {number} [size] the title size
 * @returns {object} the item
 */
function group(id, x, y, w, h, label, size) {
  return {kind: 'group', id: id, x: x, y: y, w: w, h: h, label: label, size: size || 9};
}

/**
 * An arrowhead at a point, pointing along a direction vector.
 * <p>
 * The head is emitted as an explicit filled polygon, so every backend draws it the same way and
 * none of them needs an arrow rule of its own.
 * </p>
 * @param {number} x the tip x
 * @param {number} y the tip y
 * @param {number} dx the direction x (not both zero)
 * @param {number} dy the direction y
 * @param {number} [length] the head length
 * @returns {object} the item
 */
function arrow(x, y, dx, dy, length) {
  var len = length || 7;
  var half = len * 0.42;
  var norm = Math.sqrt(dx * dx + dy * dy) || 1;
  var ux = dx / norm;
  var uy = dy / norm;
  var baseX = x - ux * len;
  var baseY = y - uy * len;
  var px = -uy * half;
  var py = ux * half;
  return {
    kind: 'polygon',
    points: [[x, y], [baseX + px, baseY + py], [baseX - px, baseY - py]],
    stroke: STROKE,
    fill: STROKE,
    lineWidth: 0.5,
    dash: null
  };
}

/**
 * Shifts a point list by a delta.
 * @param {Array<Array<number>>} points the points
 * @param {number} dx the x delta
 * @param {number} dy the y delta
 * @returns {Array<Array<number>>} the shifted points
 */
function shiftPoints(points, dx, dy) {
  var out = [];
  for (var i = 0; i < points.length; i++) {
    out.push([points[i][0] + dx, points[i][1] + dy]);
  }
  return out;
}

/**
 * Normalizes a scene to a positive origin with a margin: the smallest x/y becomes {@code padding},
 * and width/height cover everything plus the padding on both sides.
 * @param {object} scene the scene (modified)
 * @param {number} [padding] the margin (default 8)
 * @returns {object} the scene
 */
function normalize(scene, padding) {
  var pad = padding === undefined ? 8 : padding;
  var box = bounds(scene.items);
  if (box.empty) {
    scene.width = pad * 2;
    scene.height = pad * 2;
    return scene;
  }
  var dx = pad - box.minX;
  var dy = pad - box.minY;
  translate(scene.items, dx, dy);
  scene.originX = 0;
  scene.originY = 0;
  scene.width = round(box.maxX - box.minX + pad * 2);
  scene.height = round(box.maxY - box.minY + pad * 2);
  return scene;
}

/**
 * Computes the bounding box of a list of items.
 * @param {Array<object>} items the items
 * @returns {object} {minX, minY, maxX, maxY, empty}
 */
function bounds(items) {
  var minX = Infinity;
  var minY = Infinity;
  var maxX = -Infinity;
  var maxY = -Infinity;
  for (var i = 0; i < items.length; i++) {
    var box = itemBounds(items[i]);
    if (box === null) {
      continue;
    }
    minX = Math.min(minX, box[0]);
    minY = Math.min(minY, box[1]);
    maxX = Math.max(maxX, box[2]);
    maxY = Math.max(maxY, box[3]);
  }
  if (minX === Infinity) {
    return {minX: 0, minY: 0, maxX: 0, maxY: 0, empty: true};
  }
  return {minX: minX, minY: minY, maxX: maxX, maxY: maxY, empty: false};
}

/**
 * The bounding box of a single item as [minX, minY, maxX, maxY], or null when it has no extent.
 * @param {object} item the item
 * @returns {Array<number>|null} the box
 */
function itemBounds(item) {
  if (item.kind === 'rect' || item.kind === 'group') {
    return [item.x, item.y, item.x + item.w, item.y + item.h];
  }
  if (item.kind === 'ellipse') {
    return [item.cx - item.rx, item.cy - item.ry, item.cx + item.rx, item.cy + item.ry];
  }
  if (item.kind === 'text') {
    // The exact text extent needs the font; use a conservative estimate around the anchor.
    var estimated = item.text.length * item.size * 0.62;
    var half = item.anchor === 'middle' ? estimated / 2 : (item.anchor === 'end' ? estimated : 0);
    if (item.rotation === 90) {
      // A label rotated clockwise runs downwards: the length is in y, not in x.
      return [item.x - item.size, item.y - half, item.x + item.size * 0.3,
        item.y - half + estimated];
    }
    if (item.rotation === 270) {
      // ... and counter-clockwise it runs upwards.
      return [item.x - item.size, item.y - estimated + half, item.x + item.size * 0.3,
        item.y + half];
    }
    return [item.x - half, item.y - item.size, item.x - half + estimated, item.y + item.size * 0.3];
  }
  if (item.points && item.points.length && item.curve) {
    // A curve can leave the box of its support points (a spline bulges), so the hull of the
    // segment endpoints and control points is the honest extent.
    var segs = curveSegments(item.points, item.curve);
    var curveBox = {minX: item.points[0][0], minY: item.points[0][1],
      maxX: item.points[0][0], maxY: item.points[0][1]};
    for (var s = 0; s < segs.length; s++) {
      extendBox(curveBox, segs[s].to);
      if (segs[s].c1) {
        extendBox(curveBox, segs[s].c1);
      }
      if (segs[s].c2) {
        extendBox(curveBox, segs[s].c2);
      }
    }
    return [curveBox.minX, curveBox.minY, curveBox.maxX, curveBox.maxY];
  }
  if (item.points && item.points.length) {
    var minX = Infinity;
    var minY = Infinity;
    var maxX = -Infinity;
    var maxY = -Infinity;
    for (var p = 0; p < item.points.length; p++) {
      minX = Math.min(minX, item.points[p][0]);
      minY = Math.min(minY, item.points[p][1]);
      maxX = Math.max(maxX, item.points[p][0]);
      maxY = Math.max(maxY, item.points[p][1]);
    }
    return [minX, minY, maxX, maxY];
  }
  return null;
}

/**
 * Grows a box structure by one point.
 * @param {object} box {minX, minY, maxX, maxY} (modified)
 * @param {Array<number>} point the point
 */
function extendBox(box, point) {
  box.minX = Math.min(box.minX, point[0]);
  box.minY = Math.min(box.minY, point[1]);
  box.maxX = Math.max(box.maxX, point[0]);
  box.maxY = Math.max(box.maxY, point[1]);
}

/**
 * Translates every item of a scene by a delta.
 * @param {Array<object>} items the items (modified)
 * @param {number} dx the x delta
 * @param {number} dy the y delta
 */
function translate(items, dx, dy) {
  for (var i = 0; i < items.length; i++) {
    var item = items[i];
    if (item.kind === 'rect' || item.kind === 'group') {
      item.x += dx;
      item.y += dy;
    } else if (item.kind === 'ellipse') {
      item.cx += dx;
      item.cy += dy;
    } else if (item.kind === 'text') {
      item.x += dx;
      item.y += dy;
    }
    if (item.points) {
      item.points = shiftPoints(item.points, dx, dy);
    }
  }
}

/**
 * Re-orients a scene that was laid out with ranks running downwards (TB) and node order running to
 * the right, into the requested direction.
 * <p>
 * The mirroring happens about the drawing's own bounds, not about an assumed origin, so a scene
 * whose items sit at absolute coordinates (a pinned node) keeps them: a TB scene is unchanged, and
 * the others are reflected in place.
 * </p>
 * @param {object} scene the scene in internal coordinates (modified)
 * @param {string} direction TB | BT | LR | RL
 * @param {object} box the internal bounds {minX, minY, maxX, maxY}
 * @returns {object} the scene
 */
function orient(scene, direction, box) {
  if (direction === 'TB') {
    return scene;
  }
  var sumY = box.minY + box.maxY;
  var items = scene.items;
  for (var i = 0; i < items.length; i++) {
    var item = items[i];
    if (direction === 'BT') {
      flipY(item, sumY);
    } else if (direction === 'LR') {
      swapXY(item);
    } else if (direction === 'RL') {
      swapXY(item);
      // After the swap the x axis is the old y axis, so the mirror constant is sumY.
      flipX(item, sumY);
    }
  }
  return scene;
}

/**
 * Rotates a whole scene by a right angle and re-normalises it.
 * <p>
 * The rotation is about the drawing's own centre, so the result is the same picture turned - the
 * items keep their relation to each other. It is the answer to "this diagram is wide, put it on a
 * portrait page" when the page is shared with text and {@code orientation: 'landscape'} is not an
 * option: the drawing becomes tall, and {@code fit()} then measures the rotated scene.
 * </p>
 * <p>
 * The label of a node turns with the drawing (a text item carries {@code rotation}, which the SVG
 * and the PDF backend both write). The title of a group band does not: it is placed in the corner
 * of the band as before, so a rotated subgraph keeps a readable caption - turning it would put it
 * along the band's edge, where it runs out of the band.
 * </p>
 * @param {object} scene the scene (modified)
 * @param {number} degrees 0, 90, 180 or 270 (clockwise on screen)
 * @param {number} [padding] the margin {@link normalize} leaves (default 8)
 * @returns {object} the scene
 */
function rotate(scene, degrees, padding) {
  var deg = normalizeRotation(degrees);
  if (deg !== 0) {
    var box = bounds(scene.items);
    if (!box.empty) {
      var sumX = box.minX + box.maxX;
      var sumY = box.minY + box.maxY;
      for (var i = 0; i < scene.items.length; i++) {
        rotateItem(scene.items[i], deg, sumX, sumY);
      }
    }
    normalize(scene, padding);
  }
  scene.rotation = deg;
  return scene;
}

/**
 * Mirrors one item vertically about {@code y = sum / 2}.
 * @param {object} item the item
 * @param {number} sum the sum of the drawing's min and max y
 */
function flipY(item, sum) {
  if (item.kind === 'rect' || item.kind === 'group') {
    item.y = sum - item.y - item.h;
  } else if (item.kind === 'ellipse') {
    item.cy = sum - item.cy;
  } else if (item.kind === 'text') {
    item.y = sum - item.y;
  }
  if (item.points) {
    for (var p = 0; p < item.points.length; p++) {
      item.points[p][1] = sum - item.points[p][1];
    }
  }
}

/**
 * Mirrors one item horizontally about {@code x = sum / 2}.
 * @param {object} item the item
 * @param {number} sum the sum of the drawing's min and max x
 */
function flipX(item, sum) {
  if (item.kind === 'rect' || item.kind === 'group') {
    item.x = sum - item.x - item.w;
  } else if (item.kind === 'ellipse') {
    item.cx = sum - item.cx;
  } else if (item.kind === 'text') {
    item.x = sum - item.x;
  }
  if (item.points) {
    for (var p = 0; p < item.points.length; p++) {
      item.points[p][0] = sum - item.points[p][0];
    }
  }
}

/**
 * Rotates one item by 90, 180 or 270 degrees around the drawing's bounds.
 * <p>
 * Only right angles are needed: they keep every box axis-parallel, so a rect stays a rect (with
 * swapped width and height), an ellipse keeps rx/ry, and text stays text - it only carries a
 * {@code rotation} that both backends honour. Anything else would turn boxes into polygons and
 * ellipses into bezier paths, i.e. into a different drawing.
 * </p>
 * @param {object} item the item (modified)
 * @param {number} deg 90 | 180 | 270
 * @param {number} sumX the sum of the drawing's min and max x
 * @param {number} sumY the sum of the drawing's min and max y
 */
function rotateItem(item, deg, sumX, sumY) {
  if (deg === 90) {
    swapXY(item);
    flipY(item, sumX);
  } else if (deg === 180) {
    flipX(item, sumX);
    flipY(item, sumY);
  } else {
    swapXY(item);
    flipX(item, sumY);
  }
  if (item.kind === 'text') {
    item.rotation = ((item.rotation || 0) + deg) % 360;
  }
}

/**
 * Normalizes a rotation to 0, 90, 180 or 270 degrees.
 * @param {number} degrees the requested rotation
 * @returns {number} the normalized rotation
 * @throws {Error} for anything that is not a right angle
 */
function normalizeRotation(degrees) {
  if (typeof degrees !== 'number' || !isFinite(degrees)) {
    throw new Error('diagram: rotate needs a number of degrees, but was: ' + String(degrees));
  }
  var deg = ((Math.round(degrees) % 360) + 360) % 360;
  if (deg % 90 !== 0) {
    throw new Error('diagram: rotate supports 0, 90, 180 and 270 degrees (a right angle keeps '
      + 'boxes axis-parallel), but was: ' + degrees);
  }
  return deg;
}

/**
 * Swaps the x and y axes of one item (rank axis becomes horizontal).
 * @param {object} item the item
 */
function swapXY(item) {
  if (item.kind === 'rect' || item.kind === 'group') {
    var oldX = item.x;
    var oldW = item.w;
    item.x = item.y;
    item.w = item.h;
    item.y = oldX;
    item.h = oldW;
  } else if (item.kind === 'ellipse') {
    var oldCx = item.cx;
    var oldRx = item.rx;
    item.cx = item.cy;
    item.rx = item.ry;
    item.cy = oldCx;
    item.ry = oldRx;
  } else if (item.kind === 'text') {
    var oldTx = item.x;
    item.x = item.y;
    item.y = oldTx;
  }
  if (item.points) {
    for (var p = 0; p < item.points.length; p++) {
      var oldPx = item.points[p][0];
      item.points[p][0] = item.points[p][1];
      item.points[p][1] = oldPx;
    }
  }
}

/**
 * Rounds a coordinate to a tenth of a point - the precision of the scene, so two identical runs
 * produce the identical JSON.
 * @param {number} value the value
 * @returns {number} the rounded value
 */
function round(value) {
  return Math.round(value * 10) / 10;
}

/**
 * Copies a point list.
 * @param {Array<Array<number>>} points the points
 * @returns {Array<Array<number>>} the copy
 */
function clonePoints(points) {
  var out = [];
  for (var i = 0; i < points.length; i++) {
    out.push([points[i][0], points[i][1]]);
  }
  return out;
}

/**
 * Merges the members of a second object into the first.
 * @param {object} target the item
 * @param {object} extra the members to add
 * @returns {object} the item
 */
function merge(target, extra) {
  for (var key in extra) {
    if (Object.prototype.hasOwnProperty.call(extra, key)) {
      target[key] = extra[key];
    }
  }
  return target;
}

/**
 * The centre of every node in a scene, or the position map itself.
 * <p>
 * Accepts three things so that a diff can be built from whatever a caller has: a scene (the
 * bounding box of the shape items of each {@code nodeGroup} - the label is left out, a long label
 * must not shift the centre), a plain map {@code {id: [x, y]}} (what {@code svg.nodePositions()}
 * returns), or {@code {nodes: {...}}}. Text items count only when a node has nothing else.
 * </p>
 * @param {object} sceneOrMap the scene or the position map
 * @returns {object} {id: [cx, cy]}
 */
function nodeCentres(sceneOrMap) {
  if (!sceneOrMap) {
    return {};
  }
  if (!sceneOrMap.items && sceneOrMap.nodes && typeof sceneOrMap.nodes === 'object') {
    return nodeCentres(sceneOrMap.nodes);
  }
  if (!sceneOrMap.items) {
    var asMap = {};
    for (var key in sceneOrMap) {
      if (!Object.prototype.hasOwnProperty.call(sceneOrMap, key)) {
        continue;
      }
      var value = sceneOrMap[key];
      if (value && value.length >= 2 && typeof value[0] === 'number') {
        asMap[key] = [value[0], value[1]];
      }
    }
    return asMap;
  }
  var boxes = {};
  var texts = {};
  var items = sceneOrMap.items;
  for (var i = 0; i < items.length; i++) {
    var item = items[i];
    if (!item.nodeGroup || item.kind === 'group') {
      continue;
    }
    var box = itemBounds(item);
    if (!box) {
      continue;
    }
    var target = item.kind === 'text' ? texts : boxes;
    if (!target[item.nodeGroup]) {
      target[item.nodeGroup] = [box[0], box[1], box[2], box[3]];
    } else {
      var current = target[item.nodeGroup];
      current[0] = Math.min(current[0], box[0]);
      current[1] = Math.min(current[1], box[1]);
      current[2] = Math.max(current[2], box[2]);
      current[3] = Math.max(current[3], box[3]);
    }
  }
  var centres = {};
  var ids = Object.keys(boxes).concat(Object.keys(texts).filter(function (id) {
    return !boxes[id];
  }));
  for (var k = 0; k < ids.length; k++) {
    var chosen = boxes[ids[k]] || texts[ids[k]];
    centres[ids[k]] = [round((chosen[0] + chosen[2]) / 2), round((chosen[1] + chosen[3]) / 2)];
  }
  return centres;
}

/**
 * The translation shared by the most nodes - the normalisation offset between two scenes.
 * <p>
 * Two scenes of the same graph are each translated so the drawing starts at the margin, so the
 * difference between them is "what moved" <em>plus</em> that translation. It is recovered as the
 * most frequent per-node delta: the nodes that did not move vote for it. Ties go to the smaller
 * movement. Measuring the delta naively is the trap: in the ILC series the offset was 11.9 pt in
 * x and up to 57.7 pt in y, which reads like "everything moved".
 * </p>
 * @param {object} first the centres before
 * @param {object} second the centres after
 * @param {Array<string>} ids the nodes present in both
 * @returns {Array<number>} [dx, dy]
 */
function dominantShift(first, second, ids) {
  var votes = {};
  var order = [];
  for (var i = 0; i < ids.length; i++) {
    var dx = round(second[ids[i]][0] - first[ids[i]][0]);
    var dy = round(second[ids[i]][1] - first[ids[i]][1]);
    var key = dx + '/' + dy;
    if (!votes[key]) {
      votes[key] = {dx: dx, dy: dy, count: 0};
      order.push(key);
    }
    votes[key].count++;
  }
  var best = null;
  for (var k = 0; k < order.length; k++) {
    var vote = votes[order[k]];
    if (best === null || vote.count > best.count
        || (vote.count === best.count && Math.abs(vote.dx) + Math.abs(vote.dy)
          < Math.abs(best.dx) + Math.abs(best.dy))) {
      best = vote;
    }
  }
  return best === null ? [0, 0] : [best.dx, best.dy];
}

/**
 * What changed between two scenes, as a correction recipe.
 * <p>
 * The answer is the recipe a human edit produces when it is read back: which nodes moved, by how
 * much, normalisation removed. {@code nudge} is what has to be added to the layout that produced
 * the first scene (the deltas are relative to <em>it</em>, not to the free placement);
 * {@code targets} says where each moved node ended up, in the first scene's frame.
 * <p>
 * {@code absolute} turns {@code targets} into a nudge map. With {@code options.free} (the
 * positions of the no-nudge placement) that is exact for every node; with only
 * {@code options.base} (the first scene's own nudge map) it is exact for the nodes that base
 * anchors and folds the drift of the others in - {@code exact} says which of the two happened.
 * </p>
 * @param {object} first the scene before (or a position map)
 * @param {object} second the scene after (or a position map)
 * @param {object} [options] {tolerance, shift, base, free}
 * @returns {object} {shift, tolerance, moved, nudge, targets, absolute, exact, unchanged,
 *   onlyInFirst, onlyInSecond}
 */
function diff(first, second, options) {
  var opts = options || {};
  var tolerance = opts.tolerance === undefined ? 0.5 : Number(opts.tolerance);
  var before = nodeCentres(first);
  var after = nodeCentres(second);
  var onlyInFirst = [];
  var onlyInSecond = [];
  var common = [];
  var id;
  for (id in before) {
    if (Object.prototype.hasOwnProperty.call(before, id)) {
      if (after[id]) {
        common.push(id);
      } else {
        onlyInFirst.push(id);
      }
    }
  }
  for (id in after) {
    if (Object.prototype.hasOwnProperty.call(after, id) && !before[id]) {
      onlyInSecond.push(id);
    }
  }
  common.sort();
  var shift = opts.shift ? [Number(opts.shift[0]) || 0, Number(opts.shift[1]) || 0]
    : dominantShift(before, after, common);
  var moved = [];
  var nudge = {};
  var unchanged = 0;
  for (var i = 0; i < common.length; i++) {
    var name = common[i];
    var dx = round(after[name][0] - before[name][0] - shift[0]);
    var dy = round(after[name][1] - before[name][1] - shift[1]);
    if (Math.abs(dx) <= tolerance && Math.abs(dy) <= tolerance) {
      unchanged++;
      continue;
    }
    moved.push({id: name, dx: dx, dy: dy});
    nudge[name] = {dx: dx, dy: dy};
  }
  var absolute = null;
  var exactFrame = 0;
  var frameOffset = null;
  var targets = {};
  for (var t = 0; t < moved.length; t++) {
    targets[moved[t].id] = [round(before[moved[t].id][0] + moved[t].dx),
      round(before[moved[t].id][1] + moved[t].dy)];
  }
  if (opts.free && typeof opts.free === 'object') {
    // The exact route: nudge = target - (where the free placement puts the node) - frameOffset.
    // The third term is the normalisation of the *first* scene: its coordinates are the layout
    // coordinates plus an offset, and `targets` lives in that frame. The base recipe determines it
    // exactly - an anchored node sits at free + base + offset, so one anchor is enough (the mean
    // over all of them is used, they agree). Guessing it from "nodes that did not move" fails as
    // soon as a relaxation moved many, which is the normal case.
    var free = nodeCentres(opts.free);
    var offsets = [];
    if (opts.base && typeof opts.base === 'object') {
      var anchor;
      for (anchor in opts.base) {
        if (Object.prototype.hasOwnProperty.call(opts.base, anchor) && before[anchor]
            && free[anchor]) {
          offsets.push([before[anchor][0] - free[anchor][0]
            - (Number(opts.base[anchor].dx) || 0),
          before[anchor][1] - free[anchor][1] - (Number(opts.base[anchor].dy) || 0)]);
        }
      }
    }
    frameOffset = [0, 0];
    if (offsets.length) {
      for (var o = 0; o < offsets.length; o++) {
        frameOffset[0] += offsets[o][0] / offsets.length;
        frameOffset[1] += offsets[o][1] / offsets.length;
      }
    }
    absolute = {};
    for (var f in targets) {
      if (Object.prototype.hasOwnProperty.call(targets, f) && free[f]) {
        absolute[f] = {dx: round(targets[f][0] - frameOffset[0] - free[f][0]),
          dy: round(targets[f][1] - frameOffset[1] - free[f][1])};
      }
    }
    if (opts.base && typeof opts.base === 'object') {
      var keep;
      for (keep in opts.base) {
        if (Object.prototype.hasOwnProperty.call(opts.base, keep) && !absolute[keep]) {
          absolute[keep] = {dx: Number(opts.base[keep].dx) || 0,
            dy: Number(opts.base[keep].dy) || 0};
        }
      }
    }
    exactFrame = offsets.length;
  } else if (opts.base && typeof opts.base === 'object') {
    // Without the free placement this is only exact for the nodes the base already anchors; a node
    // that was merely relaxed in the first scene carries its drift with it. targets is the honest
    // way out: the caller knows where the node has to end up.
    absolute = {};
    var name2;
    for (name2 in opts.base) {
      if (Object.prototype.hasOwnProperty.call(opts.base, name2)) {
        absolute[name2] = {dx: Number(opts.base[name2].dx) || 0,
          dy: Number(opts.base[name2].dy) || 0};
      }
    }
    for (var m = 0; m < moved.length; m++) {
      var current = absolute[moved[m].id] || {dx: 0, dy: 0};
      absolute[moved[m].id] = {dx: round(current.dx + moved[m].dx),
        dy: round(current.dy + moved[m].dy)};
    }
  }
  return {shift: shift, tolerance: tolerance, moved: moved, nudge: nudge, targets: targets,
    absolute: absolute, exact: exactFrame > 0, frameOffset: frameOffset, unchanged: unchanged,
    onlyInFirst: onlyInFirst, onlyInSecond: onlyInSecond};
}


module.exports = {
  STROKE: STROKE,
  INK: INK,
  CURVE_RADIUS: CURVE_RADIUS,
  CURVE_TENSION: CURVE_TENSION,
  create: create,
  rect: rect,
  ellipse: ellipse,
  polygon: polygon,
  path: path,
  text: text,
  group: group,
  arrow: arrow,
  normalizeCurve: normalizeCurve,
  curveSegments: curveSegments,
  shiftPoints: shiftPoints,
  bounds: bounds,
  itemBounds: itemBounds,
  translate: translate,
  normalize: normalize,
  orient: orient,
  rotate: rotate,
  round: round,
  nodeCentres: nodeCentres,
  diff: diff
};
