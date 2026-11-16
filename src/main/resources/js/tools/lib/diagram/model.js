/**
 * diagram/model - the intermediate representation (IR) between a frontend (Mermaid, DOT) and the
 * layout.
 *
 * The IR is plain JSON: it can be stored, diffed and patched, and it is the seam at which a caller
 * (or an LLM) corrects a diagram before it is laid out. This module is the only place that decides
 * what a valid model is: an unknown shape, a duplicate id, an edge that names a missing node or a
 * limit that is exceeded is an <b>error with a reason</b>, never a silent default.
 *
 *     var model = require('./diagram/model.js');
 *     var normalized = model.normalize(raw, model.LIMITS);   // throws on a real defect
 *
 * Coordinates and sizes in the IR are layout-independent; the node size is filled in by the
 * measuring phase (p0measure), not by the frontend. See docs/js/diagram-plan.md §4.
 */

'use strict';

/** The four flow directions a graph can be laid out in. */
var DIRECTIONS = ['TB', 'BT', 'LR', 'RL'];

/** The node shapes the scene backends know how to draw. */
var SHAPES = ['rect', 'round', 'stadium', 'diamond', 'circle', 'subroutine', 'cylinder',
  'hexagon', 'start', 'end'];

/** The stroke styles an edge can have. */
var EDGE_STYLES = ['solid', 'dotted', 'thick'];

/** Which end(s) of an edge carry an arrowhead. */
var ARROWS = ['end', 'start', 'both', 'none'];

/** Port sides: north, east, south, west. */
var SIDES = ['n', 'e', 's', 'w'];

/**
 * How an edge is drawn: orthogonal (default), a shared tree bus, one straight line, or a smooth curve
 * with a short stub on the node's side (the Mermaid look).
 */
var ROUTES = ['orthogonal', 'tree', 'direct', 'smooth'];

/**
 * How the line of an edge is smoothed: {@code 'off'} (straight segments), {@code 'round'} (the
 * orthogonal route with filleted corners, "smoothstep") or {@code 'spline'} (a centripetal
 * Catmull-Rom curve through the route points).
 */
var CURVES = ['off', 'round', 'spline'];

/**
 * How the nodes get their coordinates: {@code 'layered'} (ranks, order, channels - the default and
 * the right picture for a flow chart) or {@code 'force'} (a spring embedder, organic and
 * direction-biased - docs/js/diagram-plan.md §13.7).
 */
var LAYOUTS = ['layered', 'force'];

/**
 * The limits every entry point enforces (docs/js/diagram-plan.md §8).
 * <p>
 * Layout on foreign text is a compute and memory DoS; these numbers are the same order of
 * magnitude Mermaid uses (maxTextSize 50000, maxEdges 500) and are, at the same time, a
 * performance guarantee: 300 nodes and 600 edges are milliseconds in GraalJS.
 * </p>
 */
var LIMITS = {
  maxTextBytes: 50000,
  maxNodes: 300,
  maxEdges: 600,
  maxGroups: 50,
  maxGroupDepth: 8,
  maxLabelChars: 200,
  maxSweeps: 8,
  maxPhaseMillis: 5000,
  maxForceIterations: 400,
  maxForceRuns: 8,
  maxRelaxIterations: 200,
  maxRelaxLevels: 8
};

/**
 * Raises a model error - the prefix names the origin, so a caller can tell a frontend defect from a
 * layout defect.
 * @param {string} message the reason
 */
function fail(message) {
  throw new Error('diagram/model: ' + message);
}

/**
 * Builds the effective limits: the defaults, with a caller's overrides validated one by one.
 * <p>
 * A bad limit is a configuration defect and aborts with the property name and value quoted - the
 * same rule the project applies to system properties. There is no silent fallback to the default.
 * </p>
 * @param {object} [overrides] the limits to change
 * @returns {object} the effective limits
 */
function limitsWith(overrides) {
  var limits = {};
  for (var key in LIMITS) {
    if (Object.prototype.hasOwnProperty.call(LIMITS, key)) {
      limits[key] = LIMITS[key];
    }
  }
  if (overrides) {
    for (var name in overrides) {
      if (!Object.prototype.hasOwnProperty.call(overrides, name)) {
        continue;
      }
      if (!Object.prototype.hasOwnProperty.call(LIMITS, name)) {
        fail("unknown limit '" + name + "' (known: " + Object.keys(LIMITS).join(', ') + ')');
      }
      var value = overrides[name];
      if (typeof value !== 'number' || !isFinite(value) || value <= 0) {
        fail("limit '" + name + "' must be a positive number, got: " + JSON.stringify(value));
      }
      limits[name] = value;
    }
  }
  return limits;
}

/**
 * Normalizes a direction token, accepting the Mermaid spellings TD (top-down) and TB.
 * @param {string} direction the token
 * @returns {string} one of {@link DIRECTIONS}
 */
function normalizeDirection(direction) {
  var value = (direction === undefined || direction === null) ? 'TB'
    : String(direction).trim().toUpperCase();
  if (value === 'TD') {
    return 'TB';
  }
  if (DIRECTIONS.indexOf(value) < 0) {
    fail("unknown direction '" + direction + "' (allowed: " + DIRECTIONS.join(', ')
      + ', and TD for TB)');
  }
  return value;
}

/**
 * Reads a mandatory, non-empty identifier.
 * @param {*} value the raw value
 * @param {string} what the field, for the message
 * @param {number} maxLength the maximum length
 * @returns {string} the trimmed identifier
 */
function requireId(value, what, maxLength) {
  if (typeof value !== 'string' || value.trim() === '') {
    fail(what + ' must be a non-empty string, got: ' + JSON.stringify(value));
  }
  var id = value.trim();
  if (id.length > maxLength) {
    fail(what + " '" + id + "' is " + id.length + ' characters long - the limit is '
      + maxLength);
  }
  return id;
}

/**
 * Reads an optional string, falling back to a default.
 * @param {*} value the raw value
 * @param {string} fallback the default
 * @returns {string} the string
 */
function optionalString(value, fallback) {
  if (value === undefined || value === null) {
    return fallback;
  }
  return String(value);
}

/**
 * Chooses a value from a declared set, or fails with the allowed values.
 * @param {*} value the raw value
 * @param {Array<string>} allowed the allowed values
 * @param {string} fallback the default when the value is absent
 * @param {string} what the field, for the message
 * @returns {string} the chosen value
 */
function oneOf(value, allowed, fallback, what) {
  if (value === undefined || value === null || value === '') {
    return fallback;
  }
  var text = String(value).trim();
  if (allowed.indexOf(text) < 0) {
    fail("unknown " + what + " '" + value + "' (allowed: " + allowed.join(', ') + ')');
  }
  return text;
}

/**
 * Reads an optional finite number.
 * @param {*} value the raw value
 * @param {string} what the field, for the message
 * @returns {number|null} the number or null
 */
function optionalNumber(value, what) {
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value !== 'number' || !isFinite(value)) {
    fail(what + ' must be a finite number, got: ' + JSON.stringify(value));
  }
  return value;
}

/**
 * Reads an optional non-negative integer.
 * @param {*} value the raw value
 * @param {string} what the field, for the message
 * @returns {number|null} the integer or null
 */
function optionalIndex(value, what) {
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value !== 'number' || !isFinite(value) || Math.floor(value) !== value || value < 0) {
    fail(what + ' must be a non-negative integer, got: ' + JSON.stringify(value));
  }
  return value;
}

/**
 * Reads an optional port side.
 * @param {*} value the raw value
 * @param {string} what the field, for the message
 * @returns {string|null} the side or null
 */
function optionalSide(value, what) {
  if (value === undefined || value === null || value === '') {
    return null;
  }
  return oneOf(value, SIDES, null, what);
}

/**
 * Validates and normalizes a raw model into the IR.
 * <p>
 * The input is not modified: every array and object is rebuilt, the notes of the frontend are
 * carried over, and the result is a model the layout can rely on. A violation of the rules in
 * docs/js/diagram-plan.md §4 is an exception, not a note - a note is for what a frontend could not
 * understand, an exception for what is not a valid model.
 * </p>
 * @param {object} raw the model a frontend produced
 * @param {object} [limits] the effective limits (defaults to {@link LIMITS})
 * @returns {object} the normalized model
 */
function normalize(raw, limits) {
  if (!raw || typeof raw !== 'object') {
    fail('the model must be an object, got: ' + JSON.stringify(raw));
  }
  var limitsInUse = limits || limitsWith(null);
  var model = {
    kind: optionalString(raw.kind, 'graph'),
    direction: normalizeDirection(raw.direction),
    route: (raw.route === undefined || raw.route === null || raw.route === '') ? null
      : oneOf(raw.route, ROUTES, null, 'route'),
    curve: (raw.curve === undefined || raw.curve === null || raw.curve === '') ? null
      : oneOf(raw.curve, CURVES, null, 'curve'),
    layout: (raw.layout === undefined || raw.layout === null || raw.layout === '') ? null
      : oneOf(raw.layout, LAYOUTS, null, 'layout'),
    nodes: [],
    edges: [],
    groups: [],
    notes: []
  };
  if (Array.isArray(raw.notes)) {
    for (var n = 0; n < raw.notes.length; n++) {
      model.notes.push(String(raw.notes[n]));
    }
  }

  // Groups first: nodes and groups reference them, so they have to exist before the check.
  var rawGroups = arrayOf(raw, 'groups');
  if (rawGroups.length > limitsInUse.maxGroups) {
    fail('maxGroups (' + limitsInUse.maxGroups + ') exceeded: ' + rawGroups.length + ' groups');
  }
  var groupById = {};
  for (var g = 0; g < rawGroups.length; g++) {
    var rawGroup = rawGroups[g];
    if (!rawGroup || typeof rawGroup !== 'object') {
      fail('group #' + (g + 1) + ' must be an object');
    }
    var groupId = requireId(rawGroup.id, 'group #' + (g + 1) + ': id', 64);
    if (Object.prototype.hasOwnProperty.call(groupById, groupId)) {
      fail("duplicate group id '" + groupId + "'");
    }
    var group = {
      id: groupId,
      label: optionalString(rawGroup.label, groupId),
      parent: (rawGroup.parent === undefined || rawGroup.parent === null || rawGroup.parent === '')
        ? null : requireId(rawGroup.parent, "group '" + groupId + "': parent", 64),
      direction: (rawGroup.direction === undefined || rawGroup.direction === null
        || rawGroup.direction === '') ? null : normalizeDirection(rawGroup.direction)
    };
    groupById[groupId] = group;
    model.groups.push(group);
  }
  applyGroupParents(groupById, limitsInUse);

  // Nodes.
  var rawNodes = arrayOf(raw, 'nodes');
  if (rawNodes.length > limitsInUse.maxNodes) {
    fail('maxNodes (' + limitsInUse.maxNodes + ') exceeded: ' + rawNodes.length + ' nodes');
  }
  var nodeById = {};
  for (var i = 0; i < rawNodes.length; i++) {
    var rawNode = rawNodes[i];
    if (!rawNode || typeof rawNode !== 'object') {
      fail('node #' + (i + 1) + ' must be an object');
    }
    var nodeId = requireId(rawNode.id, 'node #' + (i + 1) + ': id', 64);
    if (Object.prototype.hasOwnProperty.call(nodeById, nodeId)) {
      fail("duplicate node id '" + nodeId + "'");
    }
    var label = optionalString(rawNode.label, nodeId);
    if (label.length > limitsInUse.maxLabelChars) {
      fail("the label of node '" + nodeId + "' is " + label.length + ' characters long - the'
        + ' limit is maxLabelChars (' + limitsInUse.maxLabelChars + ')');
    }
    var group = null;
    if (rawNode.group !== undefined && rawNode.group !== null && rawNode.group !== '') {
      group = requireId(rawNode.group, "node '" + nodeId + "': group", 64);
      if (!Object.prototype.hasOwnProperty.call(groupById, group)) {
        fail("node '" + nodeId + "' names the group '" + group + "', which does not exist");
      }
    }
    var fixed = null;
    var shift = null;
    if (rawNode.fixed !== undefined && rawNode.fixed !== null) {
      if (typeof rawNode.fixed !== 'object') {
        fail("node '" + nodeId + "': fixed must be an object {x, y} or {dx, dy}");
      }
      if (rawNode.fixed.dx !== undefined || rawNode.fixed.dy !== undefined) {
        if (rawNode.fixed.x !== undefined || rawNode.fixed.y !== undefined) {
          fail("node '" + nodeId + "': fixed is either an absolute {x, y} or a relative"
            + ' {dx, dy}, not both');
        }
        shift = {
          dx: optionalNumber(rawNode.fixed.dx, "node '" + nodeId + "': fixed.dx"),
          dy: optionalNumber(rawNode.fixed.dy, "node '" + nodeId + "': fixed.dy")
        };
        if (shift.dx === null && shift.dy === null) {
          fail("node '" + nodeId + "': fixed.dx or fixed.dy must be a number");
        }
        shift.dx = shift.dx === null ? 0 : shift.dx;
        shift.dy = shift.dy === null ? 0 : shift.dy;
      } else {
        fixed = {
          x: optionalNumber(rawNode.fixed.x, "node '" + nodeId + "': fixed.x"),
          y: optionalNumber(rawNode.fixed.y, "node '" + nodeId + "': fixed.y")
        };
        if (fixed.x === null || fixed.y === null) {
          fail("node '" + nodeId + "': fixed needs both x and y (or a relative dx, dy)");
        }
      }
    }
    var node = {
      id: nodeId,
      label: label,
      shape: oneOf(rawNode.shape, SHAPES, 'rect', "shape of node '" + nodeId + "'"),
      w: optionalNumber(rawNode.w, "node '" + nodeId + "': w"),
      h: optionalNumber(rawNode.h, "node '" + nodeId + "': h"),
      group: group,
      rank: optionalIndex(rawNode.rank, "node '" + nodeId + "': rank"),
      order: optionalIndex(rawNode.order, "node '" + nodeId + "': order"),
      fixed: fixed,
      shift: shift
    };
    nodeById[nodeId] = node;
    model.nodes.push(node);
  }

  // Edges.
  var rawEdges = arrayOf(raw, 'edges');
  if (rawEdges.length > limitsInUse.maxEdges) {
    fail('maxEdges (' + limitsInUse.maxEdges + ') exceeded: ' + rawEdges.length + ' edges');
  }
  for (var e = 0; e < rawEdges.length; e++) {
    var rawEdge = rawEdges[e];
    if (!rawEdge || typeof rawEdge !== 'object') {
      fail('edge #' + (e + 1) + ' must be an object');
    }
    var from = requireId(rawEdge.from, 'edge #' + (e + 1) + ': from', 64);
    var to = requireId(rawEdge.to, 'edge #' + (e + 1) + ': to', 64);
    if (!Object.prototype.hasOwnProperty.call(nodeById, from)) {
      fail("edge #" + (e + 1) + " starts at '" + from + "', which is not a node");
    }
    if (!Object.prototype.hasOwnProperty.call(nodeById, to)) {
      fail("edge #" + (e + 1) + " ends at '" + to + "', which is not a node");
    }
    var constraint = (rawEdge.constraint === undefined || rawEdge.constraint === null)
      ? true : Boolean(rawEdge.constraint);
    if (from === to && constraint) {
      fail("edge #" + (e + 1) + " is a self-loop on '" + from + "' with constraint true - a"
        + ' self-loop cannot constrain the rank, set constraint: false');
    }
    var weight = optionalNumber(rawEdge.weight, 'edge #' + (e + 1) + ': weight');
    if (weight !== null && weight <= 0) {
      fail('edge #' + (e + 1) + ': weight must be greater than 0, got: ' + weight);
    }
    var waypoints = null;
    if (rawEdge.waypoints !== undefined && rawEdge.waypoints !== null) {
      if (!Array.isArray(rawEdge.waypoints)) {
        fail('edge #' + (e + 1) + ': waypoints must be an array of [x, y]');
      }
      waypoints = [];
      for (var w = 0; w < rawEdge.waypoints.length; w++) {
        var point = rawEdge.waypoints[w];
        if (!Array.isArray(point) || point.length < 2
            || typeof point[0] !== 'number' || typeof point[1] !== 'number') {
          fail('edge #' + (e + 1) + ': waypoint #' + (w + 1) + ' must be [x, y]');
        }
        waypoints.push([point[0], point[1]]);
      }
    }
    var label = rawEdge.label === undefined || rawEdge.label === null ? null
      : String(rawEdge.label);
    if (label !== null && label.length > limitsInUse.maxLabelChars) {
      fail('the label of edge #' + (e + 1) + ' is ' + label.length + ' characters long - the'
        + ' limit is maxLabelChars (' + limitsInUse.maxLabelChars + ')');
    }
    model.edges.push({
      from: from,
      to: to,
      label: label,
      style: oneOf(rawEdge.style, EDGE_STYLES, 'solid', 'style of edge #' + (e + 1)),
      arrow: oneOf(rawEdge.arrow, ARROWS, 'end', 'arrow of edge #' + (e + 1)),
      constraint: constraint,
      weight: weight === null ? 1 : weight,
      exit: optionalSide(rawEdge.exit, "edge #" + (e + 1) + ': exit'),
      entry: optionalSide(rawEdge.entry, "edge #" + (e + 1) + ': entry'),
      waypoints: waypoints
    });
  }

  return model;
}

/**
 * Reads an optional array member, failing when it is present but not an array.
 * @param {object} raw the raw model
 * @param {string} name the member name
 * @returns {Array} the array (empty when absent)
 */
function arrayOf(raw, name) {
  var value = raw[name];
  if (value === undefined || value === null) {
    return [];
  }
  if (!Array.isArray(value)) {
    fail("'" + name + "' must be an array, got: " + JSON.stringify(value));
  }
  return value;
}

/**
 * Checks that group parents exist and that the nesting is acyclic and bounded.
 * @param {object} groupById the groups by id
 * @param {object} limits the effective limits
 */
function applyGroupParents(groupById, limits) {
  for (var id in groupById) {
    if (!Object.prototype.hasOwnProperty.call(groupById, id)) {
      continue;
    }
    var seen = {};
    var depth = 0;
    var current = groupById[id];
    while (current.parent !== null) {
      if (Object.prototype.hasOwnProperty.call(seen, current.id)) {
        fail("the groups are nested cyclically at '" + current.id + "'");
      }
      seen[current.id] = true;
      if (!Object.prototype.hasOwnProperty.call(groupById, current.parent)) {
        fail("group '" + current.id + "' names the parent '" + current.parent
          + "', which does not exist");
      }
      current = groupById[current.parent];
      depth++;
      if (depth > limits.maxGroupDepth) {
        fail('the group nesting is deeper than maxGroupDepth (' + limits.maxGroupDepth
          + ") at '" + id + "'");
      }
    }
  }
}

/**
 * Creates an empty model for a caller who builds one step by step.
 * @param {string} [kind] the diagram kind (default 'graph')
 * @returns {object} the empty model
 */
function empty(kind) {
  return {
    kind: kind || 'graph',
    direction: 'TB',
    route: null,
    curve: null,
    layout: null,
    nodes: [],
    edges: [],
    groups: [],
    notes: []
  };
}

/**
 * Counts the bytes of a text in UTF-8 - the unit of maxTextBytes.
 * @param {string} text the text
 * @returns {number} the byte count
 */
function utf8Bytes(text) {
  var value = String(text);
  var bytes = 0;
  for (var i = 0; i < value.length; i++) {
    var code = value.charCodeAt(i);
    if (code < 0x80) {
      bytes += 1;
    } else if (code < 0x800) {
      bytes += 2;
    } else if (code >= 0xD800 && code <= 0xDBFF) {
      bytes += 4;
      i++;
    } else {
      bytes += 3;
    }
  }
  return bytes;
}

module.exports = {
  DIRECTIONS: DIRECTIONS,
  SHAPES: SHAPES,
  EDGE_STYLES: EDGE_STYLES,
  ARROWS: ARROWS,
  SIDES: SIDES,
  ROUTES: ROUTES,
  CURVES: CURVES,
  LAYOUTS: LAYOUTS,
  LIMITS: LIMITS,
  limitsWith: limitsWith,
  normalizeDirection: normalizeDirection,
  normalize: normalize,
  empty: empty,
  utf8Bytes: utf8Bytes
};
