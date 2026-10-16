// Event fields may summarize data or reference it on disk, never inline collections.
export function isEventSummaryValue(value) {
  if (value === null || ['string', 'number', 'boolean'].includes(typeof value)) return true;
  if (typeof value !== 'object' || Array.isArray(value)) return false;
  const keys = Object.keys(value);
  // Reference fields identify a FileRef; malformed references cannot fall back to a string map.
  const isReferenceKey = key => key === 'path' || key === 'bytes' || key === 'mediaType';
  if (keys.some(isReferenceKey)) {
    return keys.length === 3 && keys.every(isReferenceKey)
      && typeof value.path === 'string'
      && typeof value.bytes === 'number'
      && typeof value.mediaType === 'string';
  }
  // IPC.md:31/427/428: namedParams is a flat string map; an empty map is valid too.
  return Object.values(value).every(entry => typeof entry === 'string');
}
