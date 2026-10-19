/* 听风 WindTone · UI 效果图 —— 分章页面共用脚本
   ============================================================================
   这里集中了全部行为：主题切换 / 播放模式轮换 / 抽屉开合 / 屏数统计 / 全站屏索引 / 回到顶部。
   ⚠️ 分章文件里不要写内联脚本；改行为只改这一个文件。
   ⚠️ 屏数与索引一律从 DOM 现算：新增屏只写 <figure class="fig">，不改任何数字。 */
(function () {
  var html = document.documentElement;
  var btn = document.getElementById('themeBtn');

  function isDark() { return html.getAttribute('data-theme') === 'dark'; }
  /* 按钮文案写「当前主题」，且提示可回到跟随系统 */
  function syncBtn() {
    if (!btn) return;
    var saved = null;
    try { saved = localStorage.getItem('wt-ui-theme'); } catch (e) {}
    var suffix = saved ? '' : '（跟随系统）';
    btn.textContent = (isDark() ? '🌙 深色主题' : '☀️ 浅色主题') + suffix;
  }
  syncBtn();

  if (btn) {
    btn.addEventListener('click', function () {
      var next = isDark() ? 'light' : 'dark';
      html.setAttribute('data-theme', next);
      try { localStorage.setItem('wt-ui-theme', next); } catch (e) {}
      syncBtn();
    });
    /* 双击按钮 = 清除手动选择，回到「跟随系统」 */
    btn.addEventListener('dblclick', function () {
      try { localStorage.removeItem('wt-ui-theme'); } catch (e) {}
      var mq = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)');
      html.setAttribute('data-theme', mq && mq.matches ? 'light' : 'dark');
      syncBtn();
    });
  }

  /* 未手动选择时，系统主题变化实时跟随（与 App 行为一致） */
  if (window.matchMedia) {
    var mq = window.matchMedia('(prefers-color-scheme: light)');
    var onSys = function (e) {
      var saved = null;
      try { saved = localStorage.getItem('wt-ui-theme'); } catch (err) {}
      if (saved) return; /* 用户手动选过就不跟随 */
      html.setAttribute('data-theme', e.matches ? 'light' : 'dark');
      syncBtn();
    };
    if (mq.addEventListener) mq.addEventListener('change', onSys);
    else if (mq.addListener) mq.addListener(onSys);
  }

  /* 播放模式按钮：点击在四档之间轮换（与 App 内 behavior 一致） */
  var modes = [
    { ic: '#i-seq', label: '顺序播放', cls: 'mode-off' },
    { ic: '#i-repeat', label: '列表循环', cls: 'mode-on' },
    { ic: '#i-repeat-one', label: '单曲循环', cls: 'mode-on' },
    { ic: '#i-shuffle', label: '随机播放', cls: 'mode-on' }
  ];
  document.querySelectorAll('[data-playmode]').forEach(function (b) {
    var i = 1;
    b.addEventListener('click', function () {
      i = (i + 1) % 4; var m = modes[i];
      b.querySelector('use').setAttribute('href', m.ic);
      b.className = 'ibtn ' + m.cls;
      b.setAttribute('title', '播放模式：' + m.label);
      var hint = b.closest('.player').querySelector('[data-modehint]');
      if (hint) {
        hint.textContent = m.label; hint.style.display = 'block';
        clearTimeout(hint._t); hint._t = setTimeout(function () { hint.style.display = 'none'; }, 1400);
      }
    });
  });

  /* 侧边抽屉：给每个带汉堡按钮的手机壳克隆模板并支持开合
     （①C 屏是静态展开态、自带抽屉与遮罩，不注入） */
  var drawerTpl = document.getElementById('tpl-drawer');
  document.querySelectorAll('.app').forEach(function (app) {
    var b = app.querySelector('[data-menu]');
    if (!b || app.querySelector('.drawer') || !drawerTpl) return;
    var dim = document.createElement('div');
    dim.className = 'dim d-dim'; dim.style.display = 'none';
    app.appendChild(dim);
    app.appendChild(drawerTpl.content.firstElementChild.cloneNode(true));
  });
  /* 点汉堡 = 开/收；点抽屉遮罩 = 收 */
  document.addEventListener('click', function (ev) {
    var b2 = ev.target.closest ? ev.target.closest('[data-menu]') : null;
    if (b2) {
      var app = b2.closest('.app');
      if (!app) return;
      var open = app.classList.toggle('drawer-open');
      var dim = app.querySelector(':scope > .d-dim');
      if (dim) dim.style.display = open ? 'block' : 'none';
      return;
    }
    var d = ev.target.closest ? ev.target.closest('.d-dim') : null;
    if (d) {
      var app2 = d.closest('.app');
      if (app2) {
        app2.classList.remove('drawer-open');
        d.style.display = 'none';
      }
    }
  });

  /* ---------------------------------------------------------- 本页屏数 */
  /* 章节屏数徽标 + 页头屏数，都从 DOM 现算，新增屏不用手改任何地方。 */
  var secs = [].slice.call(document.querySelectorAll('section.sec'));
  var total = 0;
  secs.forEach(function (sec) {
    var figs = [].slice.call(sec.querySelectorAll('figure.fig'));
    total += figs.length;
    var hd = sec.querySelector('.sec-hd');
    if (hd && figs.length) {
      var badge = document.createElement('span');
      badge.className = 'sec-count';
      badge.textContent = figs.length + ' 屏';
      hd.appendChild(badge);
    }
  });
  ['pageCount', 'screenCount'].forEach(function (id) {
    var el = document.getElementById(id);
    if (!el) return;
    /* 总览页的 screenCount 由下面的跨章扫描接管，这里只填分章页 */
    if (id === 'screenCount' && document.getElementById('idxGrid')) return;
    el.textContent = total;
  });

  /* ---------------------------------------------------------- 总览页：跨章屏数 + 全屏索引 */
  var grid = document.getElementById('idxGrid');
  var chapters = [].slice.call(document.querySelectorAll('[data-chapter]'));
  if (grid && chapters.length) {
    grid.innerHTML = '';
    var sum = 0, ok = 0, bad = 0;
    chapters.forEach(function (card) {
      var file = card.getAttribute('data-chapter');
      fetch(file).then(function (r) {
        if (!r.ok) throw new Error(r.status);
        return r.text();
      }).then(function (txt) {
        var doc = new DOMParser().parseFromString(txt, 'text/html');
        var figs = [].slice.call(doc.querySelectorAll('figure.fig'));
        ok++; sum += figs.length;
        var nEl = card.querySelector('.hub-n');
        if (nEl) nEl.textContent = figs.length ? (figs.length + ' 屏') : '规范';
        var sc = document.getElementById('screenCount');
        if (sc) sc.textContent = sum;

        var col = document.createElement('div');
        col.className = 'idx-col';
        var head = document.createElement('b');
        var h2 = doc.querySelector('.sec-hd h2');
        head.textContent = (h2 ? h2.textContent : file) + '（' + figs.length + ' 屏）';
        col.appendChild(head);
        figs.forEach(function (f) {
          var cap = f.querySelector('.cap b');
          var a = document.createElement('a');
          a.href = file + '#' + f.id;
          a.textContent = cap ? cap.textContent : f.id;
          col.appendChild(a);
        });
        grid.appendChild(col);
      }).catch(function () {
        bad++;
        var nEl = card.querySelector('.hub-n');
        if (nEl) nEl.textContent = '—';
        if (bad === chapters.length) {
          grid.innerHTML = '<div class="idx-hint">屏索引需要 HTTP 打开本页才能自动生成（GitHub Pages 或本地预览服务）。' +
            '当前是 file:// 直开，直接点上面的章节卡即可。</div>';
        }
      });
    });
  }

  /* ---------------------------------------------- 旧链接兜底：index.html#scr-xxx → 对应分章 */
  /* 分章之前的效果图锚点（外部文档 / 书签里可能还留着）。仅在总览页、且带 hash 时生效。 */
  if (location.hash && grid && chapters.length && location.hash.length > 1) {
    var want = location.hash.slice(1);
    var tryOne = function (i) {
      if (i >= chapters.length) return;
      var file = chapters[i].getAttribute('data-chapter');
      fetch(file).then(function (r) { return r.text(); }).then(function (txt) {
        if (txt.indexOf('id="' + want + '"') >= 0) location.replace(file + '#' + want);
        else tryOne(i + 1);
      }).catch(function () {});
    };
    tryOne(0);
  }

  /* ---------------------------------------------------------- 返回顶部 */
  var top = document.createElement('button');
  top.className = 'totop';
  top.type = 'button';
  top.title = '回到顶部';
  top.textContent = '↑';
  top.addEventListener('click', function () { window.scrollTo({ top: 0, behavior: 'smooth' }); });
  document.body.appendChild(top);
  var onScroll = function () { top.classList.toggle('on', window.scrollY > 600); };
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
})();
