(function () {
    'use strict';

    // ── 面板 HTML ────────────────────────────────────────────
    const panel = document.createElement('div');
    panel.id = 'cs-panel';
    panel.innerHTML = `
        <div id="cs-header">
            <span id="cs-drag-handle" title="拖动">⠿</span>
            <input id="cs-input" type="text" placeholder="搜索聊天记录…" autocomplete="off" />
            <div id="cs-btns">
                <span id="cs-count"></span>
                <button id="cs-prev" title="上一个">↑</button>
                <button id="cs-next" title="下一个">↓</button>
                <button id="cs-theme" title="切换黑白">◑</button>
                <button id="cs-close" title="关闭">✕</button>
            </div>
        </div>
        <div id="cs-results"></div>
        <div id="cs-resize-handle"></div>
    `;
    document.body.appendChild(panel);

    // ── 状态 ─────────────────────────────────────────────────
    let results = [];
    let currentIndex = -1;
    let isLight = false;

    // ── 工具 ─────────────────────────────────────────────────
    function escapeRegex(str) {
        return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    function clearHighlights() {
        document.querySelectorAll('.cs-mark').forEach(mark => {
            const p = mark.parentNode;
            if (p) { p.replaceChild(document.createTextNode(mark.textContent), mark); p.normalize(); }
        });
        document.querySelectorAll('.cs-current').forEach(el => el.classList.remove('cs-current'));
    }

    // ── 搜索 ──────────────────────────────────────────────────
    function search(keyword) {
        clearHighlights();
        results = [];
        currentIndex = -1;
        const resultsEl = document.getElementById('cs-results');
        const countEl  = document.getElementById('cs-count');

        if (!keyword.trim()) {
            resultsEl.innerHTML = '';
            countEl.textContent = '';
            return;
        }

        const kw    = keyword.toLowerCase();
        const regex = new RegExp(escapeRegex(keyword), 'gi');

        document.querySelectorAll('.mes').forEach(mes => {
            const mesTextEl = mes.querySelector('.mes_text');
            if (!mesTextEl) return;
            const rawText = mesTextEl.textContent || '';
            if (!rawText.toLowerCase().includes(kw)) return;

            const mesId = mes.getAttribute('mesid') ?? '?';
            const name  = mes.querySelector('.name_text')?.textContent.trim() ?? '未知';
            const idx   = rawText.toLowerCase().indexOf(kw);
            const start = Math.max(0, idx - 30);
            const end   = Math.min(rawText.length, idx + keyword.length + 60);
            const snippet = (start > 0 ? '…' : '') + rawText.slice(start, end) + (end < rawText.length ? '…' : '');

            results.push({ mesId, name, snippet, mesEl: mes, mesTextEl });
        });

        if (results.length === 0) {
            resultsEl.innerHTML = '<div class="cs-empty">没有找到结果</div>';
            countEl.textContent = '0 条';
            return;
        }

        countEl.textContent = `${results.length} 条`;
        resultsEl.innerHTML = results.map((r, i) => {
            const hl = r.snippet.replace(regex, m => `<mark class="cs-mark cs-mark-list">${m}</mark>`);
            return `<div class="cs-item" data-index="${i}">
                <div class="cs-meta"><span class="cs-floor">第 ${r.mesId} 楼</span><span class="cs-name">${r.name}</span></div>
                <div class="cs-snippet">${hl}</div>
            </div>`;
        }).join('');

        resultsEl.querySelectorAll('.cs-item').forEach(item => {
            item.addEventListener('click', () => jumpTo(parseInt(item.dataset.index)));
        });

        results.forEach(r => {
            r.mesTextEl.innerHTML = r.mesTextEl.innerHTML.replace(
                regex, m => `<mark class="cs-mark">${m}</mark>`
            );
        });
    }

    // ── 跳转 ──────────────────────────────────────────────────
    function jumpTo(idx) {
        if (idx < 0 || idx >= results.length) return;
        currentIndex = idx;
        document.querySelectorAll('.cs-item').forEach((el, i) =>
            el.classList.toggle('cs-item-active', i === idx));
        document.querySelector(`.cs-item[data-index="${idx}"]`)?.scrollIntoView({ block: 'nearest' });
        const target = results[idx].mesEl;
        target.scrollIntoView({ behavior: 'smooth', block: 'center' });
        target.classList.add('cs-current');
        setTimeout(() => target.classList.remove('cs-current'), 2000);
    }

    // ── 工具：统一获取指针坐标（mouse / touch）───────────────
    function getXY(e) {
        if (e.touches && e.touches.length > 0) {
            return { x: e.touches[0].clientX, y: e.touches[0].clientY };
        }
        return { x: e.clientX, y: e.clientY };
    }

    // ── 拖动（mouse + touch）─────────────────────────────────
    let dragging = false, dragOffX = 0, dragOffY = 0;

    function onDragStart(e) {
        dragging = true;
        const rect = panel.getBoundingClientRect();
        const { x, y } = getXY(e);
        dragOffX = x - rect.left;
        dragOffY = y - rect.top;
        e.preventDefault();
    }
    function onDragMove(e) {
        if (!dragging) return;
        const { x, y } = getXY(e);
        const maxX = window.innerWidth  - panel.offsetWidth;
        const maxY = window.innerHeight - panel.offsetHeight;
        panel.style.left  = Math.min(Math.max(0, x - dragOffX), maxX) + 'px';
        panel.style.top   = Math.min(Math.max(0, y - dragOffY), maxY) + 'px';
        panel.style.right = 'auto';
        e.preventDefault();
    }
    function onDragEnd() { dragging = false; }

    const dragHandle = document.getElementById('cs-drag-handle');
    dragHandle.addEventListener('mousedown',  onDragStart);
    dragHandle.addEventListener('touchstart', onDragStart, { passive: false });
    document.addEventListener('mousemove',  onDragMove);
    document.addEventListener('touchmove',  onDragMove, { passive: false });
    document.addEventListener('mouseup',    onDragEnd);
    document.addEventListener('touchend',   onDragEnd);

    // ── 缩放（mouse + touch）─────────────────────────────────
    let resizing = false, resStartW, resStartH, resStartX, resStartY;

    function onResizeStart(e) {
        resizing = true;
        resStartW = panel.offsetWidth;
        resStartH = panel.offsetHeight;
        const { x, y } = getXY(e);
        resStartX = x;
        resStartY = y;
        e.preventDefault();
    }
    function onResizeMove(e) {
        if (!resizing) return;
        const { x, y } = getXY(e);
        const maxW = window.innerWidth  - panel.getBoundingClientRect().left - 8;
        const maxH = window.innerHeight - panel.getBoundingClientRect().top  - 8;
        const w = Math.min(maxW, Math.max(260, resStartW + (x - resStartX)));
        const h = Math.min(maxH, Math.max(180, resStartH + (y - resStartY)));
        panel.style.width     = w + 'px';
        panel.style.maxHeight = h + 'px';
        e.preventDefault();
    }
    function onResizeEnd() { resizing = false; }

    const resizeHandle = document.getElementById('cs-resize-handle');
    resizeHandle.addEventListener('mousedown',  onResizeStart);
    resizeHandle.addEventListener('touchstart', onResizeStart, { passive: false });
    document.addEventListener('mousemove',  onResizeMove);
    document.addEventListener('touchmove',  onResizeMove, { passive: false });
    document.addEventListener('mouseup',    onResizeEnd);
    document.addEventListener('touchend',   onResizeEnd);

    // ── 主题切换 ──────────────────────────────────────────────
    document.getElementById('cs-theme').addEventListener('click', () => {
        isLight = !isLight;
        panel.classList.toggle('cs-light', isLight);
    });

    // ── 事件：搜索输入 ────────────────────────────────────────
    let debounce;
    document.getElementById('cs-input').addEventListener('input', e => {
        clearTimeout(debounce);
        debounce = setTimeout(() => search(e.target.value), 280);
    });

    document.getElementById('cs-prev').addEventListener('click', () => {
        if (results.length) jumpTo((currentIndex - 1 + results.length) % results.length);
    });
    document.getElementById('cs-next').addEventListener('click', () => {
        if (results.length) jumpTo((currentIndex + 1) % results.length);
    });
    document.getElementById('cs-close').addEventListener('click', hide);

    // ── 键盘快捷键 ────────────────────────────────────────────
    document.addEventListener('keydown', e => {
        if (e.ctrlKey && e.shiftKey && e.key === 'F') {
            e.preventDefault();
            panel.style.display === 'none' ? show() : hide();
            return;
        }
        if (e.key === 'Escape' && panel.style.display !== 'none') { hide(); }
        if (panel.style.display !== 'none' && e.key === 'Enter' &&
            document.activeElement === document.getElementById('cs-input')) {
            e.preventDefault();
            jumpTo(e.shiftKey
                ? (currentIndex - 1 + results.length) % results.length
                : (currentIndex + 1) % results.length);
        }
    });

    // ── 注入菜单入口 ──────────────────────────────────────────
    function injectMenuItem() {
        // ST 的扩展菜单容器，尝试多个选择器
        const menu = document.querySelector('#extensionsMenu')
                  || document.querySelector('.extensions_block ul')
                  || document.querySelector('#send_extra_dropdown');
        if (!menu) return;

        if (document.getElementById('cs-menu-item')) return; // 防止重复注入

        const li = document.createElement('li');
        li.id = 'cs-menu-item';
        li.style.cssText = 'cursor:pointer; display:flex; align-items:center; gap:8px;';
        li.innerHTML = `<i class="fa-solid fa-magnifying-glass"></i> 搜索聊天记录`;
        li.addEventListener('click', () => {
            // 关闭菜单
            menu.closest('.popup, .dropdown, [class*="menu"]')?.classList.remove('show', 'visible');
            show();
        });
        menu.appendChild(li);
    }

    // 等 ST 渲染好再注入
    function tryInject() {
        injectMenuItem();
        if (!document.getElementById('cs-menu-item')) {
            setTimeout(tryInject, 800);
        }
    }
    setTimeout(tryInject, 1200);

    // ── 显示/隐藏 ─────────────────────────────────────────────
    function show() {
        panel.style.display = 'flex';
        const input = document.getElementById('cs-input');
        input.focus();
        input.select();
    }

    function hide() {
        panel.style.display = 'none';
        clearHighlights();
        results = [];
        currentIndex = -1;
        document.getElementById('cs-results').innerHTML = '';
        document.getElementById('cs-count').textContent = '';
    }

    panel.style.display = 'none';
})();
