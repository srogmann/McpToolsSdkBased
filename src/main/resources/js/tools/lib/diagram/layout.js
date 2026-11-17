/**
 * diagram/layout - the five layout phases p0..p5, on the IR, producing a scene.
 *
 * The phases are the ones ELK uses (docs/js/diagram-plan.md §7.1), because that split is proven:
 *
 *   p0measure  node sizes from the labels, via the injected measure function
 *   p1cycles   back edges by DFS (a greedy order over the edge weights)
 *   p2ranks    longest path; constraint:false edges excluded; fixed ranks honoured
 *   p3order    barycenter sweeps + transposition, crossing count
 *   p4place    coordinates per layer (a simplified Brandes-Koepf priority method) - or, with
 *              {@code layout: 'force'}, a force-directed simulation (diagram/force.js)
 *   p5route    orthogonal routing with a dummy node per rank, ports and lane assignment - or
 *              straight border-to-border lines in a force layout
 *
 * Two properties make the result worth anything: it is <b>deterministic</b> (ties are broken by
 * input order, never at random) and every phase is visible in {@code scene.phases}, so a wrong
 * picture can be diagnosed instead of guessed at.
 *
 *     var scene = require('./diagram/layout.js').run(model, {measure: fn, stopAfter: 'p3order'});
 *
 * The measure function is injected ({@code function({font, size, text}) -> width}). Without one the
 * built-in widths of the standard fonts are used ({@code diagram/metric.js}) - for the font this
 * layout measures with (Helvetica) those are the exact numbers of the specification, so a caller
 * who does not care about measuring gets a correct picture and no note. Only a font the table does
 * not know falls back to an estimate, and then a note says so.
 */

'use strict';

var sceneLib = require('./scene.js');
var modelLib = require('./model.js');
var forceLib = require('./force.js');
var metricLib = require('./metric.js');

/** The font the measure function is called with. */
var FONT = 'Helvetica';

/** The label size of a node. */
var FONT_SIZE = 10;

/** Horizontal padding inside a node box. */
var PAD_X = 10;

/** Vertical padding inside a node box. */
var PAD_Y = 6;

/** The maximum text width inside a node before a label is wrapped. */
var MAX_TEXT_WIDTH = 150;

/** The smallest node box. */
var MIN_WIDTH = 36;
var MIN_HEIGHT = 22;

/** The distance between two nodes of a layer. */
var NODE_GAP = 26;

/** The distance between two group strips (a subgraph gets its own vertical strip). */
var CLUSTER_GAP = 28;

/** The extra space between two layers (before the routing lanes are added). */
var RANK_GAP = 26;

/** The length of the orthogonal stub a forced port gets before the line turns. */
var PORT_STUB = 7;

/**
 * The stub a {@code route: 'smooth'} line keeps along the side's normal. Longer than the orthogonal
 * stub, because it decides the tangent of the curve: 18 pt is about the "leaves the box straight and
 * then bends" look of the flowchart renderers, where 7 pt still reads as a kink.
 */
var SMOOTH_STUB = 18;

/** How long edges get an extra arrowhead in the middle: 'off' (default) or 'long'. */
var MID_ARROWS = ['off', 'long'];

/** A step shorter than this (in points) is absorbed by moving the port onto the run. */
var MAX_PORT_STEP = 24;

/** How many crossing pairs the diagnosis lists by name (the count itself is always exact). */
var MAX_CROSSING_PAIRS = 12;

/** How many kinks the diagnosis lists by name (the sharpest first; the count is always exact). */
var MAX_KINKS = 12;

/** How many box hits the diagnosis lists by name (the count itself is always exact). */
var MAX_BOX_HITS = 12;

/**
 * Two runs closer than this (in points) are drawn as one line: the stroke is 1 pt wide, so a gap
 * below it is invisible and the line just looks doubled. Used by the near-duplicate report.
 */
var NEAR_LINE_GAP = 1.2;

/** A parallel run shorter than this (in points) is a corner artefact, not a doubled line. */
var NEAR_RUN_MIN = 4;

/** How many sweeps of the barycenter heuristic run. */
var PLACE_SWEEPS = 6;

/**
 * How the back/same-rank edges pick their channel on the right: {@code 'edge'} gives every edge its
 * own channel (the default, every line visible on its own), {@code 'target'} lets all edges that
 * reach the same node share one trunk that splits in the target's gap.
 */
var BACK_CHANNELS = ['edge', 'target'];

/**
 * How long edges are routed: {@code 'remove'} puts all dummies of one long edge on a single x (no
 * sideways jog per rank), {@code 'keep'} leaves them where the placement put them.
 */
var JOG_MODES = ['remove', 'keep'];

/**
 * How a group is honoured in the placement: {@code 'flow'} places every node by the barycenter
 * sweeps like an ungrouped graph and only draws the band around the members (short edges, the bands
 * may touch - reported), {@code 'strips'} gives every top-level group its own vertical strip (bands
 * never overlap, but a chain that runs through several groups is spread over the strips and its
 * edges become long horizontal runs).
 */
var GROUP_MODES = ['flow', 'strips'];

/**
 * Runs the layout.
 * @param {object|string} modelOrJson the normalized model (or its JSON)
 * @param {object} [options] {measure, stopAfter, limits, pin, rank, order, port, bend, route}
 * @returns {object} the scene
 */
function run(modelOrJson, options) {
  var optionsInUse = options || {};
  var limits = modelLib.limitsWith(optionsInUse.limits || null);
  var model = resolveModel(modelOrJson, limits);
  var graph = prepare(model, optionsInUse, limits);

  var completed = [];
  var stoppedIn = null;
  var stopReason = null;
  var phasesInOrder = ['p0measure', 'p1cycles', 'p2ranks', 'p3order', 'p4place', 'p5route'];

  for (var p = 0; p < phasesInOrder.length; p++) {
    var phase = phasesInOrder[p];
    var start = now();
    if (phase === 'p0measure') {
      measureNodes(graph);
    } else if (phase === 'p1cycles') {
      findCycles(graph);
    } else if (phase === 'p2ranks') {
      assignRanks(graph);
    } else if (phase === 'p3order') {
      orderLayers(graph);
    } else if (phase === 'p4place') {
      place(graph);
    } else if (phase === 'p5route') {
      route(graph);
    }
    if (elapsed(start) > limits.maxPhaseMillis) {
      stoppedIn = phase;
      stopReason = 'maxPhaseMillis (' + limits.maxPhaseMillis + ') reached in ' + phase;
      break;
    }
    completed.push(phase);
    if (optionsInUse.stopAfter === phase) {
      stoppedIn = phase;
      stopReason = "stopAfter '" + phase + "'";
      break;
    }
  }

  // A scene without coordinates would not be drawable; order and place what exists before
  // building it, but leave the routing out when p5route did not run (that is what stopAfter means).
  if (!graph.layers) {
    orderLayers(graph);
  }
  if (!graph.placed) {
    place(graph);
  }
  buildScene(graph);

  graph.scene.phases.completed = completed;
  if (stoppedIn !== null) {
    graph.scene.phases.stoppedIn = stoppedIn;
    graph.scene.phases.reason = stopReason;
  }
  graph.scene.phases.notes = graph.notes;
  attachRecipe(graph);
  return graph.scene;
}

/**
 * Echoes the settings that decided the coordinates into {@code phases.recipe}.
 * <p>
 * The reason is a conversation, not the layout: two people looking at two pictures argue about
 * them until they know they are the same two lines of code. The recipe answers "which picture is
 * this" without asking - strategy, seed, relaxation and every nudge that moved a node, with the
 * effective values (a default seed is as much part of the result as an explicit one).
 * </p>
 * <p>
 * What it cannot know is what the caller knows: which fixture, which build. {@code options.recipe}
 * is passed through unchanged, so a tool can add {@code {fixture, fixtureHash, moduleHash}} there
 * and the whole provenance ends up in the scene (and, via {@code svg.js}, in the SVG title).
 * </p>
 * @param {object} graph the graph
 */
function attachRecipe(graph) {
  var nudges = {};
  var nudged = 0;
  for (var i = 0; i < graph.nodes.length; i++) {
    var node = graph.nodes[i];
    if (node.dummy || !node.nudge) {
      continue;
    }
    if (node.nudge.dx === 0 && node.nudge.dy === 0) {
      continue;
    }
    nudges[node.id] = {dx: node.nudge.dx, dy: node.nudge.dy};
    nudged++;
  }
  var force = null;
  if (graph.layoutMode === 'force') {
    force = {seed: graph.forceSeed, runs: graph.forceRuns,
      iterations: graph.forceIterations, springLength: graph.springLength};
  }
  graph.scene.phases.recipe = {
    layout: graph.layoutMode,
    route: graph.scene.phases.route || graph.route,
    curve: graph.curve,
    curveRadius: graph.curveRadius,
    curveTension: graph.curveTension,
    backChannels: graph.backChannels,
    jogs: graph.jogs,
    groups: graph.groups,
    midArrows: graph.midArrows,
    midArrowAfter: graph.midArrowAfter,
    force: force,
    relax: graph.relaxIterations || null,
    relaxLevels: graph.relaxIterations ? graph.relaxLevels : null,
    nudgeCount: nudged,
    nudge: nudges,
    origin: graph.recipeOrigin || null
  };
}

/**
 * Resolves the model argument (object or JSON string) and normalizes it.
 * @param {object|string} modelOrJson the model
 * @param {object} limits the effective limits
 * @returns {object} the normalized model
 */
function resolveModel(modelOrJson, limits) {
  var model = modelOrJson;
  if (typeof model === 'string') {
    model = JSON.parse(model);
  }
  return modelLib.normalize(model, limits);
}

/**
 * Prepares the internal layout structures: copies nodes and edges, resolves references and applies
 * the correction options (pin/rank/order/port/bend).
 * @param {object} model the normalized model
 * @param {object} options the layout options
 * @param {object} limits the effective limits
 * @returns {object} the graph
 */
function prepare(model, options, limits) {
  var graph = {
    model: model,
    limits: limits,
    notes: [],
    measure: null,
    direction: model.direction,
    route: model.route || 'orthogonal',
    curve: model.curve || 'off',
    layoutMode: model.layout || 'layered',
    forceIterations: forceLib.DEFAULTS.iterations,
    forceRuns: forceLib.DEFAULTS.runs,
    forceSeed: forceLib.DEFAULTS.seed,
    springLength: forceLib.DEFAULTS.springLength,
    relaxIterations: 0,
    relaxLevels: 2,
    routeGiven: false,
    backChannelsGiven: false,
    jogsGiven: false,
    curveRadius: sceneLib.CURVE_RADIUS,
    curveTension: sceneLib.CURVE_TENSION,
    backChannels: 'edge',
    jogs: 'remove',
    groups: 'flow',
    groupsGiven: false,
    midArrows: 'off',
    midArrowAfter: 200,
    /** Passed through to phases.recipe.origin - the caller's provenance (fixture, hashes). */
    recipeOrigin: options.recipe && typeof options.recipe === 'object' ? options.recipe : null,
    nodes: [],
    edges: [],
    byId: {},
    scene: sceneLib.create()
  };
  graph.measure = resolveMeasure(options.measure, graph);
  for (var n = 0; n < model.nodes.length; n++) {
    var raw = model.nodes[n];
    var node = {
      uid: n,
      id: raw.id,
      label: raw.label,
      shape: raw.shape,
      group: raw.group,
      fixed: raw.fixed,
      shift: raw.shift,
      rankFixed: raw.rank,
      orderFixed: raw.order,
      rank: 0,
      x: 0,
      y: 0,
      w: 0,
      h: 0,
      lines: [],
      dummy: false
    };
    graph.nodes.push(node);
    graph.byId[node.id] = node;
  }
  for (var e = 0; e < model.edges.length; e++) {
    var rawEdge = model.edges[e];
    var edge = {
      index: e,
      from: rawEdge.from,
      to: rawEdge.to,
      label: rawEdge.label,
      style: rawEdge.style,
      arrow: rawEdge.arrow,
      constraint: rawEdge.constraint,
      weight: rawEdge.weight,
      exit: rawEdge.exit,
      entry: rawEdge.entry,
      waypoints: rawEdge.waypoints,
      fromNode: graph.byId[rawEdge.from],
      toNode: graph.byId[rawEdge.to],
      back: false,
      self: rawEdge.from === rawEdge.to,
      side: false,
      chain: null
    };
    graph.edges.push(edge);
  }
  applyOptions(graph, options);
  return graph;
}

/**
 * Applies one pin. {@code {x, y}} is absolute (layout space - the same space a resolved pin uses),
 * {@code {dx, dy}} is relative to the position the free placement would give the node, which is how a
 * caller actually thinks ("move this node 16 pt to the right") without knowing that space.
 * @param {object} node the node
 * @param {string} id the node id (for the note)
 * @param {object} pin the pin
 * @param {object} graph the graph
 */
function pinNode(node, id, pin, graph) {
  if (pin === null || typeof pin !== 'object') {
    graph.notes.push("layout: the pin of '" + id + "' is not an object {x, y} or {dx, dy} - ignored");
    return;
  }
  var relative = pin.dx !== undefined || pin.dy !== undefined;
  if (relative && (pin.x !== undefined || pin.y !== undefined)) {
    graph.notes.push("layout: the pin of '" + id + "' mixes an absolute {x, y} with a relative"
      + ' {dx, dy} - the relative part wins');
  }
  if (relative) {
    node.shift = {dx: Number(pin.dx) || 0, dy: Number(pin.dy) || 0};
    return;
  }
  if (typeof pin.x === 'number' && typeof pin.y === 'number'
      && isFinite(pin.x) && isFinite(pin.y)) {
    node.fixed = {x: pin.x, y: pin.y};
    return;
  }
  graph.notes.push("layout: the pin of '" + id + "' needs both numbers x and y, or dx and dy"
    + ' - ignored');
}

/**
 * Wraps the measuring function: the injected one, else the built-in widths of the standard fonts,
 * else an estimate - and in the last case a note says so.
 * @param {Function} measure the injected function, or undefined
 * @param {object} graph the graph (receives the note)
 * @returns {Function} the measuring function
 */
function resolveMeasure(measure, graph) {
  if (typeof measure === 'function') {
    return function (request) {
      var width = measure(request);
      if (typeof width !== 'number' || !isFinite(width) || width < 0) {
        throw new Error('diagram/layout: measure() must return a non-negative number, got: '
          + JSON.stringify(width) + ' for ' + JSON.stringify(request.text));
      }
      return width;
    };
  }
  // The layout measures with FONT, so the table of that font is what is needed here - exact for a
  // standard font, so no note: the picture is right, not guessed.
  var builtin = metricLib.metric(FONT, FONT_SIZE);
  if (builtin.source === 'builtin') {
    return builtin.measure;
  }
  graph.notes.push("layout: the font '" + FONT + "' has no built-in width table and no measure"
    + ' function was injected - node widths are estimated (length x size x 0.55), so a label may'
    + ' be slightly off; pass options.measure for exact widths (docs/js/diagram-plan.md §2.3)');
  return function (request) {
    return String(request.text).length * request.size * 0.55;
  };
}

/**
 * Applies the correction options (pin/rank/order/port/bend) to the graph.
 * @param {object} graph the graph
 * @param {object} options the layout options
 */
function applyOptions(graph, options) {
  if (options.layout !== undefined && options.layout !== null) {
    var layoutMode = String(options.layout);
    if (modelLib.LAYOUTS.indexOf(layoutMode) < 0) {
      throw new Error("diagram/layout: unknown layout '" + options.layout + "' (allowed: "
        + modelLib.LAYOUTS.join(', ') + ')');
    }
    graph.layoutMode = layoutMode;
  }
  if (options.forceIterations !== undefined) {
    graph.forceIterations = readCount(options.forceIterations, 'forceIterations',
      graph.limits.maxForceIterations);
  }
  if (options.forceRuns !== undefined) {
    graph.forceRuns = readCount(options.forceRuns, 'forceRuns', graph.limits.maxForceRuns);
  }
  if (options.forceSeed !== undefined) {
    var seed = Number(options.forceSeed);
    if (!isFinite(seed)) {
      throw new Error('diagram/layout: forceSeed must be a finite number, got: '
        + options.forceSeed);
    }
    graph.forceSeed = seed;
  }
  if (options.springLength !== undefined) {
    var springLength = Number(options.springLength);
    if (!isFinite(springLength) || springLength <= 0) {
      throw new Error('diagram/layout: springLength must be a number > 0, got: '
        + options.springLength);
    }
    graph.springLength = springLength;
  }
  if (options.relax !== undefined && options.relax !== null) {
    var relax = Number(options.relax);
    if (!isFinite(relax) || Math.floor(relax) !== relax || relax < 0) {
      throw new Error('diagram/layout: relax must be a non-negative integer (relaxation steps'
        + ' after a nudge, 0 = off), got: ' + JSON.stringify(options.relax));
    }
    if (relax > graph.limits.maxRelaxIterations) {
      throw new Error('diagram/layout: relax (' + relax + ') exceeds the limit '
        + graph.limits.maxRelaxIterations);
    }
    graph.relaxIterations = relax;
  }
  if (options.relaxLevels !== undefined) {
    graph.relaxLevels = readCount(options.relaxLevels, 'relaxLevels',
      graph.limits.maxRelaxLevels);
  }
  if (options.route !== undefined) {
    graph.routeGiven = true;
    var route = String(options.route);
    if (modelLib.ROUTES.indexOf(route) < 0) {
      throw new Error("diagram/layout: unknown route '" + options.route + "' (allowed: "
        + modelLib.ROUTES.join(', ') + ')');
    }
    graph.route = route;
  }
  if (options.curve !== undefined && options.curve !== null) {
    var curve = String(options.curve);
    if (modelLib.CURVES.indexOf(curve) < 0) {
      throw new Error("diagram/layout: unknown curve '" + options.curve + "' (allowed: "
        + modelLib.CURVES.join(', ') + ')');
    }
    graph.curve = curve;
  }
  if (options.curveRadius !== undefined) {
    var radius = Number(options.curveRadius);
    if (!isFinite(radius) || radius <= 0) {
      throw new Error('diagram/layout: curveRadius must be a number > 0, got: '
        + options.curveRadius);
    }
    graph.curveRadius = radius;
  }
  if (options.curveTension !== undefined) {
    var tension = Number(options.curveTension);
    if (!isFinite(tension) || tension <= 0 || tension > 2) {
      throw new Error('diagram/layout: curveTension must be a number in (0, 2], got: '
        + options.curveTension);
    }
    graph.curveTension = tension;
  }
  if (options.backChannels !== undefined) {
    graph.backChannelsGiven = true;
    var backChannels = String(options.backChannels);
    if (BACK_CHANNELS.indexOf(backChannels) < 0) {
      throw new Error("diagram/layout: unknown backChannels '" + options.backChannels
        + "' (allowed: " + BACK_CHANNELS.join(', ') + ')');
    }
    graph.backChannels = backChannels;
  }
  if (options.midArrows !== undefined) {
    var midArrows = String(options.midArrows);
    if (MID_ARROWS.indexOf(midArrows) < 0) {
      throw new Error("diagram/layout: unknown midArrows '" + options.midArrows + "' (allowed: "
        + MID_ARROWS.join(', ') + ')');
    }
    graph.midArrows = midArrows;
  }
  if (options.midArrowAfter !== undefined) {
    var after = Number(options.midArrowAfter);
    if (!isFinite(after) || after < 0) {
      throw new Error("diagram/layout: midArrowAfter must be a number >= 0, got: "
        + options.midArrowAfter);
    }
    graph.midArrowAfter = after;
  }
  if (options.jogs !== undefined) {
    graph.jogsGiven = true;
    var jogs = String(options.jogs);
    if (JOG_MODES.indexOf(jogs) < 0) {
      throw new Error("diagram/layout: unknown jogs '" + options.jogs + "' (allowed: "
        + JOG_MODES.join(', ') + ')');
    }
    graph.jogs = jogs;
  }
  if (options.groups !== undefined) {
    graph.groupsGiven = true;
    var groups = String(options.groups);
    if (GROUP_MODES.indexOf(groups) < 0) {
      throw new Error("diagram/layout: unknown groups '" + options.groups + "' (allowed: "
        + GROUP_MODES.join(', ') + ')');
    }
    graph.groups = groups;
  }
  var pins = options.pin || {};
  for (var id in pins) {
    if (Object.prototype.hasOwnProperty.call(pins, id)) {
      var node = graph.byId[id];
      if (node) {
        pinNode(node, id, pins[id], graph);
      } else {
        graph.notes.push("layout: pin names '" + id + "', which is not a node - ignored");
      }
    }
  }
  var nudges = options.nudge || {};
  for (var nid in nudges) {
    if (Object.prototype.hasOwnProperty.call(nudges, nid)) {
      if (graph.byId[nid]) {
        graph.byId[nid].nudge = {dx: Number(nudges[nid].dx) || 0, dy: Number(nudges[nid].dy) || 0};
      } else {
        graph.notes.push("layout: nudge names '" + nid + "', which is not a node - ignored");
      }
    }
  }
  var ranks = options.rank || {};
  for (var rid in ranks) {
    if (Object.prototype.hasOwnProperty.call(ranks, rid)) {
      if (graph.byId[rid]) {
        graph.byId[rid].rankFixed = ranks[rid];
      } else {
        graph.notes.push("layout: rank names '" + rid + "', which is not a node - ignored");
      }
    }
  }
  var order = options.order || {};
  for (var layerKey in order) {
    if (!Object.prototype.hasOwnProperty.call(order, layerKey)) {
      continue;
    }
    var layerRank = parseInt(layerKey, 10);
    var ids = order[layerKey];
    if (!Array.isArray(ids)) {
      graph.notes.push("layout: order['" + layerKey + "'] is not an array - ignored");
      continue;
    }
    for (var i = 0; i < ids.length; i++) {
      if (graph.byId[ids[i]]) {
        graph.byId[ids[i]].orderFixed = i;
        graph.byId[ids[i]].orderLayer = layerRank;
      } else {
        graph.notes.push("layout: order names '" + ids[i] + "', which is not a node - ignored");
      }
    }
  }
  var ports = options.port || {};
  for (var key in ports) {
    if (!Object.prototype.hasOwnProperty.call(ports, key)) {
      continue;
    }
    var parts = key.split('>');
    var edge = findEdge(graph, parts[0], parts[1]);
    if (!edge) {
      graph.notes.push("layout: port names the edge '" + key + "', which does not exist -"
        + ' ignored');
      continue;
    }
    if (ports[key].exit) {
      edge.exit = ports[key].exit;
    }
    if (ports[key].entry) {
      edge.entry = ports[key].entry;
    }
  }
  var bends = options.bend || {};
  for (var bkey in bends) {
    if (!Object.prototype.hasOwnProperty.call(bends, bkey)) {
      continue;
    }
    var bendParts = bkey.split('>');
    var bendEdge = findEdge(graph, bendParts[0], bendParts[1]);
    if (!bendEdge) {
      graph.notes.push("layout: bend names the edge '" + bkey + "', which does not exist -"
        + ' ignored');
      continue;
    }
    bendEdge.waypoints = bends[bkey];
  }
}

/**
 * Reads a positive whole number from an option and checks it against a limit. An overrun is an
 * error with the name and the value, never a silent cut (docs/js/diagram-plan.md §8).
 * @param {*} value the option value
 * @param {string} name the option name (for the message)
 * @param {number} limit the effective limit
 * @returns {number} the number
 */
function readCount(value, name, limit) {
  var number = Number(value);
  if (!isFinite(number) || Math.floor(number) !== number || number < 1) {
    throw new Error('diagram/layout: ' + name + ' must be a positive integer, got: '
      + JSON.stringify(value));
  }
  if (number > limit) {
    throw new Error('diagram/layout: ' + name + ' (' + number + ') exceeds the limit ' + limit);
  }
  return number;
}

/**
 * Finds an edge by its endpoints.
 * @param {object} graph the graph
 * @param {string} from the source id
 * @param {string} to the target id
 * @returns {object|null} the edge or null
 */
function findEdge(graph, from, to) {
  for (var i = 0; i < graph.edges.length; i++) {
    if (graph.edges[i].from === from && graph.edges[i].to === to) {
      return graph.edges[i];
    }
  }
  return null;
}

// ---------------------------------------------------------------------------------------------
// p0measure
// ---------------------------------------------------------------------------------------------

/**
 * Measures every label and assigns the node box.
 * @param {object} graph the graph
 */
function measureNodes(graph) {
  for (var i = 0; i < graph.nodes.length; i++) {
    var node = graph.nodes[i];
    if (node.shape === 'start' || node.shape === 'end') {
      // A start/end pseudo state is a small circle with no label.
      var pseudo = (typeof node.w === 'number' && node.w > 0) ? node.w : 14;
      node.w = pseudo;
      node.h = pseudo;
      node.lines = [];
      node.textWidth = 0;
      continue;
    }
    var lines = wrapLabel(graph, node.label);
    var textWidth = 0;
    for (var l = 0; l < lines.length; l++) {
      textWidth = Math.max(textWidth, measure(graph, lines[l]));
    }
    var lineHeight = FONT_SIZE * 1.25;
    var width;
    var height;
    if (node.shape === 'circle') {
      var diameter = Math.max(textWidth + 2 * PAD_X, lines.length * lineHeight + 2 * PAD_Y,
        MIN_WIDTH);
      width = diameter;
      height = diameter;
    } else if (node.shape === 'diamond' || node.shape === 'hexagon') {
      width = Math.max(MIN_WIDTH, textWidth * 1.6 + 2 * PAD_X);
      height = Math.max(MIN_HEIGHT, lines.length * lineHeight * 1.8 + 2 * PAD_Y);
    } else {
      width = Math.max(MIN_WIDTH, textWidth + 2 * PAD_X);
      height = Math.max(MIN_HEIGHT, lines.length * lineHeight + 2 * PAD_Y);
    }
    if (typeof node.w === 'number' && node.w > 0) {
      width = Math.max(width, node.w);
    }
    node.w = sceneLib.round(width);
    node.h = sceneLib.round(height);
    node.lines = lines;
    node.textWidth = textWidth;
  }
}

/**
 * Wraps a label into lines. A {@code \n} forces a break; words are wrapped at MAX_TEXT_WIDTH.
 * @param {object} graph the graph
 * @param {string} label the label
 * @returns {Array<string>} the lines (at least one)
 */
function wrapLabel(graph, label) {
  var paragraphs = String(label).split('\n');
  var lines = [];
  for (var p = 0; p < paragraphs.length; p++) {
    var words = paragraphs[p].split(/\s+/);
    var current = '';
    for (var w = 0; w < words.length; w++) {
      if (words[w] === '') {
        continue;
      }
      var candidate = current === '' ? words[w] : current + ' ' + words[w];
      if (current !== '' && measure(graph, candidate) > MAX_TEXT_WIDTH) {
        lines.push(current);
        current = words[w];
      } else {
        current = candidate;
      }
    }
    lines.push(current);
  }
  if (lines.length === 0) {
    lines.push('');
  }
  return lines;
}

/**
 * Measures a single string with the injected function.
 * @param {object} graph the graph
 * @param {string} text the string
 * @returns {number} the width
 */
function measure(graph, text) {
  return graph.measure({font: FONT, size: FONT_SIZE, text: text});
}

// ---------------------------------------------------------------------------------------------
// p1cycles
// ---------------------------------------------------------------------------------------------

/**
 * Marks the back edges that close a cycle (DFS, colouring white/grey/black).
 * <p>
 * Ties are broken by the edge order, so the same input always finds the same back edges - which is
 * the difference to ELK's randomised greedy cycle breaking (docs/js/diagram-plan.md §11.9).
 * </p>
 * @param {object} graph the graph
 */
function findCycles(graph) {
  var color = {};
  for (var i = 0; i < graph.nodes.length; i++) {
    color[graph.nodes[i].uid] = 0; // 0 white, 1 grey, 2 black
  }
  var outgoing = {};
  for (var e = 0; e < graph.edges.length; e++) {
    var edge = graph.edges[e];
    if (edge.self || !edge.constraint) {
      continue;
    }
    if (!outgoing[edge.fromNode.uid]) {
      outgoing[edge.fromNode.uid] = [];
    }
    outgoing[edge.fromNode.uid].push(edge);
  }
  for (var s = 0; s < graph.nodes.length; s++) {
    if (color[graph.nodes[s].uid] === 0) {
      dfs(graph, graph.nodes[s], color, outgoing);
    }
  }
}

/**
 * Depth-first search from one node, marking edges to grey nodes as back edges.
 * @param {object} graph the graph
 * @param {object} node the start node
 * @param {object} color the colour map (by uid)
 * @param {object} outgoing the adjacency by uid
 */
function dfs(graph, node, color, outgoing) {
  color[node.uid] = 1;
  var edges = outgoing[node.uid] || [];
  for (var i = 0; i < edges.length; i++) {
    var edge = edges[i];
    var target = edge.toNode;
    if (color[target.uid] === 1) {
      edge.back = true;
    } else if (color[target.uid] === 0) {
      dfs(graph, target, color, outgoing);
    }
  }
  color[node.uid] = 2;
}

// ---------------------------------------------------------------------------------------------
// p2ranks
// ---------------------------------------------------------------------------------------------

/**
 * Assigns a rank per node by longest path over the constraint edges, honouring fixed ranks.
 * @param {object} graph the graph
 */
function assignRanks(graph) {
  var ranks = {};
  var locked = {};
  var i;
  for (i = 0; i < graph.nodes.length; i++) {
    ranks[graph.nodes[i].uid] = 0;
  }
  for (i = 0; i < graph.nodes.length; i++) {
    var node = graph.nodes[i];
    if (node.rankFixed !== null && node.rankFixed !== undefined) {
      ranks[node.uid] = node.rankFixed;
      locked[node.uid] = true;
    }
  }
  var passes = graph.nodes.length + 1;
  var conflicts = {};
  for (var pass = 0; pass < passes; pass++) {
    var changed = false;
    for (var e = 0; e < graph.edges.length; e++) {
      var edge = graph.edges[e];
      if (edge.self || edge.back || !edge.constraint) {
        continue;
      }
      var need = ranks[edge.fromNode.uid] + 1;
      if (ranks[edge.toNode.uid] < need) {
        if (locked[edge.toNode.uid]) {
          if (!conflicts[edge.index]) {
            conflicts[edge.index] = true;
            graph.notes.push("layout: the fixed rank " + ranks[edge.toNode.uid] + " of '"
              + edge.toNode.id + "' contradicts the edge '" + edge.from + "' -> '" + edge.to
              + "' (it needs at least " + need + ') - the fixed rank wins');
          }
        } else {
          ranks[edge.toNode.uid] = need;
          changed = true;
        }
      }
    }
    if (!changed) {
      break;
    }
  }
  for (i = 0; i < graph.nodes.length; i++) {
    graph.nodes[i].rank = ranks[graph.nodes[i].uid];
  }
}

// ---------------------------------------------------------------------------------------------
// p3order
// ---------------------------------------------------------------------------------------------

/**
 * Orders the nodes of each layer by the barycenter heuristic, inserting a dummy node per rank for
 * every edge that spans more than one rank.
 * @param {object} graph the graph
 */
function orderLayers(graph) {
  if (graph.layoutMode === 'force') {
    buildRankLayers(graph);
    return;
  }
  var maxRank = 0;
  var i;
  for (i = 0; i < graph.nodes.length; i++) {
    maxRank = Math.max(maxRank, graph.nodes[i].rank);
  }
  var layers = [];
  for (var r = 0; r <= maxRank; r++) {
    layers.push([]);
  }
  for (i = 0; i < graph.nodes.length; i++) {
    layers[graph.nodes[i].rank].push(graph.nodes[i]);
  }

  // Dummy nodes fill the ranks a long edge passes through.
  var uid = graph.nodes.length;
  for (var e = 0; e < graph.edges.length; e++) {
    var edge = graph.edges[e];
    if (edge.self || edge.back) {
      edge.side = true;
      continue;
    }
    var span = edge.toNode.rank - edge.fromNode.rank;
    if (span <= 0) {
      edge.side = true;
      continue;
    }
    var chain = [edge.fromNode];
    for (var rank = edge.fromNode.rank + 1; rank < edge.toNode.rank; rank++) {
      var dummy = {
        uid: uid++,
        id: 'dummy:' + edge.index + ':' + rank,
        label: '',
        shape: 'rect',
        group: null,
        fixed: null,
        rankFixed: null,
        orderFixed: null,
        rank: rank,
        x: 0,
        y: 0,
        w: 0,
        h: 0,
        lines: [],
        dummy: true,
        edge: edge
      };
      layers[rank].push(dummy);
      chain.push(dummy);
    }
    chain.push(edge.toNode);
    edge.chain = chain;
  }

  graph.layers = layers;
  initialOrder(graph, layers);

  var segments = buildSegments(graph, layers);
  graph.segments = segments;
  var best = copyLayers(layers);
  var bestCrossings = countCrossings(layers, segments);
  var maxSweeps = graph.limits.maxSweeps;
  for (var sweep = 0; sweep < maxSweeps; sweep++) {
    var down = (sweep % 2) === 0;
    sweepLayers(layers, segments, down);
    var crossings = countCrossings(layers, segments);
    if (crossings < bestCrossings) {
      bestCrossings = crossings;
      best = copyLayers(layers);
      if (crossings === 0) {
        break;
      }
    }
  }
  graph.layers = best;
  graph.crossings = bestCrossings;
  clusterByGroup(graph, best);
  graph.segments = buildSegments(graph, best);
}

/**
 * Builds the plain rank layers a force layout keeps for the diagnostics.
 * <p>
 * No dummy nodes (a long edge needs no bend in a picture without channels) and no sweeps: the
 * crossing metric of p3order counts pairs of segments between two ranks, which says nothing about an
 * arrangement that has no ranks. {@code phases.crossings} therefore stays null and
 * {@code phases.drawnCrossings} is the number that describes the picture.
 * </p>
 * @param {object} graph the graph
 */
function buildRankLayers(graph) {
  var maxRank = 0;
  var i;
  for (i = 0; i < graph.nodes.length; i++) {
    maxRank = Math.max(maxRank, graph.nodes[i].rank);
  }
  var layers = [];
  for (var r = 0; r <= maxRank; r++) {
    layers.push([]);
  }
  for (i = 0; i < graph.nodes.length; i++) {
    layers[graph.nodes[i].rank].push(graph.nodes[i]);
  }
  graph.layers = layers;
  graph.segments = [];
  graph.crossings = null;
}

/**
 * Stable-sorts each layer so that the nodes of one group are contiguous. This keeps a subgraph's
 * band from being torn apart (which would make its rectangle overlap a neighbour's).
 * @param {object} graph the graph
 * @param {Array<Array<object>>} layers the layers
 */
function clusterByGroup(graph, layers) {
  if (!graph.model.groups.length) {
    return;
  }
  var groupIndex = {};
  for (var g = 0; g < graph.model.groups.length; g++) {
    groupIndex[graph.model.groups[g].id] = g;
  }
  var byId = {};
  for (var m = 0; m < graph.model.groups.length; m++) {
    byId[graph.model.groups[m].id] = graph.model.groups[m];
  }
  var clusterCache = {};
  function clusterOf(node) {
    if (!node.group) {
      return graph.model.groups.length;
    }
    if (clusterCache[node.group] !== undefined) {
      return clusterCache[node.group];
    }
    var current = byId[node.group];
    while (current && current.parent) {
      current = byId[current.parent];
    }
    var value = current ? groupIndex[current.id] : graph.model.groups.length;
    clusterCache[node.group] = value;
    return value;
  }
  for (var r = 0; r < layers.length; r++) {
    var layer = layers[r];
    var decorated = [];
    for (var i = 0; i < layer.length; i++) {
      decorated.push({node: layer[i], cluster: clusterOf(layer[i]), index: i});
    }
    decorated.sort(function (a, b) {
      if (a.cluster !== b.cluster) {
        return a.cluster - b.cluster;
      }
      return a.index - b.index;
    });
    for (var k = 0; k < decorated.length; k++) {
      layer[k] = decorated[k].node;
    }
  }
}

/**
 * Initial ordering: by the explicit order (if any), then by the model order.
 * @param {object} graph the graph
 * @param {Array<Array<object>>} layers the layers
 */
function initialOrder(graph, layers) {
  for (var r = 0; r < layers.length; r++) {
    layers[r].sort(function (a, b) {
      var aOrder = a.orderFixed !== null && a.orderFixed !== undefined
        ? a.orderFixed : (a.dummy ? Number.MAX_SAFE_INTEGER : a.uid);
      var bOrder = b.orderFixed !== null && b.orderFixed !== undefined
        ? b.orderFixed : (b.dummy ? Number.MAX_SAFE_INTEGER : b.uid);
      if (aOrder !== bOrder) {
        return aOrder - bOrder;
      }
      return a.uid - b.uid;
    });
  }
}

/**
 * Builds the segment list: one segment per pair of nodes in adjacent layers.
 * @param {object} graph the graph
 * @param {Array<Array<object>>} layers the layers
 * @returns {Array<object>} the segments
 */
function buildSegments(graph, layers) {
  var segments = [];
  for (var e = 0; e < graph.edges.length; e++) {
    var edge = graph.edges[e];
    if (!edge.chain) {
      continue;
    }
    for (var i = 0; i < edge.chain.length - 1; i++) {
      segments.push({edge: edge, a: edge.chain[i], b: edge.chain[i + 1], gap: edge.chain[i].rank});
    }
  }
  return segments;
}

/**
 * Runs one barycenter sweep over all layers (downwards or upwards).
 * @param {Array<Array<object>>} layers the layers
 * @param {Array<object>} segments the segments
 * @param {boolean} down true for downwards, false for upwards
 */
function sweepLayers(layers, segments, down) {
  var order = [];
  var r;
  if (down) {
    for (r = 1; r < layers.length; r++) {
      order.push(r);
    }
  } else {
    for (r = layers.length - 2; r >= 0; r--) {
      order.push(r);
    }
  }
  for (var o = 0; o < order.length; o++) {
    var rank = order[o];
    var reference = down ? rank - 1 : rank + 1;
    var position = positionsOf(layers[reference]);
    var layer = layers[rank];
    var keyed = [];
    for (var i = 0; i < layer.length; i++) {
      var node = layer[i];
      var sum = 0;
      var count = 0;
      for (var s = 0; s < segments.length; s++) {
        var segment = segments[s];
        var other = null;
        if (down && segment.b === node) {
          other = segment.a;
        } else if (!down && segment.a === node) {
          other = segment.b;
        }
        if (other !== null) {
          sum += position[other.uid];
          count++;
        }
      }
      keyed.push({node: node, index: i, value: count ? sum / count : i});
    }
    keyed.sort(function (a, b) {
      if (a.value !== b.value) {
        return a.value - b.value;
      }
      return a.index - b.index;
    });
    for (var k = 0; k < keyed.length; k++) {
      layer[k] = keyed[k].node;
    }
  }
}

/**
 * Maps each node of a layer to its index.
 * @param {Array<object>} layer the layer
 * @returns {object} index by uid
 */
function positionsOf(layer) {
  var position = {};
  for (var i = 0; i < layer.length; i++) {
    position[layer[i].uid] = i;
  }
  return position;
}

/**
 * Copies the layer structure (arrays of node references).
 * @param {Array<Array<object>>} layers the layers
 * @returns {Array<Array<object>>} the copy
 */
function copyLayers(layers) {
  var copy = [];
  for (var r = 0; r < layers.length; r++) {
    copy.push(layers[r].slice());
  }
  return copy;
}

/**
 * Counts the edge crossings between all adjacent layers.
 * @param {Array<Array<object>>} layers the layers
 * @param {Array<object>} segments the segments
 * @returns {number} the number of crossings
 */
function countCrossings(layers, segments) {
  var byGap = segmentsByGap(segments, layers.length);
  var total = 0;
  for (var gap = 0; gap < byGap.length; gap++) {
    var list = byGap[gap];
    if (list.length < 2) {
      continue;
    }
    var top = positionsOf(layers[gap]);
    var bottom = positionsOf(layers[gap + 1]);
    for (var i = 0; i < list.length; i++) {
      for (var j = i + 1; j < list.length; j++) {
        var dTop = top[list[i].a.uid] - top[list[j].a.uid];
        var dBottom = bottom[list[i].b.uid] - bottom[list[j].b.uid];
        if (dTop * dBottom < 0) {
          total++;
        }
      }
    }
  }
  return total;
}

/**
 * Groups the segments by the gap (layer transition) they cross.
 * @param {Array<object>} segments the segments
 * @param {number} layerCount the number of layers
 * @returns {Array<Array<object>>} the segments by gap
 */
function segmentsByGap(segments, layerCount) {
  var byGap = [];
  for (var g = 0; g < layerCount; g++) {
    byGap.push([]);
  }
  for (var i = 0; i < segments.length; i++) {
    if (segments[i].gap >= 0 && segments[i].gap < byGap.length) {
      byGap[segments[i].gap].push(segments[i]);
    }
  }
  return byGap;
}

// ---------------------------------------------------------------------------------------------
// p4place
// ---------------------------------------------------------------------------------------------

/**
 * Assigns coordinates: with {@code layout: 'layered'} the layers stack vertically, nodes pack
 * horizontally, then barycenter sweeps move each node towards its neighbours without breaking the
 * order; with {@code layout: 'force'} a force-directed simulation decides (see
 * {@link placeForce}).
 * @param {object} graph the graph
 */
function place(graph) {
  if (graph.layoutMode === 'force') {
    placeForce(graph);
    graph.placed = true;
    return;
  }
  if (graph.relaxIterations > 0) {
    graph.notes.push("layout: 'relax' exists only in a force layout - the layered placement"
      + ' computes coordinates instead of simulating them, so nothing was relaxed (pass layout:'
      + " 'force')");
  }
  var layers = graph.layers;
  var r;
  var layerHeight = [];
  for (r = 0; r < layers.length; r++) {
    var max = 0;
    for (var n = 0; n < layers[r].length; n++) {
      max = Math.max(max, layers[r][n].h);
    }
    layerHeight.push(max);
  }
  var byGap = segmentsByGap(graph.segments, layers.length);
  var gaps = [];
  for (r = 0; r < layers.length - 1; r++) {
    gaps.push(RANK_GAP + Math.min(byGap[r].length, 10) * 6);
  }
  var layerTop = [0];
  for (r = 1; r < layers.length; r++) {
    layerTop.push(layerTop[r - 1] + layerHeight[r - 1] + gaps[r - 1]);
  }
  graph.layerTop = layerTop;
  graph.layerHeight = layerHeight;
  graph.gaps = gaps;

  for (r = 0; r < layers.length; r++) {
    for (var m = 0; m < layers[r].length; m++) {
      layers[r][m].y = layerTop[r] + (layerHeight[r] - layers[r][m].h) / 2;
    }
  }

  packAndSweep(graph, layers);
  if (resolveRelativePins(graph)) {
    // A relative pin was resolved against the free placement: place again, now with the shifted
    // node as a hard pin, so the neighbours rearrange around it instead of everything moving along.
    packAndSweep(graph, layers);
  }
  if (graph.model.groups.length && hasPins(graph)) {
    graph.notes.push('layout: grouped strips are not used because a pin is set - the pin wins,'
      + ' group bands may overlap');
  }
  // A pin is a hard constraint: x and y exactly as asked. A y outside the node's rank band is
  // reported, not silently corrected.
  graph.pinnedCount = 0;
  for (var p = 0; p < graph.nodes.length; p++) {
    var pinned = graph.nodes[p];
    if (!pinned.fixed || pinned.dummy) {
      continue;
    }
    graph.pinnedCount++;
    var bandCenter = layerTop[pinned.rank] + layerHeight[pinned.rank] / 2;
    if (Math.abs((pinned.fixed.y + pinned.h / 2) - bandCenter)
        > layerHeight[pinned.rank] / 2 + 8) {
      graph.notes.push("layout: the pin of '" + pinned.id + "' puts it at y="
        + pinned.fixed.y + ', outside its rank band - the edges are routed to the moved node,'
        + ' so the picture may look stretched');
    }
    pinned.x = pinned.fixed.x;
    pinned.y = pinned.fixed.y;
  }
  applyNudges(graph, layers);
  graph.placed = true;
  if (graph.jogs !== 'keep') {
    straightenLongEdges(graph);
  }
}

/**
 * Places the nodes by a force-directed simulation (docs/js/diagram-plan.md §13.7).
 * <p>
 * The ranks of p2ranks are not the positions here: they only seed the start arrangement (the rank
 * along the flow axis, jitter across it), which is what makes the simulation converge quickly and
 * keeps the flow pointing where the diagram points. The coordinates themselves come from the
 * springs (weight = strength), the repulsion, the box collision and the separation; the edges are
 * then drawn as straight lines, because a force layout has no channels to route around (p5route).
 * </p>
 * <p>
 * A pin is a hard constraint and is kept by the simulation itself (a pinned node is never moved, its
 * neighbours give way). Three options lose their meaning here - {@code route}, {@code backChannels},
 * {@code jogs} - and so do {@code rank} and {@code order} (there are no ranks to place by); they are
 * named in a note instead of being dropped silently (§7.2).
 * </p>
 * @param {object} graph the graph
 */
function placeForce(graph) {
  noteForceIgnored(graph);
  var report = runForce(graph);
  noteForceBudget(graph, report);
  if (resolveRelativePins(graph)) {
    // A relative pin is resolved against the free placement: simulate again, now with the shifted
    // node pinned, so the neighbours give way instead of the whole picture moving along.
    report = runForce(graph);
  }
  graph.forceReport = report;
  graph.pinnedCount = 0;
  for (var i = 0; i < graph.nodes.length; i++) {
    var node = graph.nodes[i];
    if (!node.fixed || node.dummy) {
      continue;
    }
    graph.pinnedCount++;
    if (Math.abs(node.x - node.fixed.x) > 0.5 || Math.abs(node.y - node.fixed.y) > 0.5) {
      graph.notes.push("layout: the pin of '" + node.id + "' is not met by the force layout - the"
        + ' node sits at ' + round2(node.x) + ',' + round2(node.y) + ' instead of '
        + node.fixed.x + ',' + node.fixed.y);
    }
  }
  if (report.unresolved > 0) {
    graph.notes.push('layout: ' + report.unresolved + ' pair(s) of node boxes still overlap after'
      + ' the force layout - a pin and a box cannot both be satisfied (the picture is drawn as'
      + ' computed)');
  }
  applyNudgesInForce(graph, graph.relaxIterations === 0);
  if (graph.relaxIterations > 0) {
    relaxForce(graph);
  }
}

/**
 * Runs the force-directed simulation once and returns its report.
 * @param {object} graph the graph
 * @returns {object} the report of diagram/force.js
 */
function runForce(graph) {
  var inputs = forceInputs(graph);
  return forceLib.place(inputs.members, inputs.springs, {
    iterations: graph.forceIterations,
    runs: graph.forceRuns,
    seed: graph.forceSeed,
    springLength: graph.springLength,
    direction: graph.direction,
    groupOf: function (index) {
      return inputs.groupKeys[index];
    },
    rankOf: function (index) {
      return inputs.members[index].rank;
    }
  });
}

/**
 * Collects what the force module needs: the nodes to move, the springs between them (as indexes
 * into that list) and the top-level group of every node.
 * @param {object} graph the graph
 * @returns {object} {members, indexOf, springs, groupKeys}
 */
function forceInputs(graph) {
  var members = [];
  var indexOf = {};
  var i;
  for (i = 0; i < graph.nodes.length; i++) {
    if (graph.nodes[i].dummy) {
      continue;
    }
    indexOf[graph.nodes[i].uid] = members.length;
    members.push(graph.nodes[i]);
  }
  var springs = [];
  for (i = 0; i < graph.edges.length; i++) {
    var edge = graph.edges[i];
    if (edge.self || edge.from === edge.to) {
      continue;
    }
    springs.push({
      from: indexOf[edge.fromNode.uid],
      to: indexOf[edge.toNode.uid],
      weight: edge.weight,
      constraint: edge.constraint
    });
  }
  return {members: members, indexOf: indexOf, springs: springs,
    groupKeys: groupKeysOf(graph, members)};
}

/**
 * The local relaxation after a nudge - the third mode (docs/js/diagram-plan.md §13.7).
 * <p>
 * The nudged nodes stay exactly where the caller put them (the correction is the anchor), the nodes
 * up to {@code relaxLevels} hops away follow the same force field, and everything else keeps its
 * coordinates and only acts as a fixed obstacle. So the picture loses the dent a bare nudge leaves
 * without being rearranged: a few steps of the physics, no new start, no multi-start, no random
 * numbers.
 * </p>
 * @param {object} graph the graph
 */
function relaxForce(graph) {
  var inputs = forceInputs(graph);
  var seeds = [];
  var i;
  for (i = 0; i < inputs.members.length; i++) {
    if (inputs.members[i].nudge) {
      seeds.push(i);
    }
  }
  if (seeds.length === 0) {
    graph.notes.push("layout: 'relax' needs a nudge - without one there is no local correction to"
      + ' smooth, so nothing was relaxed');
    return;
  }
  var reach = reachOf(graph, inputs, seeds);
  var movable = [];
  var movableCount = 0;
  var before = [];
  for (i = 0; i < inputs.members.length; i++) {
    var node = inputs.members[i];
    var mayMove = reach[i] && !node.fixed && !node.nudge;
    movable.push(mayMove);
    if (mayMove) {
      movableCount++;
    }
    before.push([node.x, node.y]);
  }
  if (movableCount === 0) {
    graph.notes.push('layout: relaxLevels ' + graph.relaxLevels + ' reaches no node that may move'
      + ' (the nudged node itself is the anchor) - nothing was relaxed');
    return;
  }
  var report = forceLib.relax(inputs.members, inputs.springs, {
    movable: movable,
    iterations: graph.relaxIterations,
    springLength: graph.springLength,
    direction: graph.direction,
    groupOf: function (index) {
      return inputs.groupKeys[index];
    }
  });
  var moved = [];
  for (i = 0; i < inputs.members.length; i++) {
    if (Math.abs(inputs.members[i].x - before[i][0]) > 0.05
        || Math.abs(inputs.members[i].y - before[i][1]) > 0.05) {
      moved.push(inputs.members[i].id);
    }
  }
  graph.forceRelax = {iterations: report.iterations, levels: graph.relaxLevels,
    movable: movableCount, moved: moved, unresolved: report.unresolved};
  if (report.unresolved > 0) {
    graph.notes.push('layout: ' + report.unresolved + ' pair(s) of node boxes still overlap after'
      + ' the relaxation - relaxLevels ' + graph.relaxLevels + ' does not reach far enough to move'
      + ' them apart');
  }
}

/**
 * The nodes within {@code relaxLevels} hops of the nudged ones (a nudged node counts as hop 0).
 * @param {object} graph the graph
 * @param {object} inputs {members, indexOf}
 * @param {Array<number>} seeds the indexes of the nudged nodes
 * @returns {Array<boolean>} true for a node inside the reach (indexes of {@code members})
 */
function reachOf(graph, inputs, seeds) {
  var reach = [];
  var neighbours = [];
  var i;
  for (i = 0; i < inputs.members.length; i++) {
    reach.push(false);
    neighbours.push([]);
  }
  for (i = 0; i < graph.edges.length; i++) {
    var edge = graph.edges[i];
    if (edge.self || edge.from === edge.to) {
      continue;
    }
    var a = inputs.indexOf[edge.fromNode.uid];
    var b = inputs.indexOf[edge.toNode.uid];
    if (a === undefined || b === undefined) {
      continue;
    }
    neighbours[a].push(b);
    neighbours[b].push(a);
  }
  var frontier = [];
  for (i = 0; i < seeds.length; i++) {
    reach[seeds[i]] = true;
    frontier.push(seeds[i]);
  }
  for (var level = 0; level < graph.relaxLevels; level++) {
    var next = [];
    for (i = 0; i < frontier.length; i++) {
      var list = neighbours[frontier[i]];
      for (var j = 0; j < list.length; j++) {
        if (!reach[list[j]]) {
          reach[list[j]] = true;
          next.push(list[j]);
        }
      }
    }
    frontier = next;
    if (frontier.length === 0) {
      break;
    }
  }
  return reach;
}

/**
 * The top-level group of every node, as an array parallel to the node list: the clustering force
 * keeps the members of one subgraph together, so its band stays a band.
 * @param {object} graph the graph
 * @param {Array<object>} members the nodes the simulation moves
 * @returns {Array<string|null>} the group key per node (null for an ungrouped node)
 */
function groupKeysOf(graph, members) {
  var keys = [];
  var i;
  if (!graph.model.groups.length) {
    for (i = 0; i < members.length; i++) {
      keys.push(null);
    }
    return keys;
  }
  var byId = {};
  for (i = 0; i < graph.model.groups.length; i++) {
    byId[graph.model.groups[i].id] = graph.model.groups[i];
  }
  for (i = 0; i < members.length; i++) {
    var node = members[i];
    if (!node.group || !byId[node.group]) {
      keys.push(null);
      continue;
    }
    var current = byId[node.group];
    while (current && current.parent && byId[current.parent]) {
      current = byId[current.parent];
    }
    keys.push(current ? current.id : null);
  }
  return keys;
}

/**
 * Reports the correction options a force layout cannot honour. Without this a caller would keep a
 * {@code route} or a {@code rank} and wonder why the picture ignores it (§7.2: a wish that cannot be
 * met is a note, never silence).
 * @param {object} graph the graph
 */
function noteForceIgnored(graph) {
  var ignored = [];
  if (graph.routeGiven) {
    ignored.push('route');
  }
  if (graph.backChannelsGiven) {
    ignored.push('backChannels');
  }
  if (graph.jogsGiven) {
    ignored.push('jogs');
  }
  if (hasRankWish(graph)) {
    ignored.push('rank');
  }
  if (hasOrderWish(graph)) {
    ignored.push('order');
  }
  if (ignored.length === 0) {
    return;
  }
  graph.notes.push("layout: layout 'force' places the nodes by a physics simulation and draws"
    + ' straight lines, so ' + ignored.join(', ') + " cannot be honoured and is ignored (use"
    + " layout 'layered' for ranks, ports and channels)");
}

/**
 * Whether the model or the options ask for a fixed rank.
 * @param {object} graph the graph
 * @returns {boolean} true when a node has a fixed rank
 */
function hasRankWish(graph) {
  for (var i = 0; i < graph.nodes.length; i++) {
    if (graph.nodes[i].rankFixed !== null && graph.nodes[i].rankFixed !== undefined) {
      return true;
    }
  }
  return false;
}

/**
 * Whether the model or the options ask for a fixed order inside a rank.
 * @param {object} graph the graph
 * @returns {boolean} true when a node has a fixed order
 */
function hasOrderWish(graph) {
  for (var i = 0; i < graph.nodes.length; i++) {
    if (graph.nodes[i].orderFixed !== null && graph.nodes[i].orderFixed !== undefined) {
      return true;
    }
  }
  return false;
}

/**
 * Says so when the pair budget of the simulation cut the requested iterations back.
 * @param {object} graph the graph
 * @param {object} report the report of diagram/force.js
 */
function noteForceBudget(graph, report) {
  if (report.iterations >= graph.forceIterations) {
    return;
  }
  graph.notes.push('layout: the force layout ran ' + report.iterations + ' instead of '
    + graph.forceIterations + ' iterations and ' + report.runs.length + ' run(s) - the pair budget'
    + ' bounds the work for ' + graph.nodes.length + ' nodes (docs/js/diagram-plan.md §8)');
}

/**
 * Moves the nudged nodes after the force placement, leaving every other node where it is. Unlike a
 * pin that does not re-simulate, so only the edges of that node change. An overlap it produces is
 * reported, never silently repaired (§7.2) - unless a relaxation follows, which is allowed to take
 * the overlap away again; then only what the relaxation leaves behind is reported.
 * @param {object} graph the graph
 * @param {boolean} reportOverlaps true when this run has to name the overlaps it creates
 */
function applyNudgesInForce(graph, reportOverlaps) {
  for (var i = 0; i < graph.nodes.length; i++) {
    var node = graph.nodes[i];
    if (!node.nudge || node.dummy) {
      continue;
    }
    node.x += node.nudge.dx;
    node.y += node.nudge.dy;
    if (!reportOverlaps) {
      continue;
    }
    for (var j = 0; j < graph.nodes.length; j++) {
      var other = graph.nodes[j];
      if (other === node || other.dummy) {
        continue;
      }
      var overlapX = Math.min(node.x + node.w, other.x + other.w) - Math.max(node.x, other.x);
      var overlapY = Math.min(node.y + node.h, other.y + other.h) - Math.max(node.y, other.y);
      if (overlapX > 0.5 && overlapY > 0.5) {
        graph.notes.push("layout: the nudge of '" + node.id + "' puts it "
          + round2(Math.min(overlapX, overlapY)) + ' pt into ' + "'" + other.id
          + "' - reported, not silently undone");
      }
    }
  }
}

/**
 * Jog elimination: every long edge (one with dummy nodes inside the layers it crosses) gets all of
 * its dummies on one x, so it runs as a single vertical line instead of taking a small sideways step
 * in every rank it crosses. The common x is chosen where all the crossed layers are free of node
 * boxes; when no such x exists the edge keeps its jogs (nothing is reported - the jogs are a
 * consequence of the packing, not a wish that was ignored).
 * @param {object} graph the graph
 */
function straightenLongEdges(graph) {
  var layers = graph.layers;
  for (var i = 0; i < graph.edges.length; i++) {
    var edge = graph.edges[i];
    if (edge.self || !edge.chain || edge.chain.length < 3) {
      continue;
    }
    var dummies = [];
    for (var c = 1; c < edge.chain.length - 1; c++) {
      if (edge.chain[c].dummy) {
        dummies.push(edge.chain[c]);
      }
    }
    if (dummies.length === 0) {
      continue;
    }
    var wish = medianX(dummies);
    var free = null;
    for (var d = 0; d < dummies.length; d++) {
      // Each dummy sits at a free x in its own layer (the packing put it there), so its own
      // interval always exists - that is the one to intersect, not the interval around the wish.
      var interval = freeIntervalAround(layers[dummies[d].rank], dummies[d].x);
      if (interval === null) {
        free = null;
        break;
      }
      free = free === null ? interval : intersectIntervals(free, interval);
      if (free === null) {
        break;
      }
    }
    if (free === null) {
      // No common corridor through every crossed layer: straighten the longest run of consecutive
      // dummies that does share one, so at least some of the jogs go away.
      straightenRuns(graph, dummies);
      continue;
    }
    var target = Math.min(Math.max(wish, free[0] + 2), free[1] - 2);
    if (!isFinite(target)) {
      continue;
    }
    for (var a = 0; a < dummies.length; a++) {
      dummies[a].x = target;
    }
  }
}

/**
 * Aligns the longest stretches of consecutive dummies that share a free corridor, one x per stretch.
 * @param {object} graph the graph
 * @param {Array<object>} dummies the dummies of one edge, in rank order
 */
function straightenRuns(graph, dummies) {
  var layers = graph.layers;
  var start = 0;
  var common = freeIntervalAround(layers[dummies[0].rank], dummies[0].x);
  for (var d = 1; d <= dummies.length; d++) {
    var next = null;
    if (d < dummies.length && common !== null) {
      var interval = freeIntervalAround(layers[dummies[d].rank], dummies[d].x);
      next = interval === null ? null : intersectIntervals(common, interval);
    }
    if (d === dummies.length || next === null) {
      alignRun(dummies.slice(start, d), common);
      start = d;
      common = d < dummies.length
        ? freeIntervalAround(layers[dummies[d].rank], dummies[d].x) : null;
    } else {
      common = next;
    }
  }
}

/**
 * Puts a run of dummies on one x inside the given corridor.
 * @param {Array<object>} run the dummies
 * @param {Array<number>} corridor the free interval [lo, hi], or null
 */
function alignRun(run, corridor) {
  if (run.length < 2 || corridor === null) {
    return;
  }
  var target = Math.min(Math.max(medianX(run), corridor[0] + 2), corridor[1] - 2);
  if (!isFinite(target)) {
    return;
  }
  for (var i = 0; i < run.length; i++) {
    run[i].x = target;
  }
}

/**
 * The middle x of a list of nodes (the lower middle for an even count - deterministic).
 * @param {Array<object>} nodes the nodes
 * @returns {number} the x
 */
function medianX(nodes) {
  var values = [];
  for (var i = 0; i < nodes.length; i++) {
    values.push(nodes[i].x + nodes[i].w / 2);
  }
  values.sort(function (a, b) {
    return a - b;
  });
  return values[Math.floor((values.length - 1) / 2)];
}

/**
 * The largest interval around {@code x} in which no node of the layer has its box (plus a margin).
 * @param {Array<object>} layer the layer's nodes
 * @param {number} x the wanted x
 * @returns {Array<number>|null} [lo, hi], or null when x sits inside a node box
 */
function freeIntervalAround(layer, x) {
  var margin = 5;
  var lo = -Infinity;
  var hi = Infinity;
  for (var i = 0; i < layer.length; i++) {
    var node = layer[i];
    if (node.dummy) {
      continue;
    }
    var left = node.x - margin;
    var right = node.x + node.w + margin;
    if (x > left && x < right) {
      return null;
    }
    if (right <= x) {
      lo = Math.max(lo, right);
    }
    if (left >= x) {
      hi = Math.min(hi, left);
    }
  }
  return [lo, hi];
}

/**
 * The overlap of two intervals, or null when they do not overlap.
 * @param {Array<number>} first the first interval [lo, hi]
 * @param {Array<number>} second the second interval [lo, hi]
 * @returns {Array<number>|null} the overlap
 */
function intersectIntervals(first, second) {
  var lo = Math.max(first[0], second[0]);
  var hi = Math.min(first[1], second[1]);
  return lo <= hi ? [lo, hi] : null;
}

/**
 * Packs and sweeps the layers: one strip per top-level group when the graph has groups and no pin,
 * otherwise the initial packing (a pinned node keeps its x, the others are packed around it) followed
 * by the barycenter sweeps.
 * @param {object} graph the graph
 * @param {Array<Array<object>>} layers the layers
 */
function packAndSweep(graph, layers) {
  var r;
  if (graph.model.groups.length && !hasPins(graph) && graph.groups !== 'flow') {
    placeClustered(graph, layers);
    return;
  }
  if (graph.model.groups.length && graph.groupsGiven && graph.groups === 'flow') {
    graph.notes.push("layout: groups: 'flow' places every node by the barycenter sweeps and draws a"
      + ' band around each group afterwards - the edges stay short, but a band may touch another'
      + ' and the members of a group are not kept in one column (pass groups: \'strips\' for one'
      + ' strip per group, the former default)');
  }
  for (r = 0; r < layers.length; r++) {
    var cursor = 0;
    for (var k = 0; k < layers[r].length; k++) {
      var packed = layers[r][k];
      if (packed.fixed) {
        packed.x = packed.fixed.x;
        cursor = Math.max(cursor, packed.x + packed.w + NODE_GAP);
      } else {
        packed.x = cursor;
        cursor += packed.w + NODE_GAP;
      }
    }
  }
  for (var sweep = 0; sweep < PLACE_SWEEPS; sweep++) {
    var down = (sweep % 2) === 0;
    var ranks = [];
    if (down) {
      for (r = 1; r < layers.length; r++) {
        ranks.push(r);
      }
    } else {
      for (r = layers.length - 2; r >= 0; r--) {
        ranks.push(r);
      }
    }
    for (var o = 0; o < ranks.length; o++) {
      placeLayer(layers, graph.segments, ranks[o], down);
    }
  }
}

/**
 * Moves the nudged nodes after the placement, leaving every other node where it is. This is the
 * local correction a caller wants ("move this node 16 pt to the right and look again"): unlike a pin
 * it does not re-pack the row, so only the edges of that node change. An overlap it produces is
 * reported, never silently repaired (docs/js/diagram-plan.md §7.2).
 * @param {object} graph the graph
 * @param {Array<Array<object>>} layers the layers
 */
function applyNudges(graph, layers) {
  for (var i = 0; i < graph.nodes.length; i++) {
    var node = graph.nodes[i];
    if (!node.nudge || node.dummy) {
      continue;
    }
    node.x += node.nudge.dx;
    node.y += node.nudge.dy;
    var others = layers[node.rank] || [];
    for (var o = 0; o < others.length; o++) {
      var other = others[o];
      if (other === node || other.dummy) {
        continue;
      }
      var overlap = Math.min(node.x + node.w, other.x + other.w) - Math.max(node.x, other.x);
      if (overlap > 0.5 && node.y < other.y + other.h && other.y < node.y + node.h) {
        graph.notes.push("layout: the nudge of '" + node.id + "' puts it "
          + round2(overlap) + ' pt into ' + "'" + other.id + "' - reported, not silently undone");
      }
    }
  }
}

/**
 * Turns a relative pin ({@code fixed: {dx, dy}}) into an absolute one, using the position the free
 * placement gave the node. A caller can therefore say "move this node 20 pt to the right" without
 * knowing the layout's coordinate space - the scene it sees is normalized, the pin space is not.
 * @param {object} graph the graph
 * @returns {boolean} true when at least one pin was resolved (place again)
 */
function resolveRelativePins(graph) {
  var resolved = false;
  for (var i = 0; i < graph.nodes.length; i++) {
    var node = graph.nodes[i];
    if (!node.shift || node.dummy) {
      continue;
    }
    node.fixed = {x: node.x + node.shift.dx, y: node.y + node.shift.dy};
    node.shift = null;
    resolved = true;
  }
  return resolved;
}

/**
 * Places the nodes as vertical strips, one per top-level group (plus one for ungrouped nodes).
 * <p>
 * This is what keeps a subgraph's band from overlapping a neighbour's: every top-level group owns
 * a strip, its rows are centred in it, and the strips are laid side by side with a gap larger than
 * two group paddings. Nested groups stay inside their parent's strip (their own bands may still
 * touch a sibling's - reported, docs/js/diagram-plan.md §13.3). Edges between strips are routed as
 * before. Pins disable this mode (a pin is a harder constraint than a tidy band).
 * </p>
 * @param {object} graph the graph
 * @param {Array<Array<object>>} layers the layers
 */
function placeClustered(graph, layers) {
  var groupCount = graph.model.groups.length;
  var groupIndex = {};
  var byId = {};
  for (var g = 0; g < groupCount; g++) {
    groupIndex[graph.model.groups[g].id] = g;
    byId[graph.model.groups[g].id] = graph.model.groups[g];
  }
  function clusterOrder(node) {
    if (!node.group) {
      return groupCount;
    }
    var current = byId[node.group];
    while (current && current.parent) {
      current = byId[current.parent];
    }
    return current ? groupIndex[current.id] : groupCount;
  }
  var clustersById = {};
  var clusters = [];
  for (var r = 0; r < layers.length; r++) {
    for (var i = 0; i < layers[r].length; i++) {
      var node = layers[r][i];
      if (node.dummy) {
        continue;
      }
      var order = clusterOrder(node);
      var cluster = clustersById[order];
      if (!cluster) {
        cluster = {order: order, rows: {}, width: 0, minUid: node.uid};
        clustersById[order] = cluster;
        clusters.push(cluster);
      }
      if (!cluster.rows[r]) {
        cluster.rows[r] = {nodes: [], width: 0};
      }
      cluster.rows[r].nodes.push(node);
      cluster.minUid = Math.min(cluster.minUid, node.uid);
    }
  }
  clusters.sort(function (a, b) {
    if (a.order !== b.order) {
      return a.order - b.order;
    }
    return a.minUid - b.minUid;
  });

  // The width of a cluster is its widest row.
  for (var c = 0; c < clusters.length; c++) {
    var current = clusters[c];
    var maxWidth = 0;
    for (var rank in current.rows) {
      if (!Object.prototype.hasOwnProperty.call(current.rows, rank)) {
        continue;
      }
      var row = current.rows[rank].nodes;
      var width = 0;
      for (var j = 0; j < row.length; j++) {
        width += row[j].w + (j > 0 ? NODE_GAP : 0);
      }
      current.rows[rank].width = width;
      maxWidth = Math.max(maxWidth, width);
    }
    current.width = maxWidth;
  }

  // Strips side by side. The gap has to cover the group padding on both sides plus one level of
  // padding for every nested group, otherwise two bands touch at the outside.
  var maxDepth = 0;
  for (var dg = 0; dg < groupCount; dg++) {
    var depth = 0;
    var walker = graph.model.groups[dg];
    while (walker && walker.parent) {
      depth++;
      walker = byId[walker.parent];
    }
    maxDepth = Math.max(maxDepth, depth);
  }
  var clusterGap = Math.max(CLUSTER_GAP, 2 * (maxDepth + 1) * 10 + 8);
  var offset = 0;
  for (var s = 0; s < clusters.length; s++) {
    clusters[s].offset = offset;
    offset += clusters[s].width + clusterGap;
  }

  // Each row is centred in its strip.
  for (var t = 0; t < clusters.length; t++) {
    var placedCluster = clusters[t];
    for (var placedRank in placedCluster.rows) {
      if (!Object.prototype.hasOwnProperty.call(placedCluster.rows, placedRank)) {
        continue;
      }
      var placedRow = placedCluster.rows[placedRank];
      var x = placedCluster.offset + (placedCluster.width - placedRow.width) / 2;
      for (var n = 0; n < placedRow.nodes.length; n++) {
        placedRow.nodes[n].x = x;
        x += placedRow.nodes[n].w + NODE_GAP;
      }
    }
  }

  // A dummy has no row of its own: put it on the line between its edge's endpoints.
  for (var dr = 0; dr < layers.length; dr++) {
    for (var d = 0; d < layers[dr].length; d++) {
      var dummy = layers[dr][d];
      if (!dummy.dummy) {
        continue;
      }
      var edge = dummy.edge;
      var from = edge.fromNode;
      var to = edge.toNode;
      var span = to.rank - from.rank;
      var frac = span > 0 ? (dummy.rank - from.rank) / span : 0.5;
      dummy.x = (from.x + from.w / 2) * (1 - frac) + (to.x + to.w / 2) * frac;
      avoidNodes(dummy, layers[dr]);
    }
  }
  graph.clustered = true;
}

/**
 * Moves a dummy point out of a node's box in its layer, into the nearest free gap. Without this a
 * long edge would run straight through a node that the strip placement happens to put above it.
 * @param {object} dummy the dummy node
 * @param {Array<object>} layer the layer's nodes
 */
function avoidNodes(dummy, layer) {
  var intervals = [];
  for (var i = 0; i < layer.length; i++) {
    var node = layer[i];
    if (node === dummy || node.dummy) {
      continue;
    }
    intervals.push([node.x - 4, node.x + node.w + 4]);
  }
  if (intervals.length === 0) {
    return;
  }
  intervals.sort(function (a, b) {
    return a[0] - b[0];
  });
  var gaps = [];
  var previous = -Infinity;
  for (var k = 0; k < intervals.length; k++) {
    if (intervals[k][0] > previous) {
      gaps.push([previous, intervals[k][0]]);
    }
    previous = Math.max(previous, intervals[k][1]);
  }
  gaps.push([previous, Infinity]);
  var best = dummy.x;
  var bestDistance = Infinity;
  for (var g = 0; g < gaps.length; g++) {
    var low = gaps[g][0];
    var high = gaps[g][1];
    var candidate;
    if (low === -Infinity) {
      candidate = Math.min(dummy.x, high - 6);
    } else if (high === Infinity) {
      candidate = Math.max(dummy.x, low + 6);
    } else {
      candidate = Math.min(Math.max(dummy.x, low + 6), high - 6);
    }
    var distance = Math.abs(candidate - dummy.x);
    if (distance < bestDistance) {
      bestDistance = distance;
      best = candidate;
    }
  }
  dummy.x = best;
}

/**
 * Places one layer according to the neighbour barycenters.
 * @param {Array<Array<object>>} layers the layers
 * @param {Array<object>} segments the segments
 * @param {number} rank the layer index
 * @param {boolean} down true when the reference layer is above
 */
function placeLayer(layers, segments, rank, down) {
  var layer = layers[rank];
  if (layer.length === 0) {
    return;
  }
  var reference = down ? rank - 1 : rank + 1;
  if (reference < 0 || reference >= layers.length) {
    return;
  }
  var desired = [];
  var hasPinned = false;
  for (var i = 0; i < layer.length; i++) {
    var node = layer[i];
    if (node.fixed) {
      hasPinned = true;
      desired.push(node.fixed.x + node.w / 2);
      continue;
    }
    var sum = 0;
    var count = 0;
    for (var s = 0; s < segments.length; s++) {
      var segment = segments[s];
      var other = null;
      if (down && segment.b === node && segment.a.rank === rank - 1) {
        other = segment.a;
      } else if (!down && segment.a === node && segment.b.rank === rank + 1) {
        other = segment.b;
      }
      if (other !== null) {
        sum += other.x + other.w / 2;
        count++;
      }
    }
    desired.push(count ? sum / count : node.x + node.w / 2);
  }

  var cursor = -Infinity;
  for (var k = 0; k < layer.length; k++) {
    var current = layer[k];
    if (current.fixed) {
      current.x = current.fixed.x;
      cursor = Math.max(cursor, current.x + current.w + NODE_GAP);
      continue;
    }
    var left = desired[k] - current.w / 2;
    var x = Math.max(left, cursor);
    current.x = x;
    cursor = x + current.w + NODE_GAP;
  }

  // A layer that holds a pinned node is not shifted as a whole - that would move the pin.
  if (hasPinned) {
    return;
  }

  var meanDesired = 0;
  var meanActual = 0;
  for (var d = 0; d < layer.length; d++) {
    meanDesired += desired[d];
    meanActual += layer[d].x + layer[d].w / 2;
  }
  var shift = (meanDesired - meanActual) / layer.length;
  for (var t = 0; t < layer.length; t++) {
    layer[t].x += shift;
  }
}

// ---------------------------------------------------------------------------------------------
// p5route
// ---------------------------------------------------------------------------------------------

/**
 * Routes every edge orthogonally and emits the scene items.
 * @param {object} graph the graph
 */
function route(graph) {
  graph.routed = true;
}

/**
 * Builds the scene from the placed graph: groups, edges, nodes and edge labels.
 * @param {object} graph the graph
 */
function buildScene(graph) {
  var groups = [];
  var edges = [];
  var nodes = [];
  var labels = [];

  emitGroups(graph, groups);

  if (graph.routed) {
    emitEdges(graph, edges, labels);
  }

  for (var i = 0; i < graph.nodes.length; i++) {
    if (!graph.nodes[i].dummy) {
      var before = nodes.length;
      emitNode(graph.nodes[i], nodes);
      for (var t = before; t < nodes.length; t++) {
        // nodeGroup marks every element of this node (shape *and* label), so a backend can wrap
        // them in one group and the user can select the node with a single click. nodeId stays on
        // the shape only - the box diagnostics and the bounds computation read it.
        nodes[t].nodeGroup = graph.nodes[i].id;
        if (nodes[t].kind !== 'text') {
          nodes[t].nodeId = graph.nodes[i].id;
        }
      }
    }
  }

  var items = [];
  items = items.concat(groups, edges, nodes, labels);
  graph.scene.items = items;
  graph.scene.phases = graph.scene.phases || {};
  graph.scene.phases.ranks = ranksOf(graph);
  graph.scene.phases.layers = layerIds(graph);
  graph.scene.phases.crossings = graph.crossings === undefined ? 0 : graph.crossings;
  graph.scene.phases.backwardEdges = backEdgeIds(graph);
  graph.scene.phases.channels = graph.routed ? countChannels(graph) : 0;
  graph.scene.phases.pinned = pinnedIds(graph);
  graph.scene.phases.route = graph.route;
  graph.scene.phases.curve = graph.curve;
  graph.scene.phases.curveRadius = graph.curve === 'round' ? graph.curveRadius : null;
  graph.scene.phases.curveTension = graph.curve === 'spline' ? graph.curveTension : null;
  graph.scene.phases.clustered = Boolean(graph.clustered);

  // Orient in place (about the drawing's own bounds), then either normalize to a positive origin
  // (no pins) or keep the absolute coordinates a pin asked for. A force layout already has the
  // requested direction in its flow term, so the scene is not rotated again - that would turn a
  // two-dimensional arrangement on its head.
  var internal = sceneLib.bounds(graph.scene.items);
  sceneLib.orient(graph.scene, graph.layoutMode === 'force' ? 'TB' : graph.direction, internal);
  if (hasPins(graph)) {
    applyPinTranslation(graph);
    finishWithOrigin(graph.scene, 10);
  } else {
    sceneLib.normalize(graph.scene, 10);
  }
  roundItems(graph.scene.items);
  noteSharedRoutes(graph);
  graph.scene.phases.drawnCrossings = countDrawnCrossings(graph);
  graph.scene.phases.crossingPairs = graph.crossingPairs || [];
  graph.scene.phases.boxHits = describeBoxHits(graph);
  graph.scene.phases.boxHitPairs = graph.boxHitPairs || [];
  graph.scene.phases.kinks = describeKinks(graph);
  graph.scene.phases.kinkCount = graph.kinkCount || 0;
  graph.scene.phases.drawnOverlaps = countDrawnOverlaps(graph);
  graph.scene.phases.nearOverlaps = graph.nearOverlaps || 0;
  graph.scene.phases.backBundles = buildBundles(graph);
  graph.scene.phases.layout = graph.layoutMode;
  if (graph.layoutMode === 'force') {
    describeForce(graph);
  }
}

/**
 * Writes the diagnostics of a force layout into {@code phases}: the report of the multi-start, the
 * box hits and the fact that channels and clustered strips do not exist here. A line that crosses a
 * third box is reported once with its count - a force layout has no channel to route around it.
 * @param {object} graph the graph
 */
function describeForce(graph) {
  var phases = graph.scene.phases;
  var report = graph.forceReport || {runs: [], chosen: 0, iterations: 0, unresolved: 0};
  var crossings = [];
  var boxHits = [];
  for (var i = 0; i < report.runs.length; i++) {
    crossings.push(report.runs[i].crossings);
    boxHits.push(report.runs[i].boxHits);
  }
  phases.force = {
    runs: report.runs.length,
    crossings: crossings,
    boxHits: boxHits,
    chosen: report.chosen,
    iterations: report.iterations,
    unresolved: report.unresolved
  };
  if (graph.forceRelax) {
    phases.force.relax = graph.forceRelax;
  }
  phases.channels = 0;
  phases.clustered = false;
  // A force layout draws border-to-border lines, whatever the route option says (it is named in a
  // note when it was given) - so "direct" is what the picture shows.
  phases.route = 'direct';
  if (phases.boxHits > 0) {
    graph.notes.push('layout: ' + phases.boxHits + ' edge segment(s) cross a node box - a force'
      + ' layout draws straight lines and has no channel to route around one (use layout'
      + " 'layered', a nudge or another seed; phases.boxHitPairs names them)");
  }
}

/**
 * Whether any node is pinned.
 * @param {object} graph the graph
 * @returns {boolean} true when at least one node has a fixed position
 */
function hasPins(graph) {
  for (var i = 0; i < graph.nodes.length; i++) {
    if (graph.nodes[i].fixed && !graph.nodes[i].dummy) {
      return true;
    }
  }
  return false;
}

/**
 * Translates the whole scene so that the first pinned node sits exactly on its fixed coordinates,
 * and reports pins that no longer agree after the move.
 * @param {object} graph the graph
 */
function applyPinTranslation(graph) {
  var anchor = null;
  var i;
  for (i = 0; i < graph.nodes.length; i++) {
    if (graph.nodes[i].fixed && !graph.nodes[i].dummy) {
      anchor = graph.nodes[i];
      break;
    }
  }
  if (anchor === null) {
    return;
  }
  var anchorBox = nodeItemsBounds(graph.scene, anchor.id);
  if (anchorBox === null) {
    return;
  }
  var dx = anchor.fixed.x - anchorBox.minX;
  var dy = anchor.fixed.y - anchorBox.minY;
  if (Math.abs(dx) > 0.01 || Math.abs(dy) > 0.01) {
    if (graph.direction !== 'TB') {
      // The pin was honoured in layout space; only the rotation moved it. Anchor the drawing so
      // the pinned node lands on its coordinate and say what that means for the others.
      graph.notes.push("layout: the pin of '" + anchor.id + "' is interpreted in layout space"
        + ' before the ' + graph.direction + ' rotation - the drawing is shifted so it lands on '
        + anchor.fixed.x + ',' + anchor.fixed.y);
    }
    sceneLib.translate(graph.scene.items, dx, dy);
  }
  for (i = 0; i < graph.nodes.length; i++) {
    var node = graph.nodes[i];
    if (!node.fixed || node.dummy) {
      continue;
    }
    var box = nodeItemsBounds(graph.scene, node.id);
    if (box === null) {
      continue;
    }
    if (Math.abs(box.minX - node.fixed.x) > 0.5 || Math.abs(box.minY - node.fixed.y) > 0.5) {
      graph.notes.push("layout: the pins of '" + anchor.id + "' and '" + node.id
        + "' cannot both be met - '" + node.id + "' sits at " + round2(box.minX) + ','
        + round2(box.minY) + ' instead of ' + node.fixed.x + ',' + node.fixed.y);
    }
  }
}

/**
 * The bounding box of the items tagged with a node id.
 * @param {object} scene the scene
 * @param {string} nodeId the node id
 * @returns {object|null} {minX, minY, maxX, maxY} or null
 */
function nodeItemsBounds(scene, nodeId) {
  var selected = [];
  for (var i = 0; i < scene.items.length; i++) {
    if (scene.items[i].nodeId === nodeId) {
      selected.push(scene.items[i]);
    }
  }
  if (selected.length === 0) {
    return null;
  }
  var box = sceneLib.bounds(selected);
  return box.empty ? null : box;
}

/**
 * Sets width/height and a (possibly negative) origin, without moving the items. This is what a
 * pinned drawing needs: the content stays where the pins put it, and the view box grows around it.
 * @param {object} scene the scene (modified)
 * @param {number} padding the margin
 */
function finishWithOrigin(scene, padding) {
  var box = sceneLib.bounds(scene.items);
  if (box.empty) {
    scene.originX = 0;
    scene.originY = 0;
    scene.width = padding * 2;
    scene.height = padding * 2;
    return;
  }
  scene.originX = sceneLib.round(box.minX - padding);
  scene.originY = sceneLib.round(box.minY - padding);
  scene.width = sceneLib.round(box.maxX - box.minX + padding * 2);
  scene.height = sceneLib.round(box.maxY - box.minY + padding * 2);
}

/**
 * Rounds to a tenth of a point (for notes).
 * @param {number} value the value
 * @returns {number} the rounded value
 */
function round2(value) {
  return sceneLib.round(value);
}

/**
 * Emits the group bands (subgraphs) behind everything else, inner groups first.
 * @param {object} graph the graph
 * @param {Array<object>} out the item list to append to
 */
function emitGroups(graph, out) {
  if (!graph.model.groups.length) {
    return;
  }
  var memberNodes = {};
  for (var i = 0; i < graph.nodes.length; i++) {
    var node = graph.nodes[i];
    if (node.dummy || !node.group) {
      continue;
    }
    if (!memberNodes[node.group]) {
      memberNodes[node.group] = [];
    }
    memberNodes[node.group].push(node);
  }
  var byId = {};
  for (var g = 0; g < graph.model.groups.length; g++) {
    byId[graph.model.groups[g].id] = graph.model.groups[g];
  }
  var depthOf = {};
  for (var key in byId) {
    if (!Object.prototype.hasOwnProperty.call(byId, key)) {
      continue;
    }
    var depth = 0;
    var current = byId[key];
    while (current && current.parent) {
      depth++;
      current = byId[current.parent];
    }
    depthOf[key] = depth;
  }
  var order = graph.model.groups.slice().sort(function (a, b) {
    return (depthOf[a.id] - depthOf[b.id]);
  });
  var boxes = {};
  for (var o = 0; o < order.length; o++) {
    var box = groupBox(graph, order[o].id, byId, memberNodes, boxes);
    if (box === null) {
      continue;
    }
    boxes[order[o].id] = box;
    out.push(sceneLib.group(order[o].id, box.x, box.y, box.w, box.h, order[o].label, 9));
  }
  noteOverlappingGroups(graph, order, boxes);
}

/**
 * Records group bands that overlap - a first-draft limitation, because group membership guides the
 * ordering but not yet the coordinates (docs/js/diagram-plan.md §13.3, M7).
 * @param {object} graph the graph
 * @param {Array<object>} order the groups in draw order
 * @param {object} boxes the boxes by group id
 */
function noteOverlappingGroups(graph, order, boxes) {var byId = {};
  for (var g = 0; g < graph.model.groups.length; g++) {
    byId[graph.model.groups[g].id] = graph.model.groups[g];
  }
  function isAncestor(ancestorId, childId) {
    var current = byId[childId];
    while (current && current.parent) {
      if (current.parent === ancestorId) {
        return true;
      }
      current = byId[current.parent];
    }
    return false;
  }
  for (var i = 0; i < order.length; i++) {
    for (var j = i + 1; j < order.length; j++) {
      if (isAncestor(order[i].id, order[j].id) || isAncestor(order[j].id, order[i].id)) {
        continue; // a parent band necessarily contains its child band
      }
      var a = boxes[order[i].id];
      var b = boxes[order[j].id];
      if (!a || !b) {
        continue;
      }
      if (a.x < b.x + b.w - 0.5 && b.x < a.x + a.w - 0.5
          && a.y < b.y + b.h - 0.5 && b.y < a.y + a.h - 0.5) {
        graph.notes.push("layout: the subgraphs '" + order[i].label + "' and '" + order[j].label
          + "' overlap - in groups: 'flow' a band follows the nodes, in 'strips' every top-level"
          + ' group gets its own strip and only nested groups inside one strip can still touch'
          + ' (docs/js/diagram-plan.md §13.3)');
      }
    }
  }
}

/**
 * Computes the bounding box of a group (its nodes and its child groups).
 * @param {object} graph the graph
 * @param {string} id the group id
 * @param {object} byId the groups by id
 * @param {object} memberNodes nodes by group id
 * @param {object} boxes already computed child boxes
 * @returns {object|null} {x, y, w, h} or null when the group is empty
 */
function groupBox(graph, id, byId, memberNodes, boxes) {
  var minX = Infinity;
  var minY = Infinity;
  var maxX = -Infinity;
  var maxY = -Infinity;
  var nodes = memberNodes[id] || [];
  var i;
  for (i = 0; i < nodes.length; i++) {
    minX = Math.min(minX, nodes[i].x);
    minY = Math.min(minY, nodes[i].y);
    maxX = Math.max(maxX, nodes[i].x + nodes[i].w);
    maxY = Math.max(maxY, nodes[i].y + nodes[i].h);
  }
  for (var key in byId) {
    if (Object.prototype.hasOwnProperty.call(byId, key) && byId[key].parent === id) {
      if (!boxes[key]) {
        boxes[key] = groupBox(graph, key, byId, memberNodes, boxes);
      }
      if (boxes[key]) {
        minX = Math.min(minX, boxes[key].x);
        minY = Math.min(minY, boxes[key].y);
        maxX = Math.max(maxX, boxes[key].x + boxes[key].w);
        maxY = Math.max(maxY, boxes[key].y + boxes[key].h);
      }
    }
  }
  if (minX === Infinity) {
    return null;
  }
  var pad = 10;
  return {x: minX - pad, y: minY - pad - 12, w: maxX - minX + 2 * pad, h: maxY - minY + 2 * pad + 12};
}

/**
 * Emits the node shape and its label lines.
 * @param {object} node the node
 * @param {Array<object>} out the item list
 */
function emitNode(node, out) {
  var x = node.x;
  var y = node.y;
  var w = node.w;
  var h = node.h;
  var style = {stroke: sceneLib.STROKE, fill: '#ffffff', lineWidth: 1, rx: 0};
  var shape = node.shape;
  if (shape === 'round') {
    out.push(sceneLib.rect(x, y, w, h, {stroke: style.stroke, fill: style.fill,
      lineWidth: 1, rx: Math.min(6, h / 3)}));
  } else if (shape === 'stadium') {
    out.push(sceneLib.rect(x, y, w, h, {stroke: style.stroke, fill: style.fill,
      lineWidth: 1, rx: h / 2}));
  } else if (shape === 'diamond') {
    out.push(sceneLib.polygon([[x + w / 2, y], [x + w, y + h / 2], [x + w / 2, y + h],
      [x, y + h / 2]], {stroke: style.stroke, fill: style.fill, lineWidth: 1}));
  } else if (shape === 'circle') {
    out.push(sceneLib.ellipse(x + w / 2, y + h / 2, w / 2, h / 2,
      {stroke: style.stroke, fill: style.fill, lineWidth: 1}));
  } else if (shape === 'hexagon') {
    var inset = Math.min(w / 4, 16);
    out.push(sceneLib.polygon([[x + inset, y], [x + w - inset, y], [x + w, y + h / 2],
      [x + w - inset, y + h], [x + inset, y + h], [x, y + h / 2]],
      {stroke: style.stroke, fill: style.fill, lineWidth: 1}));
  } else if (shape === 'subroutine') {
    out.push(sceneLib.rect(x, y, w, h, {stroke: style.stroke, fill: style.fill, lineWidth: 1}));
    out.push(sceneLib.path([[x + 6, y], [x + 6, y + h]],
      {stroke: style.stroke, fill: null, lineWidth: 0.8}));
    out.push(sceneLib.path([[x + w - 6, y], [x + w - 6, y + h]],
      {stroke: style.stroke, fill: null, lineWidth: 0.8}));
  } else if (shape === 'cylinder') {
    out.push(sceneLib.rect(x, y + 4, w, h - 8, {stroke: style.stroke, fill: style.fill,
      lineWidth: 1}));
    out.push(sceneLib.ellipse(x + w / 2, y + 4, w / 2, 4,
      {stroke: style.stroke, fill: style.fill, lineWidth: 1}));
    out.push(sceneLib.ellipse(x + w / 2, y + h - 4, w / 2, 4,
      {stroke: style.stroke, fill: style.fill, lineWidth: 1}));
  } else if (shape === 'start') {
    out.push(sceneLib.ellipse(x + w / 2, y + h / 2, w / 2, h / 2,
      {stroke: style.stroke, fill: style.stroke, lineWidth: 1}));
  } else if (shape === 'end') {
    out.push(sceneLib.ellipse(x + w / 2, y + h / 2, w / 2, h / 2,
      {stroke: style.stroke, fill: '#ffffff', lineWidth: 1}));
    out.push(sceneLib.ellipse(x + w / 2, y + h / 2, w * 0.28, h * 0.28,
      {stroke: style.stroke, fill: style.stroke, lineWidth: 1}));
  } else {
    out.push(sceneLib.rect(x, y, w, h, {stroke: style.stroke, fill: style.fill, lineWidth: 1}));
  }
  var lineHeight = FONT_SIZE * 1.25;
  var totalHeight = node.lines.length * lineHeight;
  for (var i = 0; i < node.lines.length; i++) {
    var lineY = y + h / 2 - totalHeight / 2 + lineHeight * (i + 0.5);
    out.push(sceneLib.text(x + w / 2, lineY, node.lines[i],
      {size: FONT_SIZE, anchor: 'middle', baseline: 'middle'}));
  }
}

/**
 * Routes and emits every edge.
 * @param {object} graph the graph
 * @param {Array<object>} out the item list for the edge paths
 * @param {Array<object>} labels the item list for the edge labels
 */
function emitEdges(graph, out, labels) {
  if (graph.layoutMode === 'force') {
    emitEdgesForce(graph, out, labels);
    return;
  }
  var byGap = segmentsByGap(graph.segments, graph.layers.length);
  assignPorts(graph);
  var assignment = assignLanes(byGap, graph.layers, graph.route, graph);
  graph.gapLanes = assignment.laneCounts;
  var lanes = assignment.lanes;
  if (graph.route === 'direct') {
    graph.notes.push("layout: route 'direct' draws straight lines between the node borders -"
      + ' a straight line may cross a node box, which is expected and not reported');
  }
  if (graph.route === 'smooth') {
    graph.notes.push("layout: route 'smooth' draws one stubbed curve per edge - side and back edges"
      + ' keep the orthogonal channel (a curve around a box needs one),'
      + " and 'curve: off' leaves the two stubs as a polyline");
  }
  graph.routes = [];
  var backLanes = assignBackLanes(graph);
  for (var i = 0; i < graph.edges.length; i++) {
    var edge = graph.edges[i];
    var style = edgeStyle(edge);
    if (edge.self) {
      routeSelfLoop(graph, edge, style, out, labels);
      continue;
    }
    if (graph.route === 'direct') {
      routeDirect(graph, edge, style, out, labels);
      continue;
    }
    if (graph.route === 'smooth' && !edge.side && !edge.back && edge.chain
        && edge.chain.length === 2) {
      routeSmooth(graph, edge, style, out, labels);
      continue;
    }
    if (edge.side || edge.back) {
      routeSide(graph, edge, style, backLanes[edge.index], out, labels);
      continue;
    }
    routeForward(graph, edge, style, lanes, out, labels);
  }
  emitBundles(graph, out);
}

/**
 * Routes and emits the edges of a force layout: one straight line from the source's border to the
 * target's border (a forced port still wins, a forced bend is inserted), with the arrowheads, the
 * mid-edge arrowhead and the label. There are no channels and no lanes - the placement is what
 * keeps the picture readable, and a line that crosses a third box is counted and reported instead of
 * being routed around (that would need the layered strategy).
 * @param {object} graph the graph
 * @param {Array<object>} out the item list for the edge paths
 * @param {Array<object>} labels the item list for the edge labels
 */
function emitEdgesForce(graph, out, labels) {
  graph.routes = [];
  for (var i = 0; i < graph.edges.length; i++) {
    var edge = graph.edges[i];
    var style = edgeStyle(edge);
    if (edge.self) {
      routeSelfLoop(graph, edge, style, out, labels);
      continue;
    }
    var before = out.length;
    routeDirect(graph, edge, style, out, labels);
    // routeDirect appends the path first, then its arrowheads: the path is the item at `before`.
    var item = out[before];
    if (!item || !item.points) {
      continue;
    }
    addMidArrow(graph, edge, item.points, out);
  }
}

/**
 * Reports every node box a routed line crosses, by name, and what would clear it - the diagnosis a
 * correction loop needs (docs/js/diagram-plan.md §13.7).
 * <p>
 * The count is exact; the list is capped. Every entry names the edge, the box and a segment
 * midpoint, and carries an <b>escape</b>: the smallest shift of that box (in points) that would take
 * the line out of it. That is the difference between "2 edge segments cross a node box" and
 * "'LegacyController' -> 'LegacyModule' runs through 'Mod63590'; moving it by (0,-37) would clear
 * the line" - the second sentence is a correction, the first is a number.
 * </p>
 * @param {object} graph the graph
 * @returns {number} the number of (edge, box) hits
 */
function describeBoxHits(graph) {
  var boxes = [];
  var i;
  for (i = 0; i < graph.scene.items.length; i++) {
    var item = graph.scene.items[i];
    if (item.kind === 'rect' && item.nodeId) {
      boxes.push({id: item.nodeId, x: item.x, y: item.y, w: item.w, h: item.h});
    }
  }
  var routes = graph.routes || [];
  var pairs = [];
  var total = 0;
  for (i = 0; i < routes.length; i++) {
    var route = routes[i];
    var points = route.item.points;
    for (var s = 1; s < points.length; s++) {
      for (var b = 0; b < boxes.length; b++) {
        var box = boxes[b];
        if (box.id === route.from || box.id === route.to) {
          continue;
        }
        var span = boxHit(points[s - 1], points[s], box);
        if (span === null) {
          continue;
        }
        total++;
        if (pairs.length < MAX_BOX_HITS) {
          var escapes = escapeOf(points[s - 1], points[s], box);
          pairs.push({edge: routeName(route), node: box.id,
            at: [round2(points[s - 1][0] + (points[s][0] - points[s - 1][0])
                * (span[0] + span[1]) / 2),
              round2(points[s - 1][1] + (points[s][1] - points[s - 1][1])
                * (span[0] + span[1]) / 2)],
            escape: escapes.length ? escapes[0] : null, escapes: escapes});
        }
      }
    }
  }
  graph.boxHitPairs = pairs;
  return total;
}

/** How much a suggested escape overshoots, so that applying the rounded value really clears. */
var ESCAPE_MARGIN = 0.5;

/**
 * The shifts of a box that would take a segment out of it - one candidate per direction, the
 * shortest first.
 * <p>
 * The shortest one is not always the one a reader wants (moving a box 17 pt down can clear a line
 * that 34 pt up would also clear), so all four are reported: the caller picks the one that fits the
 * picture. The distance is bisected and then overshot by {@code ESCAPE_MARGIN}, so applying the
 * rounded value of a suggestion really does clear the line.
 * </p>
 * @param {Array<number>} p the segment start
 * @param {Array<number>} q the segment end
 * @param {object} box {x, y, w, h}
 * @returns {Array<object>} [{dx, dy}] - up to four candidates, sorted by distance
 */
function escapeOf(p, q, box) {
  var directions = [[-1, 0], [1, 0], [0, -1], [0, 1]];
  var found = [];
  for (var d = 0; d < directions.length; d++) {
    var dirX = directions[d][0];
    var dirY = directions[d][1];
    var hi = 1;
    while (hi < 2000 && shiftedHit(p, q, box, dirX * hi, dirY * hi)) {
      hi *= 2;
    }
    if (hi >= 2000) {
      continue;
    }
    var lo = 0;
    for (var step = 0; step < 24; step++) {
      var mid = (lo + hi) / 2;
      if (shiftedHit(p, q, box, dirX * mid, dirY * mid)) {
        lo = mid;
      } else {
        hi = mid;
      }
    }
    var distance = hi + ESCAPE_MARGIN;
    found.push({distance: distance, dx: round2(dirX * distance), dy: round2(dirY * distance)});
  }
  found.sort(function (a, b) {
    return a.distance - b.distance;
  });
  var out = [];
  for (var f = 0; f < found.length; f++) {
    out.push({dx: found[f].dx, dy: found[f].dy});
  }
  return out;
}

/**
 * Whether a segment crosses a box that was shifted.
 * @param {Array<number>} p the segment start
 * @param {Array<number>} q the segment end
 * @param {object} box {x, y, w, h}
 * @param {number} dx the shift in x
 * @param {number} dy the shift in y
 * @returns {boolean} true when it still crosses
 */
function shiftedHit(p, q, box, dx, dy) {
  return boxHit(p, q, {x: box.x + dx, y: box.y + dy, w: box.w, h: box.h}) !== null;
}

/**
 * The kink of every chain: a node with exactly two neighbours, and the angle between its two edges
 * as seen from the node (0° = straight, 180° = the two edges fold back on each other).
 * <p>
 * A reader sees "this chain is kinked" at once; a script sees coordinates. This is the number in
 * between, sorted sharpest first and capped - the entry point for "the chain LegacyService ->
 * Mod82250 -> Mod82270 -> LegacyModule should be one line" (docs/js/diagram-plan.md §13.7).
 * </p>
 * @param {object} graph the graph
 * @returns {Array<object>} [{node, neighbours, degrees}], the sharpest first
 */
function describeKinks(graph) {
  var neighbours = {};
  var i;
  for (i = 0; i < graph.edges.length; i++) {
    var edge = graph.edges[i];
    if (edge.self || edge.from === edge.to) {
      continue;
    }
    addNeighbour(neighbours, edge.from, edge.to);
    addNeighbour(neighbours, edge.to, edge.from);
  }
  var list = [];
  for (i = 0; i < graph.nodes.length; i++) {
    var node = graph.nodes[i];
    if (node.dummy) {
      continue;
    }
    var entry = neighbours[node.id];
    if (!entry || entry.length !== 2) {
      continue;
    }
    var a = graph.byId[entry[0]];
    var b = graph.byId[entry[1]];
    if (!a || !b) {
      continue;
    }
    list.push({node: node.id, neighbours: [entry[0], entry[1]],
      degrees: angleAt(node, a, b)});
  }
  list.sort(function (x, y) {
    if (x.degrees !== y.degrees) {
      return y.degrees - x.degrees;
    }
    return x.node < y.node ? -1 : (x.node > y.node ? 1 : 0);
  });
  graph.kinkCount = list.length;
  return list.slice(0, MAX_KINKS);
}

/**
 * Appends a neighbour to a node's list, at most once.
 * @param {object} neighbours the table by node id
 * @param {string} id the node
 * @param {string} other its neighbour
 */
function addNeighbour(neighbours, id, other) {
  if (!neighbours[id]) {
    neighbours[id] = [];
  }
  if (neighbours[id].indexOf(other) < 0) {
    neighbours[id].push(other);
  }
}

/**
 * The angle at {@code node} between the lines to two neighbours: 0 when the three centres are on one
 * line, 180 when the two lines fold back.
 * @param {object} node the middle node
 * @param {object} a the one neighbour
 * @param {object} b the other neighbour
 * @returns {number} the angle in degrees (rounded to a tenth)
 */
function angleAt(node, a, b) {
  var v1x = a.x + a.w / 2 - (node.x + node.w / 2);
  var v1y = a.y + a.h / 2 - (node.y + node.h / 2);
  var v2x = b.x + b.w / 2 - (node.x + node.w / 2);
  var v2y = b.y + b.h / 2 - (node.y + node.h / 2);
  var l1 = Math.sqrt(v1x * v1x + v1y * v1y);
  var l2 = Math.sqrt(v2x * v2x + v2y * v2y);
  if (l1 < 1e-9 || l2 < 1e-9) {
    return 0;
  }
  var cos = (v1x * v2x + v1y * v2y) / (l1 * l2);
  cos = Math.min(1, Math.max(-1, cos));
  return round2(180 - Math.acos(cos) * 180 / Math.PI);
}

/**
 * Numbers every edge port before routing: edges that leave the same side of one node - or reach
 * the same side - get distinct points, so their stubs and arrowheads never sit exactly on top of
 * each other. A single edge keeps the middle of its side, and a forced port keeps the side it asked
 * for (it only moves along that side). Back/same-rank edges are numbered a second time per gap,
 * because their horizontal runs share one gap and two runs at the same y draw one line.
 * @param {object} graph the graph
 */
function assignPorts(graph) {
  var edges = graph.edges;
  var bySide = {};
  var byGap = {};
  var bundleFirst = {};
  var i;
  var edge;
  for (i = 0; i < edges.length; i++) {
    edge = edges[i];
    if (!sharesPorts(graph, edge)) {
      continue;
    }
    edge.exitSide = edge.exit || 's';
    edge.entrySide = edge.entry || 'n';
    collect(bySide, portKey(edge.from, edge.exitSide), edge);
    if (!edge.bundle) {
      collect(bySide, portKey(edge.to, edge.entrySide), edge);
    } else if (!bundleFirst[edge.bundle]) {
      // A bundle arrives as ONE line: the first member represents it in the target's port group.
      bundleFirst[edge.bundle] = edge;
      collect(bySide, portKey(edge.to, edge.entrySide), edge);
    }
    if (edge.side || edge.back) {
      collect(byGap, 'd' + edge.fromNode.rank, edge);
      collect(byGap, 'a' + edge.toNode.rank, edge);
    }
  }
  sortPortGroups(bySide);
  for (i = 0; i < edges.length; i++) {
    edge = edges[i];
    if (!sharesPorts(graph, edge)) {
      continue;
    }
    edge.exitSpread = spreadOf(bySide[portKey(edge.from, edge.exitSide)], edge);
    var entryOwner = edge.bundle ? bundleFirst[edge.bundle] : edge;
    edge.entrySpread = spreadOf(bySide[portKey(edge.to, edge.entrySide)], entryOwner);
    edge.exitRun = (edge.side || edge.back) ? spreadOf(byGap['d' + edge.fromNode.rank], edge) : null;
    edge.entryRun = (edge.side || edge.back) ? spreadOf(byGap['a' + edge.toNode.rank], edge) : null;
  }
}

/**
 * Orders the ports of every node side by where the other end of the edge sits, so the edge that comes
 * from further left also takes the left port. Without this the ports follow the input order and two
 * edges that are already in the right order cross once right in front of the node - the crossing is
 * pure bookkeeping, not geometry (port ordering, docs/js/diagram-plan.md §7.1).
 * @param {object} bySide the port groups, keyed 'nodeId|side'
 */
function sortPortGroups(bySide) {
  for (var key in bySide) {
    if (!Object.prototype.hasOwnProperty.call(bySide, key)) {
      continue;
    }
    var group = bySide[key];
    var nodeId = key.slice(0, key.lastIndexOf('|'));
    var side = key.slice(key.lastIndexOf('|') + 1);
    group.sort(function (a, b) {
      var da = neighbourCoordinate(a, nodeId, side);
      var db = neighbourCoordinate(b, nodeId, side);
      if (da !== db) {
        return da - db;
      }
      return a.index - b.index;
    });
  }
}

/**
 * The coordinate of an edge's other end along a node side: the first run after leaving the node, or
 * the last run before reaching it, or the centre of the node at the far end.
 * @param {object} edge the edge
 * @param {string} nodeId the node whose side is ordered
 * @param {string} side n | e | s | w
 * @returns {number} x for n/s, y for e/w
 */
function neighbourCoordinate(edge, nodeId, side) {
  var horizontal = side === 'n' || side === 's';
  var leaving = edge.from === nodeId;
  var chain = edge.chain;
  var node = null;
  if (chain && chain.length > 2) {
    node = leaving ? chain[1] : chain[chain.length - 2];
  }
  if (node === null) {
    node = leaving ? edge.toNode : edge.fromNode;
  }
  return horizontal ? node.x + node.w / 2 : node.y + node.h / 2;
}

/**
 * Whether an edge takes part in the port spreading: no self loop, no forced bends (both keep the
 * centre of a side) and nothing in {@code direct} mode, where every line is drawn as asked.
 * @param {object} graph the graph
 * @param {object} edge the edge
 * @returns {boolean} true when the edge takes a port of the box
 */
function sharesPorts(graph, edge) {
  return !edge.self && !edge.waypoints && graph.route !== 'direct';
}

/**
 * The key of a node side in the port table.
 * @param {string} nodeId the node id
 * @param {string} side n | e | s | w
 * @returns {string} the key
 */
function portKey(nodeId, side) {
  return nodeId + '|' + side;
}

/**
 * Appends an edge to the list under a key.
 * @param {object} map the table
 * @param {string} key the key
 * @param {object} edge the edge
 */
function collect(map, key, edge) {
  if (!map[key]) {
    map[key] = [];
  }
  map[key].push(edge);
}

/**
 * The index of an edge inside its group, plus the group size.
 * @param {Array<object>} list the group
 * @param {object} edge the edge
 * @returns {object} {index, count}
 */
function spreadOf(list, edge) {
  return {index: list.indexOf(edge), count: list.length};
}

/**
 * The line style of an edge.
 * @param {object} edge the edge
 * @returns {object} the scene style
 */
function edgeStyle(edge) {
  return {
    stroke: sceneLib.STROKE,
    fill: null,
    lineWidth: edge.style === 'thick' ? 2 : 1,
    dash: edge.style === 'dotted' ? [2, 2] : null
  };
}

/**
 * The curve descriptor of the current run, or null for straight segments.
 * @param {object} graph the graph
 * @returns {object|null} {kind: 'round', radius} or {kind: 'spline', tension}
 */
function curveSpec(graph) {
  if (graph.curve === 'round') {
    return {kind: 'round', radius: graph.curveRadius};
  }
  if (graph.curve === 'spline') {
    return {kind: 'spline', tension: graph.curveTension};
  }
  return null;
}

/**
 * Builds the path item of an edge, smoothed when the run asked for it.
 * @param {object} graph the graph
 * @param {Array<Array<number>>} points the support points
 * @param {object} style the line style
 * @returns {object} the scene item
 */
function edgePath(graph, points, style) {
  return sceneLib.path(points, style, curveSpec(graph));
}

/**
 * Assigns a lane (a distinct channel y) to each segment within its gap, in a stable order.
 * <p>
 * In the default {@code orthogonal} mode every segment gets its own lane - the slightly offset
 * horizontal lines. In {@code tree} mode the segments that leave the same node are one bus: they
 * share a lane, so siblings split at one branch point. Two buses share a lane only when their x
 * ranges do not overlap, so unrelated families stay visibly apart.
 * </p>
 * <p>
 * The order of the lanes is the nesting rule of a one-layer channel router (docs/js/diagram-plan.md
 * §7.1): where two runs overlap in x, the one whose vertical end would cut the other's horizontal run
 * has to take the deeper lane. A fan of six edges out of one node is exactly the case where the
 * naive order (by exit x) is useless - all six exits sit on the same node - and the target x alone
 * points the wrong way for the half that turns right. The rule is applied pairwise and summed into a
 * depth per segment, so the comparator stays a total order.
 * </p>
 * @param {Array<Array<object>>} byGap the segments by gap
 * @param {Array<Array<object>>} layers the layers
 * @param {string} mode 'orthogonal' | 'tree' | 'direct'
 * @param {object} graph the graph (the port positions come from it)
 * @returns {object} {lanes: lane by segment key, laneCounts: lanes per gap}
 */
function assignLanes(byGap, layers, mode, graph) {
  var lanes = {};
  var laneCounts = [];
  for (var gap = 0; gap < byGap.length; gap++) {
    var list = byGap[gap].slice();
    if (mode === 'tree') {
      laneCounts.push(assignBusLanes(list, lanes));
      continue;
    }
    var runs = [];
    for (var r = 0; r < list.length; r++) {
      var ends = runEnds(list[r], graph);
      runs.push({seg: list[r], x1: ends[0], x2: ends[1],
        lo: Math.min(ends[0], ends[1]), hi: Math.max(ends[0], ends[1]), depth: 0});
    }
    for (var a = 0; a < runs.length; a++) {
      for (var b = 0; b < runs.length; b++) {
        if (a === b) {
          continue;
        }
        if (deeperCrosses(runs[a], runs[b])) {
          runs[a].depth++;      // b must not sit deeper than a: a takes the deeper lane
        }
        if (deeperCrosses(runs[b], runs[a])) {
          runs[a].depth--;
        }
      }
    }
    runs.sort(function (one, other) {
      if (one.depth !== other.depth) {
        return one.depth - other.depth;
      }
      if (one.x1 !== other.x1) {
        return one.x1 - other.x1;
      }
      if (one.x2 !== other.x2) {
        return one.x2 - other.x2;
      }
      return one.seg.a.uid - other.seg.a.uid;
    });
    for (var l = 0; l < runs.length; l++) {
      lanes[segmentKey(runs[l].seg)] = l;
    }
    laneCounts.push(runs.length);
  }
  return {lanes: lanes, laneCounts: laneCounts};
}

/**
 * The x of both ends of a segment where it actually touches its nodes: the port of a spread side, the
 * middle of the side otherwise, the point of a dummy.
 * @param {object} seg the segment
 * @param {object} graph the graph
 * @returns {Array<number>} the x of the start and of the end
 */
function runEnds(seg, graph) {
  var edge = seg.edge;
  return [nodeEndX(seg.a, edge === null ? null : edge, false),
    nodeEndX(seg.b, edge === null ? null : edge, true)];
}

/**
 * The x where an edge meets one of its nodes.
 * @param {object} node the node (may be a dummy)
 * @param {object} edge the edge
 * @param {boolean} atTarget true for the target end, false for the source end
 * @returns {number} the x
 */
function nodeEndX(node, edge, atTarget) {
  var centre = node.x + (node.w || 0) / 2;
  if (!edge || node.dummy) {
    return centre;
  }
  var side = atTarget ? edge.entrySide : edge.exitSide;
  if (side !== 'n' && side !== 's') {
    return centre;
  }
  var spread = atTarget ? edge.entrySpread : edge.exitSpread;
  return sidePoint(node, side, spread ? spread.index : undefined,
    spread ? spread.count : undefined)[0];
}

/**
 * Whether putting {@code deeper} on a deeper lane than {@code shallower} makes their two runs cross:
 * the vertical end of one cuts the horizontal run of the other.
 * @param {object} shallower the run on the upper lane
 * @param {object} deeper the run on the lower lane
 * @returns {boolean} true when that order crosses
 */
function deeperCrosses(shallower, deeper) {
  var eps = 1.5;
  if (deeper.x1 > shallower.lo + eps && deeper.x1 < shallower.hi - eps) {
    return true;      // the deeper run drops into the upper run's span
  }
  return shallower.x2 > deeper.lo + eps && shallower.x2 < deeper.hi - eps;
}

/**
 * Groups the segments of one gap by their source node and packs the resulting buses into lanes
 * whose x ranges do not overlap.
 * @param {Array<object>} list the segments of one gap
 * @param {object} lanes the lane map to fill (segment key -> lane)
 * @returns {number} the number of lanes used
 */
function assignBusLanes(list, lanes) {
  var bySource = {};
  var groups = [];
  for (var i = 0; i < list.length; i++) {
    var segment = list[i];
    var key = segment.a.uid;
    var group = bySource[key];
    if (!group) {
      group = {source: segment.a, segments: [], minX: Infinity, maxX: -Infinity};
      bySource[key] = group;
      groups.push(group);
    }
    group.segments.push(segment);
    var ax = segment.a.x + segment.a.w / 2;
    var bx = segment.b.x + segment.b.w / 2;
    group.minX = Math.min(group.minX, ax, bx);
    group.maxX = Math.max(group.maxX, ax, bx);
  }
  groups.sort(function (a, b) {
    if (a.minX !== b.minX) {
      return a.minX - b.minX;
    }
    return a.source.uid - b.source.uid;
  });
  var laneRanges = [];
  for (var g = 0; g < groups.length; g++) {
    var current = groups[g];
    var lane = -1;
    for (var l = 0; l < laneRanges.length; l++) {
      if (!rangesOverlap(laneRanges[l], current.minX, current.maxX)) {
        lane = l;
        break;
      }
    }
    if (lane < 0) {
      lane = laneRanges.length;
      laneRanges.push([]);
    }
    laneRanges[lane].push([current.minX, current.maxX]);
    for (var s = 0; s < current.segments.length; s++) {
      lanes[segmentKey(current.segments[s])] = lane;
    }
  }
  return laneRanges.length;
}

/**
 * Whether a new interval overlaps any interval already on a lane.
 * @param {Array<Array<number>>} ranges the intervals on the lane
 * @param {number} minX the new interval start
 * @param {number} maxX the new interval end
 * @returns {boolean} true when it overlaps
 */
function rangesOverlap(ranges, minX, maxX) {
  for (var i = 0; i < ranges.length; i++) {
    if (minX < ranges[i][1] - 0.5 && ranges[i][0] < maxX - 0.5) {
      return true;
    }
  }
  return false;
}

/**
 * A stable key for a segment.
 * @param {object} segment the segment
 * @returns {string} the key
 */
function segmentKey(segment) {
  return segment.edge.index + ':' + segment.a.uid + ':' + segment.b.uid;
}

/**
 * The channel on the right of every back/same-rank edge. In {@code 'edge'} mode (the default) each
 * edge gets its own channel, so every line is visible on its own. In {@code 'target'} mode all edges
 * that reach the same node share one channel: they run as one trunk and split in the target's gap,
 * which keeps the right margin narrow when many edges point at one node.
 * @param {object} graph the graph
 * @returns {object} the lane info by edge index: {lane, bundle}
 */
function assignBackLanes(graph) {
  var lanes = {};
  var list = [];
  var i;
  for (i = 0; i < graph.edges.length; i++) {
    var edge = graph.edges[i];
    if (!edge.self && (edge.side || edge.back)) {
      list.push(edge);
    }
  }
  if (graph.backChannels !== 'target') {
    graph.backChannelCount = list.length;
    for (i = 0; i < list.length; i++) {
      lanes[list[i].index] = {lane: i, bundle: null};
    }
    return lanes;
  }
  var byTarget = {};
  var targets = [];
  for (i = 0; i < list.length; i++) {
    var to = list[i].to;
    if (!byTarget[to]) {
      byTarget[to] = [];
      targets.push(to);
    }
    byTarget[to].push(list[i]);
  }
  graph.backChannelCount = targets.length;
  for (var t = 0; t < targets.length; t++) {
    var group = byTarget[targets[t]];
    var bundle = targets[t];
    for (var g = 0; g < group.length; g++) {
      lanes[group[g].index] = {lane: t, bundle: group.length > 1 ? bundle : null};
      group[g].bundle = group.length > 1 ? bundle : null;
    }
  }
  return lanes;
}

/**
 * Removes the steps an edge takes right after leaving its source port or right before it reaches its
 * target port: when the run is only a few points away from the port, the port itself moves onto the
 * run (it stays on its side of the box), and the edge becomes a straight connection there.
 * <p>
 * Only a step shorter than {@code MAX_PORT_STEP} is absorbed, only along the top or bottom side, only
 * when the new point stays inside the box, and only when no other edge already uses that point - a
 * moved port must not produce the stacked arrowheads the spreading exists to prevent.
 * </p>
 * @param {object} graph the graph
 * @param {object} edge the edge
 * @param {Array<Array<number>>} points the simplified path
 * @returns {Array<Array<number>>} the path, possibly with the two ends moved
 */
function absorbEndSteps(graph, edge, points) {
  if (points.length < 3) {
    return points;
  }
  var changed = false;
  var last = points.length - 1;
  if (isVertical(points[0], points[1]) && isHorizontal(points[1], points[2])
      && absorbStep(graph, edge, edge.fromNode, edge.exitSide, points, 0, 1, points[2][0])) {
    changed = true;
  }
  if (isVertical(points[last - 1], points[last]) && isHorizontal(points[last - 2], points[last - 1])
      && absorbStep(graph, edge, edge.toNode, edge.entrySide, points, last, last - 1,
        points[last - 2][0])) {
    changed = true;
  }
  return changed ? simplify(points) : points;
}

/**
 * Moves one end point of a path onto {@code runX}, if that is a short step, inside the box and free.
 * @param {object} graph the graph
 * @param {object} edge the edge
 * @param {object} node the node whose side holds the port
 * @param {string} side n | e | s | w
 * @param {Array<Array<number>>} points the path
 * @param {number} endIndex the index of the end point
 * @param {number} cornerIndex the index of the corner next to it
 * @param {number} runX the x of the run the edge continues on
 * @returns {boolean} true when the point moved
 */
function absorbStep(graph, edge, node, side, points, endIndex, cornerIndex, runX) {
  if (side !== 'n' && side !== 's') {
    return false;
  }
  var step = Math.abs(points[endIndex][0] - runX);
  if (step < 0.01 || step > MAX_PORT_STEP) {
    return false;
  }
  var inset = Math.min(12, node.w / 4);
  if (runX < node.x + inset || runX > node.x + node.w - inset) {
    return false;
  }
  if (portXInUse(graph, node.id, side, runX, edge)) {
    return false;
  }
  points[endIndex][0] = runX;
  points[cornerIndex][0] = runX;
  // The y followed the old x on the outline of a diamond or a hexagon - move it along.
  var recess = shapeRecess(node, side, runX - (node.x + node.w / 2));
  var y = side === 'n' ? node.y + recess : node.y + node.h - recess;
  points[endIndex][1] = y;
  points[cornerIndex][1] = y;
  if (endIndex === 0) {
    edge.exitX = runX;
  } else {
    edge.entryX = runX;
  }
  return true;
}

/**
 * Whether another edge already meets a node on one side at (nearly) the given x.
 * @param {object} graph the graph
 * @param {string} nodeId the node
 * @param {string} side n | e | s | w
 * @param {number} x the x
 * @param {object} except the edge that asks
 * @returns {boolean} true when the point is taken
 */
function portXInUse(graph, nodeId, side, x, except) {
  for (var i = 0; i < graph.edges.length; i++) {
    var other = graph.edges[i];
    if (other === except || !sharesPorts(graph, other)) {
      continue;
    }
    if (other.from === nodeId && other.exitSide === side) {
      var exitX = other.exitX === undefined || other.exitX === null
        ? sidePoint(other.fromNode, side, other.exitSpread.index, other.exitSpread.count)[0]
        : other.exitX;
      if (Math.abs(exitX - x) < 6) {
        return true;
      }
    }
    if (other.to === nodeId && other.entrySide === side) {
      var entryX = other.entryX === undefined || other.entryX === null
        ? sidePoint(other.toNode, side, other.entrySpread.index, other.entrySpread.count)[0]
        : other.entryX;
      if (Math.abs(entryX - x) < 6) {
        return true;
      }
    }
  }
  return false;
}

/**
 * Whether a segment is vertical.
 * @param {Array<number>} a the start
 * @param {Array<number>} b the end
 * @returns {boolean} true when vertical
 */
function isVertical(a, b) {
  return Math.abs(a[0] - b[0]) < 0.01;
}

/**
 * Whether a segment is horizontal.
 * @param {Array<number>} a the start
 * @param {Array<number>} b the end
 * @returns {boolean} true when horizontal
 */
function isHorizontal(a, b) {
  return Math.abs(a[1] - b[1]) < 0.01;
}

/**
 * Routes an edge that spans adjacent layers, segment by segment, with a channel in each gap.
 * @param {object} graph the graph
 * @param {object} edge the edge
 * @param {object} style the line style
 * @param {object} lanes the lane assignment
 * @param {Array<object>} out the item list
 * @param {Array<object>} labels the label list
 */
function routeForward(graph, edge, style, lanes, out, labels) {
  if (edge.waypoints) {
    emitWaypointEdge(graph, edge, style, out, labels);
    return;
  }
  var points = [];
  var chain = edge.chain;
  var exitSide = edge.exitSide;
  var entrySide = edge.entrySide;
  var lastIndex = chain.length - 2;
  for (var i = 0; i < chain.length - 1; i++) {
    var a = chain[i];
    var b = chain[i + 1];
    var gap = a.rank;
    var lane = lanes[segmentKey({edge: edge, a: a, b: b})] || 0;
    var channelY = channelOf(graph, gap, lane);
    var start;
    var startStub;
    if (i === 0 && !a.dummy) {
      start = sidePoint(a, exitSide, edge.exitSpread.index, edge.exitSpread.count);
      startStub = offsetNormal(start, exitSide, PORT_STUB);
    } else {
      start = bottomCenter(a);
      startStub = start;
    }
    var end;
    var endStub;
    if (i === lastIndex && !b.dummy) {
      end = sidePoint(b, entrySide, edge.entrySpread.index, edge.entrySpread.count);
      endStub = offsetNormal(end, entrySide, PORT_STUB);
    } else {
      end = topCenter(b);
      endStub = end;
    }
    appendPoint(points, start);
    appendPoint(points, startStub);
    appendPoint(points, [startStub[0], channelY]);
    appendPoint(points, [endStub[0], channelY]);
    appendPoint(points, endStub);
    appendPoint(points, end);
  }
  var clean = absorbEndSteps(graph, edge, simplify(points));
  if (clean.length < 2) {
    return;
  }
  out.push(edgePath(graph, clean, style));
  recordRoute(graph, edge, out);
  addMidArrow(graph, edge, clean, out);
  var lastPoint = clean[clean.length - 1];
  var beforeLast = clean[clean.length - 2];
  if (edge.arrow === 'end' || edge.arrow === 'both') {
    out.push(sceneLib.arrow(lastPoint[0], lastPoint[1], lastPoint[0] - beforeLast[0],
      lastPoint[1] - beforeLast[1]));
  }
  if (edge.arrow === 'start' || edge.arrow === 'both') {
    var firstPoint = clean[0];
    var second = clean[1];
    out.push(sceneLib.arrow(firstPoint[0], firstPoint[1], firstPoint[0] - second[0],
      firstPoint[1] - second[1]));
  }
  addEdgeLabel(edge, clean, labels);
  if (edge.exit || edge.entry || graph.pinnedCount) {
    noteCollisions(graph, edge, clean);
  }
}

/**
 * Routes an edge as one straight (diagonal) line between the node borders - the {@code direct}
 * route. An explicit port is honoured; without one the line starts and ends where the centre line
 * leaves the two boxes, which is what a hand-drawn tree looks like.
 * @param {object} graph the graph
 * @param {object} edge the edge
 * @param {object} style the line style
 * @param {Array<object>} out the item list
 * @param {Array<object>} labels the label list
 */
function routeDirect(graph, edge, style, out, labels) {
  var from = edge.fromNode;
  var to = edge.toNode;
  var start = edge.exit ? sidePoint(from, edge.exit) : boundaryPoint(from, to);
  var end = edge.entry ? sidePoint(to, edge.entry) : boundaryPoint(to, from);
  if (edge.waypoints) {
    var via = [start];
    for (var i = 0; i < edge.waypoints.length; i++) {
      via.push([edge.waypoints[i][0], edge.waypoints[i][1]]);
    }
    via.push(end);
    out.push(edgePath(graph, via, style));
    recordRoute(graph, edge, out);
    addArrowheads(edge, via, out);
    addEdgeLabel(edge, via, labels);
    return;
  }
  var points = [start, end];
  out.push(edgePath(graph, points, style));
  recordRoute(graph, edge, out);
  addArrowheads(edge, points, out);
  addEdgeLabel(edge, points, labels);
}

/**
 * Emits the arrowheads of a two-or-more point path at its ends.
 * @param {object} edge the edge
 * @param {Array<Array<number>>} points the path
 * @param {Array<object>} out the item list
 */
function addArrowheads(edge, points, out) {
  if (points.length < 2) {
    return;
  }
  var end = points[points.length - 1];
  var beforeEnd = points[points.length - 2];
  if (edge.arrow === 'end' || edge.arrow === 'both') {
    out.push(sceneLib.arrow(end[0], end[1], end[0] - beforeEnd[0], end[1] - beforeEnd[1]));
  }
  if (edge.arrow === 'start' || edge.arrow === 'both') {
    var start = points[0];
    var after = points[1];
    out.push(sceneLib.arrow(start[0], start[1], start[0] - after[0], start[1] - after[1]));
  }
}

/**
 * Routes an edge as one smooth line: from the node's outline, a short stub along the side's normal,
 * the same at the target, and the rest is the curve ({@code curve: 'spline'} gives the slightly
 * curved line Mermaid draws, {@code 'off'} the bare stub-to-stub polyline).
 * <p>
 * {@code direct} draws border to border, which leaves the line at an arbitrary angle and lets it cut
 * through a box; the two stubs here keep it perpendicular to the shape, so a fan out of a diamond
 * leaves the four edges of the rhombus at right angles and no arrowhead sits at a corner
 * (docs/js/diagram-plan.md §13.8).
 * </p>
 * @param {object} graph the graph
 * @param {object} edge the edge
 * @param {object} style the line style
 * @param {Array<object>} out the item list
 * @param {Array<object>} labels the label list
 */
function routeSmooth(graph, edge, style, out, labels) {
  var from = edge.fromNode;
  var to = edge.toNode;
  var exitSide = edge.exit || edge.exitSide || 's';
  var entrySide = edge.entry || edge.entrySide || 'n';
  var start = edge.exit
    ? sidePoint(from, edge.exit)
    : sidePoint(from, exitSide, edge.exitSpread ? edge.exitSpread.index : undefined,
      edge.exitSpread ? edge.exitSpread.count : undefined);
  var end = edge.entry
    ? sidePoint(to, edge.entry)
    : sidePoint(to, entrySide, edge.entrySpread ? edge.entrySpread.index : undefined,
      edge.entrySpread ? edge.entrySpread.count : undefined);
  var points = [start];
  if (edge.waypoints) {
    for (var i = 0; i < edge.waypoints.length; i++) {
      points.push([edge.waypoints[i][0], edge.waypoints[i][1]]);
    }
  } else {
    var stub = SMOOTH_STUB;
    var startStub = offsetNormal(start, exitSide, stub);
    var endStub = offsetNormal(end, entrySide, stub);
    points.push(startStub);
    points.push(endStub);
  }
  points.push(end);
  out.push(edgePath(graph, points, style));
  recordRoute(graph, edge, out);
  addArrowheads(edge, points, out);
  addEdgeLabel(edge, points, labels);
}

/**
 * The point where the line from the node's centre to another node's centre leaves the node box.
 * @param {object} node the node
 * @param {object} other the other node
 * @returns {Array<number>} the border point
 */
function boundaryPoint(node, other) {
  var cx = node.x + node.w / 2;
  var cy = node.y + node.h / 2;
  var ox = other.x + other.w / 2;
  var oy = other.y + other.h / 2;
  var dx = ox - cx;
  var dy = oy - cy;
  if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) {
    return [cx, cy];
  }
  var t = boundaryT(node, dx, dy);
  return [cx + dx * t, cy + dy * t];
}

/**
 * The factor that takes a direction from the node's centre to the point where it leaves the outline.
 * <p>
 * The box is only the outline of a rectangle. A diamond is the rhombus through the middle of the four
 * sides, a circle is the inscribed ellipse, and a line that is trimmed to the box would end in the air
 * next to those shapes (reported as a "dangling" arrowhead in docs/js/diagram-plan.md §7.1).
 * </p>
 * @param {object} node the node
 * @param {number} dx the x distance to the other centre
 * @param {number} dy the y distance to the other centre
 * @returns {number} the factor (&gt; 0)
 */
function boundaryT(node, dx, dy) {
  var a = node.w / 2;
  var b = node.h / 2;
  if (node.shape === 'diamond') {
    // |x|/a + |y|/b = 1 on every of the four edges.
    return 1 / (Math.abs(dx) / a + Math.abs(dy) / b);
  }
  if (node.shape === 'circle' || node.shape === 'start' || node.shape === 'end') {
    return 1 / Math.sqrt((dx / a) * (dx / a) + (dy / b) * (dy / b));
  }
  var tx = Math.abs(dx) < 1e-9 ? Infinity : a / Math.abs(dx);
  var ty = Math.abs(dy) < 1e-9 ? Infinity : b / Math.abs(dy);
  return Math.min(tx, ty);
}

/**
 * How far a point that sits {@code offset} beside the middle of a side lies outside the shape, i.e.
 * how far it has to move back along the side's inward normal to lie on the outline.
 * <p>
 * Zero for a rectangle (and for every shape whose side is flat) - this is what keeps the arrowheads
 * of a diamond, a hexagon, a circle or a stadium on the drawn outline instead of at the corners of
 * the invisible box (docs/js/diagram-plan.md §7.1).
 * </p>
 * @param {object} node the node
 * @param {string} side n | e | s | w
 * @param {number} offset the distance from the middle of the side
 * @returns {number} the recess, 0..h/2 resp. 0..w/2
 */
function shapeRecess(node, side, offset) {
  var along = Math.abs(offset || 0);
  if (along < 1e-9) {
    return 0;
  }
  var w = node.w;
  var h = node.h;
  var horizontal = side === 'n' || side === 's';
  if (node.shape === 'diamond') {
    return horizontal ? along * (h / w) : along * (w / h);
  }
  if (node.shape === 'circle' || node.shape === 'start' || node.shape === 'end') {
    // The outline is the ellipse through the middle of the four sides.
    var rx = w / 2;
    var ry = h / 2;
    var radius = horizontal ? ry : rx;
    var across = horizontal ? rx : ry;
    var share = Math.min(1, along / across);
    return radius - radius * Math.sqrt(Math.max(0, 1 - share * share));
  }
  if (node.shape === 'stadium') {
    // Flat in the middle, a half circle of radius h/2 at both ends.
    var r = h / 2;
    var flat = horizontal ? Math.max(0, w / 2 - r) : 0;
    if (along <= flat) {
      return 0;
    }
    var s = Math.min(1, (along - flat) / r);
    return r - r * Math.sqrt(Math.max(0, 1 - s * s));
  }
  if (node.shape === 'hexagon') {
    var inset = Math.min(w / 4, 16);
    if (horizontal) {
      var flatHex = w / 2 - inset;
      return along <= flatHex ? 0 : Math.min(h / 2, (along - flatHex) * (h / 2) / inset);
    }
    return along >= h / 2 ? w / 2 : inset * (1 - along / (h / 2));
  }
  return 0;
}

/**
 * A point on one side of a node box.
 * @param {object} node the node
 * @param {string} side n | e | s | w
 * @param {number} [index] the index of the edge among those that share this side
 * @param {number} [count] how many edges share this side
 * @returns {Array<number>} the point
 */
function sidePoint(node, side, index, count) {
  var offset = sideOffset(node, side, index, count);
  var recess = shapeRecess(node, side, offset);
  if (side === 'n') {
    return [node.x + node.w / 2 + offset, node.y + recess];
  }
  if (side === 'e') {
    return [node.x + node.w - recess, node.y + node.h / 2 + offset];
  }
  if (side === 'w') {
    return [node.x + recess, node.y + node.h / 2 + offset];
  }
  return [node.x + node.w / 2 + offset, node.y + node.h - recess];
}

/**
 * The offset of the {@code index}-th of {@code count} edges along a side of a node: they are
 * spread so their stubs and arrowheads do not sit on top of each other. A single edge keeps the
 * middle of the side, and a forced port stays on the forced side.
 * @param {object} node the node
 * @param {string} side n | e | s | w
 * @param {number} index the index (0-based)
 * @param {number} count how many edges share the side
 * @returns {number} the offset from the middle of the side
 */
function sideOffset(node, side, index, count) {
  if (index === undefined || index === null || !count || count <= 1) {
    return 0;
  }
  var length = (side === 'e' || side === 'w') ? node.h : node.w;
  var inset = Math.min(12, length / 4);
  var lo = -length / 2 + inset;
  var hi = length / 2 - inset;
  return lo + (hi - lo) * index / (count - 1);
}

/**
 * Moves a point outwards along the normal of a side.
 * @param {Array<number>} point the point on the side
 * @param {string} side the side
 * @param {number} distance the distance
 * @returns {Array<number>} the offset point
 */
function offsetNormal(point, side, distance) {
  if (side === 'n') {
    return [point[0], point[1] - distance];
  }
  if (side === 'e') {
    return [point[0] + distance, point[1]];
  }
  if (side === 'w') {
    return [point[0] - distance, point[1]];
  }
  return [point[0], point[1] + distance];
}

/**
 * Reports every node box a routed path crosses. A forced port or a forced bend is honoured as
 * asked; a collision it produces is reported, never silently rerouted (docs/js/diagram-plan.md
 * §7.2).
 * @param {object} graph the graph
 * @param {object} edge the edge
 * @param {Array<Array<number>>} points the path
 */
function noteCollisions(graph, edge, points) {
  if (!graph.collisionSeen) {
    graph.collisionSeen = {};
  }
  for (var s = 1; s < points.length; s++) {
    for (var n = 0; n < graph.nodes.length; n++) {
      var node = graph.nodes[n];
      if (node.dummy) {
        continue;
      }
      if (!segmentCrossesNode(points[s - 1], points[s], node)) {
        continue;
      }
      var key = edge.index + '|' + node.id;
      if (!graph.collisionSeen[key]) {
        graph.collisionSeen[key] = true;
        graph.notes.push("layout: the edge '" + edge.from + "' -> '" + edge.to + "' crosses the"
          + " node '" + node.id + "' (a forced port or bend) - reported, not silently"
          + ' rerouted');
      }
    }
  }
}

/**
 * Whether an (orthogonal or diagonal) segment crosses the open interior of a node box.
 * @param {Array<number>} p the segment start
 * @param {Array<number>} q the segment end
 * @param {object} node the node (x, y, w, h)
 * @returns {boolean} true when it crosses
 */
function segmentCrossesNode(p, q, node) {
  return boxHit(p, q, node) !== null;
}

/**
 * Clips a segment against the open interior of a box (Liang-Barsky), the one geometric test behind
 * the collision check and the box-hit diagnosis.
 * @param {Array<number>} p the segment start
 * @param {Array<number>} q the segment end
 * @param {object} box {x, y, w, h}
 * @returns {Array<number>|null} [t0, t1] along the segment, or null when it misses the interior
 */
function boxHit(p, q, box) {
  var eps = 0.8;
  var x0 = box.x + eps;
  var x1 = box.x + box.w - eps;
  var y0 = box.y + eps;
  var y1 = box.y + box.h - eps;
  if (x0 >= x1 || y0 >= y1) {
    return null;
  }
  var dx = q[0] - p[0];
  var dy = q[1] - p[1];
  var span = [0, 1];
  if (!clipEdge(-dx, p[0] - x0, span) || !clipEdge(dx, x1 - p[0], span)
      || !clipEdge(-dy, p[1] - y0, span) || !clipEdge(dy, y1 - p[1], span)) {
    return null;
  }
  return span[1] > span[0] ? span : null;
}

/**
 * One clipping step of Liang-Barsky: restricts the parameter interval of a segment to one side of the
 * box.
 * @param {number} pv the direction component (negated for the near side)
 * @param {number} qv the distance to the side
 * @param {Array<number>} span the interval [t0, t1] (modified)
 * @returns {boolean} false when the segment lies completely outside that side
 */
function clipEdge(pv, qv, span) {
  if (Math.abs(pv) < 1e-9) {
    return qv >= 0;
  }
  var r = qv / pv;
  if (pv < 0) {
    if (r > span[1]) {
      return false;
    }
    if (r > span[0]) {
      span[0] = r;
    }
  } else {
    if (r < span[0]) {
      return false;
    }
    if (r < span[1]) {
      span[1] = r;
    }
  }
  return true;
}

/**
 * The channel y for a lane within a gap.
 * @param {object} graph the graph
 * @param {number} gap the gap index
 * @param {number} lane the lane index
 * @returns {number} the channel y
 */
function channelOf(graph, gap, lane) {
  var top = graph.layerTop[gap] + graph.layerHeight[gap];
  var height = graph.gaps[gap] || RANK_GAP;
  var lanes = (graph.gapLanes && graph.gapLanes[gap]) ? graph.gapLanes[gap] : 1;
  var slots = Math.max(lanes, 1) + 1;
  return top + height * (lane + 1) / slots;
}

/**
 * Routes an edge whose waypoints were forced (the {@code bend} option).
 * @param {object} graph the graph
 * @param {object} edge the edge
 * @param {object} style the line style
 * @param {Array<object>} out the item list
 * @param {Array<object>} labels the label list
 */
function emitWaypointEdge(graph, edge, style, out, labels) {
  var start = bottomCenter(edge.fromNode);
  var end = topCenter(edge.toNode);
  var points = [start];
  for (var i = 0; i < edge.waypoints.length; i++) {
    points.push([edge.waypoints[i][0], edge.waypoints[i][1]]);
  }
  points.push(end);
  out.push(edgePath(graph, points, style));
  recordRoute(graph, edge, out);
  if (edge.arrow === 'end' || edge.arrow === 'both') {
    out.push(sceneLib.arrow(end[0], end[1], end[0] - points[points.length - 2][0],
      end[1] - points[points.length - 2][1]));
  }
  addEdgeLabel(edge, points, labels);
  noteCollisions(graph, edge, points);
}

/**
 * Routes a back edge or a same-rank edge around the right side.
 * @param {object} graph the graph
 * @param {object} edge the edge
 * @param {object} style the line style
 * @param {object} laneInfo the side lane: {lane, bundle}
 * @param {Array<object>} out the item list
 * @param {Array<object>} labels the label list
 */
function routeSide(graph, edge, style, laneInfo, out, labels) {
  if (edge.exit || edge.entry) {
    graph.notes.push("layout: the port '" + (edge.exit || 's') + ':' + (edge.entry || 'n')
      + "' of the back/same-rank edge '" + edge.from + "' -> '" + edge.to
      + "' is not honoured - it takes the side channel (a forward edge honours ports)");
  }
  var info = laneInfo || {lane: 0, bundle: null};
  var right = rightEdge(graph);
  var channelX = right + 18 + info.lane * 10;
  var from = edge.fromNode;
  var to = edge.toNode;
  // Both horizontal runs happen in a gap between layers, where no node box sits: source bottom
  // into the gap below its layer, the far channel up/down, and the target's top gap. A run at a
  // node's centre height would cross the boxes of its layer (docs/js/diagram-plan.md §7.1).
  // Several edges that leave one node or reach one node get distinct points and distinct lines:
  // otherwise their stubs and arrowheads sit exactly on top of each other.
  var start = sidePoint(from, edge.exitSide, edge.exitSpread.index, edge.exitSpread.count);
  var sourceGapY = sideRunY(graph, from.rank, false, edge.exitRun.index, edge.exitRun.count);
  var end = sidePoint(to, edge.entrySide, edge.entrySpread.index, edge.entrySpread.count);
  if (info.bundle) {
    // Confluent drawing: this edge is a branch that joins the shared trunk. The trunk itself (one
    // line, one arrowhead into the target) is emitted once per bundle after the loop, so the shared
    // stretch is drawn once and never splits again at the target.
    var branch = simplify([start, [start[0], sourceGapY], [channelX, sourceGapY]]);
    out.push(edgePath(graph, branch, style));
    recordRoute(graph, edge, out, info.bundle, channelX);
    addEdgeLabel(edge, branch, labels);
    addBundlePart(graph, edge, channelX, sourceGapY, style);
    return;
  }
  var targetGapY = sideRunY(graph, to.rank, true, edge.entryRun.index, edge.entryRun.count);
  var points = [start, [start[0], sourceGapY], [channelX, sourceGapY], [channelX, targetGapY],
    [end[0], targetGapY], end];
  var clean = simplify(points);
  out.push(edgePath(graph, clean, style));
  recordRoute(graph, edge, out, info.bundle, channelX);
  if (edge.arrow === 'end' || edge.arrow === 'both') {
    out.push(sceneLib.arrow(end[0], end[1], 0, 1));
  }
  if (edge.arrow === 'start' || edge.arrow === 'both') {
    out.push(sceneLib.arrow(start[0], start[1], 0, -1));
  }
  addEdgeLabel(edge, clean, labels);
  if (edge.exit || edge.entry || graph.pinnedCount) {
    noteCollisions(graph, edge, clean);
  }
}

/**
 * Remembers one member of a channel bundle: its trunk x, where the branch joins, and the line style.
 * @param {object} graph the graph
 * @param {object} edge the edge
 * @param {number} channelX the x of the shared trunk
 * @param {number} sourceGapY the y at which this branch joins the trunk
 * @param {object} style the line style of this branch
 */
function addBundlePart(graph, edge, channelX, sourceGapY, style) {
  if (!graph.bundleParts) {
    graph.bundleParts = {};
  }
  var parts = graph.bundleParts[edge.bundle];
  if (!parts) {
    parts = {target: edge.to, toNode: edge.toNode, channelX: channelX,
      entrySide: edge.entrySide, entrySpread: edge.entrySpread, mergeY: sourceGapY, style: style,
      arrow: edge.arrow === 'end' || edge.arrow === 'both'};
    graph.bundleParts[edge.bundle] = parts;
  }
  parts.mergeY = Math.max(parts.mergeY, sourceGapY);
  if (style.lineWidth > parts.style.lineWidth) {
    parts.style = style;
  }
}

/**
 * Draws the trunk of every bundle: one line from the lowest joining branch up into the target, with
 * one arrowhead. This is what makes a bundle a confluent drawing instead of two lines drawn on top of
 * each other.
 * @param {object} graph the graph
 * @param {Array<object>} out the item list
 */
function emitBundles(graph, out) {
  var parts = graph.bundleParts;
  if (!parts) {
    return;
  }
  for (var key in parts) {
    if (!Object.prototype.hasOwnProperty.call(parts, key)) {
      continue;
    }
    var part = parts[key];
    var to = part.toNode;
    var topY = offChannel(graph, to.rank - 1, aboveLayer(graph, to.rank));
    var end = sidePoint(to, part.entrySide, part.entrySpread.index, part.entrySpread.count);
    var points = simplify([[part.channelX, part.mergeY], [part.channelX, topY],
      [end[0], topY], end]);
    out.push(edgePath(graph, points, part.style));
    // The trunk is a drawn line like any other: record it, so the crossing count and the conflict
    // report see it (otherwise a bundle would look cleaner than it is).
    recordRoute(graph, {index: -1, from: 'bundle', to: part.target,
      name: 'the bundle into \'' + part.target + '\''}, out, null, part.channelX);
    addMidArrow(graph, {arrow: 'end'}, points, out);
    if (part.arrow) {
      out.push(sceneLib.arrow(end[0], end[1], 0, 1));
    }
  }
}

/**
 * The y of the {@code index}-th of {@code count} horizontal runs that share one gap. The runs are
 * spread around the gap's middle, always stay inside the gap, and are nudged off the forward-edge
 * channels of that gap - a back edge running on a forward channel draws one line for two edges.
 * @param {object} graph the graph
 * @param {number} rank the layer
 * @param {boolean} above true for the gap above the layer, false for the gap below it
 * @param {number} index the index among the runs that share the gap
 * @param {number} count how many runs share the gap
 * @returns {number} the y
 */
function sideRunY(graph, rank, above, index, count) {
  var base = above ? aboveLayer(graph, rank) : belowLayer(graph, rank);
  var y = base;
  if (count && count > 1) {
    var gap = (above ? graph.gaps[rank - 1] : graph.gaps[rank]) || 2 * RANK_GAP;
    var step = Math.min(gap / (count + 1), 6);
    y = base + (index - (count - 1) / 2) * step;
  }
  return offChannel(graph, above ? rank - 1 : rank, y);
}

/**
 * Moves a horizontal run off the forward-edge channels of a gap, keeping it inside the gap.
 * @param {object} graph the graph
 * @param {number} gapIndex the gap (layer transition), -1 for "no gap"
 * @param {number} y the wanted y
 * @returns {number} the y to use
 */
function offChannel(graph, gapIndex, y) {
  if (gapIndex < 0 || gapIndex >= graph.layerTop.length - 1) {
    return y;
  }
  var top = graph.layerTop[gapIndex] + graph.layerHeight[gapIndex];
  var height = graph.gaps[gapIndex] || RANK_GAP;
  var lanes = (graph.gapLanes && graph.gapLanes[gapIndex]) ? graph.gapLanes[gapIndex] : 0;
  var slots = Math.max(lanes, 1) + 1;
  var candidate = Math.min(Math.max(y, top + 3), top + height - 3);
  for (var attempt = 0; attempt < 8; attempt++) {
    var clash = null;
    for (var k = 0; k < lanes; k++) {
      var channelY = top + height * (k + 1) / slots;
      if (Math.abs(channelY - candidate) < 3) {
        clash = channelY;
        break;
      }
    }
    if (clash === null) {
      return candidate;
    }
    candidate = candidate >= clash ? clash + 3.5 : clash - 3.5;
    candidate = Math.min(Math.max(candidate, top + 3), top + height - 3);
  }
  return candidate;
}

/**
 * Remembers the path item that was just emitted, so a later pass can report edges that run on top
 * of each other. The item is kept (not a copy of its points): the scene is rotated and normalized
 * after routing, and the note must name the coordinates the reader sees.
 * @param {object} graph the graph
 * @param {object} edge the edge
 * @param {Array<object>} out the item list the path was just appended to
 * @param {string} [bundle] the bundle key when the edge shares its channel on purpose
 * @param {number} [channelX] the x of the side channel
 */
function recordRoute(graph, edge, out, bundle, channelX) {
  if (!graph.routes) {
    graph.routes = [];
  }
  var item = out.length ? out[out.length - 1] : null;
  // The identity travels with the item, not only with the route: whoever reads the scene (or the
  // SVG the user edits) has to be able to say which edge an element is without matching
  // coordinates. A bundle trunk gets from='bundle'.
  if (item) {
    item.edgeFrom = edge.from;
    item.edgeTo = edge.to;
  }
  graph.routes.push({edge: edge, from: edge.from, to: edge.to, name: edge.name || null,
    item: item, bundle: bundle || null,
    channelX: channelX === undefined ? null : channelX});
}

/**
 * Lists the deliberate channel bundles for the diagnostics: which node shares how many edges on one
 * trunk, and at which x the trunk runs (read from the drawn path, so the number matches the picture).
 * Nothing here is a conflict - the doubled line is what was asked for.
 * @param {object} graph the graph
 * @returns {Array<object>} [{target, x, edges}]
 */
function buildBundles(graph) {
  var routes = graph.routes || [];
  var byKey = {};
  var out = [];
  for (var i = 0; i < routes.length; i++) {
    var route = routes[i];
    if (route.bundle === null) {
      continue;
    }
    if (!byKey[route.bundle]) {
      byKey[route.bundle] = {target: route.bundle, x: 0, edges: 0};
      out.push(byKey[route.bundle]);
    }
    var entry = byKey[route.bundle];
    entry.edges++;
    var points = route.item.points;
    for (var p = 0; p < points.length; p++) {
      entry.x = Math.max(entry.x, points[p][0]);
    }
  }
  for (var k = 0; k < out.length; k++) {
    out[k].x = round2(out[k].x);
  }
  return out;
}

/**
 * Adds one arrowhead in the middle of a long edge, pointing the way the line runs there. Long edges
 * cross several ranks and are hard to follow without one (mid-edge arrowhead / direction indicator,
 * docs/js/diagram-plan.md §13.9).
 * @param {object} graph the graph
 * @param {object} edge the edge
 * @param {Array<Array<number>>} points the drawn path
 * @param {Array<object>} out the item list
 */
function addMidArrow(graph, edge, points, out) {
  if (graph.midArrows !== 'long' || edge.arrow === 'none' || !points || points.length < 2) {
    return;
  }
  var total = 0;
  var i;
  for (i = 1; i < points.length; i++) {
    total += distance(points[i - 1], points[i]);
  }
  if (total < graph.midArrowAfter) {
    return;
  }
  var half = total / 2;
  if (half < 15 || total - half < 15) {
    return;
  }
  var walked = 0;
  for (i = 1; i < points.length; i++) {
    var segment = distance(points[i - 1], points[i]);
    if (walked + segment >= half && segment > 0.01) {
      var t = (half - walked) / segment;
      out.push(sceneLib.arrow(points[i - 1][0] + (points[i][0] - points[i - 1][0]) * t,
        points[i - 1][1] + (points[i][1] - points[i - 1][1]) * t,
        points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]));
      return;
    }
    walked += segment;
  }
}

/**
 * Reports the three cases in which two edges become one line on paper: arrowheads stacked on the
 * same point, a long run on top of each other, and two runs so close that the stroke cannot separate
 * them. All edges stay in the picture - the note says the drawing needs a second look, it removes
 * nothing (docs/js/diagram-plan.md §7.1).
 * <p>
 * A short common trunk is not reported: edges that leave one node do share their stub until they
 * branch, and that is the bus look the {@code tree} route wants on purpose.
 * </p>
 * <p>
 * The third case is the one a correction creates and no exact check can see: nudging a node by a few
 * points can put two vertical runs 0.7 pt apart over 13 pt, which draws one fat line. The number of
 * such pairs is also reported as {@code phases.nearOverlaps}, so a caller can assert on it.
 * </p>
 * @param {object} graph the graph
 */
function noteSharedRoutes(graph) {
  var routes = graph.routes || [];
  var messages = [];
  var reported = {};
  var nearCount = 0;
  // In 'tree' mode a shared branch line is the point of the mode, not a conflict.
  var shareIsIntended = graph.route === 'tree';
  for (var i = 0; i < routes.length; i++) {
    for (var j = i + 1; j < routes.length; j++) {
      var first = routes[i];
      var second = routes[j];
      var same = stackedArrowheads(first, second);
      if (same && !reported['pt ' + same.key]) {
        reported['pt ' + same.key] = true;
        messages.push('the edges ' + routeName(first) + ' and ' + routeName(second)
          + ' end on the same point ' + pointName(same.point) + ' with the same direction - both'
          + ' arrowheads are drawn on top of each other');
      }
      var deliberate = shareIsIntended || sameBundle(first, second);
      var overlap = deliberate ? null : sharedRun(first, second);
      if (overlap && !reported['seg ' + overlap.key]) {
        reported['seg ' + overlap.key] = true;
        messages.push('the edges ' + routeName(first) + ' and ' + routeName(second)
          + ' run on top of each other from ' + pointName(overlap.from) + ' to '
          + pointName(overlap.to) + ' - both are drawn, so the line looks doubled');
      }
      var close = deliberate ? null : nearRun(first, second);
      if (close && !reported['near ' + close.key]) {
        reported['near ' + close.key] = true;
        nearCount++;
        messages.push('the edges ' + routeName(first) + ' and ' + routeName(second) + ' run '
          + close.gap + ' pt apart from ' + pointName(close.from) + ' to ' + pointName(close.to)
          + ' - closer than the stroke width, so they look like one doubled line; both are drawn'
          + ' (nudge one of the two nodes to separate them)');
      }
    }
  }
  graph.nearOverlaps = nearCount;
  var limit = 8;
  for (var k = 0; k < messages.length && k < limit; k++) {
    graph.notes.push('layout: ' + messages[k]);
  }
  if (messages.length > limit) {
    graph.notes.push('layout: ' + (messages.length - limit) + ' further edge pairs share a line'
      + ' or a point - the picture draws all of them');
  }
}

/**
 * Whether two routes share their channel on purpose (the same deliberate bundle).
 * @param {object} first the first route
 * @param {object} second the second route
 * @returns {boolean} true when they are one bundle
 */
function sameBundle(first, second) {
  return first.bundle !== null && first.bundle === second.bundle;
}

/**
 * Counts the crossings the drawing actually shows and remembers which edges cross where: points
 * where the lines of two edges cross and the point is strictly inside both segments. A shared end
 * point or a T-junction is a touch, not a crossing; a collinear run is counted by
 * {@link countDrawnOverlaps}, not here.
 * <p>
 * The names are what a correction loop needs - "2 crossings" says nothing, "'A' -> 'B' crosses
 * 'C' -> 'D' at (282,522)" says which node to nudge. The list is capped, the count is not.
 * </p>
 * @param {object} graph the graph
 * @returns {number} the number of visible crossings
 */
function countDrawnCrossings(graph) {
  var routes = graph.routes || [];
  var total = 0;
  var pairs = [];
  for (var i = 0; i < routes.length; i++) {
    for (var j = i + 1; j < routes.length; j++) {
      var points = crossingPoints(routes[i].item.points, routes[j].item.points);
      if (points.length === 0) {
        continue;
      }
      total += points.length;
      if (pairs.length < MAX_CROSSING_PAIRS) {
        pairs.push({a: routeName(routes[i]), b: routeName(routes[j]),
          at: [round2(points[0][0]), round2(points[0][1])]});
      }
    }
  }
  graph.crossingPairs = pairs;
  return total;
}

/**
 * The points where two polylines cross (each crossing once, even when it sits on a corner).
 * @param {Array<Array<number>>} first the first path
 * @param {Array<Array<number>>} second the second path
 * @returns {Array<Array<number>>} the crossing points
 */
function crossingPoints(first, second) {
  var seen = {};
  var points = [];
  for (var i = 1; i < first.length; i++) {
    for (var j = 1; j < second.length; j++) {
      var point = segmentCrossing(first[i - 1], first[i], second[j - 1], second[j]);
      if (point === null) {
        continue;
      }
      var key = round2(point[0]) + ',' + round2(point[1]);
      if (seen[key]) {
        continue;
      }
      seen[key] = true;
      points.push(point);
    }
  }
  return points;
}

/**
 * The point where two segments cross, or null. Parallel and collinear segments have no crossing.
 * @param {Array<number>} a0 the first segment start
 * @param {Array<number>} a1 the first segment end
 * @param {Array<number>} b0 the second segment start
 * @param {Array<number>} b1 the second segment end
 * @returns {Array<number>|null} the crossing point
 */
function segmentCrossing(a0, a1, b0, b1) {
  var ax = a1[0] - a0[0];
  var ay = a1[1] - a0[1];
  var bx = b1[0] - b0[0];
  var by = b1[1] - b0[1];
  var denominator = ax * by - ay * bx;
  if (Math.abs(denominator) < 1e-9) {
    return null;
  }
  var t = ((b0[0] - a0[0]) * by - (b0[1] - a0[1]) * bx) / denominator;
  var u = ((b0[0] - a0[0]) * ay - (b0[1] - a0[1]) * ax) / denominator;
  if (t <= 0.001 || t >= 0.999 || u <= 0.001 || u >= 0.999) {
    return null;
  }
  return [a0[0] + t * ax, a0[1] + t * ay];
}

/**
 * Counts edge pairs that draw one line on top of each other over a visible length (the same
 * threshold the notes use). A deliberate bundle is not counted - it is listed in
 * {@code phases.backBundles}.
 * @param {object} graph the graph
 * @returns {number} the number of doubled runs
 */
function countDrawnOverlaps(graph) {
  var routes = graph.routes || [];
  if (graph.route === 'tree') {
    return 0;
  }
  var total = 0;
  for (var i = 0; i < routes.length; i++) {
    for (var j = i + 1; j < routes.length; j++) {
      if (sameBundle(routes[i], routes[j])) {
        continue;
      }
      var overlap = sharedRun(routes[i], routes[j]);
      if (overlap !== null) {
        total++;
      }
    }
  }
  return total;
}

/**
 * Two edges that end on the same point and arrive from the same direction: their arrowheads are
 * drawn on top of each other.
 * @param {object} first the first route
 * @param {object} second the second route
 * @returns {object|null} {point, key} or null
 */
function stackedArrowheads(first, second) {
  if (!hasArrowAt(first.edge, 'end') || !hasArrowAt(second.edge, 'end')) {
    return null;
  }
  var a = first.item.points;
  var b = second.item.points;
  if (a.length < 2 || b.length < 2) {
    return null;
  }
  var endA = a[a.length - 1];
  var endB = b[b.length - 1];
  if (Math.abs(endA[0] - endB[0]) > 0.5 || Math.abs(endA[1] - endB[1]) > 0.5) {
    return null;
  }
  if (!sameDirection(a[a.length - 2], endA, b[b.length - 2], endB)) {
    return null;
  }
  return {point: endA, key: round2(endA[0]) + ',' + round2(endA[1])};
}

/**
 * The part two edges draw on top of each other: a collinear overlap longer than a port stub whose
 * two routes do not start at the same node port (a common trunk is not a doubled line).
 * @param {object} first the first route
 * @param {object} second the second route
 * @returns {object|null} {from, to, key} or null
 */
function sharedRun(first, second) {
  var a = first.item.points;
  var b = second.item.points;
  if (a.length < 2 || b.length < 2) {
    return null;
  }
  if (distance(a[0], b[0]) < 1) {
    return null;
  }
  var overlap = sharedOverlap(a, b);
  if (overlap === null || distance(overlap.from, overlap.to) < 3 * PORT_STUB) {
    return null;
  }
  return overlap;
}

/**
 * Two runs that are not on the same line but closer than the stroke width over a stretch longer than
 * a corner - the doubled line a small nudge creates. A common stub from one port is not reported
 * (two routes that start on the same point share it on purpose, exactly as in {@link sharedRun}).
 * @param {object} first the first route
 * @param {object} second the second route
 * @returns {object|null} {from, to, gap, key} or null
 */
function nearRun(first, second) {
  var a = first.item.points;
  var b = second.item.points;
  if (a.length < 2 || b.length < 2) {
    return null;
  }
  if (distance(a[0], b[0]) < 1) {
    return null;
  }
  var best = null;
  var bestLength = NEAR_RUN_MIN;
  for (var i = 1; i < a.length; i++) {
    for (var j = 1; j < b.length; j++) {
      var overlap = nearOverlap(a[i - 1], a[i], b[j - 1], b[j]);
      if (overlap === null) {
        continue;
      }
      var length = distance(overlap.from, overlap.to);
      if (length > bestLength) {
        bestLength = length;
        best = overlap;
      }
    }
  }
  return best;
}

/**
 * How far two segments may diverge in direction and still count as one run: the sine of the angle
 * between them (0.05 = 2.9 degrees). The gap across the run does the real work - two lines that
 * cross at a small angle drift apart quickly and fail the {@code NEAR_LINE_GAP} test anyway.
 */
var PARALLEL_SIN = 0.05;

/**
 * The overlap of two segments that run in the same direction: the gap across and the stretch they
 * share, for <em>any</em> angle.
 * <p>
 * Both checks below ({@link nearOverlap}, {@link collinearOverlap}) go through here. Until this
 * existed they compared x or y only ({@code aHorizontal}/{@code aVertical} with an epsilon on the
 * other coordinate), which is right for the layered router - it only produces horizontal and
 * vertical runs - but blind in a force layout, where every line has an arbitrary direction: the
 * doubled lines there were never reported, and {@code phases.nearOverlaps} was structurally 0.
 * </p>
 * @param {Array<number>} a0 start of the first segment
 * @param {Array<number>} a1 end of the first segment
 * @param {Array<number>} b0 start of the second segment
 * @param {Array<number>} b1 end of the second segment
 * @returns {object|null} {from, to, gap, gapMax, gapMin, length, key} or null
 */
function alignedOverlap(a0, a1, b0, b1) {
  var ax = a1[0] - a0[0];
  var ay = a1[1] - a0[1];
  var la = Math.sqrt(ax * ax + ay * ay);
  var bx = b1[0] - b0[0];
  var by = b1[1] - b0[1];
  var lb = Math.sqrt(bx * bx + by * by);
  if (la < 1e-9 || lb < 1e-9) {
    return null;
  }
  var ux = ax / la;
  var uy = ay / la;
  if (Math.abs(ux * (by / lb) - uy * (bx / lb)) > PARALLEL_SIN) {
    return null;
  }
  var s0 = (b0[0] - a0[0]) * ux + (b0[1] - a0[1]) * uy;
  var s1 = (b1[0] - a0[0]) * ux + (b1[1] - a0[1]) * uy;
  var lo = Math.max(0, Math.min(s0, s1));
  var hi = Math.min(la, Math.max(s0, s1));
  if (hi - lo <= 1) {
    return null;
  }
  var from = [a0[0] + lo * ux, a0[1] + lo * uy];
  var to = [a0[0] + hi * ux, a0[1] + hi * uy];
  // The gap is what counts where both segments actually run, not at the far end of b: two lines
  // that meet at a shared port are close there and drift apart behind it.
  var nbx = -by / lb;
  var nby = bx / lb;
  var d0 = Math.abs((from[0] - b0[0]) * nbx + (from[1] - b0[1]) * nby);
  var d1v = Math.abs((to[0] - b0[0]) * nbx + (to[1] - b0[1]) * nby);
  var gapMax = Math.max(d0, d1v);
  return {from: from, to: to, gap: round2(gapMax), gapMax: gapMax,
    gapMin: Math.min(d0, d1v), length: hi - lo,
    key: 'o' + round2(from[0]) + ',' + round2(from[1]) + '/' + round2(to[0]) + ',' + round2(to[1])};
}

/**
 * The overlap of two parallel segments that are close but not collinear (that case is
 * {@link collinearOverlap}). The gap is measured across the run, the overlap along it.
 * @param {Array<number>} a0 start of the first segment
 * @param {Array<number>} a1 end of the first segment
 * @param {Array<number>} b0 start of the second segment
 * @param {Array<number>} b1 end of the second segment
 * @returns {object|null} {from, to, gap, key} or null
 */
function nearOverlap(a0, a1, b0, b1) {
  var eps = 0.01;
  var overlap = alignedOverlap(a0, a1, b0, b1);
  if (!overlap || overlap.gapMin <= eps || overlap.gapMax > NEAR_LINE_GAP) {
    return null;
  }
  return overlap;
}


/**
 * Whether an edge carries an arrowhead at the given end.
 * @param {object} edge the edge
 * @param {string} end 'end' or 'start'
 * @returns {boolean} true when it has one
 */
function hasArrowAt(edge, end) {
  var arrow = edge.arrow || 'end';
  return arrow === 'both' || arrow === end;
}

/**
 * Whether two segments point in the same direction.
 * @param {Array<number>} a0 the first segment start
 * @param {Array<number>} a1 the first segment end
 * @param {Array<number>} b0 the second segment start
 * @param {Array<number>} b1 the second segment end
 * @returns {boolean} true when their unit vectors agree within 0.5°
 */
function sameDirection(a0, a1, b0, b1) {
  var ax = a1[0] - a0[0];
  var ay = a1[1] - a0[1];
  var bx = b1[0] - b0[0];
  var by = b1[1] - b0[1];
  var la = Math.sqrt(ax * ax + ay * ay);
  var lb = Math.sqrt(bx * bx + by * by);
  if (la < 1e-9 || lb < 1e-9) {
    return false;
  }
  return (ax * bx + ay * by) / (la * lb) > 0.9999;
}

/**
 * The shared part of two polylines: the longest collinear overlap of an axis-aligned segment.
 * @param {Array<Array<number>>} first the first path
 * @param {Array<Array<number>>} second the second path
 * @returns {object|null} {from, to, key} or null
 */
function sharedOverlap(first, second) {
  var best = null;
  var bestLength = 1;
  for (var i = 1; i < first.length; i++) {
    for (var j = 1; j < second.length; j++) {
      var overlap = collinearOverlap(first[i - 1], first[i], second[j - 1], second[j]);
      if (!overlap) {
        continue;
      }
      var length = distance(overlap.from, overlap.to);
      if (length > bestLength) {
        bestLength = length;
        best = overlap;
      }
    }
  }
  return best;
}

/**
 * The overlap of two segments that lie on the same line.
 * @param {Array<number>} a0 start of the first segment
 * @param {Array<number>} a1 end of the first segment
 * @param {Array<number>} b0 start of the second segment
 * @param {Array<number>} b1 end of the second segment
 * @returns {object|null} {from, to, key} or null
 */
function collinearOverlap(a0, a1, b0, b1) {
  var eps = 0.01;
  var overlap = alignedOverlap(a0, a1, b0, b1);
  if (!overlap || overlap.gapMax > eps) {
    return null;
  }
  return overlap;
}


/**
 * The name of a recorded route.
 * @param {object} route the route
 * @returns {string} "'A' -> 'B'"
 */
function routeName(route) {
  if (route.name) {
    return route.name;
  }
  return "'" + route.from + "' -> '" + route.to + "'";
}

/**
 * A point as text, one decimal.
 * @param {Array<number>} point the point
 * @returns {string} "(x,y)"
 */
function pointName(point) {
  return '(' + round2(point[0]) + ',' + round2(point[1]) + ')';
}

/**
 * A y inside the gap below a layer (or just below the layer when it is the last one).
 * @param {object} graph the graph
 * @param {number} rank the layer
 * @returns {number} the y
 */
function belowLayer(graph, rank) {
  var bottom = graph.layerTop[rank] + graph.layerHeight[rank];
  if (rank + 1 >= graph.layerTop.length) {
    return bottom + 12;
  }
  return bottom + (graph.gaps[rank] || RANK_GAP) / 2;
}

/**
 * A y inside the gap above a layer (or just above the layer when it is the first one).
 * @param {object} graph the graph
 * @param {number} rank the layer
 * @returns {number} the y
 */
function aboveLayer(graph, rank) {
  var top = graph.layerTop[rank];
  if (rank === 0) {
    return top - 12;
  }
  return top - (graph.gaps[rank - 1] || RANK_GAP) / 2;
}

/**
 * Routes a self-loop as a small rectangle on the right side of the node.
 * @param {object} graph the graph
 * @param {object} edge the edge
 * @param {object} style the line style
 * @param {Array<object>} out the item list
 * @param {Array<object>} labels the label list
 */
function routeSelfLoop(graph, edge, style, out, labels) {
  var node = edge.fromNode;
  var cx = node.x + node.w;
  var cy = node.y + node.h / 2;
  var outX = cx + 18;
  var points = [[cx, cy - 5], [outX, cy - 5], [outX, cy + 5], [cx, cy + 5]];
  out.push(edgePath(graph, points, style));
  // The self-loop gets its identity, so the SVG group and a scene diff can name it. It is
  // deliberately *not* passed to recordRoute(): that would let it take part in the crossing and
  // overlap diagnostics, which changes the numbers of existing pictures - a separate decision.
  var item = out[out.length - 1];
  if (item) {
    item.edgeFrom = edge.from;
    item.edgeTo = edge.to;
  }
  if (edge.arrow === 'end' || edge.arrow === 'both') {
    out.push(sceneLib.arrow(cx, cy + 5, -1, 0));
  }
  if (edge.arrow === 'start' || edge.arrow === 'both') {
    out.push(sceneLib.arrow(cx, cy - 5, -1, 0));
  }
  addEdgeLabel(edge, points, labels);
}

/**
 * Places an edge label at the midpoint of its path.
 * @param {object} edge the edge
 * @param {Array<Array<number>>} points the path
 * @param {Array<object>} labels the label list
 */
function addEdgeLabel(edge, points, labels) {
  if (!edge.label) {
    return;
  }
  var midpoint = pointAtHalfLength(points);
  labels.push(sceneLib.text(midpoint[0], midpoint[1] - 4, edge.label,
    {size: 8, anchor: 'middle', baseline: 'middle', fill: '#444444'}));
}

/**
 * The point halfway along a polyline (by length).
 * @param {Array<Array<number>>} points the points
 * @returns {Array<number>} the point
 */
function pointAtHalfLength(points) {
  var total = 0;
  var i;
  for (i = 1; i < points.length; i++) {
    total += distance(points[i - 1], points[i]);
  }
  if (total === 0) {
    return points[0];
  }
  var target = total / 2;
  var walked = 0;
  for (i = 1; i < points.length; i++) {
    var segmentLength = distance(points[i - 1], points[i]);
    if (walked + segmentLength >= target) {
      var ratio = (target - walked) / (segmentLength || 1);
      return [points[i - 1][0] + (points[i][0] - points[i - 1][0]) * ratio,
        points[i - 1][1] + (points[i][1] - points[i - 1][1]) * ratio];
    }
    walked += segmentLength;
  }
  return points[points.length - 1];
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
 * The right-most node edge in the drawing.
 * @param {object} graph the graph
 * @returns {number} the x coordinate
 */
function rightEdge(graph) {
  var right = 0;
  for (var i = 0; i < graph.nodes.length; i++) {
    if (!graph.nodes[i].dummy) {
      right = Math.max(right, graph.nodes[i].x + graph.nodes[i].w);
    }
  }
  return right;
}

/**
 * The bottom center of a node (or the point itself for a dummy).
 * @param {object} node the node
 * @returns {Array<number>} the point
 */
function bottomCenter(node) {
  if (node.dummy) {
    return [node.x, node.y];
  }
  return [node.x + node.w / 2, node.y + node.h];
}

/**
 * The top center of a node (or the point itself for a dummy).
 * @param {object} node the node
 * @returns {Array<number>} the point
 */
function topCenter(node) {
  if (node.dummy) {
    return [node.x, node.y];
  }
  return [node.x + node.w / 2, node.y];
}

/**
 * Appends a point unless it equals the previous one.
 * @param {Array<Array<number>>} points the point list
 * @param {Array<number>} point the point
 */
function appendPoint(points, point) {
  if (points.length === 0) {
    points.push([point[0], point[1]]);
    return;
  }
  var last = points[points.length - 1];
  if (Math.abs(last[0] - point[0]) > 0.01 || Math.abs(last[1] - point[1]) > 0.01) {
    points.push([point[0], point[1]]);
  }
}

/**
 * Removes duplicate and collinear points from a path.
 * @param {Array<Array<number>>} points the points
 * @returns {Array<Array<number>>} the simplified path
 */
function simplify(points) {
  var out = [];
  for (var i = 0; i < points.length; i++) {
    appendPoint(out, points[i]);
  }
  if (out.length <= 2) {
    return out;
  }
  var result = [out[0]];
  for (var j = 1; j < out.length - 1; j++) {
    var prev = result[result.length - 1];
    var current = out[j];
    var next = out[j + 1];
    var collinear = (Math.abs(prev[0] - current[0]) < 0.01
      && Math.abs(current[0] - next[0]) < 0.01)
      || (Math.abs(prev[1] - current[1]) < 0.01 && Math.abs(current[1] - next[1]) < 0.01);
    if (!collinear) {
      result.push(current);
    }
  }
  result.push(out[out.length - 1]);
  return result;
}

/**
 * Rounds every coordinate of the scene items to a tenth of a point.
 * @param {Array<object>} items the items
 */
function roundItems(items) {
  for (var i = 0; i < items.length; i++) {
    var item = items[i];
    if (item.kind === 'rect' || item.kind === 'group') {
      item.x = sceneLib.round(item.x);
      item.y = sceneLib.round(item.y);
      item.w = sceneLib.round(item.w);
      item.h = sceneLib.round(item.h);
      item.rx = sceneLib.round(item.rx || 0);
    } else if (item.kind === 'ellipse') {
      item.cx = sceneLib.round(item.cx);
      item.cy = sceneLib.round(item.cy);
      item.rx = sceneLib.round(item.rx);
      item.ry = sceneLib.round(item.ry);
    } else if (item.kind === 'text') {
      item.x = sceneLib.round(item.x);
      item.y = sceneLib.round(item.y);
    }
    if (item.points) {
      for (var p = 0; p < item.points.length; p++) {
        item.points[p][0] = sceneLib.round(item.points[p][0]);
        item.points[p][1] = sceneLib.round(item.points[p][1]);
      }
    }
  }
}

/**
 * The ranks by node id, for the diagnostics.
 * @param {object} graph the graph
 * @returns {object} rank by id
 */
function ranksOf(graph) {
  var out = {};
  for (var i = 0; i < graph.nodes.length; i++) {
    if (!graph.nodes[i].dummy) {
      out[graph.nodes[i].id] = graph.nodes[i].rank;
    }
  }
  return out;
}

/**
 * The layer contents by node id, for the diagnostics.
 * @param {object} graph the graph
 * @returns {Array<Array<string>>} the layers
 */
function layerIds(graph) {
  var out = [];
  for (var r = 0; r < graph.layers.length; r++) {
    var ids = [];
    for (var i = 0; i < graph.layers[r].length; i++) {
      if (!graph.layers[r][i].dummy) {
        ids.push(graph.layers[r][i].id);
      }
    }
    out.push(ids);
  }
  return out;
}

/**
 * The back edges, for the diagnostics.
 * @param {object} graph the graph
 * @returns {Array<string>} the edges as 'from>to'
 */
function backEdgeIds(graph) {
  var out = [];
  for (var i = 0; i < graph.edges.length; i++) {
    if (graph.edges[i].back) {
      out.push(graph.edges[i].from + '>' + graph.edges[i].to);
    }
  }
  return out;
}

/**
 * The number of routing channels used (the back-edge channels plus one per segment lane).
 * @param {object} graph the graph
 * @returns {number} the channel count
 */
function countChannels(graph) {
  var back = graph.backChannelCount;
  if (back === undefined) {
    back = 0;
    for (var i = 0; i < graph.edges.length; i++) {
      if (graph.edges[i].back || graph.edges[i].side) {
        back++;
      }
    }
  }
  return back + (graph.segments ? graph.segments.length : 0);
}

/**
 * The ids of the pinned nodes, for the diagnostics.
 * @param {object} graph the graph
 * @returns {Array<string>} the ids
 */
function pinnedIds(graph) {
  var out = [];
  for (var i = 0; i < graph.nodes.length; i++) {
    if (graph.nodes[i].fixed) {
      out.push(graph.nodes[i].id);
    }
  }
  return out;
}

/**
 * The current time in milliseconds (0 when the sandbox has no Date).
 * @returns {number} the time
 */
function now() {
  return (typeof Date !== 'undefined' && Date.now) ? Date.now() : 0;
}

/**
 * The milliseconds since a start time.
 * @param {number} start the start time
 * @returns {number} the elapsed milliseconds
 */
function elapsed(start) {
  return now() - start;
}

module.exports = {
  run: run
};
