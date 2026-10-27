const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const htmlPath = process.argv[2] || path.join(__dirname, '../../main/resources/templates/log.html');
const html = fs.readFileSync(htmlPath, 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
assert.match(html, /<dialog\b[^>]*\bclosedby='any'/);

function boot(native) {
    const handlers = {}, cleanup = [], closeButton = {};
    const video = {
        pause: () => cleanup.push('pause'),
        removeAttribute: name => cleanup.push(`remove ${name}`),
        load: () => cleanup.push('load')
    };
    const content = {
        querySelector: () => video,
        replaceChildren: () => cleanup.push('clear')
    };
    const dialog = {
        open: true,
        getBoundingClientRect: () => ({left: 100, top: 100, right: 500, bottom: 400}),
        addEventListener: (name, handler) => (handlers[name] ??= []).push(handler),
        close() { this.open = false; emit('close'); }
    };
    if (native) dialog.closedBy = 'any';
    function emit(name, values = {}) {
        const event = {target: dialog, clientX: 50, clientY: 150, button: 0, ...values};
        if (name === 'pointerdown' && !('isPrimary' in event)) event.isPrimary = true;
        for (const handler of handlers[name] || []) handler(event);
    }
    const documentHandlers = {};
    const pageClasses = [];
    vm.runInNewContext(script, {document: {
        documentElement: {classList: {add: name => pageClasses.push(name)}},
        getElementById: id => ({detail: dialog, 'detail-content': content, 'detail-close': closeButton})[id],
        addEventListener: (name, handler) => (documentHandlers[name] ??= []).push(handler)
    }, addEventListener: () => {}});
    assert.deepEqual(pageClasses, ['js'], 'the script marks the page so long messages are cut only with JavaScript');
    return {dialog, handlers, documentHandlers, cleanup, closeButton, emit};
}

const native = boot(true);
assert.deepEqual(Object.keys(native.handlers), ['close'], 'native dismissal needs no fallback listeners');
native.closeButton.onclick();
assert.equal(native.dialog.open, false, 'Close button still closes');
assert.deepEqual(native.cleanup, ['pause', 'remove src', 'load', 'clear']);

const cases = [
    ['outside', {}, {}, true],
    ['above', {clientX: 150, clientY: 50}, {clientX: 150, clientY: 50}, true],
    ['below', {clientX: 150, clientY: 450}, {clientX: 150, clientY: 450}, true],
    ['right', {clientX: 550}, {clientX: 550}, true],
    ['padding', {clientX: 110}, {clientX: 110}, false],
    ['scrollbar', {clientX: 495}, {clientX: 495}, false],
    ['border', {clientX: 500}, {clientX: 500}, false],
    ['inside control', {target: {}}, {target: {}}, false],
    ['text selection dragged outside', {target: {}, clientX: 150}, {}, false],
    ['padding dragged outside', {clientX: 110}, {}, false],
    ['outside dragged inside', {}, {clientX: 110}, false],
    ['right button', {button: 2}, {button: 2}, false],
    ['secondary pointer', {isPrimary: false}, {}, false],
    ['non-primary click button', {}, {button: 2}, false]
];
for (const [name, down, click, dismiss] of cases) {
    const test = boot(false);
    test.emit('pointerdown', down);
    test.emit('click', click);
    assert.equal(test.dialog.open, !dismiss, name);
    assert.deepEqual(test.cleanup, dismiss ? ['pause', 'remove src', 'load', 'clear'] : [], name);
}
for (const reset of ['pointercancel', 'close', 'click']) {
    const test = boot(false);
    test.emit('pointerdown');
    test.emit(reset, {clientX: 110});
    test.cleanup.length = 0;
    test.emit('click');
    assert.deepEqual(test.cleanup, [], `${reset} resets the pointer gesture`);
}
const noPointer = boot(false);
noPointer.emit('click');
assert.equal(noPointer.dialog.open, true, 'a click without a pointerdown does not dismiss');

// An attachment link is a plain anchor. It has neither [data-vars], .img-link nor .more, so the page script must
// leave its click to the browser.
const page = boot(true);
assert.equal(page.documentHandlers.click.length, 1, 'one document click handler');
const selectors = [];
function control(classes, hasData) {
    const element = {
        closest(selector) {
            selectors.push(selector);
            const match = selector.split(',').map(part => part.trim()).some(part =>
                part === '[data-vars]' ? hasData : part.startsWith('.') && classes.includes(part.slice(1)));
            return match ? element : null;
        }
    };
    return element;
}
let prevented = 0;
const plainLink = {target: control([], false), button: 0, preventDefault: () => prevented++};
page.documentHandlers.click[0](plainLink);
assert.equal(prevented, 0, 'the attachment link keeps its native click');
assert.deepEqual(selectors.map(selector => selector.split(',').map(part => part.trim()).sort()), [['.img-link', '.more', '[data-vars]']],
    'the script selects only variable buttons, media links, and Show full message buttons');
console.log(`PASS (${htmlPath}): native setup, Close button, 14 pointer cases, gesture resets, media cleanup, and plain links`);
