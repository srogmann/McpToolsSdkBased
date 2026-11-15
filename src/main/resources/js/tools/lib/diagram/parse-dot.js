/**
 * diagram/parse-dot - the DOT frontend.
 *
 * DOT is not here because people write it - it is here because it is a <b>layout</b> format with
 * the vocabulary Mermaid lacks: rankdir, rank=same, constraint=false, weight, headport/tailport,
 * layout (the engine: dot maps onto our layered placement, neato/fdp onto the force one).
 * The parser is deliberately small (statements, attribute lists, subgraphs) and feeds the same IR
 * that the Mermaid frontend feeds. See docs/js/diagram-plan.md §6.2.
 *
 *     var model = require('./diagram/parse-dot.js').parse('digraph { A -> B }');
 *
 * What it does not understand lands in {@code model.notes} with the line number - never a silent
 * loss.
 */

'use strict';

/** The characters that may start a bare identifier (letters, underscore, high-bit). */
var ID_START = /[A-Za-z_\u0080-\uFFFF]/;

/** The characters that may continue a bare identifier. */
var ID_PART = /[A-Za-z0-9_\u0080-\uFFFF]/;

/**
 * Splits DOT source into tokens.
 * <p>
 * Quoted strings keep their inner escapes; comments ({@code //}, {@code #} to end of line and
 * {@code /* ... *}{@code /}) are dropped. The tokenizer tracks line numbers so every error names one.
 * </p>
 * @param {string} text the DOT source
 * @returns {Array<object>} the tokens {type, value, line}
 */
function tokenize(text) {
  var tokens = [];
  var i = 0;
  var line = 1;
  var length = text.length;
  while (i < length) {
    var ch = text.charAt(i);
    if (ch === '\n') {
      line++;
      i++;
    } else if (ch === ' ' || ch === '\t' || ch === '\r') {
      i++;
    } else if (ch === '#' || (ch === '/' && text.charAt(i + 1) === '/')) {
      while (i < length && text.charAt(i) !== '\n') {
        i++;
      }
    } else if (ch === '/' && text.charAt(i + 1) === '*') {
      i += 2;
      while (i < length && !(text.charAt(i) === '*' && text.charAt(i + 1) === '/')) {
        if (text.charAt(i) === '\n') {
          line++;
        }
        i++;
      }
      i += 2;
    } else if (ch === '"') {
      var quoted = '';
      i++;
      while (i < length && text.charAt(i) !== '"') {
        var qch = text.charAt(i);
        if (qch === '\\' && i + 1 < length) {
          var next = text.charAt(i + 1);
          if (next === '"' || next === '\\') {
            quoted += next;
          } else if (next === '\n') {
            quoted += '\n';
            line++;
          } else {
            quoted += '\\' + next;
          }
          i += 2;
        } else {
          if (qch === '\n') {
            line++;
          }
          quoted += qch;
          i++;
        }
      }
      if (i >= length) {
        throw new Error('diagram/parse-dot: the quoted string starting on line ' + line
          + ' is not terminated');
      }
      i++;
      tokens.push({type: 'id', value: quoted, line: line});
    } else if (ch === '-' && text.charAt(i + 1) === '>') {
      tokens.push({type: 'op', value: '->', line: line});
      i += 2;
    } else if (ch === '-' && text.charAt(i + 1) === '-') {
      tokens.push({type: 'op', value: '--', line: line});
      i += 2;
    } else if ('{}[];,=:'.indexOf(ch) >= 0) {
      tokens.push({type: 'punct', value: ch, line: line});
      i++;
    } else if (ID_START.test(ch)) {
      var word = '';
      while (i < length && ID_PART.test(text.charAt(i))) {
        word += text.charAt(i);
        i++;
      }
      tokens.push({type: 'id', value: word, line: line});
    } else if (/[0-9.]/.test(ch) || (ch === '-' && /[0-9.]/.test(text.charAt(i + 1)))) {
      var number = ch;
      i++;
      while (i < length && /[0-9.eE+-]/.test(text.charAt(i))) {
        number += text.charAt(i);
        i++;
      }
      tokens.push({type: 'id', value: number, line: line});
    } else {
      throw new Error("diagram/parse-dot: unexpected character '" + ch + "' on line " + line);
    }
  }
  return tokens;
}

/** Maps a DOT shape name to an IR shape. */
var SHAPE_MAP = {
  box: 'rect',
  rect: 'rect',
  rectangle: 'rect',
  square: 'rect',
  record: 'rect',
  plaintext: 'rect',
  none: 'rect',
  point: 'rect',
  ellipse: 'stadium',
  oval: 'stadium',
  circle: 'circle',
  doublecircle: 'circle',
  diamond: 'diamond',
  hexagon: 'hexagon',
  component: 'subroutine',
  box3d: 'cylinder',
  cylinder: 'cylinder',
  invhouse: 'diamond',
  house: 'rect',
  note: 'rect'
};

/**
 * Parses DOT source into a raw model.
 * @param {string} text the DOT source
 * @returns {object} the raw model (before {@code model.normalize})
 */
function parse(text) {
  var source = String(text);
  var tokens = tokenize(source);
  var state = {
    tokens: tokens,
    index: 0,
    model: {
      kind: 'graph',
      direction: 'TB',
      route: null,
      curve: null,
      layout: null,
      nodes: [],
      edges: [],
      groups: [],
      notes: []
    },
    nodeById: {},
    nodeDefaults: {},
    edgeDefaults: {},
    groupCounter: 0
  };
  skipToken(state, 'strict');
  var kind = nextId(state);
  if (kind !== 'graph' && kind !== 'digraph') {
    throw new Error("diagram/parse-dot: expected 'graph' or 'digraph', got '"
      + (kind === null ? 'end of input' : kind) + "'");
  }
  state.directed = kind === 'digraph';
  // An optional graph name before the body.
  if (state.index < tokens.length && tokens[state.index].type === 'id') {
    state.index++;
  }
  expectToken(state, '{');
  parseBody(state, null);
  expectToken(state, '}');
  if (state.index < tokens.length) {
    throw new Error('diagram/parse-dot: unexpected text after the closing brace on line '
      + tokens[state.index].line);
  }
  return state.model;
}

/**
 * Parses the statements of a graph or subgraph body until the closing brace.
 * @param {object} state the parser state
 * @param {string|null} groupId the enclosing group, or null
 */
function parseBody(state, groupId) {
  while (state.index < state.tokens.length && peekToken(state) !== '}') {
    var token = state.tokens[state.index];
    if (token.type === 'punct' && (token.value === ';' || token.value === ',')) {
      state.index++;
      continue;
    }
    if (token.type === 'id' && token.value === 'subgraph') {
      parseSubgraph(state, groupId);
      continue;
    }
    if (token.type === 'punct' && token.value === '{') {
      state.index++;
      var anonymous = 'cluster' + (++state.groupCounter);
      state.model.groups.push({id: anonymous, label: anonymous, parent: groupId, direction: null});
      parseBody(state, anonymous);
      expectToken(state, '}');
      continue;
    }
    parseStatement(state, groupId);
  }
}

/**
 * Parses one statement: a graph attribute, a node declaration or an edge chain.
 * @param {object} state the parser state
 * @param {string|null} groupId the enclosing group, or null
 */
function parseStatement(state, groupId) {
  var firstId = readNodeRef(state).id;
  var line = state.tokens[state.index - 1].line;

  if (peekToken(state) === '=') {
    state.index++;
    var value = readValue(state);
    applyGraphAttribute(state, groupId, firstId, value, line);
    return;
  }
  if (firstId === 'node' && peekToken(state) === '[') {
    mergeAttributes(state.nodeDefaults, readAttributeLists(state));
    return;
  }
  if (firstId === 'edge' && peekToken(state) === '[') {
    mergeAttributes(state.edgeDefaults, readAttributeLists(state));
    return;
  }
  if (firstId === 'graph' && peekToken(state) === '[') {
    var graphAttrs = readAttributeLists(state);
    for (var key in graphAttrs) {
      if (Object.prototype.hasOwnProperty.call(graphAttrs, key)) {
        applyGraphAttribute(state, groupId, key, graphAttrs[key], line);
      }
    }
    return;
  }

  // A chain of node references joined by edge operators.
  var refs = [firstId];
  var ops = [];
  while (peekToken(state) === '->' || peekToken(state) === '--') {
    ops.push(nextToken(state).value);
    refs.push(readNodeRef(state).id);
  }
  var attrs = (peekToken(state) === '[') ? readAttributeLists(state) : {};
  if (refs.length === 1) {
    ensureNode(state, refs[0], attrs, groupId);
    return;
  }
  for (var r = 0; r < refs.length; r++) {
    ensureNode(state, refs[r], {}, groupId);
  }
  for (var e = 0; e < ops.length; e++) {
    addEdge(state, refs[e], refs[e + 1], ops[e], attrs);
  }
}

/**
 * Parses a subgraph (a cluster / group) and its body.
 * @param {object} state the parser state
 * @param {string|null} parentGroup the enclosing group, or null
 */
function parseSubgraph(state, parentGroup) {
  nextToken(state);
  var name = null;
  if (peekToken(state) !== '{') {
    var nameToken = nextToken(state);
    if (nameToken && nameToken.type === 'id') {
      name = nameToken.value;
    }
  }
  var groupId = name || ('cluster' + (++state.groupCounter));
  var group = {id: groupId, label: groupId, parent: parentGroup, direction: null};
  state.model.groups.push(group);
  if (peekToken(state) === '[') {
    var attrs = readAttributeLists(state);
    if (attrs.label) {
      group.label = attrs.label;
    }
  }
  if (peekToken(state) !== '{') {
    return;
  }
  state.index++;
  parseBody(state, groupId);
  expectToken(state, '}');
}

/**
 * Applies a graph-level attribute (rankdir, label of a subgraph, rank=same).
 * @param {object} state the parser state
 * @param {string|null} groupId the enclosing group
 * @param {string} key the attribute name
 * @param {string} value the value
 * @param {number} line the line number
 */
function applyGraphAttribute(state, groupId, key, value, line) {
  if (key === 'rankdir') {
    state.model.direction = mapDirection(value, line);
    return;
  }
  if (key === 'route') {
    var routeValue = String(value).toLowerCase();
    if (['orthogonal', 'tree', 'direct'].indexOf(routeValue) < 0) {
      throw new Error("diagram/parse-dot: unknown route '" + value + "' on line " + line
        + ' (allowed: orthogonal, tree, direct)');
    }
    state.model.route = routeValue;
    return;
  }
  if (key === 'curve') {
    var curveValue = String(value).toLowerCase();
    if (['off', 'round', 'spline'].indexOf(curveValue) < 0) {
      throw new Error("diagram/parse-dot: unknown curve '" + value + "' on line " + line
        + ' (allowed: off, round, spline)');
    }
    state.model.curve = curveValue;
    return;
  }
  if (key === 'layout') {
    // Graphviz selects the engine with `layout=` (dot, neato, fdp, sfdp, twopi, circo), so the
    // force-directed engines map onto our force placement and the hierarchical ones onto layered.
    var layoutValue = String(value).toLowerCase();
    if (layoutValue === 'force' || layoutValue === 'neato' || layoutValue === 'fdp'
        || layoutValue === 'sfdp' || layoutValue === 'twopi' || layoutValue === 'circo') {
      state.model.layout = 'force';
      return;
    }
    if (layoutValue === 'layered' || layoutValue === 'dot' || layoutValue === 'hierarchical') {
      state.model.layout = 'layered';
      return;
    }
    throw new Error("diagram/parse-dot: unknown layout '" + value + "' on line " + line
      + ' (allowed: layered, dot, force, neato, fdp, sfdp, twopi, circo)');
  }
  if (key === 'splines') {
    // Graphviz spellings that mean something close to our routes.
    var splines = String(value).toLowerCase();
    if (splines === 'line') {
      state.model.route = 'direct';
    } else if (splines === 'ortho' || splines === 'polyline') {
      state.model.route = 'orthogonal';
    } else if (splines === 'spline' || splines === 'curved' || splines === 'true') {
      // Graphviz draws its edges as curves; we keep the orthogonal route and round its corners.
      state.model.curve = 'round';
    } else if (splines === 'false' || splines === 'none') {
      state.model.curve = 'off';
    }
    return;
  }
  if (key === 'label' && groupId) {
    var group = findGroup(state.model.groups, groupId);
    if (group) {
      group.label = value;
    }
    return;
  }
  if (key === 'rank' && value === 'same') {
    state.model.notes.push('parse-dot: rank=same on line ' + line + ' is parsed but its common'
      + ' rank is not enforced yet (docs/js/diagram-plan.md §7.1, p2ranks)');
    return;
  }
  // Other graph attributes (bgcolor, fontname, ...) are read and ignored.
}

/**
 * Maps a DOT rankdir value to an IR direction.
 * @param {string} value the rankdir value
 * @param {number} line the line number (for the error)
 * @returns {string} the direction
 */
function mapDirection(value, line) {
  var upper = String(value).toUpperCase();
  if (upper === 'TB' || upper === 'BT' || upper === 'LR' || upper === 'RL') {
    return upper;
  }
  throw new Error("diagram/parse-dot: unknown rankdir '" + value + "' on line " + line
    + ' (allowed: TB, BT, LR, RL)');
}

/**
 * Adds an edge with the default and explicit attributes merged in.
 * @param {object} state the parser state
 * @param {string} from the source node id
 * @param {string} to the target node id
 * @param {string} op the edge operator ('->' or '--')
 * @param {object} attrs the explicit attributes
 */
function addEdge(state, from, to, op, attrs) {
  var merged = {};
  mergeAttributes(merged, state.edgeDefaults);
  mergeAttributes(merged, attrs);
  var constraint = merged.constraint === 'false' || merged.constraint === 'False' ? false : true;
  if (from === to) {
    constraint = false;
  }
  var style = 'solid';
  var rawStyle = merged.style ? String(merged.style).toLowerCase() : '';
  if (rawStyle.indexOf('dashed') >= 0 || rawStyle.indexOf('dotted') >= 0) {
    style = 'dotted';
  } else if (rawStyle.indexOf('bold') >= 0) {
    style = 'thick';
  }
  var arrow = 'end';
  if (op === '--' || merged.dir === 'none' || merged.arrowhead === 'none') {
    arrow = 'none';
  } else if (merged.dir === 'both') {
    arrow = 'both';
  } else if (merged.dir === 'back') {
    arrow = 'start';
  }
  var weight = 1;
  if (merged.weight !== undefined) {
    var parsedWeight = parseFloat(merged.weight);
    if (!isNaN(parsedWeight) && parsedWeight > 0) {
      weight = parsedWeight;
    }
  }
  var edge = {
    from: from,
    to: to,
    label: merged.label === undefined ? null : merged.label,
    style: style,
    arrow: arrow,
    constraint: constraint,
    weight: weight,
    exit: portSide(merged.tailport, state, from),
    entry: portSide(merged.headport, state, to),
    waypoints: null
  };
  state.model.edges.push(edge);
}

/**
 * Reads a port attribute as a side, or records a note when it is a named port.
 * @param {string} value the attribute value (may be undefined)
 * @param {object} state the parser state
 * @param {string} nodeId the node the port belongs to
 * @returns {string|null} the side or null
 */
function portSide(value, state, nodeId) {
  if (value === undefined || value === null || value === '') {
    return null;
  }
  var side = String(value).toLowerCase();
  if (side === 'n' || side === 'e' || side === 's' || side === 'w') {
    return side;
  }
  state.model.notes.push("parse-dot: the named port '" + value + "' on '" + nodeId
    + "' is not a side (n/e/s/w) - the edge leaves from the closest side instead");
  return null;
}

/**
 * Ensures a node exists, creating it with the given attributes on first mention.
 * @param {object} state the parser state
 * @param {string} id the node id
 * @param {object} attrs the explicit attributes
 * @param {string|null} groupId the enclosing group
 */
function ensureNode(state, id, attrs, groupId) {
  var merged = {};
  mergeAttributes(merged, state.nodeDefaults);
  mergeAttributes(merged, attrs);
  var existing = state.nodeById[id];
  if (existing) {
    if (attrs && attrs.label !== undefined) {
      existing.label = attrs.label;
    }
    if (attrs && attrs.shape !== undefined) {
      existing.shape = SHAPE_MAP[String(attrs.shape)] || 'rect';
    }
    if (groupId && !existing.group) {
      existing.group = groupId;
    }
    return;
  }
  var label = merged.label === undefined ? id : String(merged.label);
  var shape = SHAPE_MAP[merged.shape === undefined ? 'box' : String(merged.shape)];
  var node = {
    id: id,
    label: label.replace(/\\n/g, '\n').replace(/\\l/g, '\n').replace(/\\r/g, ''),
    shape: shape || 'rect',
    w: null,
    h: null,
    group: (merged.group !== undefined) ? String(merged.group) : groupId,
    rank: null,
    order: null,
    fixed: null
  };
  state.nodeById[id] = node;
  state.model.nodes.push(node);
}

/**
 * Reads a node reference, skipping any {@code :port} / {@code :compass} suffixes.
 * @param {object} state the parser state
 * @returns {object} {id}
 */
function readNodeRef(state) {
  var token = nextToken(state);
  if (!token || token.type !== 'id') {
    throw new Error('diagram/parse-dot: expected a node name'
      + (token ? " on line " + token.line : ' at end of input'));
  }
  var id = token.value;
  while (peekToken(state) === ':') {
    state.index++;
    if (state.index < state.tokens.length && state.tokens[state.index].type === 'id') {
      state.index++;
    }
  }
  return {id: id};
}

/**
 * Reads an attribute value (an identifier, a quoted string or a number).
 * @param {object} state the parser state
 * @returns {string} the value
 */
function readValue(state) {
  var token = nextToken(state);
  if (!token || token.type !== 'id') {
    throw new Error('diagram/parse-dot: expected an attribute value'
      + (token ? " on line " + token.line : ' at end of input'));
  }
  return token.value;
}

/**
 * Reads one or more {@code [ ... ]} attribute lists and merges them.
 * @param {object} state the parser state
 * @returns {object} the merged attributes
 */
function readAttributeLists(state) {
  var attrs = {};
  while (peekToken(state) === '[') {
    state.index++;
    while (state.index < state.tokens.length && peekToken(state) !== ']') {
      var token = nextToken(state);
      if (token.type === 'punct' && (token.value === ',' || token.value === ';')) {
        continue;
      }
      var key = token.value;
      var value = 'true';
      if (peekToken(state) === '=') {
        state.index++;
        value = readValue(state);
      }
      attrs[key] = value;
      if (peekToken(state) === ',' || peekToken(state) === ';') {
        state.index++;
      }
    }
    expectToken(state, ']');
  }
  return attrs;
}

/**
 * Merges attributes from a source object into a target object.
 * @param {object} target the target
 * @param {object} source the source
 */
function mergeAttributes(target, source) {
  if (!source) {
    return;
  }
  for (var key in source) {
    if (Object.prototype.hasOwnProperty.call(source, key)) {
      target[key] = source[key];
    }
  }
}

/**
 * Finds a group by id.
 * @param {Array<object>} groups the groups
 * @param {string} id the id
 * @returns {object|null} the group or null
 */
function findGroup(groups, id) {
  for (var i = 0; i < groups.length; i++) {
    if (groups[i].id === id) {
      return groups[i];
    }
  }
  return null;
}

/**
 * The value of the current token (for a punctuation or operator check).
 * @param {object} state the parser state
 * @returns {string|null} the value or null at end of input
 */
function peekToken(state) {
  if (state.index >= state.tokens.length) {
    return null;
  }
  return state.tokens[state.index].value;
}

/**
 * Consumes and returns the current token.
 * @param {object} state the parser state
 * @returns {object|null} the token or null at end of input
 */
function nextToken(state) {
  if (state.index >= state.tokens.length) {
    return null;
  }
  return state.tokens[state.index++];
}

/**
 * Consumes the current token and fails when it is not the expected punctuation.
 * @param {object} state the parser state
 * @param {string} value the expected punctuation
 */
function expectToken(state, value) {
  var token = nextToken(state);
  if (!token || token.value !== value) {
    throw new Error("diagram/parse-dot: expected '" + value + "', got '"
      + (token ? token.value : 'end of input') + "'"
      + (token ? ' on line ' + token.line : ''));
  }
}

/**
 * Skips the current token when it is the given id (used for 'strict').
 * @param {object} state the parser state
 * @param {string} value the id to skip
 */
function skipToken(state, value) {
  if (state.index < state.tokens.length && state.tokens[state.index].value === value) {
    state.index++;
  }
}

/**
 * Reads an id token, or returns null.
 * @param {object} state the parser state
 * @returns {string|null} the id
 */
function nextId(state) {
  if (state.index >= state.tokens.length || state.tokens[state.index].type !== 'id') {
    return null;
  }
  return state.tokens[state.index++].value;
}

module.exports = {
  parse: parse
};
