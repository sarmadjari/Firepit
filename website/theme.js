// Theme, shared by every page: System by default, or Light or Dark when someone picks one. Loaded in the head,
// before the page draws, so a chosen theme never flashes the other one first. main.js adds the menu.
// Remembered in this browser only (localStorage, no cookie).
const THEME_KEY = 'firepit-theme';

function savedTheme() {
  try {
    const value = localStorage.getItem(THEME_KEY);
    return value === 'light' || value === 'dark' ? value : 'system';
  } catch {
    return 'system';
  }
}

// The stylesheet reads html[data-theme]. The screenshots and the browser's own colour pick light or dark through
// media queries, which only know the system setting, so a choice rewrites those queries too.
function applyTheme(choice) {
  const root = document.documentElement;
  if (choice === 'system') delete root.dataset.theme;
  else root.dataset.theme = choice;
  const forced = { light: ['all', 'not all'], dark: ['not all', 'all'] }[choice];
  const [lightMedia, darkMedia] = forced ?? ['(prefers-color-scheme: light)', '(prefers-color-scheme: dark)'];
  document.querySelectorAll('[data-scheme="light"]').forEach((el) => el.setAttribute('media', lightMedia));
  document.querySelectorAll('[data-scheme="dark"]').forEach((el) => el.setAttribute('media', darkMedia));
}

applyTheme(savedTheme());

function setUpThemeMenu() {
  const theme = document.querySelector('.theme');
  if (!theme) return;
  const button = theme.querySelector('.theme-button');
  const options = [...theme.querySelectorAll('[data-theme-choice]')];
  const names = { system: 'System', light: 'Light', dark: 'Dark' };

  const sync = () => {
    const current = document.documentElement.dataset.theme ?? 'system';
    options.forEach((option) => option.setAttribute('aria-pressed', String(option.dataset.themeChoice === current)));
    button.setAttribute('aria-label', `Theme: ${names[current]}`);
  };
  const open = (yes) => {
    theme.classList.toggle('is-open', yes);
    button.setAttribute('aria-expanded', String(yes));
    if (yes) options.find((option) => option.getAttribute('aria-pressed') === 'true')?.focus({ preventScroll: true });
  };

  button.addEventListener('click', () => open(!theme.classList.contains('is-open')));
  options.forEach((option) => option.addEventListener('click', () => {
    const choice = option.dataset.themeChoice;
    try {
      if (choice === 'system') localStorage.removeItem(THEME_KEY);
      else localStorage.setItem(THEME_KEY, choice);
    } catch {
      // Storage refused (a private window, say): the choice still holds until the page is closed.
    }
    applyTheme(choice);
    sync();
    open(false);
    button.focus({ preventScroll: true });
  }));
  document.addEventListener('click', (event) => {
    if (!theme.contains(event.target)) open(false);
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && theme.classList.contains('is-open')) {
      open(false);
      button.focus({ preventScroll: true });
    }
  });
  sync();
}
