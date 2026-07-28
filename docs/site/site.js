/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 *
 * Three behaviours the site cannot get from CSS alone: the off-canvas sidebar
 * on a phone, filtering the page list, and closing the contents panel when
 * there is no room for it beside the text. Nothing else — every page renders
 * and reads correctly with scripting disabled.
 */

(function () {
  'use strict';

  var body = document.body;
  var burger = document.getElementById('burger');
  var scrim = document.getElementById('scrim');
  var sidebar = document.getElementById('sidebar');

  function setNav(open) {
    body.classList.toggle('nav-open', open);
    if (burger) {
      burger.setAttribute('aria-expanded', open ? 'true' : 'false');
    }
    if (scrim) {
      scrim.hidden = !open;
    }
  }

  if (burger) {
    burger.addEventListener('click', function () {
      setNav(!body.classList.contains('nav-open'));
    });
  }
  if (scrim) {
    scrim.addEventListener('click', function () { setNav(false); });
  }
  if (sidebar) {
    sidebar.addEventListener('click', function (event) {
      if (event.target.tagName === 'A') { setNav(false); }
    });
  }
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') { setNav(false); }
  });

  // The contents panel is generated open so it still works without scripting.
  // Below the three-column breakpoint it sits above the prose, where an open
  // panel would push the article off the first screen.
  var toc = document.getElementById('toc');
  if (toc && !window.matchMedia('(min-width: 1320px)').matches) {
    toc.open = false;
  }

  var filter = document.getElementById('filter');
  var nav = document.getElementById('nav');
  if (!filter || !nav) {
    return;
  }

  var groups = nav.querySelectorAll('.grp');
  var wasOpen = [];
  var i;
  for (i = 0; i < groups.length; i++) {
    wasOpen.push(groups[i].open);
  }

  filter.addEventListener('input', function () {
    var query = filter.value.trim().toLowerCase();
    var group;
    var items;
    var hits;
    var hit;
    var g;
    var j;
    for (g = 0; g < groups.length; g++) {
      group = groups[g];
      items = group.querySelectorAll('li');
      hits = 0;
      for (j = 0; j < items.length; j++) {
        hit = query === '' || items[j].textContent.toLowerCase().indexOf(query) !== -1;
        items[j].hidden = !hit;
        if (hit) { hits++; }
      }
      group.hidden = query !== '' && hits === 0;
      group.open = query === '' ? wasOpen[g] : true;
    }
  });
}());
