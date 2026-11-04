// The shared token, kept by this browser (research.md R5). It is sent as a bearer header and never
// placed in a URL. Storage can be refused (a private window); then the token lasts this page only.
const key = "edp.token";
let inMemory: string | null = null;

export function getToken(): string | null {
  try {
    return localStorage.getItem(key) ?? inMemory;
  } catch {
    return inMemory;
  }
}

export function setToken(token: string): void {
  inMemory = token;
  try {
    localStorage.setItem(key, token);
  } catch {
    // kept in memory only
  }
}

export function forgetToken(): void {
  inMemory = null;
  try {
    localStorage.removeItem(key);
  } catch {
    // nothing kept
  }
}
