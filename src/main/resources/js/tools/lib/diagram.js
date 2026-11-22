/**
 * diagram - parse Mermaid/DOT, lay it out yourself, draw it as SVG, PDF or PPTX.
 *
 * The facade of the diagram module: {@code parse()} turns text into a model (JSON),
 * {@code layout()} turns a model into a scene (JSON), {@code render()} turns a scene into a string.
 * Model and scene are the two serializable artefacts - they can be stored, diffed and patched, and
 * they are the seam at which a caller corrects a picture. See docs/js/diagram-plan.md.
 *
 *     var diagram = loadShipped('diagram');
 *     var model = diagram.parse('flowchart LR\n  A --> B');
 *     var scene = diagram.layout(model, {measure: function (r) { return r.text.length * 5; }});
 *     var svg = diagram.render(scene);              // scene → SVG string
 *     diagram.pptx(scene, {file: 'folien.pptx'});   // scene → shapes on a slide
 *
 * Nothing here knows about a font or a page: text measurement is injected
 * ({@code measure({font, size, text}) -> width}), and the outputs are plain strings/JSON. The one
 * Java component this module needs is the test-only ELK oracle; a PDF is written by {@code pdftext}
 * and a slide by the {@code pptx} bridge, both on top of this scene.
 *
 * Limits: 300 nodes, 600 edges, 50 groups (depth 8), 50 000 text bytes, a label of 200 characters,
 * 8 ordering sweeps and 5 000 ms per phase. Every overrun is a named error or a note - never a
 * silent fallback (docs/js/diagram-plan.md §8).
 */

'use strict';

var modelLib = require('./diagram/model.js');
var layoutLib = require('./diagram/layout.js');
var svgLib = require('./diagram/svg.js');
var sceneLib = require('./diagram/scene.js');
var dotLib = require('./diagram/parse-dot.js');
var mermaidLib = require('./diagram/parse-mermaid.js');
var stateLib = require('./diagram/parse-state.js');
var fitLib = require('./diagram/fit.js');
var metricLib = require('./diagram/metric.js');
var pptxLib = require('./diagram/pptx.js');

/** The module version. */
var VERSION = '0.1.0';

/**
 * Detects the frontend from the first meaningful line of the text.
 * @param {string} text the source
 * @returns {string} 'dot', 'mermaid' or 'state'
 */
function detectFormat(text) {
  var lines = String(text).replace(/\r\n?/g, '\n').split('\n');
  var i = 0;
  // Skip blank lines, comments and a YAML frontmatter block.
  if (i < lines.length && lines[i].trim() === '---') {
    i++;
    while (i < lines.length && lines[i].trim() !== '---') {
      i++;
    }
    i++;
  }
  for (; i < lines.length; i++) {
    var line = lines[i].trim();
    if (line === '' || line.indexOf('//') === 0 || line.indexOf('#') === 0
        || line.indexOf('%%') === 0) {
      continue;
    }
    // A Mermaid header is 'flowchart'/'graph' plus a direction; a DOT header is 'digraph'
    // (optionally 'strict'), or 'graph' followed by a body brace - never a direction token.
    if (/^stateDiagram(-v2)?\b/i.test(line)) {
      return 'state';
    }
    if (/^(flowchart|graph)\s+(TB|TD|BT|LR|RL)\b/i.test(line)) {
      return 'mermaid';
    }
    if (/^(strict\s+)?(di)?graph\b/i.test(line)) {
      return 'dot';
    }
    return 'mermaid';
  }
  throw new Error('diagram: the text has no diagram header (expected a Mermaid flowchart or a'
    + ' DOT digraph)');
}

/**
 * Parses diagram text into a normalized model.
 * @param {string} text the source (Mermaid or DOT)
 * @param {object} [options] {format: 'dot'|'mermaid'|'state'|'auto', limits}
 * @returns {object} the model (JSON)
 */
function parse(text, options) {
  var opts = options || {};
  var source = String(text);
  var limits = modelLib.limitsWith(opts.limits || null);
  if (modelLib.utf8Bytes(source) > limits.maxTextBytes) {
    throw new Error('diagram: the text is ' + modelLib.utf8Bytes(source) + ' bytes - the limit'
      + ' is maxTextBytes (' + limits.maxTextBytes + ')');
  }
  var format = opts.format && opts.format !== 'auto' ? opts.format : detectFormat(source);
  var raw;
  if (format === 'dot') {
    raw = dotLib.parse(source);
  } else if (format === 'mermaid') {
    raw = mermaidLib.parse(source);
  } else if (format === 'state') {
    raw = stateLib.parse(source);
  } else {
    throw new Error("diagram: unknown format '" + format + "' (allowed: dot, mermaid, state,"
      + ' auto)');
  }
  return modelLib.normalize(raw, limits);
}

/**
 * Lays a model out and returns the scene.
 * @param {object|string} model the model (or its JSON)
 * @param {object} [options] {measure, stopAfter, limits, pin, rank, order, port, bend, route,
 *   backChannels, jogs, curve, curveRadius, curveTension, layout, forceIterations, forceRuns,
 *   forceSeed, springLength}
 * @returns {object} the scene (JSON)
 */
function layout(model, options) {
  var scene = layoutLib.run(model, options || {});
  if (scene.phases && scene.phases.recipe) {
    scene.phases.recipe.version = VERSION;
  }
  return scene;
}

/**
 * Renders a scene.
 * <p>
 * {@code 'svg'} is the one string format this module writes. A PDF comes out of {@code pdftext}
 * ({@code doc.diagram(scene)}), a slide out of {@link pptxExport} - both walk the same scene, and
 * neither is a string.
 * </p>
 * @param {object|string} scene the scene (or its JSON)
 * @param {object} [options] {format: 'svg', background, title}
 * @returns {string} the rendered document
 */
function render(scene, options) {
  var opts = options || {};
  var resolved = typeof scene === 'string' ? JSON.parse(scene) : scene;
  var format = opts.format || 'svg';
  if (format === 'svg') {
    return svgLib.render(resolved, opts);
  }
  throw new Error("diagram: unknown output format '" + format + "' (implemented: svg; for a PDF"
    + ' use pdftext.diagram(scene), for a slide diagram.pptx(scene, {file}) -'
    + ' docs/js/diagram-plan.md §10)');
}

/**
 * Draws a scene onto a PowerPoint slide - a new file, a new slide or an existing slide.
 * <p>
 * The PPTX backend is the one that stays editable: every node box is an auto shape, every routed
 * edge a chain of line shapes, every label a text box, so a reader of the deck can still move a
 * box. The drawing is placed with the same arithmetic as a page ({@code fit()}), but against the
 * slide: it is scaled down until it fits, never enlarged, and the report names the size the
 * smallest label ends up at.
 * </p>
 * <pre>{@code
 * var scene = diagram.layout(diagram.parse('flowchart LR\n  A --> B'));
 * diagram.pptx(scene, {file: 'target/diagram.pptx'});                   // a new file
 * diagram.pptx(scene, {file: 'deck.pptx', mode: 'append'});             // a new slide in it
 * diagram.pptx(scene, {file: 'deck.pptx', mode: 'slide', slide: 2});    // onto slide 2
 * // {file, mode, slideNumber, scale, width, height, shapes, lines, labels, skipped,
 * //  approximations, smallestFont, legible, fits, warning, notes}
 * }</pre>
 * @param {object|string} scene the scene (or its JSON)
 * @param {object} options {file, mode: 'new'|'append'|'slide', slide, saveAs, margin, fit, scale,
 *        align, valign, widthOf, font}
 * @returns {object} the report
 * @throws {Error} without a {@code pptx} namespace in the sandbox, without a file, for an unknown
 *         mode or a slide that does not exist
 */
function pptxExport(scene, options) {
  return pptxLib.write(scene, options || {});
}

/**
 * Draws a scene onto a slide handle the caller already holds (no file is opened or written).
 * @param {string} slideHandle the slide handle (e.g. from {@code pptx.createBlankSlide(show)})
 * @param {object|string} scene the scene (or its JSON)
 * @param {object} [options] {page|pageSize, show, margin, fit, scale, align, valign, widthOf, font}
 * @returns {object} the report of {@link pptxExport} without the file fields
 */
function pptxDraw(slideHandle, scene, options) {
  return pptxLib.draw(slideHandle, scene, options || {});
}

/**
 * The drawing operations a scene becomes on a slide, without a presentation - the inspection call.
 * <p>
 * {@code {op: 'shape'|'line'|'text'}} with slide coordinates (already scaled and placed), so a
 * caller can check what would be drawn, count it, or hand it to a test - without a pptx bridge and
 * without writing a file.
 * </p>
 * @param {object|string} scene the scene (or its JSON)
 * @param {object} [options] {page: {width, height}, margin, fit, scale, align, valign, widthOf,
 *        font}
 * @returns {Array<object>} the operations in drawing order
 */
function pptxOps(scene, options) {
  return pptxLib.ops(scene, options || {});
}

/**
 * How a scene is placed on a slide: the factor, the offset and the smallest label.
 * @param {object|string} scene the scene (or its JSON)
 * @param {object} [options] {page: {width, height}, margin, fit, scale, align, valign}
 * @returns {object} {page, textArea, scale, width, height, offsetX, offsetY, fits, smallestFont,
 *   legible, warning}
 */
function pptxPlace(scene, options) {
  return pptxLib.place(scene, options || {});
}

/**
 * The node positions of a scene ({id: [cx, cy]}).
 * @param {object} scene the scene (or a position map)
 * @returns {object} {nodeId: [cx, cy]}
 */
function positions(scene) {
  return sceneLib.nodeCentres(typeof scene === 'string' ? JSON.parse(scene) : scene);
}

/**
 * What changed between two pictures, as a correction recipe.
 * <p>
 * The counterpart of drawing: a user moves nodes in an editor, the edit comes back as positions
 * ({@link positionsOfSvg}), and this turns it into the nudge map that reproduces it. The scene
 * normalisation is removed first - a naive coordinate difference reads like "everything moved".
 * </p>
 * @param {object|string} first the scene before (or a position map)
 * @param {object|string} second the scene after (or a position map)
 * @param {object} [options] {tolerance, shift, base}
 * @returns {object} {shift, tolerance, moved, nudge, absolute, unchanged, onlyInFirst,
 *   onlyInSecond}
 */
function diff(first, second, options) {
  var before = typeof first === 'string' ? JSON.parse(first) : first;
  var after = typeof second === 'string' ? JSON.parse(second) : second;
  return sceneLib.diff(before, after, options || {});
}

/**
 * The node positions an SVG carries (the return path of an editor round-trip).
 * @param {string} svgText the SVG document
 * @returns {object} {nodeId: [cx, cy]}
 */
function positionsOfSvg(svgText) {
  return svgLib.nodePositions(svgText);
}

/**
 * Parses, lays out and renders in one call - the short way for a demo or a test.
 * @param {string} text the source
 * @param {object} [options] parse and layout options, plus {format}
 * @returns {object} {model, scene, output}
 */
function draw(text, options) {
  var opts = options || {};
  var model = parse(text, opts);
  var scene = layout(model, opts);
  var output = render(scene, {format: opts.format || 'svg', title: opts.title,
    background: opts.background});
  return {model: model, scene: scene, output: output};
}

/**
 * The version of the module.
 * @returns {string} the version
 */
function version() {
  return VERSION;
}

/**
 * The effective limits.
 * @param {object} [overrides] limits to change
 * @returns {object} the limits
 */
function limits(overrides) {
  return modelLib.limitsWith(overrides || null);
}

/**
 * How a scene fits onto a page - a calculation, not a drawing.
 * <p>
 * The formula is one `min`, but three things around it are easy to get wrong by hand: the scene is
 * normalised (its origin is the margin, not 0), the text area of a page is the page minus twice the
 * margin minus the space of a header and a footer, and scaling never enlarges. This call answers
 * all three, plus the number that matters for a diagram: the size the smallest label ends up at.
 * </p>
 * <pre>{@code
 * diagram.fit(scene, {page: 'A4', margin: 15, orientation: 'auto'})
 * // {scale: 0.452, width: 565, height: 542, orientation: 'portrait',
 * //  smallestFont: 4.52, legible: false, fits: true,
 * //  warning: 'the drawing is … and is scaled to 45.2 % …; the smallest text would be 4.52 pt …'}
 * }</pre>
 * @param {object|string} scene the scene (or its JSON)
 * @param {object} [options] {page, margin, orientation, fit, scale, headerSpace, footerSpace}
 * @returns {object} {page, pageSize, orientation, textArea, drawing, scale, width, height,
 *   smallestFont, legible, fits, warning}
 */
function fit(scene, options) {
  var resolved = typeof scene === 'string' ? JSON.parse(scene) : scene;
  return fitLib.fit(resolved, options || {});
}

/**
 * The built-in advance widths of a standard font, usable as the {@code measure} of a layout.
 * <p>
 * A caller who only wants a picture should not have to import a typesetter to measure a label:
 * the widths of the base-14 fonts are part of the specification, so they ship with this module
 * (ASCII exactly, the font's default width otherwise). {@code layout()} uses them on its own; this
 * call is for a caller who wants the number, or who lays out with another font.
 * </p>
 * <pre>{@code
 * var m = diagram.metric('Helvetica', 10);
 * m.width('Knoten');                                  // 35.58
 * diagram.layout(model, {measure: m.measure});         // same numbers as pdftext.measure()
 * }</pre>
 * @param {string} [font] the font name (default 'Helvetica'; Arial and Times New Roman are
 *        aliases, Courier is monospaced)
 * @param {number} [size] the size in points (default 10)
 * @returns {object} {font, size, source, width(text[, size]), measure(request)}
 */
function metric(font, size) {
  return metricLib.metric(font, size);
}

/**
 * Rotates a scene by a right angle (the picture, not the page).
 * <p>
 * The answer to "this drawing is wide and the page is portrait" when a landscape page is not an
 * option (the drawing shares the page with text, or the reader turns the sheet). Right angles keep
 * every box axis-parallel, so nothing is re-laid out: the drawing is turned and re-normalised, and
 * the labels turn with it (both backends write the rotation). {@code fit()} measures the result as
 * it is - a wide drawing becomes a tall one, so the scale changes.
 * </p>
 * <pre>{@code
 * var scene = diagram.layout(model);                      // e.g. 720 x 240 pt
 * var tall  = diagram.rotate(scene, 90);                  // 240 x 720 pt, labels turned
 * var f     = diagram.fit(tall, {page: 'A4'});            // measured on the rotated scene
 * }</pre>
 * @param {object|string} scene the scene (or its JSON)
 * @param {number} degrees 0, 90, 180 or 270 (clockwise on screen); anything else is an error,
 *        because a box cannot be a tilted rectangle in this scene format
 * @returns {object} the rotated scene (a copy - the input is not changed)
 */
function rotate(scene, degrees) {
  var copy = JSON.parse(typeof scene === 'string' ? scene : JSON.stringify(scene));
  return sceneLib.rotate(copy, degrees);
}

/**
 * Turns a node/link contract into Mermaid or DOT source - the front door for a graph that is
 * already data (a {@code dynsgraph} contract, a JSON export, a query result).
 * <p>
 * The layout module reads <em>text</em>; a caller usually has objects. The mapping is mechanical
 * and deliberately tolerant about the field names, because a contract has its own names for the
 * same two things:
 * </p>
 * <pre>{@code
 * var src = diagram.fromContract({nodes: [{id: 'MODA', label: 'Modul A'}],
 *                                 links: [{from: 'MODA', to: 'MODB', label: 'liest'}]});
 * // 'flowchart LR\n  MODA["Modul A"]\n  MODB["Modul B"]\n  MODA -->|liest| MODB\n'
 * var scene = diagram.layout(diagram.parse(src));
 * }</pre>
 * <p>
 * node id: {@code id|name|key|nodeId}, node label: {@code label|title|text|name};
 * link ends: {@code from|source|src|start} and {@code to|target|tgt|end}, link label:
 * {@code label|text|title|name}. A node that no link mentions is emitted too (an isolated box is
 * information). Ids are sanitized to {@code [A-Za-z0-9_]} and kept unique, the label is quoted, so
 * the result parses back with the same names.
 * </p>
 * @param {object} graph {nodes: [...], links: [...]} (links may be omitted)
 * @param {object} [options] {format: 'mermaid' (default)|'dot', direction: 'TB' (default for dot)
 *        'LR' (default for mermaid), id: 'G' (DOT graph name)}
 * @returns {string} the source text
 * @throws {Error} for a missing nodes array, a node without an id or more nodes/links than the
 *         module's limits allow
 */
function fromContract(graph, options) {
  var opts = options || {};
  if (graph === null || typeof graph !== 'object') {
    throw new Error('diagram.fromContract: argument graph must be an object {nodes: [...],'
      + ' links: [...]}, but was: ' + String(graph));
  }
  var nodes = graph.nodes || graph.vertices || graph.knoten;
  if (!Array.isArray(nodes)) {
    throw new Error('diagram.fromContract: argument graph.nodes must be an array of '
      + '{id, label} objects');
  }
  var links = graph.links || graph.edges || graph.kanten || [];
  if (!Array.isArray(links)) {
    throw new Error('diagram.fromContract: argument graph.links must be an array of '
      + '{from, to, label} objects');
  }
  var caps = limits();
  if (nodes.length > caps.maxNodes) {
    throw new Error('diagram.fromContract: ' + nodes.length + ' nodes exceed the limit of '
      + caps.maxNodes + ' (diagram.limits())');
  }
  if (links.length > caps.maxEdges) {
    throw new Error('diagram.fromContract: ' + links.length + ' links exceed the limit of '
      + caps.maxEdges + ' (diagram.limits())');
  }
  var format = opts.format || 'mermaid';
  if (format !== 'mermaid' && format !== 'dot') {
    throw new Error("diagram.fromContract: option 'format' must be 'mermaid' or 'dot', but was: '"
      + format + "'");
  }
  var ids = {};
  var names = {};
  var order = [];
  function register(raw, label) {
    var key = String(raw);
    if (ids[key] !== undefined) {
      return ids[key];
    }
    var safe = sanitizeId(key);
    var unique = safe;
    for (var n = 2; names[unique] !== undefined; n++) {
      unique = safe + '_' + n;
    }
    ids[key] = unique;
    names[unique] = true;
    order.push({name: unique, label: label === undefined || label === null ? '' : String(label)});
    return unique;
  }
  for (var i = 0; i < nodes.length; i++) {
    var node = nodes[i];
    if (node === null || typeof node !== 'object') {
      throw new Error('diagram.fromContract: graph.nodes[' + i + '] must be an object with an id');
    }
    var raw = node.id !== undefined ? node.id : (node.name !== undefined ? node.name
      : (node.key !== undefined ? node.key : node.nodeId));
    if (raw === undefined || raw === null || String(raw) === '') {
      throw new Error('diagram.fromContract: graph.nodes[' + i + '] has no id (looked for'
        + ' id, name, key, nodeId)');
    }
    register(raw, firstOf(node, ['label', 'title', 'text', 'name']));
  }
  var edges = [];
  for (var e = 0; e < links.length; e++) {
    var link = links[e];
    if (link === null || typeof link !== 'object') {
      throw new Error('diagram.fromContract: graph.links[' + e + '] must be an object'
        + ' {from, to}');
    }
    var from = firstOf(link, ['from', 'source', 'src', 'start']);
    var to = firstOf(link, ['to', 'target', 'tgt', 'end']);
    if (from === undefined || to === undefined) {
      throw new Error('diagram.fromContract: graph.links[' + e + '] needs both ends (from/to,'
        + ' source/target)');
    }
    edges.push({from: register(from), to: register(to),
      label: firstOf(link, ['label', 'text', 'title', 'name'])});
  }
  var lines = [];
  if (format === 'dot') {
    lines.push('digraph ' + (opts.id ? sanitizeId(opts.id) : 'G') + ' {');
    lines.push('  rankdir=' + (opts.direction || 'TB') + ';');
  } else {
    lines.push('flowchart ' + (opts.direction || 'LR'));
  }
  for (var n2 = 0; n2 < order.length; n2++) {
    var label = order[n2].label;
    if (format === 'dot') {
      lines.push('  ' + order[n2].name + (label ? ' [label="' + quoteDot(label) + '"]' : '') + ';');
    } else {
      lines.push('  ' + order[n2].name + (label ? '["' + quoteMermaid(label) + '"]' : ''));
    }
  }
  for (var m = 0; m < edges.length; m++) {
    var l = edges[m].label;
    if (format === 'dot') {
      lines.push('  ' + edges[m].from + ' -> ' + edges[m].to
        + (l ? ' [label="' + quoteDot(l) + '"]' : '') + ';');
    } else {
      lines.push('  ' + edges[m].from + ' -->' + (l ? '|' + quoteMermaid(l) + '|' : '')
        + ' ' + edges[m].to);
    }
  }
  if (format === 'dot') {
    lines.push('}');
  }
  return lines.join('\n') + '\n';
}

/** The first defined value of a list of field names (a contract names the same thing twice). */
function firstOf(object, names) {
  for (var i = 0; i < names.length; i++) {
    if (object[names[i]] !== undefined && object[names[i]] !== null) {
      return object[names[i]];
    }
  }
  return undefined;
}

/** An identifier the Mermaid/DOT parsers accept (letters, digits, underscore, a leading letter). */
function sanitizeId(raw) {
  var text = String(raw).replace(/[^A-Za-z0-9_]/g, '_');
  if (text === '' || /^[0-9]/.test(text)) {
    text = 'N' + text;
  }
  return text;
}

/** A label for a Mermaid {@code ["..."]} - quotes and line breaks would end the node. */
function quoteMermaid(text) {
  return String(text).replace(/"/g, "'").replace(/[\r\n]+/g, ' ');
}

/** A label for a DOT {@code [label="..."]}. */
function quoteDot(text) {
  return String(text).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/[\r\n]+/g, ' ');
}

/**
 * The help text - the manual a caller reads before the first line.
 * @returns {string} the help text
 */
function help() {
  return [
    'diagram - parse Mermaid/DOT, lay it out, draw it as SVG, PDF or PPTX.',
    '',
    '  var diagram = loadShipped(\'diagram\');',
    '  var model = diagram.parse(\'flowchart LR\\n  A --> B\');',
    '  var scene = diagram.layout(model, {measure: function (r) { return r.text.length * 5; }});',
    '  var svg   = diagram.render(scene);              // scene -> SVG string',
    '',
    'Calls',
    '  parse(text, {format:\'auto\'|\'dot\'|\'mermaid\'|\'state\', limits})  -> model (JSON)',
    '  layout(model, {measure, stopAfter, limits, rank, order, port, bend, route, backChannels,',
    '                 jogs, curve, curveRadius, curveTension, pin, nudge, layout, forceSeed,',
    '                 forceRuns, forceIterations, springLength, relax, relaxLevels, recipe})',
    '                 -> scene (JSON);  {recipe: {...}} is passed through to phases.recipe.origin',
    '  render(scene, {format:\'svg\', background, title})       -> string',
    '  draw(text, options)                                     -> {model, scene, output}',
    '  positions(scene)                                        -> {id: [cx, cy]}',
    '  positionsOfSvg(svg)                                     -> {id: [cx, cy]} (reads the',
    '                 data-node groups, so an edited SVG can be read back)',
    '  diff(before, after, {tolerance, shift, base, free})      -> {shift, moved, nudge, targets,',
    '                 absolute, exact, unchanged, onlyInFirst, onlyInSecond}',
    '  fit(scene, {page, margin, orientation, fit, scale, headerSpace, footerSpace})',
    '                 -> {scale, width, height, smallestFont, legible, fits, warning, ...}',
    "  metric(font, size)                                      -> {width(text), measure(request)}",
    '  rotate(scene, 90|180|270)                               -> scene (turned + re-normalised)',
    '  fromContract({nodes, links}, {format})                  -> Mermaid/DOT source text',
    '  pptx(scene, {file, mode, slide, saveAs, margin, ...})   -> report (draws on a slide)',
    '  pptxDraw(slideHandle, scene, {page|show, ...})          -> report (slide the caller holds)',
    '  pptxOps(scene, {page: {width, height}, ...})            -> [{op:"shape"|"line"|"text"}]',
    '  pptxPlace(scene, {page, margin, fit, scale, align})     -> {scale, offsetX, offsetY, ...}',
    '  version(), limits(overrides), help()',
    '',
    'From a node/link contract (dynsgraph, a JSON export, a query result) to a picture - the',
    'front door for a graph that is already data, so that nobody writes Mermaid by hand:',
    '  var src = diagram.fromContract({',
    '    nodes: [{id: "MODA", label: "Modul A"}, {id: "MODB", label: "Modul B"}],',
    '    links: [{from: "MODA", to: "MODB", label: "liest"}]',
    '  });                                     // flowchart LR ... MODA -->|liest| MODB',
    '  diagram.draw(src);                      // or parse + layout + render separately',
    '  field names are tolerant: node id/name/key, label/title/text, link from/source/src/start,',
    '  to/target/tgt/end, label/text; a node no link mentions is emitted too. Ids are sanitized',
    '  to [A-Za-z0-9_] and kept unique, labels are quoted, {format: "dot"} gives DOT instead.',
    '',
    'Turning the picture (a wide drawing on a portrait page):',
    '  var tall = diagram.rotate(scene, 90);   // right angles only - a box cannot be tilted in',
    '                                          // this scene format, and re-layout would move nodes',
    '  diagram.fit(tall, {page: "A4"});        // measured on the rotated scene, labels turned',
    '  Both backends write the rotation: SVG as transform="rotate(deg x y)", PDF as',
    '  text.put rotate. 180 turns labels upside down - use it to correct a mirrored export, not',
    '  to save space. If the page allows it, orientation:"landscape" in fit() is the better answer',
    '  (the text stays upright).',
    '',
    'Mermaid subset: flowchart/graph TB/TD/BT/LR/RL; shapes A[ ] A( ) A([ ]) A[[ ]] A[( )]',
    'A{ } A(( )) A{{ }}; edges --> -.-> ==> --- ~~~ <--> with |label|; subgraph ... end with',
    'direction. Other diagram types are refused with a clear message, not a puzzle.',
    '',
    'State subset: stateDiagram / stateDiagram-v2; [*] as start/end (filled circle / ring),',
    'state X, state "Label" as X, state X { ... } (a group), A --> B : text, direction, %% route.',
    '',
    'Directives as %% comments (invisible to Mermaid, visible here):',
    '  %% diagram: direction=TB      %% pin: MODA 120,80',
    '  %% rank: MODA,MODB=0         %% port: MODA->MODC e:w',
    '  %% weight: MODA->MODC 5      %% route: tree   (orthogonal | tree | direct)',
    '  %% curve: round              (off | round | spline)',
    '  %% layout: force             (layered | force)',
    '',
    'layout (how the nodes get their coordinates, docs/js/diagram-plan.md §13.7):',
    "  'layered' (default)     ranks over the longest path, order by the barycenter, one channel",
    '                          per edge - the hierarchical picture a flow chart wants',
    "  'force'                 a force-directed placement (a spring embedder): repulsion between",
    '                          all nodes, one spring per edge (the weight is the spring strength),',
    '                          a direction term that keeps the flow pointing the way the diagram',
    '                          says, and a separation pass so that no two boxes overlap. The',
    '                          edges are drawn as straight border-to-border lines (a force',
    '                          layout has no channels). Multi-start: several runs, ranked by the',
    '                          lines the drawing shows - crossings + 3 x lines through a node box,',
    '                          then the shorter edge sum. Deterministic - the random numbers come',
    '                          from a seeded generator, never from Math.random.',
    '  forceSeed: <number>     the seed of that generator (default 4711); another seed is another',
    '                          arrangement of the same graph',
    '  forceRuns: <n>          how many runs take part in the multi-start (default 3, limit 8)',
    '  forceIterations: <n>    simulation steps per run (default 200, limit 400; a big graph gets',
    '                          fewer, because the work per run is bounded)',
    '  springLength: <pt>      the rest length of an edge spring (default 100; a heavy edge is',
    '                          shorter: springLength / sqrt(weight))',
    '  relax: <n>             after a nudge, run <n> steps of the same physics again (0 = off,',
    '                          default) - a local relaxation: the nudged nodes stay (they are the',
    '                          anchor), the nodes up to relaxLevels hops follow, everything else',
    '                          keeps its coordinates and only acts as an obstacle. Use the',
    '                          counters to see whether it helped (30-40 steps is a good start)',
    '  relaxLevels: <n>       how many hops from a nudged node take part (default 2, limit 8)',
    '  A pin is honoured (the pinned node never moves, the neighbours give way); rank, order,',
    '  route, backChannels and jogs lose their meaning there and are named in a note.',
    '',
    'route (how an edge is drawn):',
    "  'orthogonal' (default)  horizontal/vertical, each edge in its own channel",
    "  'tree'                  orthogonal, but siblings share one branch line (a tree look)",
    "  'direct'                one straight (diagonal) line between the node borders",
    "  'smooth'                one line per edge, with a short stub along the side's normal at both",
    '                          ends (18 pt) - with curve: spline that is the slightly curved line a',
    '                          flowchart renderer draws; side/back edges keep their channel',
    '',
    'curve (how smooth the line of an edge is, docs/js/diagram-plan.md §13.8):',
    "  'off' (default)         straight segments",
    "  'round'                 the route stays orthogonal, its corners are filleted (smoothstep)",
    '  curveRadius: <pt>       the fillet radius (default 4; cut back to half the shorter neighbour)',
    "  'spline'                a centripetal Catmull-Rom curve through the route points",
    '  curveTension: <number>  the tangent scale of the spline, (0, 2], default 0.5 (1 = classic)',
    '                          Catmull-Rom, 2 = loopy)',
    'The control points are derived by scene.curveSegments() and used by the SVG and the PDF',
    'backend alike, so both draw the same curve. The geometry checks in "phases" (crossings,',
    'overlaps, box hits) keep measuring the support points, i.e. the routed polyline, not the',
    'smoothed line; a spline stays inside the corridor of its support points.',
    '',
    'Correcting the picture (docs/js/diagram-plan.md §7.2):',
    '  pin:   {NODE: {x, y}}       absolute, in layout space',
    '         {NODE: {dx, dy}}     relative: shift from where the free placement puts it',
    '  nudge: {NODE: {dx, dy}}     local: move only this node after placement (no re-packing)',
    '                              -> use nudge for "move it a bit and look again", pin to fix it',
    '  rank / order / port / bend  see the plan; every unmet wish is a note, never silence',
    '',
    'The correction loop: lay out once, read the diagnosis, then pass nudge for the nodes it names',
    'and lay out again. The diagnosis is:',
    '  phases.crossingPairs  which edges cross where ({a, b, at}, the first 12)',
    '  phases.boxHitPairs    which edge runs through which box, where, and the shift that would',
    '                        clear it ({edge, node, at, escape, escapes} - one candidate per',
    '                        direction, the shortest first; applying the rounded value works)',
    '  phases.kinks          the sharpest chain bends: every node with exactly two neighbours and',
    '                        the angle between its two edges (0 = straight), the first 12;',
    '                        phases.kinkCount says how many such nodes exist',
    'A nudge does not re-simulate, so every other node keeps its place and the change is exactly the',
    'one asked for; the counters of the new scene say whether it helped. pin is the harder version',
    '(the neighbours rearrange, a force layout simulates again). A bare nudge can leave a dent - the',
    'moved node no longer matches its neighbours; relax: <n> then runs the physics once more for',
    'the neighbourhood only (relaxLevels), which smooths the picture without rearranging it.',
    "  phases.force.relax names what it did: {iterations, levels, movable, moved, unresolved}.",
    '',
    'Reading what a user says (this section is for the assistant, not for the layout):',
    '  The module never sees a picture; a user describes one. The same sentence then has two',
    '  readings, and picking silently is how a correction loop wastes a session.',
    '',
    '  1. Spatial or structural? "the chain from X to Y" can be the graph path X -> ... -> Y or the',
    '     block of nodes that sits between X and Y on paper; "between A and B there is plenty of',
    '     room" is about white space, not about nodes. Resolve the sentence to an explicit node',
    '     list and say the list back BEFORE laying out ("I am moving these 11 nodes: ...") - that',
    '     costs one sentence and prevents the wrong 20 tool calls. If both readings fit, ask.',
    '  2. A negative result is only as true as the set behind it. "The chain cannot move down"',
    '     means nothing until it reads "these 11 nodes cannot move down as a block". Print the set',
    '     you tried and the range you tried; the user then sees a mismatch immediately.',
    '  3. Say which frame you measure in. nudge/pin deltas are relative to the FREE placement',
    '     (layout with no nudge at all), never to the previous picture - a user who says "move it',
    '     20 pt" means 20 pt from what they see. Scene coordinates are normalised: the whole',
    '     drawing is translated so it starts at the margin, so a coordinate read out of an SVG is',
    '     not a nudge delta (the translation has to be subtracted first, it is not 0).',
    '  4. Name the threshold a number uses. A box hit is measured with a 0.8 pt inset of the box;',
    '     a doubled line means two runs closer than 1.2 pt that are parallel within 0.05 rad. Two',
    '     tools reporting "0 hits" against different definitions are not contradicting each other.',
    '  5. When the user draws instead of describing, their edit is the better statement of intent.',
    '     Every item of a node carries nodeGroup, every path carries edgeFrom/edgeTo, and the SVG',
    '     wraps them in <g id="node-..." data-node="..."> / <g id="edge-...-to-..." data-edge="...">,',
    '     so a moved node can be read back by name instead of by matching coordinates or labels.',
    '     Turn the edit into nudge deltas (undo the normalisation!), then confirm it by laying the',
    '     model out again and reading the counters (or diff() against the new positions).',
    '  6. phases.recipe answers "which picture is this": strategy, seed, relaxation, every nudge',
    '     and the module version, with the effective values. The SVG repeats it in the <title> and',
    '     in a comment. Pass {recipe: {fixture, fixtureHash, moduleHash}} to layout() and the',
    '     provenance travels with the scene.',
    '',
    'The editor round-trip (a drawn correction read back as a recipe):',
    '  var svg = diagram.render(sceneA, {title: "before"});   // the user edits and saves it',
    '  var after = diagram.positionsOfSvg(editedSvg);          // {id: [cx, cy]}, transforms included',
    '  var d = diagram.diff(diagram.positions(sceneA), after, {base: recipeA, free: freePositions});',
    '  diagram.layout(model, {nudge: d.absolute, relax: 40, relaxLevels: 1});   // reproduces the edit',
    '  Two traps, both measured in the ILC series:',
    '  * The scenes are normalised, so the raw difference is "what moved" plus a translation (11.9 pt',
    '    in x and 57.7 pt in y there). diff removes it: shift is the translation shared by most',
    '    nodes, and with {base, free} the offset between the layout frame and the scene frame is',
    '    computed exactly (exact: true) - the frameOffset field says what it was.',
    '  * diff reports the picture, not the intention. With relax on, moving one block also moves its',
    '    neighbours, so a four-node edit can show up as twelve differences (and does: the four are',
    '    the round -25.7/+65.7, the rest are 1-3 pt of relaxation). Read moved[] with tolerance and',
    '    the numbers in mind, not as "the user touched twelve nodes".',
    '  nudge is the increment relative to the first picture; absolute is the recipe to hand to',
    '  layout(); targets are the absolute positions in the first picture\'s frame.',
    '',
    'backChannels (how back/same-rank edges pick their channel on the right):',
    "  'edge' (default)        every edge gets its own channel",
    "  'target'               confluent: all edges into one node join one trunk and arrive as one",
    "                          line with one arrowhead (listed in phases.backBundles, never a",
    "                          conflict, and the shared stretch is drawn once)",
    '',
    'midArrows: off | long   add one arrowhead in the middle of a long edge (default off)',
    'midArrowAfter: <pt>     from which drawn length that happens (default 200)',
    '',
    'jogs (how long edges (those crossing several ranks) are drawn):',
    "  'remove' (default)      all dummies of one long edge get one x - no sideways step per rank",
    "  'keep'                  the placement decides where the edge steps sideways",
    '',
    'groups (how a subgraph is honoured in the placement):',
    "  'flow' (default)        place by the barycenter sweeps like an ungrouped graph and draw the",
    '                          band around the members afterwards - short edges; a band may touch',
    '                          another (a note says so)',
    "  'strips'                every top-level group gets its own vertical strip - the bands never",
    '                          overlap, but a chain that runs through several groups is spread over',
    '                          the strips and its edges become long horizontal runs',
    '',
    'Measuring labels: layout never touches a font. Without a measure function it uses the',
    'built-in widths of the standard fonts (diagram/metric.js) - exact numbers for Helvetica, the',
    'font this layout measures with, so nothing is guessed. Pass one for another font:',
    '  measure(request)                       request {font, size, text} -> width in pt',
    "  var m = diagram.metric('Helvetica', 10);  // built-in widths, nothing to import",
    '  m.width("Knoten")                         -> 35.58 pt',
    "  diagram.metric('Courier')              // monospaced; Arial/Times New Roman are aliases",
    '  diagram.layout(model, {measure: m.measure});',
    "  An unknown font reports source:'estimate' and falls back to length x size x 0.55, and the",
    '  scene says so in notes. A server with pdftext has the same numbers:',
    "  pdftext.measure('Helvetica', 10, ['Knoten']).",
    '',
    'Onto a page: fit(scene, {page, margin, orientation, fit, scale}) - the calculation, not the',
    '  drawing (nothing in the scene changes):',
    "  page:        'A4' (default), 'A3', 'A5', 'LETTER', 'LEGAL', 'TABLOID' or {width, height} in pt",
    "  orientation: 'portrait' is how a preset is defined; 'landscape' swaps width and height",
    "               (also on {width, height}), 'auto' picks landscape for a wide drawing",
    '  margin:      one number or {top, right, bottom, left}, default 57 pt (the pdftext default)',
    "  fit:         'page' (default, shrink into the text area), 'width' (width only), 'none';",
    '               scale: <f> is a fixed factor and wins over fit. Scaling only ever shrinks.',
    '  The text area is the page minus twice the margin minus headerSpace/footerSpace (pdftext',
    '  reserves 2.2 x size per header/footer line and passes that in).',
    '  The answer: scale, width, height, page, pageSize, orientation, textArea, drawing, fits -',
    '  and smallestFont, the size the smallest label ends up at, with legible:false below about',
    '  6 pt and one warning sentence. Read that number: a drawing that only fits at 45 % does not',
    '  have a page problem, it has a legibility problem.',
    '  render() knows nothing about a page, so an SVG alone never says "too small" - call fit()',
    '  before drawing and read smallestFont/legible. The SVG is vector and scales losslessly, so',
    '  a small scale is never an artefact of the format.',
    '  Drawing it: pdfedit ops come from diagram/pdf.js, the normal way is pdftext -',
    "    doc.diagram(scene, {fit:'page'});            // factor in result.diagrams[0].appliedScale",
    "    var result = doc.save('out.pdf');",
    '  and looking at it (image.preview/describe take a path or the bytes, never a handle):',
    "    var png = pdfedit.render(result.file, {page:1, dpi:150}).data;",
    "    fs.writeBytes('seite1.png', png);",
    "    console.log(image.preview('seite1.png', {width:80}));   // coarse text view, no tokens",
    "    image.describe('seite1.png');                            // the vision model",
    '',
    'Onto a slide (PowerPoint .pptx) - the one output that stays editable:',
    '  Every node box becomes an auto shape, every routed edge a chain of line shapes, every label',
    '  a transparent text shape (centred paragraph, no fill, no outline, no inner margin, one line),',
    '  every arrowhead a filled triangle - so a reader of the deck can still move a box.',
    "  It needs the pptx namespace of the sandbox (pptx.help()); ops/place work without it.",
    '    diagram.pptx(scene, {file: "target/diagram.pptx"})              // a new file',
    '    diagram.pptx(scene, {file: "deck.pptx", mode: "append"})        // one more slide',
    '    diagram.pptx(scene, {file: "deck.pptx", mode: "slide", slide: 2})   // onto slide 2',
    '    diagram.pptx(scene, {file: "vorlage.pptx", mode: "slide", slide: 1,',
    '                         saveAs: "target/bericht.pptx"})            // read A, write B',
    '  mode is "new" (default: a new presentation, overwriting file), "append" (a blank slide at the',
    '  end) or "slide" (draw ONTO an existing slide, its shapes stay). The mode is never guessed: a',
    '  file that cannot be opened must not silently become a new one.',
    '  Placement is the arithmetic of fit(), against the slide (a default POI slide is 720 x 540 pt,',
    '  i.e. 4:3): margin 36 (half an inch), fit:"page"|"width"|"none", scale, align:"center"|',
    '  "left"|"right", valign:"middle"|"top"|"bottom". A drawing that does not fit is reported',
    '  (fits:false), not clipped.',
    '  The report: {file, mode, slideNumber, scale, width, height, offsetX, offsetY, shapes, lines,',
    '  labels, skipped, items, approximations, smallestFont, legible, fits, warning, notes}. Read',
    '  smallestFont - a slide is smaller than a page, so a diagram is scaled down more often.',
    '  What a slide cannot express, and what happens instead (counted in "approximations", so it is',
    '  never silent): a curve is sampled into segments (scene.curveSegments, the same control',
    '  points both other backends draw), a rounded box keeps PowerPoint\'s corner radius, an',
    '  arrowhead is the preset triangle turned into its direction, a dash pattern becomes the',
    '  closest preset (dot | dash). Nothing else is approximated - the geometry stays exact.',
    '  Before drawing, pptxOps(scene, {page: {width: 720, height: 540}}) answers what would be',
    '  drawn ({op:"shape"|"line"|"text"} with slide coordinates, already scaled and placed), and',
    '  pptxPlace(scene, {...}) answers the scale and the offset. pptxDraw(slideHandle, scene, {show})',
    '  draws onto a slide the caller already holds and writes no file.',
    '',
    'The scene carries a "phases" block (layout, ranks, layers,',
    'crossings, drawnCrossings, crossingPairs, drawnOverlaps, nearOverlaps, backwardEdges,',
    'channels, backBundles, pinned, route, curve, force, boxHits, boxHitPairs, kinks, kinkCount,',
    'recipe)',
    'so a wrong picture can be diagnosed instead of guessed at. "crossings" is the layered-ordering',
    'metric (pairs of segments between two ranks that swap order; null in a force layout, which has',
    'no ranks to order);',
    '"drawnCrossings" counts where the routed lines actually cross and "crossingPairs" names the',
    'edges ({a, b, at}, the first 12 of them) - that is the list a correction loop works from: it',
    'says which node to nudge, not just how many crossings there are. "drawnOverlaps" counts where',
    'two edges draw exactly one line on top of each other and "nearOverlaps" where two runs are',
    'closer than the stroke width (they look like one doubled line) - the last two are also named',
    'in "notes", with both edge names and the place, so a correction can react to them. In a force',
    'layout "force" holds the multi-start (runs, their crossings and box hits, which run won) and',
    '"boxHits" counts the straight lines that cross a third box.',
    '',
    'Limits: 300 nodes, 600 edges, 50 groups (depth 8), 50 000 text bytes, 200 label characters,',
    '8 ordering sweeps, 400 force iterations, 8 force runs, 200 relaxation steps, 8 relaxation',
    'levels, 5 000 ms per phase. Every overrun is a named error or a note.'
  ].join('\n');
}

module.exports = {
  VERSION: VERSION,
  parse: parse,
  layout: layout,
  render: render,
  draw: draw,
  version: version,
  limits: limits,
  help: help,
  positions: positions,
  positionsOfSvg: positionsOfSvg,
  diff: diff,
  fit: fit,
  metric: metric,
  rotate: rotate,
  fromContract: fromContract,
  /** A scene onto a PowerPoint slide (a new file, a new slide or an existing slide). */
  pptx: pptxExport,
  /** A scene onto a slide handle the caller holds - no file is opened or written. */
  pptxDraw: pptxDraw,
  /** The drawing operations a scene becomes on a slide (no presentation needed). */
  pptxOps: pptxOps,
  /** How a scene is placed on a slide (scale, offset, smallest label). */
  pptxPlace: pptxPlace,
  /** The IR module (for a caller who builds a model step by step). */
  model: modelLib
};
