/**
 * pdftext - a small typesetter on top of pdfedit: pages, headings, justified paragraphs, lists,
 * tables and flowcharts, rendered as pdfedit operations.
 *
 * The library is a document model plus two passes: the first one collects every string that needs
 * a width, the second one lays out and emits the ops. Measuring is the only thing it asks the
 * server for, and it asks once per (font, size) - a report of ten pages costs one measuring call,
 * not one per line.
 *
 *     var pdftext = loadShipped('pdftext');   // shipped in the server's jar (js/tools/modules.json)
 *     var doc = pdftext.report({size: 10, font: 'Helvetica', margin: 57});
 *     doc.heading('Pruefbericht 2026-220');
 *     doc.paragraph('Der Antrag ist vollstaendig eingegangen ...', {align: 'justify'});
 *     doc.table({cols: [{title: 'Nr.'}, {title: 'Leistung'}, {title: 'Betrag', align: 'right'}],
 *                rows: [['1', 'Aufstellung der Anlage 3', '1.240,00']]});
 *     doc.footer('Seite {page} von {pages}');
 *     var result = doc.save('target/report.pdf');
 *
 * Design notes that matter (and are documented in docs/js/pdftext.md):
 *
 *  - Measured is measured, not estimated. There is one width source per (font, size), and it is
 *    the one the writer uses. Which backend answers it is decided at run time:
 *      'op'        the pdfedit op {op:'text.measure'} - exact for standard fonts and for
 *                  embedded files, one call, no document written;
 *      'placement' the older way for an embedded TrueType file: a throwaway page whose lines are
 *                  read back with pdf.placement() (its `advance`). It cannot measure a standard
 *                  font, because a Base-14 font writes no /Widths.
 *    The second backend exists so the library works before the server carries text.measure; it
 *    says which one ran in the result.
 *
 *  - Justification is not `Tw`. A Tw does nothing on a Type 0 font (embedded TrueType), whose
 *    codes are two bytes wide, so the gap would be silently lost. pdfedit writes word spacing on
 *    such a font as TJ adjustments; this library asks for exactly that and says so in its notes.
 *    Against a server that predates that (wordSpacingMode), it falls back to placing every word
 *    of the line on its own - the page looks the same, but a copy of the text yields one word per
 *    line, which the result reports.
 *
 *  - No hyphenation, no font fallback, no bidi. A word that is wider than its column runs over
 *    (and the element is reported), because a silently clipped number column is the kind of thing
 *    one sees only on paper.
 */

'use strict';

/** Page size presets in points. */
var PAGES = {
  A4: {width: 595.28, height: 841.89},
  A3: {width: 841.89, height: 1190.55},
  A5: {width: 419.53, height: 595.28},
  LETTER: {width: 612, height: 792},
  LEGAL: {width: 612, height: 1008},
  TABLOID: {width: 792, height: 1224}
};

/**
 * What a report uses when its options say nothing - the one place where the house style of a
 * document lives. A4 and 2 cm margins are the German office default; change it per document with
 * {@code report({page:'LETTER'})} or for a whole run with {@code pdftext.defaults({page:'A5'})}.
 * <p>
 * Deliberately <em>not</em> a system property: this file runs in the JS sandbox, which has no
 * {@code Java}, no {@code process} and no {@code env} - a configurable paper size would have to be
 * handed down from the server ({@code -D…} read in Java, published e.g. through
 * {@code pdfedit.status()}), and that is a decision about the module, not a line in a library.
 * </p>
 */
var DEFAULTS = {
  page: 'A4',
  margin: 57,
  font: 'Helvetica',
  size: 10,
  color: 'black'
};

/** The measuring backend, probed once per run: 'op' (text.measure) or 'placement'. */
var backend = null;

/** Whether text.put writes word spacing as TJ on an embedded font (probed on demand). */
var tjGapProbe = null;

/**
 * Which measuring backend this server carries. A status answer that lists text.measure is the
 * clean case; everything else falls back to the placement round trip.
 * <p>
 * The call is deliberately not wrapped in a try/catch: a {@code status()} that throws is a broken
 * server, not a server without the op. Swallowing it would turn "the module does not answer" into
 * "this build cannot measure" - and the caller would read the wrong reason a few lines later.
 * </p>
 * @returns {string} 'op' or 'placement'
 */
function measuringBackend() {
  if (backend) {
    return backend;
  }
  var status = pdfedit.status();
  var ops = (status && status.ops) || [];
  backend = ops.indexOf('text.measure') >= 0 ? 'op' : 'placement';
  return backend;
}

/**
 * Whether pdfedit writes a word gap on a Type 0 font as TJ (the fix) or as a Tw (which is lost).
 * @param {object|string} font the font of the document
 * @returns {boolean} true when wordSpacing reaches the line
 */
function wordGapWorks(font) {
  if (tjGapProbe !== null) {
    return tjGapProbe;
  }
  if (typeof font === 'string') {
    // A standard font is a simple font: a Tw applies there, no probe needed.
    tjGapProbe = true;
    return tjGapProbe;
  }
  try {
    var probe = pdfedit.create([
      {op: 'page.add', size: 'A5'},
      {op: 'text.put', page: 1, space: 'page', x: 20, y: 40, text: 'a b', font: font, size: 10,
        color: 'black', wordSpacing: 1}
    ], {verify: false});
    var entry = (probe.changed || [])[1] || {};
    tjGapProbe = entry.wordSpacingMode === 'TJ';
  } catch (e) {
    // This catch IS the answer of the probe: a server that refuses wordSpacing on this font
    // cannot put a gap into the line, so the caller takes the word-by-word way - and says so in
    // notes. Nothing is hidden: `result.backend` and the note name the state.
    tjGapProbe = false;
  }
  return tjGapProbe;
}

// ---------------------------------------------------------------------------------------------
// measurement
// ---------------------------------------------------------------------------------------------

/**
 * Measures strings with one backend call.
 * @param {object|string} font the font (a standard name or {file, subset})
 * @param {number} size the text size
 * @param {Array<string>} texts the strings
 * @returns {Array<number>} the widths in points, in the order of the strings
 */
function measureTexts(font, size, texts) {
  if (texts.length === 0) {
    return [];
  }
  if (measuringBackend() === 'op') {
    return measureByOp(font, size, texts);
  }
  return measureByPlacement(font, size, texts);
}

/**
 * Measures with {op:'text.measure'} - the exact path, also for a standard font.
 * @param {object|string} font the font
 * @param {number} size the text size
 * @param {Array<string>} texts the strings
 * @returns {Array<number>} the widths
 */
function measureByOp(font, size, texts) {
  var answer = pdfedit.create([
    {op: 'text.measure', texts: texts, font: font, size: size}
  ], {verify: false});
  var entry = (answer.measured || [])[0];
  if (!entry || !entry.widths) {
    throw new Error('pdftext: text.measure answered no widths (' + JSON.stringify(answer.notes || []).slice(0, 200) + ')');
  }
  return entry.widths;
}

/**
 * Measures an embedded TrueType font the way it had to be done before text.measure existed: one
 * throwaway page with one line per string, read back with pdf.placement().
 * <p>
 * A Base-14 font cannot be measured this way - a standard font writes no /Widths, so the reader
 * answers advance: null. The error says which way out there is.
 * </p>
 * @param {object|string} font the font
 * @param {number} size the text size
 * @param {Array<string>} texts the strings
 * @returns {Array<number>} the widths
 */
function measureByPlacement(font, size, texts) {
  if (typeof font === 'string') {
    throw new Error("pdftext: the standard font '" + font + "' cannot be measured on this"
      + " server - a Base-14 font writes no /Widths, so pdf.placement() has no advance for it."
      + " Use {font: {file:'...ttf', subset:true}} or run the server with the text.measure op.");
  }
  var widths = [];
  var chunk = 400;
  for (var from = 0; from < texts.length; from += chunk) {
    var part = texts.slice(from, from + chunk);
    var ops = [{op: 'page.add', width: 1400, height: 60 + 16 * part.length}];
    for (var i = 0; i < part.length; i++) {
      ops.push({op: 'text.put', page: 1, space: 'page', x: 20, y: 20 + 16 * i,
        text: part[i], font: font, size: size, color: 'black'});
    }
    var created = pdfedit.create(ops, {verify: false});
    var placed = pdf.placement(created.data, {page: 1});
    var items = (placed.pages && placed.pages[0] && placed.pages[0].items) || [];
    var shown = [];
    for (var k = 0; k < items.length; k++) {
      if (items[k].kind === 'text') {
        shown.push(items[k]);
      }
    }
    if (shown.length !== part.length) {
      throw new Error('pdftext: the measuring page answered ' + shown.length + ' of '
        + part.length + ' lines');
    }
    for (var j = 0; j < shown.length; j++) {
      if (shown[j].advance === null || shown[j].advance === undefined) {
        throw new Error('pdftext: no width for ' + JSON.stringify(part[j]) + ' - widthsMissing '
          + shown[j].widthsMissing);
      }
      widths.push(shown[j].advance);
    }
  }
  return widths;
}

// ---------------------------------------------------------------------------------------------
// the document
// ---------------------------------------------------------------------------------------------

/**
 * Creates a report.
 * @param {object} options {page, margin, font, size, leading, color, header, footer, title, author}
 * @returns {object} the report
 */
function report(options) {
  return new Report(options || {});
}

/**
 * Reads or changes the defaults of every following report.
 * <p>
 *     pdftext.defaults()                    // {page, margin, font, size, color}
 *     pdftext.defaults({page: 'A5'})        // a whole run on A5
 *     pdftext.defaults({page: 'A4', margin: 71})
 * </p>
 * @param {object} [options] the values to change (a partial update)
 * @returns {object} the defaults as they stand now
 */
function defaults(options) {
  if (options) {
    var keys = Object.keys(options);
    for (var i = 0; i < keys.length; i++) {
      if (!(keys[i] in DEFAULTS)) {
        throw new Error("pdftext: unknown default '" + keys[i] + "' - known are "
          + Object.keys(DEFAULTS).join(', '));
      }
      DEFAULTS[keys[i]] = options[keys[i]];
    }
  }
  return {
    page: DEFAULTS.page,
    margin: DEFAULTS.margin,
    font: DEFAULTS.font,
    size: DEFAULTS.size,
    color: DEFAULTS.color
  };
}

/**
 * One report: the element list, the measuring cache and the layout pass.
 * @param {object} options the report options
 * @constructor
 */
function Report(options) {
  this.pageSize = resolvePage(options.page || DEFAULTS.page);
  this.margin = resolveMargin(options.margin === undefined ? DEFAULTS.margin : options.margin);
  this.font = options.font || DEFAULTS.font;
  this.size = options.size || DEFAULTS.size;
  this.leading = options.leading || Math.round(this.size * 1.3 * 100) / 100;
  this.color = options.color || DEFAULTS.color;
  this.title = options.title || null;
  this.author = options.author || null;
  this.headerText = options.header || null;
  this.footerText = options.footer || null;
  this.blocks = [];
  this.cache = new Map();
  this.jobs = new Map();
  this.notes = [];
}

/**
 * Resolves the page size option.
 * @param {string|object} page 'A4' or {width, height}
 * @returns {{width: number, height: number}} the size in points
 */
function resolvePage(page) {
  if (typeof page === 'string') {
    var preset = PAGES[page.toUpperCase()];
    if (!preset) {
      throw new Error("pdftext: unknown page '" + page + "' - known are "
        + Object.keys(PAGES).join(', ') + ' or {width, height} in points');
    }
    return preset;
  }
  if (page && page.width > 0 && page.height > 0) {
    return page;
  }
  throw new Error('pdftext: page is a preset name or {width, height} in points');
}

/**
 * Resolves the margin option.
 * @param {number|object} margin one number or {top, right, bottom, left}
 * @returns {{top: number, right: number, bottom: number, left: number}} the margins in points
 */
function resolveMargin(margin) {
  if (typeof margin === 'number') {
    return {top: margin, right: margin, bottom: margin, left: margin};
  }
  return {
    top: margin.top || 0,
    right: margin.right || 0,
    bottom: margin.bottom || 0,
    left: margin.left || 0
  };
}

// ---------------------------------------------------------------------------------------------
// elements
// ---------------------------------------------------------------------------------------------

/**
 * A heading.
 * @param {string} text the text
 * @param {object} [options] {level, size, spaceBefore, spaceAfter, align, font, color}
 * @returns {object} this report (for chaining)
 */
Report.prototype.heading = function (text, options) {
  var opts = options || {};
  var level = opts.level || 1;
  var size = opts.size || [this.size * 1.6, this.size * 1.3, this.size * 1.1][Math.min(level, 3) - 1];
  size = Math.round(size * 100) / 100;
  this.blocks.push({kind: 'paragraph', text: text, size: size, font: opts.font || this.font,
    align: opts.align || 'left', color: opts.color || this.color,
    leading: Math.round(size * 1.25 * 100) / 100,
    spaceBefore: opts.spaceBefore === undefined ? (level === 1 ? 0 : 8) : opts.spaceBefore,
    spaceAfter: opts.spaceAfter === undefined ? 6 : opts.spaceAfter});
  return this;
};

/**
 * A paragraph of flowing text.
 * @param {string} text the text; a line break forces a new line
 * @param {object} [options] {align, size, font, leading, spaceBefore, spaceAfter, color, indent}
 * @returns {object} this report
 */
Report.prototype.paragraph = function (text, options) {
  var opts = options || {};
  var size = opts.size || this.size;
  this.blocks.push({kind: 'paragraph', text: text, size: size, font: opts.font || this.font,
    align: opts.align || 'left', color: opts.color || this.color,
    leading: opts.leading || Math.round(size * 1.3 * 100) / 100,
    spaceBefore: opts.spaceBefore || 0, spaceAfter: opts.spaceAfter === undefined ? 6 : opts.spaceAfter,
    indent: opts.indent || 0});
  return this;
};

/**
 * A bullet or numbered list.
 * @param {Array<string>} items the items
 * @param {object} [options] {type: 'bullet'|'number', size, font, indent, spaceAfter}
 * @returns {object} this report
 */
Report.prototype.list = function (items, options) {
  var opts = options || {};
  var type = opts.type || 'bullet';
  var indent = opts.indent === undefined ? 14 : opts.indent;
  for (var i = 0; i < items.length; i++) {
    var marker = type === 'number' ? (i + 1) + '.' : '\u2022';
    this.blocks.push({kind: 'listitem', text: items[i], marker: marker, indent: indent,
      size: opts.size || this.size, font: opts.font || this.font,
      align: opts.align || 'left', color: opts.color || this.color,
      leading: Math.round((opts.size || this.size) * 1.3 * 100) / 100,
      spaceAfter: i === items.length - 1 ? (opts.spaceAfter === undefined ? 6 : opts.spaceAfter) : 0});
  }
  return this;
};

/**
 * A table.
 * @param {object} table {cols, rows, fontSize, header, zebra, padding, lineWidth, borderColor,
 *                        headerFill, zebraFill, spaceBefore, spaceAfter}
 * @returns {object} this report
 */
Report.prototype.table = function (table) {
  if (!table || !table.cols || !table.cols.length) {
    throw new Error('pdftext: a table needs cols:[{title, width, align}, …]');
  }
  this.blocks.push({
    kind: 'table',
    cols: table.cols,
    rows: table.rows || [],
    header: table.header === undefined ? true : table.header,
    fontSize: table.fontSize || Math.max(7, this.size - 1),
    font: table.font || this.font,
    zebra: !!table.zebra,
    padding: table.padding === undefined ? 4 : table.padding,
    lineWidth: table.lineWidth === undefined ? 0.5 : table.lineWidth,
    borderColor: table.borderColor || 'gray',
    headerFill: table.headerFill || '#e8e8e8',
    zebraFill: table.zebraFill || '#f6f6f6',
    spaceBefore: table.spaceBefore === undefined ? 8 : table.spaceBefore,
    spaceAfter: table.spaceAfter === undefined ? 10 : table.spaceAfter
  });
  return this;
};

/**
 * A flowchart: nodes with labels, edges between them, drawn orthogonally.
 * @param {object} chart {nodes: [{id, label}], edges: [[from, to]], size, gap, rankGap,
 *                        spaceBefore, spaceAfter}
 * @returns {object} this report
 */
Report.prototype.flowchart = function (chart) {
  if (!chart || !chart.nodes || !chart.nodes.length) {
    throw new Error('pdftext: a flowchart needs nodes:[{id, label}, …]');
  }
  this.blocks.push({
    kind: 'flowchart',
    nodes: chart.nodes,
    edges: chart.edges || [],
    size: chart.size || Math.max(8, this.size - 1),
    gap: chart.gap === undefined ? 34 : chart.gap,
    rankGap: chart.rankGap === undefined ? 96 : chart.rankGap,
    nodeHeight: chart.nodeHeight === undefined ? 26 : chart.nodeHeight,
    spaceBefore: chart.spaceBefore === undefined ? 8 : chart.spaceBefore,
    spaceAfter: chart.spaceAfter === undefined ? 10 : chart.spaceAfter
  });
  return this;
};

/**
 * A horizontal rule.
 * @param {object} [options] {spaceBefore, spaceAfter, color, lineWidth}
 * @returns {object} this report
 */
Report.prototype.rule = function (options) {
  var opts = options || {};
  this.blocks.push({kind: 'rule', color: opts.color || 'gray',
    lineWidth: opts.lineWidth === undefined ? 0.6 : opts.lineWidth,
    spaceBefore: opts.spaceBefore === undefined ? 8 : opts.spaceBefore,
    spaceAfter: opts.spaceAfter === undefined ? 8 : opts.spaceAfter});
  return this;
};

/**
 * Empty space.
 * @param {number} [height] the height in points (default 8)
 * @returns {object} this report
 */
Report.prototype.spacer = function (height) {
  this.blocks.push({kind: 'spacer', height: height === undefined ? 8 : height});
  return this;
};

/**
 * Starts a new page.
 * @returns {object} this report
 */
Report.prototype.pageBreak = function () {
  this.blocks.push({kind: 'pagebreak'});
  return this;
};

/**
 * Sets the header or the footer text. Both may hold {page} and {pages}.
 * @param {string} text the text, or null to clear it
 * @returns {object} this report
 */
Report.prototype.header = function (text) {
  this.headerText = text;
  return this;
};

/**
 * Sets the footer text, which may hold {page} and {pages}.
 * @param {string} text the text, or null to clear it
 * @returns {object} this report
 */
Report.prototype.footer = function (text) {
  this.footerText = text;
  return this;
};

// ---------------------------------------------------------------------------------------------
// measuring: collect every string, then ask once per (font, size)
// ---------------------------------------------------------------------------------------------

/**
 * The cache key of a font.
 * @param {object|string} font the font
 * @returns {string} the key
 */
function fontKey(font) {
  return typeof font === 'string' ? 'std:' + font : 'file:' + font.file + '|' + (font.subset !== false);
}

/**
 * Registers a string for the measuring pass.
 * @param {object} report the report
 * @param {string} text the string
 * @param {object|string} font the font
 * @param {number} size the size
 */
function want(report, text, font, size) {
  var fKey = fontKey(font);
  var key = fKey + '|' + size;
  var job = report.jobs.get(key);
  if (!job) {
    job = {key: key, font: font, size: size, texts: [], seen: {}};
    report.jobs.set(key, job);
  }
  if (!report.cache.has(key + '|' + text) && !job.seen[text]) {
    job.seen[text] = true;
    job.texts.push(text);
  }
}

/**
 * The width of a registered string.
 * @param {object} report the report
 * @param {string} text the string
 * @param {object|string} font the font
 * @param {number} size the size
 * @returns {number} the width in points
 */
function widthOf(report, text, font, size) {
  var key = fontKey(font) + '|' + size + '|' + text;
  var cached = report.cache.get(key);
  if (cached === undefined) {
    throw new Error('pdftext: "' + text + '" was not registered for measuring (internal)');
  }
  return cached;
}

/**
 * Collects the strings of every block.
 * @param {object} report the report
 */
function collect(report) {
  for (var i = 0; i < report.blocks.length; i++) {
    var block = report.blocks[i];
    if (block.kind === 'paragraph' || block.kind === 'listitem') {
      var words = splitWords(block.text);
      for (var w = 0; w < words.length; w++) {
        want(report, words[w], block.font, block.size);
      }
      want(report, ' ', block.font, block.size);
      if (block.kind === 'listitem') {
        want(report, block.marker, block.font, block.size);
      }
    } else if (block.kind === 'table') {
      for (var c = 0; c < block.cols.length; c++) {
        if (block.cols[c].title) {
          want(report, String(block.cols[c].title), block.font, block.fontSize);
        }
      }
      for (var r = 0; r < block.rows.length; r++) {
        for (var k = 0; k < block.rows[r].length; k++) {
          var cellWords = splitWords(cellText(block.rows[r][k]));
          for (var cw = 0; cw < cellWords.length; cw++) {
            want(report, cellWords[cw], block.font, block.fontSize);
          }
          want(report, ' ', block.font, block.fontSize);
        }
      }
    } else if (block.kind === 'flowchart') {
      for (var n = 0; n < block.nodes.length; n++) {
        want(report, block.nodes[n].label, report.font, block.size);
      }
    }
  }
}

/**
 * Runs the measuring jobs - only what is not in the cache yet (a later round may add the header
 * and the footer, whose text is only known once the page count is).
 * @param {object} report the report
 */
function measureAll(report) {
  var pending = [];
  report.jobs.forEach(function (job) {
    var todo = [];
    for (var i = 0; i < job.texts.length; i++) {
      if (!report.cache.has(job.key + '|' + job.texts[i])) {
        todo.push(job.texts[i]);
      }
    }
    if (todo.length) {
      pending.push({job: job, texts: todo});
    }
  });
  if (!pending.length) {
    return;
  }
  if (measuringBackend() === 'op') {
    // One call carries every pending job: the op list is the batch, and a report of ten pages
    // costs one measuring call - not one per font size.
    var ops = [];
    for (var p = 0; p < pending.length; p++) {
      ops.push({op: 'text.measure', texts: pending[p].texts, font: pending[p].job.font,
        size: pending[p].job.size});
    }
    var answer = pdfedit.create(ops, {verify: false});
    var measured = answer.measured || [];
    if (measured.length !== pending.length) {
      throw new Error('pdftext: text.measure answered ' + measured.length + ' entries for '
        + pending.length + ' fonts');
    }
    for (var m = 0; m < pending.length; m++) {
      var widths = measured[m].widths || [];
      for (var w = 0; w < pending[m].texts.length; w++) {
        report.cache.set(pending[m].job.key + '|' + pending[m].texts[w], widths[w]);
      }
    }
    return;
  }
  for (var q = 0; q < pending.length; q++) {
    var jobWidths = measureTexts(pending[q].job.font, pending[q].job.size, pending[q].texts);
    for (var k = 0; k < pending[q].texts.length; k++) {
      report.cache.set(pending[q].job.key + '|' + pending[q].texts[k], jobWidths[k]);
    }
  }
}

/**
 * Splits a text into words (a line break is a hard break, not a word).
 * @param {string} text the text
 * @returns {Array<string>} the words
 */
function splitWords(text) {
  return String(text).split(/\s+/).filter(function (word) {
    return word.length > 0;
  });
}

/**
 * The text of a table cell.
 * @param {object|string} cell the cell
 * @returns {string} its text
 */
function cellText(cell) {
  if (cell === null || cell === undefined) {
    return '';
  }
  return typeof cell === 'object' ? String(cell.text === undefined ? '' : cell.text) : String(cell);
}

/**
 * Greedy line breaking at word boundaries.
 * @param {object} report the report
 * @param {string} text the text (a line break in it starts a new line)
 * @param {object|string} font the font
 * @param {number} size the size
 * @param {number} width the available width
 * @returns {Array<{words: Array<string>, width: number}>} the lines
 */
function wrapText(report, text, font, size, width) {
  var lines = [];
  var paragraphs = String(text).split(/\r\n|\n|\r/);
  var spaceWidth = widthOf(report, ' ', font, size);
  for (var p = 0; p < paragraphs.length; p++) {
    var words = splitWords(paragraphs[p]);
    var current = [];
    var currentWidth = 0;
    for (var i = 0; i < words.length; i++) {
      var wordWidth = widthOf(report, words[i], font, size);
      var add = current.length ? spaceWidth + wordWidth : wordWidth;
      if (current.length && currentWidth + add > width) {
        lines.push({words: current, width: currentWidth});
        current = [words[i]];
        currentWidth = wordWidth;
      } else {
        current.push(words[i]);
        currentWidth += add;
      }
    }
    if (current.length) {
      lines.push({words: current, width: currentWidth});
    }
  }
  if (!lines.length) {
    lines.push({words: [], width: 0});
  }
  return lines;
}

// ---------------------------------------------------------------------------------------------
// layout
// ---------------------------------------------------------------------------------------------

/**
 * The layout pass: walks the blocks, breaks pages, and emits pdfedit ops.
 * @param {object} report the report
 * @returns {{ops: Array<object>, pageCount: number, notes: Array<string>, dropped: Array<string>}}
 *          the ops and what the layout noticed
 */
function layout(report) {
  var pages = [];
  var state = {pages: pages, page: null, y: 0, notes: report.notes, dropped: [],
    headerSpace: report.headerText ? report.size * 2.2 : 0,
    footerSpace: report.footerText ? report.size * 2.2 : 0};
  newPage(report, state);
  for (var i = 0; i < report.blocks.length; i++) {
    var block = report.blocks[i];
    if (block.kind === 'pagebreak') {
      newPage(report, state);
    } else if (block.kind === 'spacer') {
      state.y += block.height;
    } else if (block.kind === 'rule') {
      state.y += block.spaceBefore;
      place(state, {op: 'draw.line', space: 'page', x1: report.margin.left,
        y1: state.y, x2: pageWidth(report) - report.margin.right, y2: state.y,
        color: block.color, lineWidth: block.lineWidth});
      state.y += block.spaceAfter;
    } else if (block.kind === 'paragraph' || block.kind === 'listitem') {
      placeParagraph(report, state, block);
    } else if (block.kind === 'table') {
      placeTable(report, state, block);
    } else if (block.kind === 'flowchart') {
      placeFlowchart(report, state, block);
    }
  }
  return {pageCount: pages.length, notes: state.notes, dropped: state.dropped, state: state};
}

/**
 * Flattens the pages into one op list, in page order.
 * @param {Array<Array<object>>} pages the ops of each page
 * @returns {Array<object>} the ops
 */
function flatten(pages) {
  var ops = [];
  for (var p = 0; p < pages.length; p++) {
    for (var k = 0; k < pages[p].length; k++) {
      ops.push(pages[p][k]);
    }
  }
  return ops;
}

/** @param {object} report the report @returns {number} the usable page width */
function pageWidth(report) {
  return report.pageSize.width - report.margin.left - report.margin.right;
}

/** @param {object} report the report @returns {number} the usable page height */
function contentHeight(report) {
  var header = report.headerText ? report.size * 2.2 : 0;
  var footer = report.footerText ? report.size * 2.2 : 0;
  return report.pageSize.height - report.margin.top - report.margin.bottom - header - footer;
}

/**
 * Starts a new page.
 * @param {object} report the report
 * @param {object} state the layout state
 */
function newPage(report, state) {
  var header = report.headerText ? report.size * 2.2 : 0;
  // The page itself first: an op list that names page 3 has to create it before it uses it.
  state.page = [{op: 'page.add', width: report.pageSize.width, height: report.pageSize.height}];
  state.pages.push(state.page);
  state.y = report.margin.top + header;
  state.bottom = report.margin.top + header + contentHeight(report);
}

/**
 * Appends an op to the current page, with the page number filled in.
 * @param {object} state the layout state
 * @param {object} op the op
 */
function place(state, op) {
  op.page = state.pages.length;
  state.page.push(op);
}

/**
 * Ensures that a block of the given height still fits, and breaks the page otherwise.
 * @param {object} report the report
 * @param {object} state the layout state
 * @param {number} height the height the block needs
 * @param {boolean} [allowEmpty] keep it on an empty page even if it does not fit
 * @returns {boolean} true when the block fits on the current page
 */
function fit(report, state, height, allowEmpty) {
  if (state.y + height <= state.bottom + 0.001) {
    return true;
  }
  if (allowEmpty && state.y <= report.margin.top + (report.headerText ? report.size * 2.2 : 0) + 0.001) {
    return true;
  }
  newPage(report, state);
  return true;
}

/**
 * Places a paragraph (or one list item) line by line.
 * @param {object} report the report
 * @param {object} state the layout state
 * @param {object} block the block
 */
function placeParagraph(report, state, block) {
  var indent = block.indent || 0;
  var markerWidth = block.marker ? widthOf(report, block.marker, block.font, block.size) : 0;
  var spaceWidth = widthOf(report, ' ', block.font, block.size);
  state.y += block.spaceBefore || 0;
  var width = pageWidth(report) - indent - (block.marker ? markerWidth + 4 : 0);
  var lines = wrapText(report, block.text, block.font, block.size, width);
  for (var i = 0; i < lines.length; i++) {
    fit(report, state, block.leading, true);
    var baseline = state.y + block.size * 0.85;
    var x = report.margin.left + indent + (block.marker ? markerWidth + 4 : 0);
    if (block.marker && i === 0) {
      place(state, {op: 'text.put', space: 'page', x: report.margin.left + indent,
        y: baseline, text: block.marker, font: block.font, size: block.size, color: block.color});
    }
    var line = lines[i];
    if (line.words.length) {
      var text = line.words.join(' ');
      var last = i === lines.length - 1;
      if (block.align === 'justify' && !last && line.words.length > 1) {
        var extra = (width - line.width) / (line.words.length - 1);
        if (extra > 0.05 && wordGapWorks(block.font)) {
          place(state, {op: 'text.put', space: 'page', x: x, y: baseline, text: text,
            font: block.font, size: block.size, color: block.color, wordSpacing: extra});
        } else if (extra > 0.05) {
          // No TJ on this server: place the words, and say what that costs.
          var wx = x;
          for (var w = 0; w < line.words.length; w++) {
            place(state, {op: 'text.put', space: 'page', x: wx, y: baseline,
              text: line.words[w], font: block.font, size: block.size, color: block.color});
            wx += widthOf(report, line.words[w], block.font, block.size) + spaceWidth + extra;
          }
          noteOnce(state, 'justified text is set word by word on this server (word spacing on an'
            + ' embedded font needs the TJ support of text.put): the page looks justified, but a'
            + ' copy of the text yields one word per line');
        } else {
          place(state, {op: 'text.put', space: 'page', x: x, y: baseline, text: text,
            font: block.font, size: block.size, color: block.color});
        }
      } else {
        var offset = block.align === 'center' ? (width - line.width) / 2
          : block.align === 'right' ? width - line.width : 0;
        place(state, {op: 'text.put', space: 'page', x: x + offset, y: baseline, text: text,
          font: block.font, size: block.size, color: block.color});
      }
    }
    if (line.width > width + 0.5) {
      state.dropped.push('a word runs over its column by '
        + Math.round((line.width - width) * 10) / 10 + ' pt: ' + JSON.stringify(line.words[0]));
    }
    state.y += block.leading;
  }
  state.y += block.spaceAfter || 0;
}

/**
 * Adds a note once.
 * @param {object} state the layout state
 * @param {string} note the note
 */
function noteOnce(state, note) {
  if (state.notes.indexOf(note) < 0) {
    state.notes.push(note);
  }
}

/**
 * Places a table: header, rows, borders, page breaks with a repeated header.
 * @param {object} report the report
 * @param {object} state the layout state
 * @param {object} block the table block
 */
function placeTable(report, state, block) {
  var available = pageWidth(report);
  var widths = columnWidths(report, block, available);
  var leading = Math.round(block.fontSize * 1.25 * 100) / 100;
  var pad = block.padding;
  state.y += block.spaceBefore || 0;

  var header = [];
  if (block.header) {
    for (var c = 0; c < block.cols.length; c++) {
      header.push(wrapText(report, String(block.cols[c].title || ''), block.font,
        block.fontSize, widths[c] - 2 * pad));
    }
  }
  var headerHeight = header.length ? rowHeight(header, leading, pad) : 0;
  var top = 0;

  if (block.header) {
    fit(report, state, headerHeight, false);
    top = state.y;
    drawRow(report, state, block, header, widths, top, headerHeight, leading, pad, true, false,
      true);
    state.y += headerHeight;
  }
  for (var r = 0; r < block.rows.length; r++) {
    var cells = [];
    for (var k = 0; k < block.cols.length; k++) {
      cells.push(wrapText(report, cellText(block.rows[r][k]), block.font, block.fontSize,
        widths[k] - 2 * pad));
    }
    var height = rowHeight(cells, leading, pad);
    var firstOnPage = !block.header && r === 0;
    if (state.y + height > state.bottom + 0.001) {
      if (height > contentHeight(report) - (block.header ? headerHeight : 0)) {
        throw new Error('pdftext: row ' + (r + 1) + ' is ' + Math.round(height)
          + ' pt tall and does not fit a page - split the row or shorten its text');
      }
      newPage(report, state);
      firstOnPage = !block.header;
      if (block.header) {
        top = state.y;
        drawRow(report, state, block, header, widths, top, headerHeight, leading, pad, true,
          false, true);
        state.y += headerHeight;
      }
    }
    top = state.y;
    drawRow(report, state, block, cells, widths, top, height, leading, pad, false,
      block.zebra && r % 2 === 1, firstOnPage);
    state.y += height;
  }
  state.y += block.spaceAfter || 0;
}

/**
 * The height of a row.
 * @param {Array<Array<object>>} cells the wrapped cells
 * @param {number} leading the line height
 * @param {number} pad the cell padding
 * @returns {number} the height in points
 */
function rowHeight(cells, leading, pad) {
  var lines = 1;
  for (var i = 0; i < cells.length; i++) {
    lines = Math.max(lines, cells[i].length);
  }
  return lines * leading + 2 * pad;
}

/**
 * Computes the column widths: explicit ones first, the rest by content.
 * @param {object} report the report
 * @param {object} block the table block
 * @param {number} available the usable width
 * @returns {Array<number>} the widths in points
 */
function columnWidths(report, block, available) {
  var widths = new Array(block.cols.length);
  var auto = [];
  var used = 0;
  for (var i = 0; i < block.cols.length; i++) {
    var spec = block.cols[i].width;
    if (spec === undefined || spec === null || spec === 'auto') {
      auto.push(i);
    } else if (typeof spec === 'string' && spec.charAt(spec.length - 1) === '%') {
      widths[i] = available * parseFloat(spec) / 100;
      used += widths[i];
    } else {
      widths[i] = Number(spec);
      used += widths[i];
    }
  }
  if (auto.length) {
    var natural = new Array(auto.length);
    var total = 0;
    for (var a = 0; a < auto.length; a++) {
      natural[a] = naturalWidth(report, block, auto[a]);
      total += natural[a];
    }
    var rest = Math.max(0, available - used);
    for (var b = 0; b < auto.length; b++) {
      widths[auto[b]] = total > 0 ? rest * natural[b] / total : rest / auto.length;
    }
  }
  return widths;
}

/**
 * The widest single-line content of a column - what auto widths are distributed by.
 * @param {object} report the report
 * @param {object} block the table block
 * @param {number} index the column index
 * @returns {number} the natural width in points
 */
function naturalWidth(report, block, index) {
  var widest = widthOf(report, String(block.cols[index].title || ''), block.font, block.fontSize);
  for (var r = 0; r < block.rows.length; r++) {
    var words = splitWords(cellText(block.rows[r][index]));
    var width = 0;
    for (var w = 0; w < words.length; w++) {
      width += widthOf(report, words[w], block.font, block.fontSize) + widthOf(report, ' ', block.font, block.fontSize);
    }
    widest = Math.max(widest, Math.min(width, 260));
  }
  return Math.max(widest + 2 * block.padding, 24);
}

/**
 * Draws one row of a table: background, borders, text.
 * @param {object} report the report
 * @param {object} state the layout state
 * @param {object} block the table block
 * @param {Array<Array<object>>} cells the wrapped cells
 * @param {Array<number>} widths the column widths
 * @param {number} top the top edge of the row
 * @param {number} height the row height
 * @param {number} leading the line height
 * @param {number} pad the cell padding
 * @param {boolean} isHeader whether this is the header row
 * @param {boolean} shaded whether a zebra fill goes behind the row
 */
function drawRow(report, state, block, cells, widths, top, height, leading, pad, isHeader,
                 shaded, withTop) {
  var left = report.margin.left;
  var x = left;
  var fill = isHeader ? block.headerFill : shaded ? block.zebraFill : null;
  if (fill) {
    place(state, {op: 'draw.rect', space: 'page', x: left, y: top, width: sum(widths),
      height: height, fill: fill});
  }
  for (var c = 0; c < widths.length; c++) {
    var cell = cells[c] || [];
    var align = block.cols[c].align || 'left';
    for (var l = 0; l < cell.length; l++) {
      if (!cell[l].words.length) {
        continue;
      }
      var text = cell[l].words.join(' ');
      var offset = align === 'right' ? widths[c] - 2 * pad - cell[l].width
        : align === 'center' ? (widths[c] - 2 * pad - cell[l].width) / 2 : 0;
      place(state, {op: 'text.put', space: 'page', x: x + pad + offset,
        y: top + pad + block.fontSize * 0.85 + l * leading, text: text, font: block.font,
        size: block.fontSize, color: report.color});
    }
    place(state, {op: 'draw.line', space: 'page', x1: x, y1: top, x2: x, y2: top + height,
      color: block.borderColor, lineWidth: block.lineWidth});
    x += widths[c];
  }
  place(state, {op: 'draw.line', space: 'page', x1: x, y1: top, x2: x, y2: top + height,
    color: block.borderColor, lineWidth: block.lineWidth});
  if (withTop) {
    place(state, {op: 'draw.line', space: 'page', x1: left, y1: top, x2: left + sum(widths),
      y2: top, color: block.borderColor, lineWidth: block.lineWidth});
  }
  place(state, {op: 'draw.line', space: 'page', x1: left, y1: top + height, x2: left + sum(widths),
    y2: top + height, color: block.borderColor, lineWidth: block.lineWidth});
}

/**
 * Sums a list of numbers.
 * @param {Array<number>} values the values
 * @returns {number} the sum
 */
function sum(values) {
  var total = 0;
  for (var i = 0; i < values.length; i++) {
    total += values[i];
  }
  return total;
}

/**
 * Places a flowchart.
 * @param {object} report the report
 * @param {object} state the layout state
 * @param {object} block the flowchart block
 */
function placeFlowchart(report, state, block) {
  var ranks = rankNodes(block);
  var maxRank = 0;
  var nodes = [];
  for (var i = 0; i < block.nodes.length; i++) {
    var node = block.nodes[i];
    var width = widthOf(report, node.label, report.font, block.size) + 2 * 10;
    nodes.push({id: node.id, label: node.label, rank: ranks[node.id] || 0,
      width: Math.round(width * 100) / 100, height: block.nodeHeight});
    maxRank = Math.max(maxRank, ranks[node.id] || 0);
  }
  var height = (block.spaceBefore || 0) + maxRank * block.rankGap + block.nodeHeight
    + (block.spaceAfter || 0);
  // The page has to be chosen before the nodes are placed - a new page moves the top edge.
  fit(report, state, height, false);
  state.y += block.spaceBefore || 0;

  var byId = {};
  for (var n = 0; n < nodes.length; n++) {
    byId[nodes[n].id] = nodes[n];
  }
  for (var r = 0; r <= maxRank; r++) {
    var list = [];
    for (var m = 0; m < nodes.length; m++) {
      if (nodes[m].rank === r) {
        list.push(nodes[m]);
      }
    }
    var total = 0;
    for (var t = 0; t < list.length; t++) {
      total += list[t].width;
    }
    total += Math.max(0, list.length - 1) * block.gap;
    var x = report.margin.left + (pageWidth(report) - total) / 2;
    for (var p = 0; p < list.length; p++) {
      list[p].x = x;
      list[p].y = state.y + r * block.rankGap;
      x += list[p].width + block.gap;
    }
  }

  // The arrows are drawn in PAGE coordinates: content.append inherits the mirrored CTM of the
  // page content stream, so a raw y is a distance from the top edge.
  var rightEdge = 0;
  for (var q = 0; q < nodes.length; q++) {
    rightEdge = Math.max(rightEdge, nodes[q].x + nodes[q].width);
  }
  var back = backEdges(block);
  var channels = 0;
  for (var e = 0; e < block.edges.length; e++) {
    var from = byId[block.edges[e][0]];
    var to = byId[block.edges[e][1]];
    if (!from || !to) {
      throw new Error("pdftext: flowchart edge " + JSON.stringify(block.edges[e])
        + ' names a node that is not there');
    }
    if (back[block.edges[e][0] + '>' + block.edges[e][1]]) {
      var exitY = from.y + from.height + 24;
      var channel = rightEdge + 22;
      var inY = to.y + to.height / 2;
      line(state, from.x + from.width / 2, from.y + from.height, from.x + from.width / 2, exitY);
      line(state, from.x + from.width / 2, exitY, channel, exitY);
      line(state, channel, exitY, channel, inY);
      line(state, channel, inY, to.x + to.width + 7, inY);
      arrow(state, to.x + to.width, inY, -1, 0);
    } else {
      var startX = from.x + from.width / 2;
      var endX = to.x + to.width / 2;
      var channelY = from.y + from.height + 22 + (channels++) * 12;
      line(state, startX, from.y + from.height, startX, channelY);
      line(state, startX, channelY, endX, channelY);
      line(state, endX, channelY, endX, to.y - 7);
      arrow(state, endX, to.y, 0, 1);
    }
  }
  for (var v = 0; v < nodes.length; v++) {
    var box = nodes[v];
    place(state, {op: 'draw.rect', space: 'page', x: box.x, y: box.y, width: box.width,
      height: box.height, fill: null, stroke: 'black', lineWidth: 0.9});
    place(state, {op: 'text.put', space: 'page',
      x: box.x + (box.width - widthOf(report, box.label, report.font, block.size)) / 2,
      y: box.y + box.height / 2 + block.size * 0.35, text: box.label, font: report.font,
      size: block.size, color: report.color});
  }
  state.y += maxRank * block.rankGap + block.nodeHeight + (block.spaceAfter || 0);
}

/**
 * Computes a rank per node: longest path over the edges, back edges (cycles) excluded.
 * @param {object} block the flowchart block
 * @returns {object} rank by node id
 */
function rankNodes(block) {
  var adjacency = {};
  for (var i = 0; i < block.nodes.length; i++) {
    adjacency[block.nodes[i].id] = [];
  }
  for (var e = 0; e < block.edges.length; e++) {
    if (adjacency[block.edges[e][0]]) {
      adjacency[block.edges[e][0]].push(block.edges[e][1]);
    }
  }
  var ranks = {};
  for (var r = 0; r < block.nodes.length; r++) {
    ranks[block.nodes[r].id] = 0;
  }
  for (var pass = 0; pass < block.nodes.length; pass++) {
    var changed = false;
    for (var k = 0; k < block.edges.length; k++) {
      var edge = block.edges[k];
      if (backEdges(block)[edge[0] + '>' + edge[1]]) {
        continue;
      }
      var next = (ranks[edge[0]] || 0) + 1;
      if (next > (ranks[edge[1]] || 0)) {
        ranks[edge[1]] = next;
        changed = true;
      }
    }
    if (!changed) {
      break;
    }
  }
  return ranks;
}

/** Ranked once per block, so that the edges and the ranks agree. */
var backEdgeCache = new WeakMap();

/**
 * Finds the back edges of a flowchart (the edges that close a cycle).
 * @param {object} block the flowchart block
 * @returns {object} a set of "from>to" keys
 */
function backEdges(block) {
  var cached = backEdgeCache.get(block);
  if (cached) {
    return cached;
  }
  var adjacency = {};
  for (var i = 0; i < block.nodes.length; i++) {
    adjacency[block.nodes[i].id] = [];
  }
  for (var e = 0; e < block.edges.length; e++) {
    if (adjacency[block.edges[e][0]]) {
      adjacency[block.edges[e][0]].push(block.edges[e][1]);
    }
  }
  var state = {};
  var back = {};
  var visit = function (id) {
    state[id] = 1;
    var next = adjacency[id] || [];
    for (var n = 0; n < next.length; n++) {
      if (state[next[n]] === 1) {
        back[id + '>' + next[n]] = true;
      } else if (!state[next[n]]) {
        visit(next[n]);
      }
    }
    state[id] = 2;
  };
  for (var s = 0; s < block.nodes.length; s++) {
    if (!state[block.nodes[s].id]) {
      visit(block.nodes[s].id);
    }
  }
  backEdgeCache.set(block, back);
  return back;
}

/**
 * One orthogonal segment.
 * @param {object} state the layout state
 * @param {number} x1 start x
 * @param {number} y1 start y
 * @param {number} x2 end x
 * @param {number} y2 end y
 */
function line(state, x1, y1, x2, y2) {
  place(state, {op: 'draw.line', space: 'page', x1: x1, y1: y1, x2: x2, y2: y2,
    color: 'black', lineWidth: 0.8});
}

/**
 * A filled arrow head at (x, y), pointing in (dx, dy).
 * @param {object} state the layout state
 * @param {number} x the tip
 * @param {number} y the tip
 * @param {number} dx the direction, x part
 * @param {number} dy the direction, y part
 */
function arrow(state, x, y, dx, dy) {
  var len = 7;
  var half = 3.5;
  var nx = -dy;
  var ny = dx;
  var b1x = x - dx * len + nx * half;
  var b1y = y - dy * len + ny * half;
  var b2x = x - dx * len - nx * half;
  var b2y = y - dy * len - ny * half;
  place(state, {op: 'content.append', text: '0 g ' + round2(x) + ' ' + round2(y) + ' m '
    + round2(b1x) + ' ' + round2(b1y) + ' l ' + round2(b2x) + ' ' + round2(b2y) + ' l h f'});
}

/**
 * Rounds to two decimals for a content stream.
 * @param {number} value the value
 * @returns {number} the rounded value
 */
function round2(value) {
  return Math.round(value * 100) / 100;
}

/**
 * Writes the header and the footer on every page, now that the page count is known.
 * @param {object} report the report
 * @param {object} state the layout state
 */
function applyHeaderFooter(report, state) {
  var pages = state.pages.length;
  for (var p = 0; p < pages; p++) {
    var ops = state.pages[p];
    if (report.headerText) {
      var header = String(report.headerText).replace('{page}', String(p + 1))
        .replace('{pages}', String(pages));
      ops.push({op: 'text.put', page: p + 1, space: 'page', x: report.margin.left,
        y: report.margin.top + report.size * 0.9, text: header, font: report.font,
        size: report.size, color: 'gray'});
      ops.push({op: 'draw.line', page: p + 1, space: 'page', x1: report.margin.left,
        y1: report.margin.top + report.size * 1.4, x2: report.pageSize.width - report.margin.right,
        y2: report.margin.top + report.size * 1.4, color: 'lightgray', lineWidth: 0.5});
      if (widthOf(report, header, report.font, report.size) > pageWidth(report) + 0.5) {
        state.dropped.push('the header of page ' + (p + 1) + ' is wider than the text area');
      }
    }
    if (report.footerText) {
      var footer = String(report.footerText).replace('{page}', String(p + 1))
        .replace('{pages}', String(pages));
      var footerWidth = widthOf(report, footer, report.font, report.size);
      ops.push({op: 'text.put', page: p + 1, space: 'page',
        x: report.pageSize.width - report.margin.right - footerWidth,
        y: report.pageSize.height - report.margin.bottom + report.size * 0.2,
        text: footer, font: report.font, size: report.size, color: 'gray'});
    }
  }
}

// ---------------------------------------------------------------------------------------------
// rendering
// ---------------------------------------------------------------------------------------------

/**
 * Lays the report out and answers the pdfedit ops (without running them).
 * @returns {object} {ops, pageCount, notes, dropped}
 */
Report.prototype.ops = function () {
  if (!this.blocks.length) {
    throw new Error('pdftext: the report has no content');
  }
  this.cache = new Map();
  this.jobs = new Map();
  this.notes = [];
  collect(this);
  measureAll(this);
  var laid = layout(this);
  // The header and the footer hold {page} and {pages}, so their text is only known now - one
  // more measuring round, and then they stand on every page.
  var pages = laid.state.pages.length;
  for (var p = 1; p <= pages; p++) {
    if (this.headerText) {
      want(this, this.headerText.replace('{page}', String(p)).replace('{pages}', String(pages)),
        this.font, this.size);
    }
    if (this.footerText) {
      want(this, this.footerText.replace('{page}', String(p)).replace('{pages}', String(pages)),
        this.font, this.size);
    }
  }
  measureAll(this);
  applyHeaderFooter(this, laid.state);
  laid.ops = flatten(laid.state.pages);
  laid.backend = measuringBackend();
  if (laid.backend === 'placement') {
    noteOnce(laid.state, 'measured with pdf.placement() because this server has no text.measure'
      + ' op: exact for an embedded TrueType font, but a standard font cannot be measured this'
      + ' way (a Base-14 font writes no /Widths)');
  }
  return laid;
};

/**
 * Lays the report out, writes it with pdfedit and puts the bytes into a file.
 * @param {string} path the output path in the project
 * @param {object} [options] pdfedit options (passed through)
 * @returns {object} {file, bytes, pages, ops, backend, notes, dropped, verify}
 */
Report.prototype.save = function (path, options) {
  var laid = this.ops();
  var ops = laid.ops;
  if (this.title || this.author) {
    var info = {op: 'doc.info', set: {}};
    if (this.title) {
      info.set.title = this.title;
    }
    if (this.author) {
      info.set.author = this.author;
    }
    ops.unshift(info);
  }
  var opts = options || {verify: true};
  var created = pdfedit.create(ops, opts);
  fs.writeBytes(path, created.data);
  return {
    file: path,
    bytes: created.bytes,
    pages: laid.pageCount,
    ops: ops.length,
    backend: laid.backend,
    notes: (created.notes || []).concat(laid.notes),
    dropped: laid.dropped,
    verify: created.verify || null
  };
};

module.exports = {
  report: report,
  PAGES: PAGES,
  /** Reads or changes the defaults of following reports ({page, margin, font, size, color}). */
  defaults: defaults,
  /** Which measuring backend this server has ('op' or 'placement'). */
  backend: measuringBackend,
  /** Measures strings directly - the same numbers the report uses. */
  measure: measureTexts
};
