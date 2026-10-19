/* 听风 WindTone · UI 效果图 —— 图标池（38 个 symbol）+ 侧边抽屉模板
   ============================================================================
   全站共用，插在每个分章页 <body> 的最前面。
   ⚠️ 新增 / 修改图标只改这个文件，别把 <symbol> 抄进分章文件。
   ⚠️ 不要在 sprite 里出现 </script> 字样（会提前闭合本脚本）。
   ⚠️ 「恢复默认」等弧线路径：flag 与相邻数字之间必须留空格（连写 `0 00` 会让浏览器以外的
     解析器少读参数、静默丢掉半条路径）。
   ⚠️ sprite 里不要写 HTML 注释（反引号会截断模板字符串），口径写在本头部注释里。 */
(function () {
  var SPRITE = `<svg style="display:none" xmlns="http://www.w3.org/2000/svg">
  <symbol id="i-play" viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></symbol>
  <symbol id="i-pause" viewBox="0 0 24 24"><path d="M6 5h4v14H6zM14 5h4v14h-4z"/></symbol>
  <symbol id="i-next" viewBox="0 0 24 24"><path d="M6 5l8.5 7L6 19zM16 5h2.2v14H16z"/></symbol>
  <symbol id="i-prev" viewBox="0 0 24 24"><path d="M18 5l-8.5 7L18 19zM5.8 5H8v14H5.8z"/></symbol>
  <symbol id="i-shuffle" viewBox="0 0 24 24"><path d="M10.6 9.2L5.4 4 4 5.4l5.2 5.2 1.4-1.4zM14.5 4l2 2L4 18.6 5.4 20 17.9 7.5 20 9.5V4h-5.5zm.3 9.4l-1.4 1.4 3.1 3.1L14.5 20H20v-5.5l-2 2-3.2-3.1z"/></symbol>
  <symbol id="i-repeat" viewBox="0 0 24 24"><path d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z"/></symbol>
  <symbol id="i-repeat-one" viewBox="0 0 24 24"><path d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zM13 15V9h-1.6l-1.9 1.2.6 1.1L11 10.7V15h2z"/></symbol>
  <symbol id="i-seq" viewBox="0 0 24 24"><path d="M4 11h12.2l-4.6-4.6L13 5l7 7-7 7-1.4-1.4 4.6-4.6H4v-2z"/></symbol>
  <symbol id="i-note" viewBox="0 0 24 24"><path d="M12 3v10.6A4 4 0 1014 17V7h4V3h-6z"/></symbol>
  <symbol id="i-search" viewBox="0 0 24 24"><path d="M15.5 14h-.8l-.3-.3a6.5 6.5 0 10-.7.7l.3.3v.8l5 5 1.5-1.5-5-5zm-6 0a4.5 4.5 0 110-9 4.5 4.5 0 010 9z"/></symbol>
  <symbol id="i-sort" viewBox="0 0 24 24"><path d="M3 18h6v-2H3v2zM3 6v2h18V6H3zm0 7h12v-2H3v2z"/></symbol>
  <symbol id="i-more" viewBox="0 0 24 24"><path d="M12 8a2 2 0 100-4 2 2 0 000 4zm0 2a2 2 0 100 4 2 2 0 000-4zm0 6a2 2 0 100 4 2 2 0 000-4z"/></symbol>
  <symbol id="i-close" viewBox="0 0 24 24"><path d="M19 6.4L17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z"/></symbol>
  <symbol id="i-back" viewBox="0 0 24 24"><path d="M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20v-2z"/></symbol>
  <symbol id="i-down" viewBox="0 0 24 24"><path d="M7.4 8.6L12 13.2l4.6-4.6L18 10l-6 6-6-6z"/></symbol>
  <symbol id="i-chevron" viewBox="0 0 24 24"><path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/></symbol>
  <symbol id="i-up" viewBox="0 0 24 24"><path d="M7.4 15.4L12 10.8l4.6 4.6L18 14l-6-6-6 6z"/></symbol>
  <symbol id="i-add" viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z"/></symbol>
  <symbol id="i-check" viewBox="0 0 24 24"><path d="M9 16.2L4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"/></symbol>
  <symbol id="i-folder" viewBox="0 0 24 24"><path d="M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 00-2-2h-8l-2-2z"/></symbol>
  <symbol id="i-album" viewBox="0 0 24 24"><path d="M12 2a10 10 0 100 20 10 10 0 000-20zm0 14.5a4.5 4.5 0 110-9 4.5 4.5 0 010 9zm0-6.5a2 2 0 100 4 2 2 0 000-4z"/></symbol>
  <symbol id="i-artist" viewBox="0 0 24 24"><path d="M12 12a4 4 0 100-8 4 4 0 000 8zm0 2c-3.3 0-8 1.7-8 5v3h16v-3c0-3.3-4.7-5-8-5z"/></symbol>
  <symbol id="i-library" viewBox="0 0 24 24"><path d="M20 2H8a2 2 0 00-2 2v12a2 2 0 002 2h12a2 2 0 002-2V4a2 2 0 00-2-2zm-6 11.5a2.5 2.5 0 110-5 2.5 2.5 0 010 5zM4 6H2v14a2 2 0 002 2h14v-2H4V6z"/></symbol>
  <symbol id="i-queue" viewBox="0 0 24 24"><path d="M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zm14-10v8.2a3 3 0 100 5.8 3 3 0 003-3V8h3V6h-6z"/></symbol>
  <symbol id="i-lyrics" viewBox="0 0 24 24"><path d="M4 4h16a2 2 0 012 2v9a2 2 0 01-2 2H8l-6 5V6a2 2 0 012-2zm3 4v2h10V8H7zm0 4v2h7v-2H7z"/></symbol>
  <symbol id="i-tune" viewBox="0 0 24 24"><path d="M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z"/></symbol>
  <symbol id="i-vol" viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.8-1-3.3-2.5-4v8c1.5-.7 2.5-2.2 2.5-4zM14 3.2v2.1c2.9.9 5 3.5 5 6.7s-2.1 5.9-5 6.7v2.1c4-1 7-4.5 7-8.8s-3-7.9-7-8.8z"/></symbol>
  <symbol id="i-bed" viewBox="0 0 24 24"><path d="M9.5 2A10 10 0 1021 13.6 8 8 0 019.5 2z"/></symbol>
  <symbol id="i-edit" viewBox="0 0 24 24"><path d="M3 17.3V21h3.8L17.8 9.9l-3.8-3.8L3 17.3zM20.7 7a1 1 0 000-1.4l-2.3-2.3a1 1 0 00-1.4 0l-1.8 1.8 3.7 3.8L20.7 7z"/></symbol>
  <symbol id="i-info" viewBox="0 0 24 24"><path d="M12 2a10 10 0 100 20 10 10 0 000-20zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></symbol>
  <symbol id="i-speed" viewBox="0 0 24 24"><path d="M20.4 8.6l-1.2 1.8a8 8 0 01-.2 7.6H5.1A8 8 0 0115.6 6.9l1.8-1.3A10 10 0 003.4 19a2 2 0 001.7 1h13.8a2 2 0 001.7-1 10 10 0 00-.2-10.4z"/></symbol>
  <symbol id="i-heart" viewBox="0 0 24 24"><path d="M12 21s-7.5-4.6-9.3-9A5.4 5.4 0 0112 6.3 5.4 5.4 0 0121.3 12c-1.8 4.4-9.3 9-9.3 9z"/></symbol>
  <symbol id="i-star" viewBox="0 0 24 24"><path d="M12 17.3l-6.2 3.7 1.6-7L2 9.2l7.1-.6L12 2l2.9 6.6 7.1.6-5.4 4.8 1.6 7z"/></symbol>
  <symbol id="i-cloud" viewBox="0 0 24 24"><path d="M19.4 10a7.5 7.5 0 0 0 -14.3 -1.6A5 5 0 0 0 6 18h13a4 4 0 0 0 .4 -8z"/></symbol>
  
  <symbol id="i-reset" viewBox="0 0 24 24"><path d="M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z"/></symbol>
  <symbol id="i-trash" viewBox="0 0 24 24"><path d="M6 19a2 2 0 002 2h8a2 2 0 002-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></symbol>
  <symbol id="i-menu" viewBox="0 0 24 24"><path d="M3 6h18v2H3V6zm0 5h18v2H3v-2zm0 5h18v2H3v-2z"/></symbol>
  <symbol id="i-palette" viewBox="0 0 24 24"><path d="M12 2a10 10 0 000 20c1.1 0 2-.9 2-2 0-.5-.2-1-.5-1.3-.3-.4-.5-.8-.5-1.3 0-1.1.9-2 2-2h2.4A4.6 4.6 0 0022 11.1C21.9 6.1 17.4 2 12 2zM6.5 12a1.5 1.5 0 110-3 1.5 1.5 0 010 3zm3-4a1.5 1.5 0 110-3 1.5 1.5 0 010 3zm5 0a1.5 1.5 0 110-3 1.5 1.5 0 010 3zm3 4a1.5 1.5 0 110-3 1.5 1.5 0 010 3z"/></symbol>
</svg>


<template id="tpl-drawer">
  <nav class="drawer">
    <div class="d-brand">
      <span class="d-halo"><svg class="ic"><use href="#i-note"/></svg></span>
      <span><span class="d-t">听风</span><span class="d-s">WindTone · 本地音乐</span></span>
    </div>
    <div class="d-label">音乐库</div>
    <div class="d-item"><svg class="ic ic-22"><use href="#i-note"/></svg>歌曲</div>
    <div class="d-item"><svg class="ic ic-22"><use href="#i-album"/></svg>专辑</div>
    <div class="d-item"><svg class="ic ic-22"><use href="#i-artist"/></svg>歌手</div>
    <div class="d-item"><svg class="ic ic-22"><use href="#i-folder"/></svg>文件夹</div>
    <div class="d-item"><svg class="ic ic-22"><use href="#i-library"/></svg>歌单</div>
    <div class="d-gap"></div>
    <div class="d-label">更多</div>
    <div class="d-item"><svg class="ic ic-22"><use href="#i-folder"/></svg>扫描范围</div>
    <div class="d-item"><svg class="ic ic-22"><use href="#i-tune"/></svg>设置</div>
  </nav>
</template>`;
  var cs = document.currentScript;
  if (cs) cs.insertAdjacentHTML('beforebegin', SPRITE);
  else document.body.insertAdjacentHTML('afterbegin', SPRITE);
})();
