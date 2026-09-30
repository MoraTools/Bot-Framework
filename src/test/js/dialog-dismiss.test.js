const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../../main/resources/templates/log.html'), 'utf8');
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
    vm.runInNewContext(script, {document: {
        getElementById: id => ({detail: dialog, 'detail-content': content, 'detail-close': closeButton})[id],
        addEventListener() {}
    }});
    return {dialog, handlers, cleanup, closeButton, emit};
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
console.log('PASS: native setup, Close button, 14 pointer cases, gesture resets, and media cleanup');
