// Progressive touches only: the page reads the same without this file.
document.documentElement.classList.add('js');

document.addEventListener('DOMContentLoaded', () => {
  // Again now the body exists: the screenshots' light and dark sources are in it.
  applyTheme(savedTheme());
  setUpThemeMenu();
  setUpSectionMenu();

  const header = document.querySelector('.site-header');
  const onScroll = () => header.classList.toggle('is-scrolled', window.scrollY > 8);
  onScroll();
  window.addEventListener('scroll', onScroll, { passive: true });

  const reveals = document.querySelectorAll('.reveal');
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (!('IntersectionObserver' in window) || reduceMotion) {
    reveals.forEach((el) => el.classList.add('is-in'));
  } else {
    const io = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add('is-in');
        io.unobserve(entry.target);
      }
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.1 });
    reveals.forEach((el) => io.observe(el));
  }

  // Underline the section being read: the last one whose top has passed 40% of the viewport.
  const links = [...document.querySelectorAll('.nav-links a')];
  const sections = links.map((a) => document.getElementById(a.hash.slice(1)));
  let ticking = false;
  const spy = () => {
    ticking = false;
    const line = window.innerHeight * 0.4;
    let current = -1;
    sections.forEach((section, i) => {
      if (section && section.getBoundingClientRect().top <= line) current = i;
    });
    links.forEach((a, i) => {
      if (i === current) a.setAttribute('aria-current', 'true');
      else a.removeAttribute('aria-current');
    });
  };
  spy();
  window.addEventListener('scroll', () => {
    if (!ticking) { ticking = true; requestAnimationFrame(spy); }
  }, { passive: true });
  window.addEventListener('resize', spy);

  playPacketStory(reduceMotion);
  playInviteDemo();
});

// "How it works": a short chat between Ryan and Maya, showing where each layer of encryption is added and removed.
// Every message makes the whole trip: sealed on the sender's phone with the room key, wrapped by the sender's radio in
// the channel key, passed on by a radio that cannot open it, unwrapped by the other radio, opened on the other phone.
// Maya's replies run the same path the other way. Each step sets attributes on the diagram and marks the stop or link
// that is acting; the stylesheet does the rest.
function playPacketStory(reduceMotion) {
  const flow = document.querySelector('.flow');
  if (!flow || reduceMotion) return;

  const nodes = [...flow.querySelectorAll('.node')];
  const links = [...flow.querySelectorAll('.link')];
  const bubbles = [...flow.querySelectorAll('.bubble')];
  const words = flow.querySelector('.v-plain .wire-text');
  const sealedBytes = flow.querySelector('.v-sealed code');
  const wrappedBytes = flow.querySelector('.v-wrapped code');

  const chat = [
    // Plain words anyone can follow, whatever their first language. 'out' is Ryan writing, 'in' is Maya.
    ['out', 'Hi Maya! Where are you?'],
    ['in', 'I am at the camp. And you?'],
    ['out', 'I am 10 minutes away.'],
    ['in', 'OK. The fire is ready 🔥'],
    ['out', 'Great! I am bringing water.'],
    ['in', 'Thank you! See you soon 👋'],
  ];
  // stop: which stop is in focus, counted from the sender (1 = sender's phone ... 5 = the other phone).
  // act: what that stop does now. hop: which link the packet is on, counted from the sender.
  const steps = [
    { stage: 'plain', phase: 'a', content: 'plain', ms: 1200, stop: 1 },
    { stage: 'seal', phase: 'a', content: 'sealed', ms: 500, stop: 1, act: 'room' },
    { stage: 'hop', phase: 'a', content: 'sealed', ms: 1200, stop: 1, hop: 1 },
    { stage: 'wrap', phase: 'b', content: 'wrapped', ms: 600, stop: 2, act: 'chan' },
    { stage: 'hop', phase: 'b', content: 'wrapped', ms: 1200, stop: 2, hop: 2 },
    { stage: 'relay', phase: 'c', content: 'wrapped', ms: 700, stop: 3, act: 'relay' },
    { stage: 'hop', phase: 'c', content: 'wrapped', ms: 1200, stop: 3, hop: 3 },
    { stage: 'unwrap', phase: 'd', content: 'sealed', ms: 600, stop: 4, act: 'chan' },
    { stage: 'hop', phase: 'd', content: 'sealed', ms: 1200, stop: 4, hop: 4 },
    { stage: 'open', phase: 'e', content: 'plain', ms: 2600, stop: 5, act: 'room' },
    // Everything fades out before the next message, so the direction can change unseen.
    { stage: 'rest', phase: '', content: '', ms: 600 },
  ];

  // What sealed bytes look like: new for every message, as real encryption would make them.
  const randomHex = (count) => [...crypto.getRandomValues(new Uint8Array(count))]
    .map((b) => b.toString(16).padStart(2, '0').toUpperCase()).join(' ');

  let message = 0;
  let step = 0;
  let timer = 0;
  let inView = false;

  const show = () => {
    const [dir, text] = chat[message];
    const { stage, phase, content, ms, stop, act, hop } = steps[step];
    const back = dir === 'in';

    if (step === 0) {
      bubbles.forEach((bubble) => { bubble.textContent = text; });
      words.textContent = `"${text}"`;
      sealedBytes.textContent = randomHex(16);
      wrappedBytes.textContent = randomHex(16);
    }
    Object.assign(flow.dataset, { dir, stage, phase, content });

    nodes.forEach((node) => node.classList.remove('is-current', 'is-act-room', 'is-act-chan', 'is-act-relay'));
    links.forEach((link) => link.classList.remove('is-moving', 'is-back'));
    if (stop) {
      const node = nodes[back ? 5 - stop : stop - 1];
      node.classList.add('is-current');
      if (act) node.classList.add(`is-act-${act}`);
    }
    if (hop) {
      const link = links[back ? 4 - hop : hop - 1];
      link.classList.add('is-moving');
      if (back) link.classList.add('is-back');
    }

    timer = window.setTimeout(() => {
      step = (step + 1) % steps.length;
      if (step === 0) message = (message + 1) % chat.length;
      show();
    }, ms);
  };
  const stop = () => window.clearTimeout(timer);
  const start = () => {
    stop();
    message = 0;
    step = 0;
    show();
  };

  // The first frame shows straight away; the chat plays from the start each time it scrolls into view.
  start();
  stop();
  if ('IntersectionObserver' in window) {
    new IntersectionObserver(([entry]) => {
      inView = entry.isIntersecting;
      if (inView && !document.hidden) start(); else stop();
    }, { threshold: 0.4 }).observe(flow);
  } else {
    inView = true;
    start();
  }
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) stop(); else if (inView) start();
  });
}

// The invite screen beside the steps: like the app, the code is replaced every 8 seconds, on the clock, and counts
// down to it. A real invite would be pointless here, so each code just says hello, in turn in eleven languages
// (scripts/make-site-qr.py makes them).
function playInviteDemo() {
  const screen = document.querySelector('.invite-screen');
  if (!screen) return;
  const codes = [...screen.querySelectorAll('.inv-qr')];
  const seconds = screen.querySelector('.inv-secs');
  const period = 8000; // RoomCrypto.rotationSeconds in the apps
  let timer = 0;
  let shown = -1;
  let inView = false;

  const tick = () => {
    const now = Date.now();
    const index = Math.floor(now / period) % codes.length;
    if (index !== shown) {
      codes.forEach((code, i) => code.classList.toggle('is-current', i === index));
      shown = index;
    }
    seconds.textContent = String(Math.ceil((period - (now % period)) / 1000));
    timer = window.setTimeout(tick, 1000 - (now % 1000) + 15);
  };
  const stop = () => window.clearTimeout(timer);
  const restart = () => { stop(); tick(); };

  restart();
  stop();
  if ('IntersectionObserver' in window) {
    new IntersectionObserver(([entry]) => {
      inView = entry.isIntersecting;
      if (inView && !document.hidden) restart(); else stop();
    }).observe(screen);
  } else {
    restart();
  }
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) stop(); else if (inView) restart();
  });
}

// On phones the section links fold behind a menu button (the stylesheet decides when). The same list serves both, so
// the underline that follows the reader marks the current section in the menu too.
function setUpSectionMenu() {
  const end = document.querySelector('.nav-end');
  const button = end?.querySelector('.menu-button');
  const list = end?.querySelector('.nav-links');
  if (!end || !button || !list) return;
  const links = [...list.querySelectorAll('a')];

  const open = (yes) => {
    end.classList.toggle('is-open', yes);
    button.setAttribute('aria-expanded', String(yes));
    if (yes) (links.find((link) => link.hasAttribute('aria-current')) ?? links[0]).focus({ preventScroll: true });
  };

  button.addEventListener('click', () => open(!end.classList.contains('is-open')));
  links.forEach((link) => link.addEventListener('click', () => {
    if (end.classList.contains('is-open')) open(false);
  }));
  document.addEventListener('click', (event) => {
    if (!button.contains(event.target) && !list.contains(event.target)) open(false);
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && end.classList.contains('is-open')) {
      open(false);
      button.focus({ preventScroll: true });
    }
  });
}
