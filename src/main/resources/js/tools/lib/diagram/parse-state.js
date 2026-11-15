/**
 * diagram/parse-state - the Mermaid state-diagram subset, into the same IR as the flowcharts.
 *
 * A state diagram is a flowchart plus two things: the start/end pseudo states (`[*]`, drawn as a
 * filled circle / a ring) and composite states (`state X { ... }`), which become a group. The
 * layout therefore needs nothing new (docs/js/diagram-plan.md §13.4).
 *
 *     stateDiagram-v2
 *         [*] --> Still
 *         Still --> Moving
 *         Moving --> [*]
 *         state Moving {
 *             [*] --> Fast
 *             Fast --> Slow
 *         }
 *
 * Supported: transitions (`A --> B`, with `: text`), `[*]` as source/target, `state X`,
 * `state "Label" as X`, `state X { ... }` (composite), `direction TB|BT|LR|RL`, and the same
 * `%%`-directives as the flowchart where they make sense (`route`, `curve`, `layout`). Notes,
 * `classDef` and the like are read and ignored (with a note).
 */

'use strict';

/** Diagram kinds this frontend refuses with a clear message. */
var NOT_STATE = ['flowchart', 'graph', 'sequenceDiagram', 'classDiagram', 'erDiagram', 'gantt',
  'pie', 'journey'];

/**
 * Parses Mermaid state-diagram source into a raw model.
 * @param {string} text the source
 * @returns {object} the raw model
 */
function parse(text) {
  var lines = String(text).replace(/\r\n?/g, '\n').split('\n');
  var state = {
    model: {
      kind: 'state',
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
    groupById: {},
    groupStack: [],
    startCount: 0,
    endCount: 0,
    groupCounter: 0,
    compositePlaceholders: {}
  };
  var index = firstMeaningfulLine(lines);
  parseHeader(lines[index] || '', state);
  for (index = index + 1; index < lines.length; index++) {
    parseLine(lines[index].trim(), index + 1, state);
  }
  for (var g = 0; g < state.model.groups.length; g++) {
    var groupId = state.model.groups[g].id;
    if (state.nodeById[groupId] && !state.compositePlaceholders[groupId]) {
      state.compositePlaceholders[groupId] = true;
      state.model.notes.push("parse-state: the composite state '" + groupId + "' is also used"
        + ' in a transition - a placeholder node next to its band represents it (the band'
        + ' itself is not an edge endpoint)');
    }
  }
  return state.model;
}

/**
 * Finds the first line that is not blank and not a comment.
 * @param {Array<string>} lines the lines
 * @returns {number} the index (0 when there is none)
 */
function firstMeaningfulLine(lines) {
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].trim();
    if (line !== '' && line.indexOf('%%') !== 0) {
      return i;
    }
  }
  return 0;
}

/**
 * Reads the header line.
 * @param {string} line the header
 * @param {object} state the parser state
 */
function parseHeader(line, state) {
  if (/^stateDiagram(-v2)?\b/i.test(line.trim())) {
    return;
  }
  var word = /^([A-Za-z][A-Za-z0-9-]*)/.exec(line.trim());
  if (word && NOT_STATE.indexOf(word[1]) >= 0) {
    throw new Error("diagram: '" + word[1] + "' is not a state diagram (this frontend reads"
      + ' stateDiagram / stateDiagram-v2)');
  }
  throw new Error("diagram/parse-state: the first line '" + line + "' is not a state diagram"
    + " header - expected 'stateDiagram-v2'");
}

/**
 * Parses one body line.
 * @param {string} line the trimmed line
 * @param {number} lineNumber the 1-based line number
 * @param {object} state the parser state
 */
function parseLine(line, lineNumber, state) {
  if (line === '') {
    return;
  }
  if (line.indexOf('%%') === 0) {
    parseDirective(line.replace(/^%%\s*/, ''), state);
    return;
  }
  if (line === '}') {
    if (state.groupStack.length === 0) {
      state.model.notes.push('parse-state: line ' + lineNumber + ": '}' without an open state");
      return;
    }
    state.groupStack.pop();
    return;
  }
  var direction = /^direction\s+([A-Za-z]{2})\s*$/i.exec(line);
  if (direction) {
    var value = mapDirection(direction[1], lineNumber);
    var group = currentGroup(state);
    if (group) {
      group.direction = value;
    } else {
      state.model.direction = value;
    }
    return;
  }
  if (/^note\b/i.test(line) || /^classDef\b/i.test(line) || /^class\b/i.test(line)
      || /^style\b/i.test(line) || /^hide\b/i.test(line)) {
    state.model.notes.push('parse-state: line ' + lineNumber + ": '"
      + line.split(/\s+/)[0] + "' is parsed and ignored");
    return;
  }
  if (/^state\b/i.test(line)) {
    parseStateDeclaration(line, lineNumber, state);
    return;
  }
  parseTransition(line, lineNumber, state);
}

/**
 * Handles the same directives the flowchart knows where they apply here.
 * @param {string} body the comment body
 * @param {object} state the parser state
 */
function parseDirective(body, state) {
  var layout = /^layout\s*:\s*(.*)$/.exec(body);
  if (layout) {
    var layoutValue = layout[1].trim().toLowerCase();
    if (['layered', 'force'].indexOf(layoutValue) < 0) {
      state.model.notes.push("parse-state: unknown layout '" + layoutValue + "' (allowed:"
        + ' layered, force)');
      return;
    }
    state.model.layout = layoutValue;
    return;
  }
  var curve = /^curve\s*:\s*(.*)$/.exec(body);
  if (curve) {
    var curveValue = curve[1].trim().toLowerCase();
    if (['off', 'round', 'spline'].indexOf(curveValue) < 0) {
      state.model.notes.push("parse-state: unknown curve '" + curveValue + "' (allowed: off,"
        + ' round, spline)');
      return;
    }
    state.model.curve = curveValue;
    return;
  }
  var route = /^route\s*:\s*(.*)$/.exec(body);
  if (route) {
    var value = route[1].trim().toLowerCase();
    if (['orthogonal', 'tree', 'direct'].indexOf(value) < 0) {
      state.model.notes.push("parse-state: unknown route '" + value + "' (allowed: orthogonal,"
        + ' tree, direct)');
      return;
    }
    state.model.route = value;
    return;
  }
  var diagram = /^diagram\s*:\s*direction\s*=\s*([A-Za-z]{2})$/.exec(body);
  if (diagram) {
    state.model.direction = mapDirection(diagram[1], 0);
  }
}

/**
 * Maps a Mermaid direction token.
 * @param {string} token the token
 * @param {number} lineNumber the line (for the error)
 * @returns {string} the direction
 */
function mapDirection(token, lineNumber) {
  var upper = String(token).toUpperCase();
  if (upper === 'TD') {
    return 'TB';
  }
  if (upper === 'TB' || upper === 'BT' || upper === 'LR' || upper === 'RL') {
    return upper;
  }
  throw new Error("diagram/parse-state: unknown direction '" + token + "' near line "
    + lineNumber);
}

/**
 * Parses a {@code state ...} declaration: plain, aliased or composite.
 * @param {string} line the line
 * @param {number} lineNumber the 1-based line number
 * @param {object} state the parser state
 */
function parseStateDeclaration(line, lineNumber, state) {
  var rest = line.replace(/^state\b/i, '').trim();
  var composite = /^(.+?)\s*\{\s*$/.exec(rest);
  if (composite) {
    var compositeId = unquote(composite[1].trim());
    if (compositeId === '') {
      compositeId = 'state' + (++state.groupCounter);
    }
    openGroup(state, compositeId, compositeId, lineNumber);
    return;
  }
  if (/^\{/.test(rest)) {
    openGroup(state, 'state' + (++state.groupCounter), '', lineNumber);
    return;
  }
  // state "Long label" as Id   or   state Id
  var alias = /^"([^"]*)"\s+as\s+([A-Za-z0-9_.-]+)$/.exec(rest);
  if (alias) {
    ensureNode(state, alias[2], alias[1]);
    return;
  }
  if (/^[A-Za-z0-9_.-]+$/.test(rest)) {
    ensureNode(state, rest, rest);
    return;
  }
  state.model.notes.push('parse-state: line ' + lineNumber + ": could not read the state '"
    + rest + "' - skipped");
}

/**
 * Opens a composite state (a group) and pushes it.
 * @param {object} state the parser state
 * @param {string} id the group id
 * @param {string} label the group label
 * @param {number} lineNumber the line
 */
function openGroup(state, id, label, lineNumber) {
  var uniqueId = id;
  if (state.groupById[uniqueId]) {
    uniqueId = id + '-' + (++state.groupCounter);
    state.model.notes.push('parse-state: line ' + lineNumber + ": duplicate state id '" + id
      + "' - a fresh id is used");
  }
  var parent = state.groupStack.length
    ? state.groupStack[state.groupStack.length - 1] : null;
  var group = {id: uniqueId, label: label, parent: parent, direction: null};
  state.groupById[uniqueId] = group;
  state.model.groups.push(group);
  state.groupStack.push(uniqueId);
}

/**
 * Parses a transition line {@code A --> B : label}.
 * @param {string} line the line
 * @param {number} lineNumber the 1-based line number
 * @param {object} state the parser state
 */
function parseTransition(line, lineNumber, state) {
  var match = /^(.+?)\s*-->\s*(.+?)(?:\s*:\s*(.*))?$/.exec(line);
  if (!match) {
    state.model.notes.push('parse-state: line ' + lineNumber + ": could not read '" + line
      + "' - skipped");
    return;
  }
  var from = endpoint(state, match[1].trim(), 'start');
  var to = endpoint(state, match[2].trim(), 'end');
  var label = match[3] === undefined ? null : cleanLabel(match[3]);
  state.model.edges.push({
    from: from,
    to: to,
    label: label,
    style: 'solid',
    arrow: 'end',
    constraint: from !== to,
    weight: 1,
    exit: null,
    entry: null,
    waypoints: null
  });
}

/**
 * Resolves a transition endpoint to a node id, creating pseudo states for {@code [*]}.
 * @param {object} state the parser state
 * @param {string} token the endpoint text
 * @param {string} role 'start' or 'end'
 * @returns {string} the node id
 */
function endpoint(state, token, role) {
  if (token === '[*]') {
    var id;
    if (role === 'start') {
      id = '__start' + (++state.startCount);
      ensureNode(state, id, '', 'start');
    } else {
      id = '__end' + (++state.endCount);
      ensureNode(state, id, '', 'end');
    }
    return id;
  }
  var id = unquote(token);
  if (state.groupById[id] && !state.compositePlaceholders[id]) {
    state.compositePlaceholders[id] = true;
    state.model.notes.push("parse-state: a transition names the composite state '" + id
      + "' - it is drawn as a placeholder node next to the composite group (the band itself"
      + ' is not an edge endpoint)');
  }
  ensureNode(state, id, id);
  return id;
}

/**
 * Ensures a state node exists.
 * @param {object} state the parser state
 * @param {string} id the node id
 * @param {string} label the label
 * @param {string} [shape] an explicit shape
 */
function ensureNode(state, id, label, shape) {
  var existing = state.nodeById[id];
  if (existing) {
    if (label !== undefined && label !== null && label !== '') {
      existing.label = label;
    }
    return;
  }
  var group = state.groupStack.length
    ? state.groupStack[state.groupStack.length - 1] : null;
  var node = {
    id: id,
    label: label === undefined || label === null ? id : label,
    shape: shape || 'round',
    w: null,
    h: null,
    group: group,
    rank: null,
    order: null,
    fixed: null
  };
  state.nodeById[id] = node;
  state.model.nodes.push(node);
}

/**
 * The currently open composite state, or null.
 * @param {object} state the parser state
 * @returns {object|null} the group
 */
function currentGroup(state) {
  if (state.groupStack.length === 0) {
    return null;
  }
  return state.groupById[state.groupStack[state.groupStack.length - 1]];
}

/**
 * Removes surrounding quotes.
 * @param {string} value the value
 * @returns {string} the unquoted value
 */
function unquote(value) {
  var text = String(value).trim();
  if (text.length >= 2 && text.charAt(0) === '"' && text.charAt(text.length - 1) === '"') {
    return text.substring(1, text.length - 1);
  }
  return text;
}

/**
 * Cleans a transition label.
 * @param {string} raw the raw label
 * @returns {string} the label
 */
function cleanLabel(raw) {
  return unquote(raw).replace(/<br\s*\/?>/gi, '\n');
}

module.exports = {
  parse: parse
};
