const CHARSET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

export function generateJoinCode(): string {
  const raw = Array.from({ length: 6 }, () =>
    CHARSET[Math.floor(Math.random() * CHARSET.length)]
  ).join('');
  return `${raw.slice(0, 3)}-${raw.slice(3)}`;
}

export function normalizeJoinCode(input: string): string {
  const cleaned = input.toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (cleaned.length !== 6) {
    return input.trim().toUpperCase();
  }
  return `${cleaned.slice(0, 3)}-${cleaned.slice(3)}`;
}
