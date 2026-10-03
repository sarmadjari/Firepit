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
});
