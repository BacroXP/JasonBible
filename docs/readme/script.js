(() => {
  'use strict';

  document.documentElement.classList.add('js');

  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

  const hero = $('.hero');
  if (!hero) return;

  hero.id = 'top';

  /* The site link is only useful when the page is not the site itself. */
  const siteLink = $('.site-link');
  if (siteLink) {
    const target = siteLink.querySelector('a');
    if (target && target.host === window.location.host) siteLink.remove();
  }

  /* Tables scroll horizontally inside a rounded frame. */
  $$('table').forEach((table) => {
    const wrap = document.createElement('div');
    wrap.className = 'table-wrap';
    table.before(wrap);
    wrap.append(table);
  });

  /* Sticky top bar, cloned from the hero navigation. */
  const topbar = document.createElement('header');
  topbar.className = 'topbar';
  topbar.innerHTML = `
    <a class="topbar-brand" href="#top">
      <img src="icon.png" width="26" height="26" alt="">
      <span>Jason<em>Bible</em></span>
    </a>
    <nav class="topbar-nav" id="topbar-nav" aria-label="Sections"></nav>
    <button class="menu-btn" type="button" aria-label="Open section menu" aria-expanded="false" aria-controls="topbar-nav">
      <span></span><span></span><span></span>
    </button>
    <div class="progress" aria-hidden="true"><span></span></div>`;
  document.body.prepend(topbar);

  const topbarNav = $('#topbar-nav', topbar);
  const heroNav = $('.hero .nav');
  if (heroNav) {
    $$('a', heroNav).forEach((link) => {
      const clone = link.cloneNode(true);
      clone.className = '';
      topbarNav.append(clone);
    });
  }

  const progressBar = $('.progress span', topbar);
  const menuButton = $('.menu-btn', topbar);

  const closeMenu = () => {
    topbar.classList.remove('is-open');
    menuButton.setAttribute('aria-expanded', 'false');
  };

  menuButton.addEventListener('click', () => {
    const open = topbar.classList.toggle('is-open');
    menuButton.setAttribute('aria-expanded', String(open));
    menuButton.setAttribute('aria-label', open ? 'Close section menu' : 'Open section menu');
  });

  topbar.addEventListener('click', (event) => {
    if (event.target.closest('.topbar-nav a')) closeMenu();
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeMenu();
  });

  document.addEventListener('click', (event) => {
    if (!topbar.contains(event.target)) closeMenu();
  });

  /* Right-hand section rail. */
  const headings = $$('h2[id]');
  const rail = document.createElement('nav');
  rail.className = 'rail';
  rail.setAttribute('aria-label', 'Page sections');

  headings.forEach((heading, index) => {
    const link = document.createElement('a');
    link.className = 'rail-item';
    link.href = `#${heading.id}`;
    link.dataset.target = heading.id;
    link.innerHTML = `
      <span class="rail-num">${String(index + 1).padStart(2, '0')}</span>
      <span class="rail-label">${heading.textContent}</span>`;
    rail.append(link);
  });
  document.body.append(rail);

  const railItems = $$('.rail-item', rail);
  const setActiveSection = (id) => {
    railItems.forEach((item) => {
      const active = item.dataset.target === id;
      item.classList.toggle('is-active', active);
      if (active) item.setAttribute('aria-current', 'true');
      else item.removeAttribute('aria-current');
    });
  };

  if (headings.length) setActiveSection(headings[0].id);

  /* Hero scroll hint. */
  const hint = document.createElement('a');
  hint.className = 'scroll-hint';
  hint.href = '#download';
  hint.setAttribute('aria-hidden', 'true');
  hint.tabIndex = -1;
  hint.innerHTML = `
    <span>Scroll to explore</span>
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>`;
  hero.append(hint);

  /* Screenshot placeholders (kept out of the pandoc source). */
  const shotsNote = $('.shots-note');
  if (shotsNote) {
    const grid = document.createElement('div');
    grid.className = 'shot-grid';
    grid.innerHTML = `
      <figure class="shot">
        <div class="shot-art" aria-hidden="true"></div>
        <figcaption><strong>Home</strong><span>Daily verse &amp; reading plan</span></figcaption>
      </figure>
      <figure class="shot">
        <div class="shot-art" aria-hidden="true"></div>
        <figcaption><strong>Split view</strong><span>Reader and notes side by side</span></figcaption>
      </figure>`;
    shotsNote.after(grid);
  }

  /* Copy buttons for shell snippets. */
  $$('pre').forEach((pre) => {
    const code = pre.querySelector('code');
    if (!code) return;

    const wrap = document.createElement('div');
    wrap.className = 'code-wrap';
    pre.before(wrap);
    wrap.append(pre);

    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'copy';
    button.textContent = 'Copy';
    button.setAttribute('aria-label', 'Copy code');

    button.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(code.textContent.replace(/\n+$/, ''));
        button.textContent = 'Copied';
      } catch (error) {
        button.textContent = 'Copy failed';
      }
      window.setTimeout(() => {
        button.textContent = 'Copy';
      }, 1800);
    });

    wrap.append(button);
  });

  /* Footer (also kept out of the pandoc source). */
  const footer = document.createElement('footer');
  footer.className = 'footer';
  footer.innerHTML = `
    <div class="footer-inner">
      <div class="footer-brand">
        <img src="icon.png" width="34" height="34" alt="">
        <div>
          <strong>JasonBible</strong>
          <p class="footer-copy">© ${new Date().getFullYear()} JasonBible · Offline Bible study for Linux, Windows &amp; macOS · MIT</p>
        </div>
      </div>
      <a class="to-top" href="#top">Back to top <span aria-hidden="true">↑</span></a>
    </div>`;
  document.body.append(footer);

  /* External links open in a new tab. */
  $$('a[href^="http"]').forEach((link) => {
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
  });

  /* Reveal on scroll. */
  if (!reduceMotion && 'IntersectionObserver' in window) {
    const targets = $$('h2, h3, p, .table-wrap, blockquote, .code-wrap, ul.links, .shot-grid')
      .filter((element) => {
        if (element.closest('.hero') || element.classList.contains('shots-note')) return false;
        return element.getBoundingClientRect().top > window.innerHeight * 0.92;
      });

    targets.forEach((element) => element.classList.add('reveal'));

    const revealObserver = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          entry.target.classList.add('is-in');
          revealObserver.unobserve(entry.target);
        });
      },
      { rootMargin: '0px 0px -6% 0px', threshold: 0.08 }
    );

    targets.forEach((element) => revealObserver.observe(element));
  }

  /* Scroll state: sticky bar, rail, progress and active section. */
  let frame = 0;
  const update = () => {
    frame = 0;
    const y = window.scrollY;
    const heroHeight = hero.offsetHeight;

    topbar.classList.toggle('is-visible', y > heroHeight * 0.55);
    rail.classList.toggle('is-visible', y > heroHeight * 0.7);
    if (topbar.classList.contains('is-open') && y > heroHeight * 0.55) closeMenu();

    const scrollable = document.documentElement.scrollHeight - window.innerHeight;
    progressBar.style.width = `${scrollable > 0 ? Math.min(100, (y / scrollable) * 100) : 0}%`;

    let current = headings.length ? headings[0].id : '';
    headings.forEach((heading) => {
      if (heading.getBoundingClientRect().top <= 140) current = heading.id;
    });
    if (current) setActiveSection(current);
  };

  const onScroll = () => {
    if (frame) return;
    frame = window.requestAnimationFrame(update);
  };

  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', onScroll);
  update();
})();
