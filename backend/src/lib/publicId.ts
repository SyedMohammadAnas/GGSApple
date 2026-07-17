/** Normalize 11-digit public ID — strips dashes and spaces. */
export function normalizePublicId(raw: string): string {
  return raw.replace(/[\s-]/g, '').trim();
}

/** Format for display: X-XXX-XXX-XXX */
export function formatPublicId(id: string): string {
  const digits = normalizePublicId(id);
  if (digits.length !== 11) return digits;
  return `${digits[0]}-${digits.slice(1, 4)}-${digits.slice(4, 7)}-${digits.slice(7, 11)}`;
}

export function isValidPublicId(raw: string): boolean {
  const digits = normalizePublicId(raw);
  return /^\d{11}$/.test(digits);
}
