/**
 * diagram - parse Mermaid/DOT, lay it out yourself, draw it as SVG (PDF/PPTX later).
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
 *
 * Nothing here knows about a font or a page: text measurement is injected
 * ({@code measure({font, size, text}) -> width}), and the outputs are plain strings/JSON. The one
 * Java component this module needs is the test-only ELK oracle; there is no runtime dependency.
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
  throw new Error("diagram: unknown output format '" + format + "' (implemented: svg; the PDF"
    + ' backend is milestone M5, docs/js/diagram-plan.md §10)');
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
 * The help text - the manual a caller reads before the first line.
 * @returns {string} the help text
 */
function help() {
  return [
    'diagram - parse Mermaid/DOT, lay it out, draw it as SVG (PDF/PPTX later).',
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
    '  version(), limits(overrides), help()',
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
    '     Turn the edit into nudge deltas (undo the normalisation!), then confirm with report().',
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
    'The measure function is injected: layout never touches a font. Without one, widths are',
    'estimated and a note says so. The scene carries a "phases" block (layout, ranks, layers,',
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
  /** The IR module (for a caller who builds a model step by step). */
  model: modelLib
};
