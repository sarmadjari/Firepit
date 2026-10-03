// Progressive touches only: the page reads the same without this file.
document.documentElement.classList.add('js');

document.addEventListener('DOMContentLoaded', () => {
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
});

// "How it works": one message from phone to phone, showing where each layer of encryption is added and removed.
// Each step sets three attributes on the diagram and the stylesheet does the rest:
//   stage   where the packet is (which link it travels, which stop acts on it)
//   phase   which caption and which stop are highlighted
//   content what the packet holds right now: words, sealed bytes, or sealed bytes inside the channel layer
function playPacketStory(reduceMotion) {
  const flow = document.querySelector('.flow');
  if (!flow || reduceMotion) return;

  const steps = [
    ['plain', 'a', 'plain', 1100],
    ['seal', 'a', 'sealed', 500],
    ['bt1', 'a', 'sealed', 1200],
    ['wrap', 'b', 'wrapped', 600],
    ['lora1', 'b', 'wrapped', 1200],
    ['relay', 'c', 'wrapped', 700],
    ['lora2', 'c', 'wrapped', 1200],
    ['unwrap', 'd', 'sealed', 600],
    ['bt2', 'd', 'sealed', 1200],
    ['open', 'e', 'plain', 2600],
    ['rest', 'e', 'plain', 500],
  ];
  let index = 0;
  let timer = 0;
  let inView = false;

  const show = () => {
    const [stage, phase, content, ms] = steps[index];
    flow.dataset.stage = stage;
    flow.dataset.phase = phase;
    flow.dataset.content = content;
    timer = window.setTimeout(() => {
      index = (index + 1) % steps.length;
      show();
    }, ms);
  };
  const stop = () => window.clearTimeout(timer);
  const start = () => {
    stop();
    index = 0;
    show();
  };

  // The first frame shows straight away; the story plays from the start each time it scrolls into view.
  [flow.dataset.stage, flow.dataset.phase, flow.dataset.content] = steps[0];
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
