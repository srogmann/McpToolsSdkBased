/**
 * diagram/svg - the SVG backend.
 *
 * The cheapest backend, the debugging bridge and the test channel: the scene is already in SVG
 * coordinates, so turning it into a string is a walk over the items. SVG comes before PDF because
 * without it the web UI cannot show a result at all (docs/js/diagram-plan.md §11.3).
 *
 *     var svg = require('./diagram/svg.js').render(scene);
 *
 * The output is self-contained: no external CSS, no script, a viewBox in points and a white
 * background. Text is escaped; nothing of a label is interpreted as markup.
 */

'use strict';

var sceneLib = require('./scene.js');

/** The font stack a label is drawn in. */
var FONT_FAMILY = 'Helvetica, Arial, sans-serif';

/**
 * Renders a scene to an SVG document string.
 * @param {object} scene the scene
 * @param {object} [options] {background, title}
 * @returns {string} the SVG
 */
function render(scene, options) {
  var opts = options || {};
  var width = round(scene.width || 1);
  var height = round(scene.height || 1);
  var originX = round(scene.originX || 0);
  var originY = round(scene.originY || 0);
  var lines = [];
  lines.push('<?xml version="1.0" encoding="UTF-8"?>');
  lines.push('<svg xmlns="http://www.w3.org/2000/svg" version="1.1" width="' + width
    + '" height="' + height + '" viewBox="' + originX + ' ' + originY + ' ' + width + ' '
    + height + '">');
  if (opts.title) {
    lines.push('  <title>' + escapeXml(opts.title + recipeTail(scene)) + '</title>');
  }
  if (scene.phases && scene.phases.recipe) {
    lines.push('  <!-- recipe: ' + recipeText(scene.phases.recipe) + ' -->');
  }
  lines.push('  <rect x="' + originX + '" y="' + originY + '" width="' + width + '" height="'
    + height + '" fill="' + escapeXml(opts.background || '#ffffff') + '"/>');
  var openGroup = null;
  var opened = {};
  for (var i = 0; i < (scene.items || []).length; i++) {
    var item = scene.items[i];
    var key = groupKeyOf(item);
    if (key !== openGroup) {
      if (openGroup !== null) {
        lines.push('  </g>');
      }
      openGroup = key;
      if (key !== null) {
        // Two edges with the same endpoints would share a key; the second gets a suffix, so no
        // id appears twice (the data-* attribute keeps the plain name).
        opened[key] = (opened[key] || 0) + 1;
        lines.push('  ' + groupTag(item, key, opened[key]));
      }
    }
    var rendered = renderItem(item);
    if (rendered !== null) {
      lines.push((key === null ? '  ' : '    ') + rendered);
    }
  }
  if (openGroup !== null) {
    lines.push('  </g>');
  }
  lines.push('</svg>');
  return lines.join('\n');
}

/**
 * The group key of an item: every element of one node shares {@code node-<id>}, every element of
 * one edge {@code edge-<from>-to-<to>}; anything else is ungrouped.
 * <p>
 * The grouping is what makes the picture and the model talk to each other: a user moves a node in
 * an editor, the group moves with it, and its {@code id} says which node it was - no matching of
 * coordinates, no guessing from label texts.
 * </p>
 * @param {object} item the item
 * @returns {string|null} the key
 */
function groupKeyOf(item) {
  if (item.nodeGroup) {
    return 'node-' + item.nodeGroup;
  }
  if (item.edgeFrom !== undefined && item.edgeTo !== undefined) {
    return 'edge-' + item.edgeFrom + '-to-' + item.edgeTo;
  }
  return null;
}

/**
 * The opening tag of a group, with the model name in {@code data-*} as well: the {@code id} is
 * sanitized (an XML id has no spaces), the {@code data-*} value is the exact name.
 * @param {object} item the first item of the group
 * @param {string} key the group key
 * @param {number} [seq] 1 for the first group with this key, 2 for the next and so on
 * @returns {string} the tag
 */
function groupTag(item, key, seq) {
  var id = key.replace(/[^A-Za-z0-9_.-]/g, '_') + (seq && seq > 1 ? '-' + seq : '');
  if (item.nodeGroup) {
    return '<g id="' + escapeXml(id) + '" data-node="' + escapeXml(item.nodeGroup) + '">';
  }
  return '<g id="' + escapeXml(id) + '" data-edge="' + escapeXml(item.edgeFrom + ' -> '
    + item.edgeTo) + '">';
}

/**
 * The recipe of a scene as one compact line - for the SVG title, which survives an editor
 * round-trip even where an XML comment does not.
 * @param {object} recipe the recipe
 * @returns {string} the line
 */
function recipeLine(recipe) {
  var parts = ['layout=' + recipe.layout];
  if (recipe.force) {
    parts.push('seed=' + recipe.force.seed);
  }
  if (recipe.relax) {
    parts.push('relax=' + recipe.relax + '/' + recipe.relaxLevels);
  }
  if (recipe.nudgeCount) {
    parts.push('nudge=' + recipe.nudgeCount);
  }
  return parts.join(' ');
}

/**
 * The appended title text (empty when the scene carries no recipe).
 * @param {object} scene the scene
 * @returns {string} the tail
 */
function recipeTail(scene) {
  if (!scene.phases || !scene.phases.recipe) {
    return '';
  }
  return ' | recipe: ' + recipeLine(scene.phases.recipe);
}

/**
 * The full recipe as JSON, for the XML comment.
 * <p>
 * Written raw, not escaped: a comment is not an attribute, so the quotes can stay readable.
 * XML forbids {@code --} inside a comment, so a doubled hyphen is broken up.
 * </p>
 * @param {object} recipe the recipe
 * @returns {string} the JSON
 */
function recipeText(recipe) {
  var text;
  try {
    text = JSON.stringify(recipe);
  } catch (e) {
    text = recipeLine(recipe);
  }
  return String(text).replace(/--/g, '- -');
}

/**
 * Renders one scene item.
 * @param {object} item the item
 * @returns {string|null} the SVG element, or null for an unknown kind
 */
function renderItem(item) {
  if (item.kind === 'rect') {
    return '<rect' + position(item) + ' width="' + round(item.w) + '" height="' + round(item.h)
      + '"' + (item.rx ? ' rx="' + round(item.rx) + '" ry="' + round(item.rx) + '"' : '')
      + ' ' + paint(item) + '/>';
  }
  if (item.kind === 'ellipse') {
    return '<ellipse cx="' + round(item.cx) + '" cy="' + round(item.cy) + '" rx="'
      + round(item.rx) + '" ry="' + round(item.ry) + '" ' + paint(item) + '/>';
  }
  if (item.kind === 'polygon') {
    return '<polygon points="' + pointsAttr(item.points) + '" ' + paint(item) + '/>';
  }
  if (item.kind === 'path') {
    return '<path d="' + pathData(item.points, item.curve) + '" fill="none" ' + strokeOnly(item)
      + '/>';
  }
  if (item.kind === 'group') {
    return renderGroup(item);
  }
  if (item.kind === 'text') {
    return renderText(item);
  }
  return null;
}

/**
 * Renders a group band and its title.
 * @param {object} item the group item
 * @returns {string} the SVG
 */
function renderGroup(item) {
  var rect = '<rect' + position(item) + ' width="' + round(item.w) + '" height="'
    + round(item.h) + '" rx="4" ry="4" fill="#f6f7f9" stroke="#b9bec7" stroke-width="1"'
    + ' stroke-dasharray="4 3"/>';
  var label = renderText({
    kind: 'text',
    x: item.x + 6,
    y: item.y + item.size + 2,
    text: item.label,
    size: item.size,
    anchor: 'start',
    baseline: 'middle',
    bold: false,
    fill: '#555b66'
  });
  return rect + '\n  ' + label;
}

/**
 * Renders a text item.
 * @param {object} item the text item
 * @returns {string} the SVG
 */
function renderText(item) {
  var attrs = 'x="' + round(item.x) + '" y="' + round(item.y) + '"'
    + ' font-family="' + FONT_FAMILY + '"'
    + ' font-size="' + round(item.size) + '"'
    + ' text-anchor="' + anchorOf(item.anchor) + '"'
    + ' dominant-baseline="' + (item.baseline === 'middle' ? 'central' : 'alphabetic') + '"'
    + ' fill="' + escapeXml(item.fill || '#111111') + '"';
  if (item.bold) {
    attrs += ' font-weight="bold"';
  }
  if (item.rotation) {
    // Right angles only (scene.rotate): the anchor stays where it is, the line turns around it.
    // SVG counts clockwise, which is the sense scene.rotate documents.
    attrs += ' transform="rotate(' + round(item.rotation) + ' ' + round(item.x) + ' '
      + round(item.y) + ')"';
  }
  return '<text ' + attrs + '>' + escapeXml(item.text) + '</text>';
}

/**
 * The {@code text-anchor} value for an anchor.
 * @param {string} anchor start | middle | end
 * @returns {string} the SVG value
 */
function anchorOf(anchor) {
  if (anchor === 'middle') {
    return 'middle';
  }
  if (anchor === 'end') {
    return 'end';
  }
  return 'start';
}

/**
 * The x/y attributes of a box item.
 * @param {object} item the item
 * @returns {string} the attributes
 */
function position(item) {
  return ' x="' + round(item.x) + '" y="' + round(item.y) + '"';
}

/**
 * The fill and stroke attributes of a filled item.
 * @param {object} item the item
 * @returns {string} the attributes
 */
function paint(item) {
  var attrs = 'fill="' + (item.fill ? escapeXml(item.fill) : 'none') + '"';
  if (item.stroke) {
    attrs += ' stroke="' + escapeXml(item.stroke) + '" stroke-width="'
      + round(item.lineWidth === undefined ? 1 : item.lineWidth) + '"';
    if (item.dash && item.dash.length) {
      attrs += ' stroke-dasharray="' + item.dash.join(' ') + '"';
    }
  }
  return attrs;
}

/**
 * The stroke-only attributes of an open path.
 * @param {object} item the item
 * @returns {string} the attributes
 */
function strokeOnly(item) {
  var attrs = 'stroke="' + escapeXml(item.stroke || '#333333') + '" stroke-width="'
    + round(item.lineWidth === undefined ? 1 : item.lineWidth) + '"'
    + ' stroke-linejoin="round" stroke-linecap="round"';
  if (item.dash && item.dash.length) {
    attrs += ' stroke-dasharray="' + item.dash.join(' ') + '"';
  }
  return attrs;
}

/**
 * The {@code points} attribute of a polygon.
 * @param {Array<Array<number>>} points the points
 * @returns {string} the attribute value
 */
function pointsAttr(points) {
  var parts = [];
  for (var i = 0; i < points.length; i++) {
    parts.push(round(points[i][0]) + ',' + round(points[i][1]));
  }
  return parts.join(' ');
}

/**
 * The {@code d} attribute of a polyline, straight or smoothed.
 * <p>
 * The segments come from {@code scene.curveSegments}, the same function the PDF backend uses, so
 * both draw the identical curve; a rounded corner is an exactly elevated quadratic, hence
 * {@code C} here and {@code c} there.
 * </p>
 * @param {Array<Array<number>>} points the support points
 * @param {object|null} [curve] the curve descriptor
 * @returns {string} the path data
 */
function pathData(points, curve) {
  if (!points || points.length === 0) {
    return '';
  }
  var parts = ['M ' + round(points[0][0]) + ' ' + round(points[0][1])];
  var segments = sceneLib.curveSegments(points, curve);
  for (var i = 0; i < segments.length; i++) {
    var segment = segments[i];
    if (segment.type === 'C') {
      parts.push('C ' + round(segment.c1[0]) + ' ' + round(segment.c1[1]) + ' '
        + round(segment.c2[0]) + ' ' + round(segment.c2[1]) + ' '
        + round(segment.to[0]) + ' ' + round(segment.to[1]));
    } else {
      parts.push('L ' + round(segment.to[0]) + ' ' + round(segment.to[1]));
    }
  }
  return parts.join(' ');
}

/**
 * Escapes the five XML metacharacters.
 * @param {*} value the value
 * @returns {string} the escaped text
 */
function escapeXml(value) {
  return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&apos;');
}

/**
 * Rounds to a tenth of a point.
 * @param {number} value the value
 * @returns {number} the rounded value
 */
function round(value) {
  return Math.round(value * 10) / 10;
}

/**
 * Reads the node positions back out of an SVG this backend wrote - the return path of an edit.
 * <p>
 * A user moves nodes in an editor and saves; the coordinates that come back are the answer to
 * "what did you change", provided each node can be named. That is what the {@code data-node}
 * groups are for. The reader accumulates {@code translate} and {@code matrix} over the ancestors,
 * because an editor may bake a move into {@code x}/{@code y} (what Inkscape does for a rectangle)
 * or keep the coordinates and write a {@code transform} (what it does for a polygon) - both have
 * to give the same centre.
 * </p>
 * <p>
 * Only the shape items count, not the labels: a longer label must not move the centre. Files
 * without {@code data-node} (written before the groups existed) yield an empty map - that is a
 * property of the file, not an error.
 * </p>
 * @param {string} svgText the SVG document
 * @returns {object} {nodeId: [cx, cy]}
 */
function nodePositions(svgText) {
  var text = String(svgText);
  var tag = /<(\/?)([A-Za-z][\w:.-]*)((?:"[^"]*"|'[^']*'|[^>"'])*)(\/?)>/g;
  var stack = [];
  var boxes = {};
  var match;
  while ((match = tag.exec(text)) !== null) {
    if (match[1] === '/') {
      stack.pop();
      continue;
    }
    var name = match[2];
    var attrs = match[3] || '';
    // The attribute pattern is greedy and swallows the "/" of a self-closing tag, so the fourth
    // group is often empty; both spellings have to count, otherwise every <rect/> and <polygon/>
    // is pushed and the element stack runs away (a transform then leaks to all later siblings).
    var selfClosing = match[4] === '/' || /\/\s*$/.test(attrs);
    if (/\/\s*$/.test(attrs)) {
      attrs = attrs.replace(/\/\s*$/, '');
    }
    var parent = stack.length ? stack[stack.length - 1] : {tx: 0, ty: 0, node: null};
    var own = translationOf(attrs);
    var frame = {tx: parent.tx + own[0], ty: parent.ty + own[1], node: parent.node};
    var named = attributeOf(attrs, 'data-node');
    if (named) {
      frame.node = named;
    }
    if (frame.node) {
      var box = shapeBounds(name, attrs, frame.tx, frame.ty);
      if (box) {
        var current = boxes[frame.node];
        boxes[frame.node] = current ? {
          x1: Math.min(current.x1, box.x1), y1: Math.min(current.y1, box.y1),
          x2: Math.max(current.x2, box.x2), y2: Math.max(current.y2, box.y2)
        } : box;
      }
    }
    if (!selfClosing) {
      stack.push(frame);
    }
  }
  var positions = {};
  for (var id in boxes) {
    if (Object.prototype.hasOwnProperty.call(boxes, id)) {
      positions[id] = [round((boxes[id].x1 + boxes[id].x2) / 2),
        round((boxes[id].y1 + boxes[id].y2) / 2)];
    }
  }
  return positions;
}

/**
 * The translation of a {@code transform} attribute ({@code translate} and the e/f part of
 * {@code matrix}); rotation and scale are ignored, this reader is for moves.
 * @param {string} attrs the attribute text of the tag
 * @returns {Array<number>} [dx, dy]
 */
function translationOf(attrs) {
  var value = attributeOf(attrs, 'transform');
  if (!value) {
    return [0, 0];
  }
  var dx = 0;
  var dy = 0;
  var move = /translate\s*\(\s*(-?[\d.]+)(?:[\s,]+(-?[\d.]+))?\s*\)/g;
  var match;
  while ((match = move.exec(value)) !== null) {
    dx += parseFloat(match[1]);
    dy += match[2] === undefined ? 0 : parseFloat(match[2]);
  }
  var matrix = new RegExp('matrix\\s*\\(\\s*(-?[\\d.]+)[\\s,]+(-?[\\d.]+)[\\s,]+'
    + '(-?[\\d.]+)[\\s,]+(-?[\\d.]+)[\\s,]+(-?[\\d.]+)[\\s,]+(-?[\\d.]+)\\s*\\)', 'g');
  while ((match = matrix.exec(value)) !== null) {
    dx += parseFloat(match[5]);
    dy += parseFloat(match[6]);
  }
  return [dx, dy];
}

/**
 * The bounding box of one shape element, moved by the accumulated translation.
 * @param {string} name the element name
 * @param {string} attrs its attributes
 * @param {number} tx the accumulated x translation
 * @param {number} ty the accumulated y translation
 * @returns {object|null} {x1, y1, x2, y2} or null for an element that is not a shape
 */
function shapeBounds(name, attrs, tx, ty) {
  var num = function (attribute) {
    var raw = attributeOf(attrs, attribute);
    return raw === null ? 0 : parseFloat(raw);
  };
  if (name === 'rect') {
    var x = num('x') + tx;
    var y = num('y') + ty;
    return {x1: x, y1: y, x2: x + num('width'), y2: y + num('height')};
  }
  if (name === 'ellipse') {
    var cx = num('cx') + tx;
    var cy = num('cy') + ty;
    return {x1: cx - num('rx'), y1: cy - num('ry'), x2: cx + num('rx'), y2: cy + num('ry')};
  }
  if (name === 'polygon') {
    var raw = attributeOf(attrs, 'points');
    if (!raw) {
      return null;
    }
    var numbers = raw.match(/-?\d*\.?\d+/g) || [];
    if (numbers.length < 4) {
      return null;
    }
    var box = null;
    for (var i = 0; i + 1 < numbers.length; i += 2) {
      var px = parseFloat(numbers[i]) + tx;
      var py = parseFloat(numbers[i + 1]) + ty;
      box = box === null ? {x1: px, y1: py, x2: px, y2: py}
        : {x1: Math.min(box.x1, px), y1: Math.min(box.y1, py),
          x2: Math.max(box.x2, px), y2: Math.max(box.y2, py)};
    }
    return box;
  }
  return null;
}

/**
 * The value of one attribute in a tag's attribute text, or null.
 * @param {string} attrs the attribute text
 * @param {string} name the attribute name
 * @returns {string|null} the value
 */
function attributeOf(attrs, name) {
  var found = attrs.match(new RegExp('(?:^|\\s)' + name + '\\s*=\\s*"([^"]*)"'));
  return found ? found[1] : null;
}

module.exports = {
  render: render,
  nodePositions: nodePositions
};
