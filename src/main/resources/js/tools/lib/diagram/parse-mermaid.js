/**
 * diagram/parse-mermaid - the Mermaid frontend, a <b>declared subset</b>.
 *
 * Supported: {@code flowchart}/{@code graph} in TB/TD/BT/LR/RL, the node shapes of §6.1, the edge
 * operators {@code -->}, {@code -.->}, {@code ==>}, {@code ---}, {@code ~~~}, {@code <-->} with
 * {@code |label|}, {@code subgraph ... end} with {@code direction}, and {@code %%}-comments that
 * carry our directives (pin/rank/port/weight/route/curve/layout). Everything else - the other
 * diagram types, icons, markdown formatting, {@code classDef}/{@code linkStyle} evaluation - is
 * either a clear error at the first line or a note. See docs/js/diagram-plan.md §6.1.
 *
 *     var model = require('./diagram/parse-mermaid.js').parse(text);
 *
 * Directives are ordinary {@code %%} comments, so the same file keeps rendering in Mermaid while
 * our renderer sees more:
 *
 *     flowchart LR
 *         %% diagram: direction=TB
 *         %% pin: MODA 120,80
 *         %% rank: MODA,MODB=0
 *         %% port: MODA->MODC e:w
 *         %% weight: MODA->MODC 5
 *         %% curve: round
 *         %% layout: force
 *         MODA --> MODC
 */

'use strict';

/** The first word of the diagram types we recognise and refuse with a clear message. */
var OTHER_DIAGRAMS = ['sequenceDiagram', 'classDiagram', 'stateDiagram', 'stateDiagram-v2',
  'erDiagram', 'journey', 'gantt', 'pie', 'quadrantChart', 'requirementDiagram', 'gitGraph',
  'mindmap', 'timeline', 'sankey-beta', 'xychart-beta', 'block-beta', 'packet-beta',
  'architecture-beta', 'C4Context', 'C4Container', 'C4Component', 'C4Dynamic', 'zenuml'];

/** Statement keywords that are read but not evaluated. */
var IGNORED_STATEMENTS = ['classDef', 'class', 'style', 'linkStyle', 'click', 'accTitle',
  'accDescr', 'title'];

/** Edge operators, longest first so that {@code -->} wins over {@code --}. */
var EDGE_OPERATORS = [
  {text: '<-->', arrow: 'both', style: 'solid', constraint: true, directed: true},
  {text: '-->', arrow: 'end', style: 'solid', constraint: true, directed: true},
  {text: '---', arrow: 'none', style: 'solid', constraint: true, directed: true},
  {text: '-.->', arrow: 'end', style: 'dotted', constraint: true, directed: true},
  {text: '-.-', arrow: 'none', style: 'dotted', constraint: true, directed: true},
  {text: '==>', arrow: 'end', style: 'thick', constraint: true, directed: true},
  {text: '===', arrow: 'none', style: 'thick', constraint: true, directed: true},
  {text: '~~~', arrow: 'none', style: 'solid', constraint: false, directed: true},
  {text: '--', arrow: 'none', style: 'solid', constraint: true, directed: true},
  {text: '==', arrow: 'none', style: 'thick', constraint: true, directed: true},
  {text: '<--', arrow: 'start', style: 'solid', constraint: true, directed: true},
  {text: '-.', arrow: 'none', style: 'dotted', constraint: true, directed: true}
];

/**
 * Parses Mermaid flowchart source into a raw model.
 * @param {string} text the Mermaid source
 * @returns {object} the raw model (before {@code model.normalize})
 */
function parse(text) {
  var normalized = String(text).replace(/\r\n?/g, '\n');
  var lines = normalized.split('\n');
  var state = {
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
    groupStack: [],
    groupById: {},
    groupCounter: 0,
    pins: {},
    ranks: {},
    ports: {},
    weights: {},
    headerSeen: false,
    lastDirectionLine: null
  };

  var index = skipFrontmatter(lines, state);
  // The first meaningful line is the diagram header.
  while (index < lines.length) {
    var header = lines[index].trim();
    index++;
    if (header === '' || header.indexOf('%%') === 0) {
      if (header.indexOf('%%{') === 0) {
        state.model.notes.push('parse-mermaid: the init/theme directive ' + header
          + ' is ignored');
      }
      continue;
    }
    parseHeader(header, state);
    state.headerSeen = true;
    break;
  }
  if (!state.headerSeen) {
    throw new Error('diagram/parse-mermaid: no diagram header found (expected e.g.'
      + " 'flowchart LR')");
  }

  var prepared = prepareLines(lines, index);
  for (var p = 0; p < prepared.length; p++) {
    parseLine(prepared[p].text, prepared[p].number, state);
  }
  applyDirectives(state);
  return state.model;
}

/**
 * Joins lines whose shape brackets are not closed yet into one logical line, so a multi-line
 * label (a backtick block across three lines) is read as one node instead of three errors.
 * @param {Array<string>} lines the source lines
 * @param {number} from the first body line
 * @returns {Array<object>} {text, number} per logical line
 */
function prepareLines(lines, from) {
  var prepared = [];
  var buffer = null;
  var startNumber = 0;
  var depth = 0;
  for (var i = from; i < lines.length; i++) {
    var line = lines[i];
    if (buffer === null) {
      buffer = line;
      startNumber = i + 1;
      depth = bracketDepth(line);
    } else {
      buffer = buffer + '\n' + line;
      depth += bracketDepth(line);
    }
    if (depth <= 0) {
      prepared.push({text: buffer, number: startNumber});
      buffer = null;
      depth = 0;
    }
  }
  if (buffer !== null) {
    prepared.push({text: buffer, number: startNumber});
  }
  return prepared;
}

/**
 * The net number of unclosed brackets on a line (a coarse balance, used only to join lines).
 * @param {string} line the line
 * @returns {number} opens minus closes
 */
function bracketDepth(line) {
  var depth = 0;
  for (var i = 0; i < line.length; i++) {
    var ch = line.charAt(i);
    if (ch === '(' || ch === '[' || ch === '{') {
      depth++;
    } else if (ch === ')' || ch === ']' || ch === '}') {
      depth--;
    }
  }
  return depth;
}

/**
 * Skips YAML frontmatter and returns the index of the first line after it.
 * @param {Array<string>} lines the source lines
 * @param {object} state the parser state
 * @returns {number} the index to continue at
 */
function skipFrontmatter(lines, state) {
  var i = 0;
  while (i < lines.length && lines[i].trim() === '') {
    i++;
  }
  if (i < lines.length && lines[i].trim() === '---') {
    var start = i;
    i++;
    while (i < lines.length && lines[i].trim() !== '---') {
      i++;
    }
    if (i < lines.length) {
      i++;
    }
    state.model.notes.push('parse-mermaid: the frontmatter config (lines ' + (start + 1) + '-'
      + i + ') is ignored');
  }
  return i;
}

/**
 * Parses the diagram header line, accepting flowchart/graph and refusing other types clearly.
 * @param {string} header the header line
 * @param {object} state the parser state
 */
function parseHeader(header, state) {
  var match = /^(flowchart|graph)\b\s*([A-Za-z]{2})?\b/.exec(header);
  if (match) {
    var direction = match[2] || 'TB';
    state.model.direction = mapDirection(direction, header);
    return;
  }
  var wordMatch = /^([A-Za-z][A-Za-z0-9-]*)/.exec(header);
  var word = wordMatch ? wordMatch[1] : header;
  if (OTHER_DIAGRAMS.indexOf(word) >= 0) {
    throw new Error("diagram: '" + word + "' wird nicht unterstuetzt (unterstuetzt:"
      + ' flowchart; geplant: state, sequence - docs/js/diagram-plan.md §6.1)');
  }
  throw new Error("diagram/parse-mermaid: the first line '" + header + "' is not a flowchart"
    + " header - expected 'flowchart <direction>' or 'graph <direction>'");
}

/**
 * Maps a Mermaid direction token to an IR direction.
 * @param {string} token the token
 * @param {string} line the source line (for the error)
 * @returns {string} the direction
 */
function mapDirection(token, line) {
  var upper = String(token).toUpperCase();
  if (upper === 'TD') {
    return 'TB';
  }
  if (upper === 'TB' || upper === 'BT' || upper === 'LR' || upper === 'RL') {
    return upper;
  }
  throw new Error("diagram/parse-mermaid: unknown direction '" + token + "' in '" + line
    + "' (allowed: TB, TD, BT, LR, RL)");
}

/**
 * Parses one body line.
 * @param {string} rawLine the line
 * @param {number} lineNumber the 1-based line number
 * @param {object} state the parser state
 */
function parseLine(rawLine, lineNumber, state) {
  var line = rawLine.trim();
  while (line.length > 0 && line.charAt(line.length - 1) === ';') {
    line = line.substring(0, line.length - 1).trim();
  }
  if (line === '') {
    return;
  }
  if (line.indexOf('%%') === 0) {
    parseDirective(line, lineNumber, state);
    return;
  }
  if (/^end\s*$/i.test(line)) {
    if (state.groupStack.length === 0) {
      state.model.notes.push('parse-mermaid: line ' + lineNumber + ": 'end' without an open"
        + ' subgraph');
      return;
    }
    state.groupStack.pop();
    return;
  }
  if (/^subgraph\b/i.test(line)) {
    parseSubgraph(line, lineNumber, state);
    return;
  }
  var directionLine = /^direction\s+([A-Za-z]{2})\s*$/i.exec(line);
  if (directionLine) {
    var targetGroup = state.groupStack.length
      ? state.groupById[state.groupStack[state.groupStack.length - 1]] : null;
    if (targetGroup) {
      targetGroup.direction = mapDirection(directionLine[1], line);
    } else {
      state.model.notes.push('parse-mermaid: line ' + lineNumber + ': a top-level direction'
        + ' line changes nothing (use the flowchart header)');
    }
    return;
  }
  var firstWord = /^([A-Za-z]+)\b/.exec(line);
  if (firstWord && IGNORED_STATEMENTS.indexOf(firstWord[1]) >= 0) {
    state.model.notes.push('parse-mermaid: line ' + lineNumber + ": '" + firstWord[1]
      + "' is parsed and ignored (no class/style evaluation)");
    return;
  }
  parseStatement(line, lineNumber, state);
}

/**
 * Parses a {@code %%} line: one of our directives, an init directive or a plain comment.
 * @param {string} line the trimmed line
 * @param {number} lineNumber the 1-based line number
 * @param {object} state the parser state
 */
function parseDirective(line, lineNumber, state) {
  var body = line.replace(/^%%\s*/, '');
  if (body.indexOf('{') === 0) {
    state.model.notes.push('parse-mermaid: line ' + lineNumber + ': the init directive is'
      + ' ignored');
    return;
  }
  var match = /^(diagram|pin|rank|port|weight|route|curve|layout)\s*:\s*(.*)$/.exec(body);
  if (!match) {
    return; // an ordinary comment
  }
  var kind = match[1];
  var rest = match[2].trim();
  if (kind === 'layout') {
    var layoutValue = rest.toLowerCase();
    if (['layered', 'force'].indexOf(layoutValue) < 0) {
      state.model.notes.push("parse-mermaid: line " + lineNumber + ": unknown layout '"
        + rest + "' (allowed: layered, force)");
      return;
    }
    state.model.layout = layoutValue;
    return;
  }
  if (kind === 'curve') {
    var curveValue = rest.toLowerCase();
    if (['off', 'round', 'spline'].indexOf(curveValue) < 0) {
      state.model.notes.push("parse-mermaid: line " + lineNumber + ": unknown curve '"
        + rest + "' (allowed: off, round, spline)");
      return;
    }
    state.model.curve = curveValue;
    return;
  }
  if (kind === 'route') {
    var routeValue = rest.toLowerCase();
    if (['orthogonal', 'tree', 'direct'].indexOf(routeValue) < 0) {
      state.model.notes.push("parse-mermaid: line " + lineNumber + ": unknown route '"
        + rest + "' (allowed: orthogonal, tree, direct)");
      return;
    }
    state.model.route = routeValue;
    return;
  }
  if (kind === 'diagram') {
    var dir = /^direction\s*=\s*([A-Za-z]{2})$/.exec(rest);
    if (!dir) {
      state.model.notes.push("parse-mermaid: line " + lineNumber + ": unknown diagram"
        + " directive '" + rest + "' (expected 'direction=<TB|BT|LR|RL>')");
      return;
    }
    state.model.direction = mapDirection(dir[1], line);
    return;
  }
  if (kind === 'pin') {
    var pin = /^(\S+)\s+(-?[0-9.]+)\s*,\s*(-?[0-9.]+)$/.exec(rest);
    if (!pin) {
      state.model.notes.push("parse-mermaid: line " + lineNumber + ": malformed pin '"
        + rest + "' (expected 'pin: ID x,y')");
      return;
    }
    state.pins[pin[1]] = {x: parseFloat(pin[2]), y: parseFloat(pin[3])};
    return;
  }
  if (kind === 'rank') {
    var rank = /^(.+?)\s*=\s*([0-9]+)$/.exec(rest);
    if (!rank) {
      state.model.notes.push("parse-mermaid: line " + lineNumber + ": malformed rank '"
        + rest + "' (expected 'rank: A,B=0')");
      return;
    }
    var rankValue = parseInt(rank[2], 10);
    var ids = rank[1].split(',');
    for (var i = 0; i < ids.length; i++) {
      var id = ids[i].trim();
      if (id !== '') {
        state.ranks[id] = rankValue;
      }
    }
    return;
  }
  if (kind === 'port') {
    var port = /^(\S+?)\s*->\s*(\S+)\s+([nesw])\s*:\s*([nesw])$/.exec(rest);
    if (!port) {
      state.model.notes.push("parse-mermaid: line " + lineNumber + ": malformed port '"
        + rest + "' (expected 'port: A->B e:w')");
      return;
    }
    state.ports[port[1] + '>' + port[2]] = {exit: port[3], entry: port[4]};
    return;
  }
  if (kind === 'weight') {
    var weight = /^(\S+?)\s*->\s*(\S+)\s+([0-9.]+)$/.exec(rest);
    if (!weight) {
      state.model.notes.push("parse-mermaid: line " + lineNumber + ": malformed weight '"
        + rest + "' (expected 'weight: A->B 5')");
      return;
    }
    state.weights[weight[1] + '>' + weight[2]] = parseFloat(weight[3]);
    return;
  }
}

/**
 * Parses a {@code subgraph ...} line and opens a group.
 * @param {string} line the trimmed line
 * @param {number} lineNumber the 1-based line number
 * @param {object} state the parser state
 */
function parseSubgraph(line, lineNumber, state) {
  var rest = line.replace(/^subgraph\b/i, '').trim();
  var id;
  var label;
  var withId = /^([A-Za-z0-9_.-]+)\s*\[(.*)\]\s*$/.exec(rest);
  if (withId) {
    id = withId[1];
    label = cleanLabel(withId[2], state, lineNumber);
  } else if (rest !== '') {
    label = cleanLabel(rest, state, lineNumber);
    id = 'subgraph' + (++state.groupCounter);
  } else {
    id = 'subgraph' + (++state.groupCounter);
    label = id;
  }
  var parent = state.groupStack.length
    ? state.groupStack[state.groupStack.length - 1] : null;
  var group = {id: id, label: label, parent: parent, direction: null};
  if (state.groupById[id]) {
    state.model.notes.push("parse-mermaid: line " + lineNumber + ": duplicate subgraph id '"
      + id + "' - a fresh id is used");
    id = id + '-' + (++state.groupCounter);
    group.id = id;
  }
  state.groupById[id] = group;
  state.model.groups.push(group);
  state.groupStack.push(id);
}

/**
 * Parses a node declaration or an edge chain.
 * @param {string} line the trimmed line
 * @param {number} lineNumber the 1-based line number
 * @param {object} state the parser state
 */
function parseStatement(line, lineNumber, state) {
  var chain = readChain(line, lineNumber, state);
  if (chain === null) {
    state.model.notes.push('parse-mermaid: line ' + lineNumber + ": could not read '" + line
      + "' - skipped");
  }
}

/**
 * Reads a chain of node references joined by edge operators.
 * @param {string} line the statement
 * @param {number} lineNumber the 1-based line number
 * @param {object} state the parser state
 * @returns {boolean|null} true when read, null when not a chain
 */
function readChain(line, lineNumber, state) {
  var pos = 0;
  var first = readNodeRef(line, pos, state, lineNumber);
  if (first === null) {
    return null;
  }
  pos = first.end;
  ensureNode(state, first);
  var previous = first;
  while (true) {
    pos = skipSpace(line, pos);
    var op = readOperator(line, pos);
    if (op === null) {
      break;
    }
    pos = op.end;
    var label = null;
    var afterOp = skipSpace(line, pos);
    if (line.charAt(afterOp) === '|') {
      var close = line.indexOf('|', afterOp + 1);
      if (close < 0) {
        throw new Error('diagram/parse-mermaid: line ' + lineNumber + ': an edge label starts'
          + " with '|' but is not closed");
      }
      label = cleanLabel(line.substring(afterOp + 1, close), state, lineNumber);
      pos = close + 1;
    } else if ((op.value === '--' || op.value === '==') && !isNodeStart(line, afterOp)) {
      // The "A -- text --> B" spelling: the text between the operators is the label.
      var closing = findClosingOperator(line, afterOp);
      if (closing !== null) {
        label = cleanLabel(line.substring(afterOp, closing.index), state, lineNumber);
        op = closing.operator;
        pos = closing.end;
      }
    }
    pos = skipSpace(line, pos);
    var target = readNodeRef(line, pos, state, lineNumber);
    if (target === null) {
      throw new Error('diagram/parse-mermaid: line ' + lineNumber + ": the edge '"
        + op.value + "' has no target node");
    }
    pos = target.end;
    ensureNode(state, target);
    addEdge(state, previous.id, target.id, op, label, lineNumber);
    previous = target;
  }
  var rest = line.substring(pos).trim();
  if (rest !== '') {
    state.model.notes.push('parse-mermaid: line ' + lineNumber + ": the rest '" + rest
      + "' is not part of the subset (multiple targets with '&', inline shapes after an edge"
      + ' and similar) - skipped');
  }
  return true;
}

/**
 * Finds the closing operator of the {@code A -- label --> B} spelling.
 * @param {string} line the statement
 * @param {number} from the index to search from
 * @returns {object|null} {index, end, operator} or null
 */
function findClosingOperator(line, from) {
  var best = null;
  for (var i = 0; i < EDGE_OPERATORS.length; i++) {
    var candidate = EDGE_OPERATORS[i];
    if (candidate.text === '--' || candidate.text === '==') {
      continue;
    }
    var found = line.indexOf(candidate.text, from);
    if (found >= 0 && (best === null || found < best.index)) {
      best = {index: found, end: found + candidate.text.length, operator: candidate};
    }
  }
  return best;
}

/**
 * Whether a position starts a node reference (used to tell a label from a node).
 * @param {string} line the statement
 * @param {number} pos the position
 * @returns {boolean} true for a node start
 */
function isNodeStart(line, pos) {
  var ch = line.charAt(pos);
  return ch !== '' && /[A-Za-z0-9_(\[{]/.test(ch);
}

/**
 * Reads an edge operator at a position, longest match first.
 * @param {string} line the statement
 * @param {number} pos the position
 * @returns {object|null} the operator with its end index, or null
 */
function readOperator(line, pos) {
  var best = null;
  for (var i = 0; i < EDGE_OPERATORS.length; i++) {
    var candidate = EDGE_OPERATORS[i];
    if (line.substring(pos, pos + candidate.text.length) === candidate.text) {
      if (best === null || candidate.text.length > best.value.length) {
        best = {value: candidate.text, end: pos + candidate.text.length,
          arrow: candidate.arrow, style: candidate.style,
          constraint: candidate.constraint};
      }
    }
  }
  return best;
}

/**
 * Reads a node reference: an id, optionally followed by a shape bracket pair.
 * @param {string} line the statement
 * @param {number} pos the start position
 * @param {object} state the parser state
 * @param {number} lineNumber the 1-based line number
 * @returns {object|null} {id, label, shape, end} or null
 */
function readNodeRef(line, pos, state, lineNumber) {
  var start = skipSpace(line, pos);
  var idMatch = /^[A-Za-z0-9_\u0080-\uFFFF]+/.exec(line.substring(start));
  if (!idMatch) {
    return null;
  }
  var id = idMatch[0];
  var cursor = start + id.length;
  var shape = readShape(line, cursor, state, lineNumber);
  if (shape === null) {
    return {id: id, label: null, shape: 'rect', end: cursor};
  }
  return {id: id, label: shape.label, shape: shape.shape, end: shape.end};
}

/**
 * Reads the shape bracket pair after a node id, if any.
 * @param {string} line the statement
 * @param {number} pos the position right after the id
 * @param {object} state the parser state
 * @param {number} lineNumber the 1-based line number
 * @returns {object|null} {label, shape, end} or null
 */
function readShape(line, pos, state, lineNumber) {
  var cursor = pos;
  // A tiny space between id and bracket is legal in Mermaid; skip it.
  var spaced = skipSpace(line, cursor);
  var openers = [
    {open: '((', close: '))', shape: 'circle'},
    {open: '([', close: '])', shape: 'stadium'},
    {open: '[[', close: ']]', shape: 'subroutine'},
    {open: '[(', close: ')]', shape: 'cylinder'},
    {open: '{{', close: '}}', shape: 'hexagon'},
    {open: '[', close: ']', shape: 'rect'},
    {open: '(', close: ')', shape: 'round'},
    {open: '{', close: '}', shape: 'diamond'}
  ];
  for (var i = 0; i < openers.length; i++) {
    var opener = openers[i];
    if (line.substring(spaced, spaced + opener.open.length) === opener.open) {
      var contentStart = spaced + opener.open.length;
      var closeIndex = line.indexOf(opener.close, contentStart);
      if (closeIndex < 0) {
        throw new Error('diagram/parse-mermaid: line ' + lineNumber + ": a node shape opens"
          + " with '" + opener.open + "' but is not closed");
      }
      var raw = line.substring(contentStart, closeIndex);
      return {
        label: cleanLabel(raw, state, lineNumber),
        shape: opener.shape,
        end: closeIndex + opener.close.length
      };
    }
  }
  return null;
}

/**
 * Cleans a label: strips surrounding quotes and markdown backticks, turns {@code <br/>} into a
 * line break and records the markdown/HTML it had to interpret.
 * @param {string} raw the raw label
 * @param {object} state the parser state
 * @param {number} lineNumber the 1-based line number
 * @returns {string} the cleaned label
 */
function cleanLabel(raw, state, lineNumber) {
  var value = String(raw).trim();
  if (value.length >= 2 && value.charAt(0) === '"' && value.charAt(value.length - 1) === '"') {
    value = value.substring(1, value.length - 1);
  }
  if (value.indexOf('`') >= 0) {
    value = value.replace(/`/g, '');
    state.model.notes.push('parse-mermaid: line ' + lineNumber + ': markdown formatting in a'
      + ' label is not rendered - the backticks are removed');
  }
  value = value.replace(/<br\s*\/?>/gi, '\n');
  if (/<[A-Za-z/][^>]*>/.test(value)) {
    state.model.notes.push('parse-mermaid: line ' + lineNumber + ': HTML in a label is kept as'
      + ' text, not interpreted');
  }
  return value;
}

/**
 * Ensures a node exists and records its label/shape/group.
 * @param {object} state the parser state
 * @param {object} ref the node reference
 */
function ensureNode(state, ref) {
  var group = state.groupStack.length
    ? state.groupStack[state.groupStack.length - 1] : null;
  var existing = state.nodeById[ref.id];
  if (existing) {
    if (ref.label !== null && ref.label !== '') {
      existing.label = ref.label;
    }
    if (ref.label !== null && ref.shape && ref.shape !== 'rect') {
      existing.shape = ref.shape;
    }
    if (group && !existing.group) {
      existing.group = group;
    }
    return;
  }
  var node = {
    id: ref.id,
    label: (ref.label === null || ref.label === '') ? ref.id : ref.label,
    shape: ref.shape || 'rect',
    w: null,
    h: null,
    group: group,
    rank: null,
    order: null,
    fixed: null
  };
  state.nodeById[ref.id] = node;
  state.model.nodes.push(node);
}

/**
 * Adds an edge.
 * @param {object} state the parser state
 * @param {string} from the source id
 * @param {string} to the target id
 * @param {object} op the operator
 * @param {string|null} label the label
 * @param {number} lineNumber the 1-based line number
 */
function addEdge(state, from, to, op, label, lineNumber) {
  var constraint = op.constraint;
  if (from === to) {
    constraint = false;
  }
  state.model.edges.push({
    from: from,
    to: to,
    label: label,
    style: op.style,
    arrow: op.arrow,
    constraint: constraint,
    weight: 1,
    exit: null,
    entry: null,
    waypoints: null
  });
}

/**
 * Applies the collected directives (pin/rank/port/weight) to the parsed model.
 * @param {object} state the parser state
 */
function applyDirectives(state) {
  var id;
  for (id in state.pins) {
    if (!Object.prototype.hasOwnProperty.call(state.pins, id)) {
      continue;
    }
    if (state.nodeById[id]) {
      state.nodeById[id].fixed = state.pins[id];
    } else {
      state.model.notes.push("parse-mermaid: the pin directive names '" + id
        + "', which is not a node - ignored");
    }
  }
  for (id in state.ranks) {
    if (!Object.prototype.hasOwnProperty.call(state.ranks, id)) {
      continue;
    }
    if (state.nodeById[id]) {
      state.nodeById[id].rank = state.ranks[id];
    } else {
      state.model.notes.push("parse-mermaid: the rank directive names '" + id
        + "', which is not a node - ignored");
    }
  }
  for (var i = 0; i < state.model.edges.length; i++) {
    var edge = state.model.edges[i];
    var key = edge.from + '>' + edge.to;
    if (Object.prototype.hasOwnProperty.call(state.ports, key)) {
      edge.exit = state.ports[key].exit;
      edge.entry = state.ports[key].entry;
    }
    if (Object.prototype.hasOwnProperty.call(state.weights, key)) {
      edge.weight = state.weights[key];
    }
  }
  var portKey;
  for (portKey in state.ports) {
    if (!Object.prototype.hasOwnProperty.call(state.ports, portKey)) {
      continue;
    }
    if (!edgeExists(state.model.edges, portKey)) {
      state.model.notes.push("parse-mermaid: the port directive names the edge '" + portKey
        + "', which does not exist - ignored");
    }
  }
  var weightKey;
  for (weightKey in state.weights) {
    if (!Object.prototype.hasOwnProperty.call(state.weights, weightKey)) {
      continue;
    }
    if (!edgeExists(state.model.edges, weightKey)) {
      state.model.notes.push("parse-mermaid: the weight directive names the edge '" + weightKey
        + "', which does not exist - ignored");
    }
  }
}

/**
 * Whether an edge (given as 'from>to') exists.
 * @param {Array<object>} edges the edges
 * @param {string} key the key
 * @returns {boolean} true when it exists
 */
function edgeExists(edges, key) {
  var parts = key.split('>');
  for (var i = 0; i < edges.length; i++) {
    if (edges[i].from === parts[0] && edges[i].to === parts[1]) {
      return true;
    }
  }
  return false;
}

/**
 * Skips spaces and tabs.
 * @param {string} line the statement
 * @param {number} pos the position
 * @returns {number} the first non-space position
 */
function skipSpace(line, pos) {
  var cursor = pos;
  while (cursor < line.length && (line.charAt(cursor) === ' '
      || line.charAt(cursor) === '\t')) {
    cursor++;
  }
  return cursor;
}

module.exports = {
  parse: parse
};
