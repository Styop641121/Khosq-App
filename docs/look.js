// KHOSQ Web, stage 11 (6k): how the page looks, chosen by each person in this browser only (like the phone, where the theme and the chat
// background are local too and are not sent anywhere):
//  - the theme: "system" (follows the browser), "light" or "dark" (the phone's AppTheme);
//  - the chat background: one of 8 (the phone's Wallpaper.kt): 0 none, then six colours (the last two with a pattern of dots and of lines).
// Both are kept in localStorage (try/catch: where the browser refuses, the choice lasts until the page is closed).

export const THEMES = ["system", "light", "dark"];
export const THEME_KEY = "khosq_theme";
export const WALL_KEY = "khosq_wall";

/** The phone's colours for the chat background (Wallpaper.kt): [light top, light bottom], [dark top, dark bottom], pattern. Number 0 is none. */
export const WALLS = [
  null,
  { light: ["#E3F2FD", "#BBDEFB"], dark: ["#0B1B33", "#12305A"], pattern: "none" },
  { light: ["#FFE9D6", "#FFD1DC"], dark: ["#2A1530", "#3A1B2A"], pattern: "none" },
  { light: ["#E0F5EC", "#C8EBDD"], dark: ["#0E2420", "#12332B"], pattern: "none" },
  { light: ["#F7F1E3", "#F2E8D2"], dark: ["#1C1A16", "#26221A"], pattern: "none" },
  { light: ["#D9DCE6", "#C5C9D8"], dark: ["#000000", "#05060C"], pattern: "none" },
  { light: ["#EDE7FF", "#DCD3FA"], dark: ["#14122A", "#1E1A44"], pattern: "dots" },
  { light: ["#FFF3D6", "#FFE3A8"], dark: ["#1B1508", "#2B2010"], pattern: "lines" },
];
export const WALL_COUNT = WALLS.length;

/** The theme word to use: anything that is not one of the three is "system". */
export const themeOf = (v) => (THEMES.includes(v) ? v : "system");
/** The background number to use: a whole number from 0 to 7, else 0. */
export const wallOf = (v) => {
  const n = typeof v === "string" && /^\d{1,2}$/.test(v) ? Number(v) : typeof v === "number" ? v : NaN;
  return Number.isInteger(n) && n >= 0 && n < WALL_COUNT ? n : 0;
};

function read(key) {
  try {
    return localStorage.getItem(key);
  } catch (e) {
    return null;
  }
}
function write(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch (e) {
    // the choice lasts until the page is closed
  }
}

let theme = "system";
let wall = 0;
let root = null;
let media = null;

const prefersDark = () => {
  try {
    return Boolean(media && media.matches);
  } catch (e) {
    return false;
  }
};

/** Whether the page is dark now: chosen, or (for "system") what the browser says. */
export const isDark = () => (theme === "dark" ? true : theme === "light" ? false : prefersDark());

/** Writes the choices onto the page (the root element carries them; style.css does the rest). */
function paint() {
  if (!root) return;
  if (theme === "system") delete root.dataset.theme;
  else root.dataset.theme = theme;
  const w = WALLS[wall];
  if (!w) {
    delete root.dataset.wall;
    delete root.dataset.wallp;
    for (const v of ["--wall-a", "--wall-b", "--wall-ink"]) root.style.removeProperty(v);
    return;
  }
  const [a, b] = isDark() ? w.dark : w.light;
  root.dataset.wall = String(wall);
  root.dataset.wallp = w.pattern;
  root.style.setProperty("--wall-a", a);
  root.style.setProperty("--wall-b", b);
  root.style.setProperty("--wall-ink", isDark() ? "rgba(255,255,255,0.09)" : "rgba(0,0,0,0.08)");
}

/**
 * Starts: reads what this browser kept and paints it. Called once, before the first screen, so even the sign-in page follows the theme.
 * @param {HTMLElement} [el]  the root element
 * @param {{matchMedia?: (q: string) => {matches: boolean, addEventListener?: Function}}} [win]
 */
export function startLook(el = document.documentElement, win = window) {
  root = el;
  theme = themeOf(read(THEME_KEY));
  wall = wallOf(read(WALL_KEY));
  try {
    media = win.matchMedia ? win.matchMedia("(prefers-color-scheme: dark)") : null;
    if (media && media.addEventListener) {
      // for "system" the colours of the background follow the browser when it changes
      media.addEventListener("change", paint);
    }
  } catch (e) {
    media = null;
  }
  paint();
  return { theme, wall };
}

export const getTheme = () => theme;
export const getWall = () => wall;

export function setTheme(v) {
  theme = themeOf(v);
  write(THEME_KEY, theme);
  paint();
  return theme;
}

export function setWall(v) {
  wall = wallOf(v);
  write(WALL_KEY, String(wall));
  paint();
  return wall;
}
