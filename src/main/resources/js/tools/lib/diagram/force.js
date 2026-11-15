/**
 * diagram/force - the force-directed placement (a spring embedder), the second placement strategy
 * next to the layered one.
 *
 * The layered layout is hierarchical: ranks, an order inside a rank, then a channel per edge. That
 * is the right picture for a flow chart and the wrong one for a graph whose edges point in all
 * directions. This module computes coordinates from a small physics simulation instead
 * (docs/js/diagram-plan.md §13.7):
 *
 *   repulsion   every pair of nodes pushes itself apart (Fruchterman-Reingold, k^2 / d)
 *   collision   the node boxes, inflated by a gap, are pushed apart along the axis of the smaller
 *               penetration - so the picture leaves room between the boxes
 *   springs     every edge pulls its two ends together; the edge weight is the spring strength
 *               (a heavy edge is stiffer and shorter, rest length = springLength / sqrt(weight))
 *   direction   one extra term along the flow axis (TB/BT/LR/RL) keeps the edges pointing the way
 *               the diagram says - organic, but still readable as a flow
 *   clusters    the nodes of one top-level group are pulled towards their centroid
 *
 * Then the boxes are separated until no two of them overlap (a pinned node never moves, the other
 * side of the pair does), and the result is measured: crossings of the drawn lines, how many of them
 * pass through a third box, and the edge sum.
 *
 * The run is a <b>multi-start</b> (several runs with different seeds). The runs are ranked by
 * {@code crossings + 3 * boxHits} - one line through a box covers a label, so it weighs like three
 * crossings - then by the shorter edge sum, and at the very end by the earlier run, so the choice is
 * deterministic too.
 *
 * Three modes, one force field:
 *
 *   place()   the full placement: random start, multi-start, separation
 *   relax()   a local relaxation in place: only the nodes the caller marks movable move, everything
 *             else stays and still pushes - what a nudge needs so the picture has no dent
 *   (a bare nudge does no physics at all; it is the layout's business, not this module's)
 *
 * Determinism is not negotiable in this project (docs/js/diagram-plan.md §2.6, §11.9): the random
 * numbers come from an xorshift32 seeded by the caller, never from {@code Math.random}, so the same
 * input produces the same coordinates on every machine and in every run.
 *
 *     var report = require('./diagram/force.js').place(nodes, edges, {seed: 4711, rankOf: fn});
 *
 * The nodes are plain objects {@code {x, y, w, h, fixed}} and are moved in place; the edges are
 * {@code {from, to, weight, constraint}} with indexes into the node array (self-loops are the
 * caller's business - a loop has no direction to attract).
 */

'use strict';

/** The default rest length of an edge spring, in points. */
var SPRING_LENGTH = 100;

/** The default number of simulation steps per run. */
var ITERATIONS = 200;

/** The default number of runs (multi-start). */
var RUNS = 3;

/** The default seed - fixed, so a caller who says nothing gets the same picture every time. */
var SEED = 4711;

/**
 * How many pair interactions one run may cost. The requested number of iterations is cut back for a
 * big graph, so 300 nodes do not turn one layout into seconds of simulation
 * (docs/js/diagram-plan.md §8, the phase budget).
 */
var PAIR_BUDGET = 1500000;

/** How many pair interactions all runs together may cost (bounds the multi-start). */
var RUN_BUDGET = 6000000;

/** The largest step of the first iteration, as a share of the spring length. */
var MAX_STEP = 0.35;

/**
 * The strength of the repulsion, relative to {@code length * length / d}. The springs are linear
 * (d - rest), the repulsion decays with the distance, so the two balance at roughly 1.7 x
 * springLength for a node with one neighbour and a little further out for a node with many - which is
 * the scale a picture of boxes wants.
 */
var REPULSION = 0.15;

/** How hard the flow direction of an edge is enforced (relative to its spring force). */
var DIRECTION_BIAS = 0.5;

/** How hard the nodes of one group are pulled towards their group's centroid. */
var CLUSTER_STRENGTH = 0.05;

/** How hard two overlapping (inflated) boxes are pushed apart. */
var COLLISION = 0.6;

/**
 * How many crossings one line through a node box is worth when the runs are ranked: such a line
 * covers a label, which a reader notices at once, while a crossing is normal in an organic picture.
 */
var BOX_HIT_WEIGHT = 3;

/** The free space a node keeps around itself while the boxes are separated, in points. */
var GAP_X = 22;
var GAP_Y = 16;

/**
 * The largest step of a local relaxation, as a share of the spring length. A relaxation must not
 * rearrange the picture, only let the neighbourhood follow a correction, so its steps are much
 * smaller than the ones of the placement.
 */
var RELAX_STEP = 0.12;

/** The default number of relaxation steps after a nudge. */
var RELAX_ITERATIONS = 100;

/** How many separation steps run after the simulation (worst overlap first, stops early). */
var SEPARATE_PASSES = 150;

/** A distance below this (in points) counts as "the same place". */
var EPSILON = 0.01;

/** The defaults, so a caller (the layout) can show what it is going to use. */
var DEFAULTS = {
  springLength: SPRING_LENGTH,
  iterations: ITERATIONS,
  runs: RUNS,
  seed: SEED,
  gapX: GAP_X,
  gapY: GAP_Y,
  relaxIterations: RELAX_ITERATIONS
};

/**
 * Relaxes a neighbourhood in place - the third mode next to a full placement ({@link place}) and a
 * bare nudge (which does no physics at all).
 * <p>
 * It starts from the positions the caller hands in (no random start, no multi-start, no re-packing)
 * and runs a few steps of the same forces, but <b>only the nodes marked movable move</b>. Everything
 * else stays exactly where it is and still pushes and pulls - a fixed obstacle, not a hole. That is
 * what a correction needs: the nodes around a nudged one follow a little instead of leaving a dent,
 * and the rest of the picture keeps its shape.
 * </p>
 * <p>
 * Deterministic like everything else here: no random numbers take part, and the step size is capped
 * by a small temperature, so the same input gives the same coordinates.
 * </p>
 * @param {Array<object>} nodes the nodes {@code {x, y, w, h, fixed}} - moved in place
 * @param {Array<object>} edges the edges {@code {from, to, weight, constraint}} (indexes)
 * @param {object} options {movable: Array<boolean>, iterations, springLength, direction, gapX,
 *   gapY, groupOf(index)}
 * @returns {object} the report {iterations, moved, unresolved, crossings, boxHits}
 */
function relax(nodes, edges, options) {
  var opts = options || {};
  var settings = {
    springLength: positiveOr(opts.springLength, SPRING_LENGTH),
    iterations: positiveOr(opts.iterations, RELAX_ITERATIONS),
    direction: directionOf(opts.direction),
    gapX: positiveOr(opts.gapX, GAP_X),
    gapY: positiveOr(opts.gapY, GAP_Y),
    groupOf: typeof opts.groupOf === 'function' ? opts.groupOf : null,
    movable: Array.isArray(opts.movable) ? opts.movable : null,
    maxStep: RELAX_STEP
  };
  settings.iterations = Math.max(1, Math.round(settings.iterations));
  var before = snapshot(nodes);
  simulate(nodes, edges, settings);
  var unresolved = separate(nodes, settings);
  var moved = 0;
  for (var i = 0; i < nodes.length; i++) {
    if (Math.abs(nodes[i].x - before[i][0]) > 0.05
        || Math.abs(nodes[i].y - before[i][1]) > 0.05) {
      moved++;
    }
  }
  return {
    iterations: settings.iterations,
    moved: moved,
    unresolved: unresolved,
    crossings: countCrossings(nodes, edges),
    boxHits: countBoxHits(nodes, edges)
  };
}

/**
 * Places the nodes by a force-directed simulation with a multi-start.
 * @param {Array<object>} nodes the nodes {@code {x, y, w, h, fixed}} - moved in place
 * @param {Array<object>} edges the edges {@code {from, to, weight, constraint}} (indexes)
 * @param {object} [options] {springLength, iterations, runs, seed, direction, gapX, gapY,
 *   groupOf(index), rankOf(index)}
 * @returns {object} the report {iterations, runs: [{crossings, boxHits, score, length,
 *   unresolved}], chosen, crossings, boxHits, score, unresolved, springLength, seed, direction}
 */
function place(nodes, edges, options) {
  var opts = options || {};
  var settings = {
    springLength: positiveOr(opts.springLength, SPRING_LENGTH),
    iterations: positiveOr(opts.iterations, ITERATIONS),
    runs: positiveOr(opts.runs, RUNS),
    seed: seedOf(opts.seed),
    direction: directionOf(opts.direction),
    gapX: positiveOr(opts.gapX, GAP_X),
    gapY: positiveOr(opts.gapY, GAP_Y),
    groupOf: typeof opts.groupOf === 'function' ? opts.groupOf : null,
    rankOf: typeof opts.rankOf === 'function' ? opts.rankOf : null
  };
  settings.iterations = Math.max(1, Math.round(settings.iterations));
  settings.runs = Math.max(1, Math.round(settings.runs));
  settings.iterations = fitIterations(settings.iterations, nodes.length);
  settings.runs = fitRuns(settings.runs, nodes.length, settings.iterations);

  var report = {
    iterations: settings.iterations,
    runs: [],
    chosen: 0,
    crossings: 0,
    boxHits: 0,
    score: 0,
    unresolved: 0,
    springLength: settings.springLength,
    seed: settings.seed,
    direction: settings.direction
  };
  var best = null;
  for (var run = 0; run < settings.runs; run++) {
    var random = createRandom(settings.seed + run * 7919);
    initialPlacement(nodes, settings, random, run);
    simulate(nodes, edges, settings);
    var unresolved = separate(nodes, settings);
    var crossings = countCrossings(nodes, edges);
    var boxHits = countBoxHits(nodes, edges);
    var length = totalLength(nodes, edges);
    var runScore = score(crossings, boxHits);
    report.runs.push({crossings: crossings, boxHits: boxHits, score: runScore,
      length: round(length), unresolved: unresolved});
    if (best === null || runScore < best.score
        || (runScore === best.score && length < best.length - 1e-9)) {
      best = {crossings: crossings, boxHits: boxHits, score: runScore, length: length,
        index: run, positions: snapshot(nodes)};
    }
  }
  restore(nodes, best.positions);
  report.chosen = best.index;
  report.crossings = best.crossings;
  report.boxHits = best.boxHits;
  report.score = best.score;
  report.unresolved = report.runs[best.index].unresolved;
  return report;
}

/**
 * The score a run is ranked by: the crossings plus the lines through a box, weighted.
 * @param {number} crossings the crossings of this run
 * @param {number} boxHits the lines through a box of this run
 * @returns {number} the score (smaller is better)
 */
function score(crossings, boxHits) {
  return crossings + BOX_HIT_WEIGHT * boxHits;
}

/**
 * A seed from the option, or the default. A seed is a whole number; the sign is dropped so that
 * -1 and 1 are not two different arrangements by accident.
 * @param {*} value the option value
 * @returns {number} the seed
 */
function seedOf(value) {
  if (typeof value !== 'number' || !isFinite(value)) {
    return SEED;
  }
  return Math.floor(Math.abs(value));
}

/**
 * A direction from the option, or TB.
 * @param {*} value the option value
 * @returns {string} TB | BT | LR | RL
 */
function directionOf(value) {
  var text = (value === undefined || value === null) ? 'TB' : String(value).toUpperCase();
  if (text === 'BT' || text === 'LR' || text === 'RL') {
    return text;
  }
  return 'TB';
}

/**
 * A positive number from the option, or the default.
 * @param {*} value the option value
 * @param {number} fallback the default
 * @returns {number} the number
 */
function positiveOr(value, fallback) {
  if (typeof value !== 'number' || !isFinite(value) || value <= 0) {
    return fallback;
  }
  return value;
}

/**
 * The number of pairs of nodes - the unit of the simulation's cost.
 * @param {number} count the node count
 * @returns {number} the number of pairs (at least 1)
 */
function pairCount(count) {
  return Math.max(count * (count - 1) / 2, 1);
}

/**
 * Cuts the requested iterations back so that one run stays inside the pair budget.
 * @param {number} requested the requested iterations
 * @param {number} count the node count
 * @returns {number} the iterations to run (at least 1)
 */
function fitIterations(requested, count) {
  var affordable = Math.floor(PAIR_BUDGET / pairCount(count));
  return Math.max(1, Math.min(requested, affordable));
}

/**
 * Cuts the requested runs back so that all runs together stay inside the run budget.
 * @param {number} requested the requested runs
 * @param {number} count the node count
 * @param {number} iterations the iterations per run
 * @returns {number} the runs to do (at least 1)
 */
function fitRuns(requested, count, iterations) {
  var perRun = Math.max(iterations * pairCount(count), 1);
  var affordable = Math.floor(RUN_BUDGET / perRun);
  return Math.max(1, Math.min(requested, affordable));
}

/**
 * The deterministic random number generator of this module: xorshift32, seeded with a constant for
 * every run. Bit operations are exactly specified in JavaScript, so the sequence is the same in
 * every engine - which is what makes the layout reproducible.
 * @param {number} seed the seed
 * @returns {Function} {@code function () -> [0, 1)}
 */
function createRandom(seed) {
  var state = Math.floor(Math.abs(seed)) >>> 0;
  if (state === 0) {
    state = 0x9e3779b9;
  }
  return function () {
    state ^= (state << 13);
    state >>>= 0;
    state ^= (state >>> 17);
    state ^= (state << 5);
    state >>>= 0;
    return state / 4294967296;
  };
}

/**
 * The flow axis of a direction: the coordinate that runs from the sources to the targets, and its
 * sign.
 * @param {string} direction TB | BT | LR | RL
 * @returns {object} {horizontal, sign}
 */
function flowAxis(direction) {
  var horizontal = direction === 'LR' || direction === 'RL';
  var sign = (direction === 'BT' || direction === 'RL') ? -1 : 1;
  return {horizontal: horizontal, sign: sign};
}

/**
 * The starting arrangement: when the caller knows the ranks, the rank gives the position along the
 * flow axis and the rest is jitter - that converges much faster (and keeps the flow direction) than
 * a purely random start. Without ranks the nodes start on a disc.
 * @param {Array<object>} nodes the nodes (modified)
 * @param {object} settings the settings
 * @param {Function} random the random number generator
 * @param {number} run the run index (later runs jitter more, so they explore other arrangements)
 */
function initialPlacement(nodes, settings, random, run) {
  var count = nodes.length;
  var spread = settings.springLength * Math.sqrt(Math.max(count, 1));
  var axis = flowAxis(settings.direction);
  var minRank = null;
  var i;
  for (i = 0; i < count; i++) {
    var rankValue = settings.rankOf ? settings.rankOf(i) : null;
    if (typeof rankValue === 'number' && isFinite(rankValue)) {
      minRank = (minRank === null) ? rankValue : Math.min(minRank, rankValue);
    }
  }
  var jitter = (run === 0 ? 0.2 : 0.8) * spread;
  for (i = 0; i < count; i++) {
    var node = nodes[i];
    if (node.fixed) {
      node.x = node.fixed.x;
      node.y = node.fixed.y;
      continue;
    }
    var rank = settings.rankOf ? settings.rankOf(i) : null;
    var flow;
    if (minRank !== null && typeof rank === 'number' && isFinite(rank)) {
      flow = (rank - minRank) * settings.springLength;
    } else {
      flow = spread * (random() - 0.5);
    }
    flow *= axis.sign;
    var across = (random() - 0.5) * jitter;
    if (axis.horizontal) {
      node.x = flow;
      node.y = across;
    } else {
      node.x = across;
      node.y = flow;
    }
  }
}

/**
 * Runs the simulation: forces first, then a step that is limited by the cooling temperature.
 * @param {Array<object>} nodes the nodes (modified)
 * @param {Array<object>} edges the edges
 * @param {object} settings the settings
 */
function simulate(nodes, edges, settings) {
  var count = nodes.length;
  if (count === 0) {
    return;
  }
  var length = settings.springLength;
  var initialStep = (settings.maxStep === undefined ? MAX_STEP : settings.maxStep) * length;
  var axis = flowAxis(settings.direction);
  for (var iteration = 0; iteration < settings.iterations; iteration++) {
    var temperature = initialStep * (1 - iteration / settings.iterations) + 0.4;
    var fx = [];
    var fy = [];
    for (var i = 0; i < count; i++) {
      fx.push(0);
      fy.push(0);
    }
    addRepulsion(nodes, settings, fx, fy, length);
    addSprings(nodes, edges, settings, fx, fy, length, axis);
    if (settings.groupOf) {
      addClusters(nodes, settings, fx, fy);
    }
    move(nodes, settings, fx, fy, temperature);
  }
}

/**
 * Whether a node may move in this run: a pinned node never moves, and a relaxation moves only the
 * nodes it marked movable.
 * @param {object} settings the settings ({movable} or null)
 * @param {number} index the node index
 * @returns {boolean} true when the node may move
 */
function isMovable(settings, index) {
  if (settings.movable === null || settings.movable === undefined) {
    return true;
  }
  return Boolean(settings.movable[index]);
}

/**
 * Repulsion between every pair of nodes, plus the box collision (the boxes, inflated by the gap,
 * push apart along the axis of the smaller penetration).
 * @param {Array<object>} nodes the nodes
 * @param {object} settings the settings
 * @param {Array<number>} fx the force in x (accumulated)
 * @param {Array<number>} fy the force in y (accumulated)
 * @param {number} length the spring length (the scale of the repulsion)
 */
function addRepulsion(nodes, settings, fx, fy, length) {
  for (var i = 0; i < nodes.length; i++) {
    var a = nodes[i];
    var acx = a.x + a.w / 2;
    var acy = a.y + a.h / 2;
    for (var j = i + 1; j < nodes.length; j++) {
      var b = nodes[j];
      var dx = (b.x + b.w / 2) - acx;
      var dy = (b.y + b.h / 2) - acy;
      if (Math.abs(dx) < EPSILON && Math.abs(dy) < EPSILON) {
        // Two nodes on the same point: pull them apart deterministically (a zero vector has no
        // direction, and Math.random is not available here anyway).
        dx = (i < j) ? EPSILON : -EPSILON;
        dy = EPSILON;
      }
      var d = Math.sqrt(dx * dx + dy * dy);
      if (d < EPSILON) {
        d = EPSILON;
      }
      var push = REPULSION * length * length / d;
      fx[i] -= dx / d * push;
      fy[i] -= dy / d * push;
      fx[j] += dx / d * push;
      fy[j] += dy / d * push;
      var overlapX = (a.w + b.w) / 2 + settings.gapX - Math.abs(dx);
      var overlapY = (a.h + b.h) / 2 + settings.gapY - Math.abs(dy);
      if (overlapX > 0 && overlapY > 0) {
        if (overlapX <= overlapY) {
          var pushX = overlapX * COLLISION * (dx >= 0 ? 1 : -1);
          fx[i] -= pushX;
          fx[j] += pushX;
        } else {
          var pushY = overlapY * COLLISION * (dy >= 0 ? 1 : -1);
          fy[i] -= pushY;
          fy[j] += pushY;
        }
      }
    }
  }
}

/**
 * The springs of the edges (weight = strength, rest length shrinks with the weight) and the flow
 * term along the direction axis.
 * @param {Array<object>} nodes the nodes
 * @param {Array<object>} edges the edges
 * @param {object} settings the settings
 * @param {Array<number>} fx the force in x (accumulated)
 * @param {Array<number>} fy the force in y (accumulated)
 * @param {number} length the spring length
 * @param {object} axis the flow axis {horizontal, sign}
 */
function addSprings(nodes, edges, settings, fx, fy, length, axis) {
  for (var e = 0; e < edges.length; e++) {
    var edge = edges[e];
    var from = edge.from;
    var to = edge.to;
    if (from === to || !nodes[from] || !nodes[to]) {
      continue;
    }
    var a = nodes[from];
    var b = nodes[to];
    var dx = (b.x + b.w / 2) - (a.x + a.w / 2);
    var dy = (b.y + b.h / 2) - (a.y + a.h / 2);
    var d = Math.sqrt(dx * dx + dy * dy);
    if (d < EPSILON) {
      d = EPSILON;
    }
    var weight = (typeof edge.weight === 'number' && edge.weight > 0) ? edge.weight : 1;
    var rest = length / Math.sqrt(weight);
    var pull = weight * (d - rest);
    if (d > EPSILON * 2) {
      fx[from] += dx / d * pull;
      fy[from] += dy / d * pull;
      fx[to] -= dx / d * pull;
      fy[to] -= dy / d * pull;
    }
    if (edge.constraint === false) {
      // A dangling edge (Mermaid's ~~~) says nothing about the flow direction.
      continue;
    }
    // The target has to be "ahead" of the source by one rest length; a shortfall is pushed along
    // the flow axis only, so the picture keeps pointing where the diagram points.
    var ahead = axis.horizontal ? dx * axis.sign : dy * axis.sign;
    var shortfall = rest - ahead;
    if (shortfall > 0) {
      var bias = DIRECTION_BIAS * weight * Math.min(shortfall, 2 * rest) * axis.sign;
      if (axis.horizontal) {
        fx[from] -= bias;
        fx[to] += bias;
      } else {
        fy[from] -= bias;
        fy[to] += bias;
      }
    }
  }
}

/**
 * Pulls the nodes of one top-level group towards the centroid of that group, so a subgraph's band
 * stays together (its rectangle is the hull of its members).
 * @param {Array<object>} nodes the nodes
 * @param {object} settings the settings
 * @param {Array<number>} fx the force in x (accumulated)
 * @param {Array<number>} fy the force in y (accumulated)
 */
function addClusters(nodes, settings, fx, fy) {
  var sums = {};
  var keys = [];
  var i;
  for (i = 0; i < nodes.length; i++) {
    var key = settings.groupOf(i);
    if (key === null || key === undefined) {
      continue;
    }
    var entry = sums[key];
    if (!entry) {
      entry = {x: 0, y: 0, count: 0};
      sums[key] = entry;
      keys.push(key);
    }
    entry.x += nodes[i].x + nodes[i].w / 2;
    entry.y += nodes[i].y + nodes[i].h / 2;
    entry.count++;
  }
  if (keys.length === 0) {
    return;
  }
  for (var k = 0; k < keys.length; k++) {
    var cluster = sums[keys[k]];
    cluster.x /= cluster.count;
    cluster.y /= cluster.count;
  }
  for (i = 0; i < nodes.length; i++) {
    var nodeKey = settings.groupOf(i);
    if (nodeKey === null || nodeKey === undefined) {
      continue;
    }
    var target = sums[nodeKey];
    if (!target || target.count < 2) {
      continue;
    }
    fx[i] += (target.x - (nodes[i].x + nodes[i].w / 2)) * CLUSTER_STRENGTH;
    fy[i] += (target.y - (nodes[i].y + nodes[i].h / 2)) * CLUSTER_STRENGTH;
  }
}

/**
 * Moves every free node along its accumulated force, limited by the temperature.
 * @param {Array<object>} nodes the nodes (modified)
 * @param {object} settings the settings (decides which nodes may move)
 * @param {Array<number>} fx the force in x
 * @param {Array<number>} fy the force in y
 * @param {number} temperature the largest step of this iteration
 */
function move(nodes, settings, fx, fy, temperature) {
  for (var i = 0; i < nodes.length; i++) {
    var node = nodes[i];
    if (node.fixed || !isMovable(settings, i)) {
      continue;
    }
    var dx = fx[i];
    var dy = fy[i];
    var force = Math.sqrt(dx * dx + dy * dy);
    if (force < EPSILON) {
      continue;
    }
    var step = Math.min(force, temperature);
    node.x += dx / force * step;
    node.y += dy / force * step;
  }
}

/**
 * Separates the boxes until no two inflated boxes overlap: always the deepest overlap first, along
 * the axis of the smaller penetration. A pinned node never moves - the other side of the pair takes
 * the whole step. When both sides are pinned the pair cannot be resolved, and the pass stops
 * (the caller reports what is left instead of looping).
 * @param {Array<object>} nodes the nodes (modified)
 * @param {object} settings the settings
 * @returns {number} how many node boxes still overlap (0 when the picture is clean)
 */
function separate(nodes, settings) {
  // A pair of nodes that may not move at all cannot be resolved - remember it and keep separating
  // the others, so one frozen overlap does not stop the whole pass (the count reports it at the end).
  var frozen = {};
  for (var pass = 0; pass < SEPARATE_PASSES; pass++) {
    var worst = findWorstOverlap(nodes, settings, frozen);
    if (worst === null) {
      return countBoxOverlaps(nodes);
    }
    if (!separatePair(nodes, worst, settings)) {
      frozen[worst.a + ':' + worst.b] = true;
    }
  }
  return countBoxOverlaps(nodes);
}

/**
 * Finds the pair of nodes whose inflated boxes overlap most.
 * @param {Array<object>} nodes the nodes
 * @param {object} settings the settings
 * @param {object} [frozen] pairs that may not be separated (keyed 'a:b')
 * @returns {object|null} {a, b, x, y, dx, dy, penetration} or null
 */
function findWorstOverlap(nodes, settings, frozen) {
  var worst = null;
  for (var i = 0; i < nodes.length; i++) {
    var a = nodes[i];
    var acx = a.x + a.w / 2;
    var acy = a.y + a.h / 2;
    for (var j = i + 1; j < nodes.length; j++) {
      if (frozen && frozen[i + ':' + j]) {
        continue;
      }
      var b = nodes[j];
      var dx = (b.x + b.w / 2) - acx;
      var dy = (b.y + b.h / 2) - acy;
      var overlapX = (a.w + b.w) / 2 + settings.gapX - Math.abs(dx);
      var overlapY = (a.h + b.h) / 2 + settings.gapY - Math.abs(dy);
      if (overlapX <= 0 || overlapY <= 0) {
        continue;
      }
      var penetration = Math.min(overlapX, overlapY);
      if (worst === null || penetration > worst.penetration + 1e-9) {
        worst = {a: i, b: j, x: overlapX, y: overlapY, dx: dx, dy: dy,
          penetration: penetration};
      }
    }
  }
  return worst;
}

/**
 * Pushes one overlapping pair apart.
 * @param {Array<object>} nodes the nodes (modified)
 * @param {object} overlap the overlap from {@link findWorstOverlap}
 * @param {object} settings the settings (decides which nodes may move)
 * @returns {boolean} false when both nodes are pinned and nothing can move
 */
function separatePair(nodes, overlap, settings) {
  var a = nodes[overlap.a];
  var b = nodes[overlap.b];
  var aFree = !a.fixed && isMovable(settings, overlap.a);
  var bFree = !b.fixed && isMovable(settings, overlap.b);
  if (!aFree && !bFree) {
    return false;
  }
  var horizontal = overlap.x <= overlap.y;
  var penetration = (horizontal ? overlap.x : overlap.y) + 0.5;
  var delta = horizontal ? overlap.dx : overlap.dy;
  var direction = delta > EPSILON ? 1 : (delta < -EPSILON ? -1
    : (overlap.a < overlap.b ? 1 : -1));
  var share = (aFree && bFree) ? penetration / 2 : penetration;
  if (horizontal) {
    if (aFree) {
      a.x -= direction * share;
    }
    if (bFree) {
      b.x += direction * share;
    }
  } else {
    if (aFree) {
      a.y -= direction * share;
    }
    if (bFree) {
      b.y += direction * share;
    }
  }
  return true;
}

/**
 * The line a reader sees for an edge: from the border of the source box to the border of the target
 * box (the drawing uses exactly this segment, so the numbers of the multi-start match what the scene
 * reports as {@code phases.drawnCrossings} and {@code phases.boxHits}).
 * @param {Array<object>} nodes the nodes
 * @param {object} edge the edge
 * @returns {object|null} {x0, y0, x1, y1} or null for a loop or a missing node
 */
function drawnLine(nodes, edge) {
  if (edge.from === edge.to || !nodes[edge.from] || !nodes[edge.to]) {
    return null;
  }
  var from = nodes[edge.from];
  var to = nodes[edge.to];
  var start = borderPoint(from, to);
  var end = borderPoint(to, from);
  return {x0: start[0], y0: start[1], x1: end[0], y1: end[1]};
}

/**
 * The point where the line between two node centres leaves a box.
 * @param {object} node the node
 * @param {object} other the other node
 * @returns {Array<number>} the border point
 */
function borderPoint(node, other) {
  var cx = node.x + node.w / 2;
  var cy = node.y + node.h / 2;
  var dx = (other.x + other.w / 2) - cx;
  var dy = (other.y + other.h / 2) - cy;
  if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) {
    return [cx, cy];
  }
  var tx = Math.abs(dx) < 1e-9 ? Infinity : (node.w / 2) / Math.abs(dx);
  var ty = Math.abs(dy) < 1e-9 ? Infinity : (node.h / 2) / Math.abs(dy);
  var t = Math.min(tx, ty);
  return [cx + dx * t, cy + dy * t];
}

/**
 * Counts the node pairs whose real boxes still overlap (the gap of the separation does not count -
 * this is the check the acceptance uses).
 * @param {Array<object>} nodes the nodes
 * @returns {number} the number of overlapping pairs
 */
function countBoxOverlaps(nodes) {
  var total = 0;
  for (var i = 0; i < nodes.length; i++) {
    for (var j = i + 1; j < nodes.length; j++) {
      var dx = Math.abs((nodes[j].x + nodes[j].w / 2) - (nodes[i].x + nodes[i].w / 2));
      var dy = Math.abs((nodes[j].y + nodes[j].h / 2) - (nodes[i].y + nodes[i].h / 2));
      if (dx < (nodes[i].w + nodes[j].w) / 2 - 0.5
          && dy < (nodes[i].h + nodes[j].h) / 2 - 0.5) {
        total++;
      }
    }
  }
  return total;
}

/**
 * Counts the crossings of the lines a reader sees (border to border, the same measure the scene
 * reports) - the first criterion of the multi-start.
 * @param {Array<object>} nodes the nodes
 * @param {Array<object>} edges the edges
 * @returns {number} the number of crossings
 */
function countCrossings(nodes, edges) {
  var lines = [];
  var i;
  for (i = 0; i < edges.length; i++) {
    var line = drawnLine(nodes, edges[i]);
    if (line !== null) {
      lines.push(line);
    }
  }
  var total = 0;
  for (i = 0; i < lines.length; i++) {
    for (var j = i + 1; j < lines.length; j++) {
      if (segmentsCross(lines[i], lines[j])) {
        total++;
      }
    }
  }
  return total;
}

/**
 * Whether two segments cross in their interior (a shared endpoint or a touch does not count).
 * @param {object} first {x0, y0, x1, y1}
 * @param {object} second {x0, y0, x1, y1}
 * @returns {boolean} true when they cross
 */
function segmentsCross(first, second) {
  var ax = first.x1 - first.x0;
  var ay = first.y1 - first.y0;
  var bx = second.x1 - second.x0;
  var by = second.y1 - second.y0;
  var denominator = ax * by - ay * bx;
  if (Math.abs(denominator) < 1e-9) {
    return false;
  }
  var t = ((second.x0 - first.x0) * by - (second.y0 - first.y0) * bx) / denominator;
  var u = ((second.x0 - first.x0) * ay - (second.y0 - first.y0) * ax) / denominator;
  return t > 0.001 && t < 0.999 && u > 0.001 && u < 0.999;
}

/**
 * Counts the (edge, box) pairs where the line of an edge passes through a third node's box. A force
 * layout has no channel to route around such a box, so this number is what a reader sees as a defect
 * - and it is the second criterion of the multi-start.
 * @param {Array<object>} nodes the nodes
 * @param {Array<object>} edges the edges
 * @returns {number} the number of edge/box hits
 */
function countBoxHits(nodes, edges) {
  var hits = 0;
  for (var e = 0; e < edges.length; e++) {
    var edge = edges[e];
    var line = drawnLine(nodes, edge);
    if (line === null) {
      continue;
    }
    for (var i = 0; i < nodes.length; i++) {
      if (i === edge.from || i === edge.to) {
        continue;
      }
      if (crossesBox(line.x0, line.y0, line.x1, line.y1, nodes[i])) {
        hits++;
      }
    }
  }
  return hits;
}

/**
 * Whether a segment crosses the open interior of a node box (Liang-Barsky clipping; a border touch
 * does not count, an edge attaches there).
 * @param {number} x0 the segment start x
 * @param {number} y0 the segment start y
 * @param {number} x1 the segment end x
 * @param {number} y1 the segment end y
 * @param {object} node the node box
 * @returns {boolean} true when the segment crosses the interior
 */
function crossesBox(x0, y0, x1, y1, node) {
  var eps = 0.8;
  var left = node.x + eps;
  var right = node.x + node.w - eps;
  var top = node.y + eps;
  var bottom = node.y + node.h - eps;
  if (left >= right || top >= bottom) {
    return false;
  }
  var dx = x1 - x0;
  var dy = y1 - y0;
  var p = [-dx, dx, -dy, dy];
  var q = [x0 - left, right - x0, y0 - top, bottom - y0];
  var t0 = 0;
  var t1 = 1;
  for (var i = 0; i < 4; i++) {
    if (Math.abs(p[i]) < 1e-9) {
      if (q[i] < 0) {
        return false;
      }
      continue;
    }
    var r = q[i] / p[i];
    if (p[i] < 0) {
      if (r > t1) {
        return false;
      }
      if (r > t0) {
        t0 = r;
      }
    } else {
      if (r < t0) {
        return false;
      }
      if (r < t1) {
        t1 = r;
      }
    }
  }
  return t1 > t0;
}

/**
 * The sum of the drawn line lengths (border to border) - the third criterion of the multi-start.
 * @param {Array<object>} nodes the nodes
 * @param {Array<object>} edges the edges
 * @returns {number} the length sum
 */
function totalLength(nodes, edges) {
  var total = 0;
  for (var i = 0; i < edges.length; i++) {
    var line = drawnLine(nodes, edges[i]);
    if (line === null) {
      continue;
    }
    var dx = line.x1 - line.x0;
    var dy = line.y1 - line.y0;
    total += Math.sqrt(dx * dx + dy * dy);
  }
  return total;
}

/**
 * Saves the positions of all nodes.
 * @param {Array<object>} nodes the nodes
 * @returns {Array<Array<number>>} the positions
 */
function snapshot(nodes) {
  var positions = [];
  for (var i = 0; i < nodes.length; i++) {
    positions.push([nodes[i].x, nodes[i].y]);
  }
  return positions;
}

/**
 * Puts the nodes back on saved positions.
 * @param {Array<object>} nodes the nodes (modified)
 * @param {Array<Array<number>>} positions the positions from {@link snapshot}
 */
function restore(nodes, positions) {
  for (var i = 0; i < nodes.length && i < positions.length; i++) {
    nodes[i].x = positions[i][0];
    nodes[i].y = positions[i][1];
  }
}

/**
 * Rounds to a tenth of a point (the precision of the scene).
 * @param {number} value the value
 * @returns {number} the rounded value
 */
function round(value) {
  return Math.round(value * 10) / 10;
}

module.exports = {
  DEFAULTS: DEFAULTS,
  place: place,
  relax: relax
};
