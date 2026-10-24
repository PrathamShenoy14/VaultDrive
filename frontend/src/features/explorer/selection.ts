export function selectEntry(
  current: Set<string>,
  key: string,
  ordered: string[],
  anchor: string | null,
  toggle: boolean,
  range: boolean,
) {
  if (range && anchor && ordered.includes(anchor)) {
    const a = ordered.indexOf(anchor),
      b = ordered.indexOf(key);
    return new Set([
      ...current,
      ...ordered.slice(Math.min(a, b), Math.max(a, b) + 1),
    ]);
  }
  if (!toggle) return new Set([key]);
  const next = new Set(current);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  return next;
}
