// Only the built-ins used by the pinned renderer that are absent from Chrome 80.
// No runtime package or framework is required.
function define(target, name, implementation) {
  if (!target[name]) Object.defineProperty(target, name, {
    value: implementation, configurable: true, writable: true,
  });
}

define(Array.prototype, "at", function (index) {
  const integer = Math.trunc(Number(index)) || 0;
  return this[integer < 0 ? this.length + integer : integer];
});
define(Array.prototype, "findLast", function (predicate, thisArg) {
  for (let index = this.length - 1; index >= 0; index--) {
    if (predicate.call(thisArg, this[index], index, this)) return this[index];
  }
});
define(Object, "hasOwn", function (object, key) {
  return Object.prototype.hasOwnProperty.call(object, key);
});
define(String.prototype, "replaceAll", function (search, replacement) {
  // Braccato uses a global RegExp for punctuation and a literal underscore for locale names.
  if (search instanceof RegExp) {
    if (!search.global) throw new TypeError("replaceAll requires a global RegExp");
    return String(this).replace(search, replacement);
  }
  const escaped = String(search).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return String(this).replace(new RegExp(escaped, "g"), replacement);
});
