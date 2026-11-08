(function () {
  const app = document.getElementById('app');
  const toastEl = document.getElementById('toast');
  const TOKEN_KEY = 'drivelog.token';
  let state = null;
  let token = null;
  try { token = localStorage.getItem(TOKEN_KEY); } catch (_) { /* private mode: linked for this tab only */ }
  const photoCache = new Map();

  // --- helpers ---------------------------------------------------------------------------------
  function el(tag, attrs, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs || {})) {
      if (key === 'onclick' || key === 'oninput' || key === 'onchange') node[key] = value;
      else if (key === 'class') node.className = value;
      else if (value === true) node.setAttribute(key, '');
      else if (value !== false && value != null) node.setAttribute(key, value);
    }
    for (const child of children.flat()) if (child != null) node.append(child);
    return node;
  }

  function toast(message) {
    toastEl.textContent = message; toastEl.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => { toastEl.hidden = true; }, 2600);
  }

  async function api(path, options) {
    const opts = options || {};
    const headers = Object.assign({}, opts.headers);
    if (token) headers.Authorization = 'Bearer ' + token;
    let body = opts.body;
    if (opts.json !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(opts.json); }
    const response = await fetch('/api/v1' + path, { method: opts.method || 'GET', headers, body });
    if (response.status === 401 && token) {
      token = null;
      try { localStorage.removeItem(TOKEN_KEY); } catch (_) {}
      state = null;
      render();
      throw new Error('연결이 해제되었습니다.');
    }
    if (!response.ok) {
      let message = '요청을 처리하지 못했습니다.';
      try { const err = await response.json(); if (err.detail && /[가-힣]/.test(err.detail)) message = err.detail; } catch (_) {}
      const error = new Error(message); error.status = response.status; throw error;
    }
    if (response.status === 204) return null;
    const type = response.headers.get('Content-Type') || '';
    return type.includes('json') ? response.json() : response.blob();
  }

  async function act(fn) {
    try { return await fn(); } catch (error) { toast(error.message || '네트워크 연결을 확인해 주세요.'); return undefined; }
  }

  async function refresh() { state = await api('/journey'); photoCache.clear(); }

  function go(hash) { if (location.hash === hash) render(); else location.hash = hash; }
  function bar(title, back, extra) {
    return el('div', { class: 'bar' },
      back ? el('button', { class: 'icon-btn', 'aria-label': '뒤로', onclick: () => go(back) }, '‹') : null,
      el('div', { class: 'title' }, title), extra || null);
  }

  function recordOf(id) { return state.records.find(record => record.id === id); }
  function recordFor(place) { return place.visitRecordId ? recordOf(place.visitRecordId) : null; }
  function visited(goal) { return goal.places.filter(place => place.visitRecordId).length; }
  function statusTags(record) {
    return [record.status === 'PARTIAL' ? el('span', { class: 'tag' }, '불완전한 기록') : null,
      record.simulated ? el('span', { class: 'tag' }, '시뮬레이션') : null];
  }

  async function photoFor(record) {
    if (!record || !record.hasPhoto) return null;
    if (photoCache.has(record.id)) return photoCache.get(record.id);
    const blob = await api(`/records/${record.id}/photo`);
    const bitmap = await createImageBitmap(blob);
    photoCache.set(record.id, bitmap);
    return bitmap;
  }

  /** Re-encoding through canvas drops EXIF, including GPS, before anything leaves the phone. */
  async function prepareUpload(file) {
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, 2400 / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(bitmap.width * scale); canvas.height = Math.round(bitmap.height * scale);
    canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('사진을 읽을 수 없습니다.')), 'image/jpeg', 0.9));
  }

  function canvasBlob(canvas) {
    return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('이미지를 만들지 못했습니다.')), 'image/png'));
  }

  function download(blob, name) {
    const url = URL.createObjectURL(blob);
    const anchor = el('a', { href: url, download: name });
    document.body.append(anchor); anchor.click(); anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  }

  // --- screens ---------------------------------------------------------------------------------
  function linkScreen() {
    const input = el('input', { class: 'code-input', inputmode: 'numeric', maxlength: 6, autocomplete: 'one-time-code', 'aria-label': '연결 코드 6자리' });
    const submit = el('button', { class: 'btn', onclick: () => act(async () => {
      if (!/^\d{6}$/.test(input.value)) { toast('6자리 숫자를 입력해 주세요.'); return; }
      submit.disabled = true;
      try {
        const result = await api('/link/claim', { method: 'POST', json: { code: input.value } });
        token = result.accessToken;
        try { localStorage.setItem(TOKEN_KEY, token); } catch (_) {}
        await refresh();
        go('#/');
      } catch (error) {
        submit.disabled = false;
        throw error.status === 404 ? new Error('코드가 올바르지 않거나 만료되었습니다.') : error;
      }
    }) }, '차량과 연결');
    return [el('h1', {}, '차량과 연결'), el('p', { class: 'muted' }, '차량 화면의 DRIVELOG 앱에 표시된 연결 코드를 입력하세요.'),
      el('div', { style: 'height:20px' }), input, el('div', { style: 'height:12px' }), submit,
      el('p', { class: 'notice' }, '차량 기록의 경로와 사진은 기본적으로 비공개로 저장되며, 연결된 기기에서만 볼 수 있습니다. 위치·주행 정보는 카드 제작과 방문 진행률 표시에 사용하고, 기록과 사진은 언제든 삭제할 수 있습니다.')];
  }

  function progress(goal) {
    const nodes = [];
    goal.places.forEach((place, i) => {
      if (i) nodes.push(el('b'));
      nodes.push(el('i', { class: place.visitRecordId ? 'on' : '' }));
    });
    return el('div', { class: 'progress', 'aria-hidden': 'true' }, nodes);
  }

  function homeScreen() {
    const goal = state.activeGoal;
    const archiveLink = el('button', { class: 'link-btn', onclick: () => go('#/archive') }, '보관함');
    if (!goal) {
      return [bar('DRIVELOG', null, archiveLink), el('h1', {}, '차량에서 목표를 만들어 주세요'),
        el('p', { class: 'muted' }, '정차 중에 내비의 즐겨찾기와 최근 목적지에서 세 곳을 고르면 이곳에 나타나요.')];
    }
    const count = visited(goal), done = goal.status === 'ACHIEVED';
    const next = goal.places.find(place => !place.visitRecordId);
    const orphans = state.records.filter(record => record.goalId === goal.id && !goal.places.some(place => place.visitRecordId === record.id));
    return [bar('DRIVELOG', null, archiveLink), el('h1', {}, goal.name),
      el('p', { class: 'muted' }, done ? '모든 장소를 만났어요' : `다음 기억은 ${DriveCard.shortPlace(next.name)}에서`),
      el('p', { style: 'margin-top:12px' }, `${count} / ${goal.places.length}곳 방문`), progress(goal),
      el('div', { style: 'height:12px' }),
      goal.places.map(place => {
        const record = recordFor(place);
        return el(record ? 'button' : 'div', { class: 'place', onclick: record ? () => go(`#/record/${record.id}`) : null },
          el('span', { class: 'check' + (record ? ' on' : '') }, record ? '✓' : ''),
          el('span', { class: 'body' }, el('div', { class: 'name' }, place.name),
            el('div', { class: 'meta' }, record ? `방문 완료 · ${DriveCard.date(record.startedAtMillis)}` : '방문 전')), record ? '›' : null);
      }),
      done ? el('div', { class: 'stack', style: 'margin-top:16px' },
        el('button', { class: 'btn', onclick: () => go('#/goal/card') }, '완성 카드 만들기')) : null,
      orphans.length ? [el('h2', {}, '방문 확인 전 기록'), orphans.map(record => recordRow(record))] : null,
      el('div', { style: 'height:16px' }),
      el('button', { class: 'btn outline', onclick: () => go('#/goal/edit') }, '목표 수정')];
  }

  function recordRow(record) {
    return el('button', { class: 'place', onclick: () => go(`#/record/${record.id}`) },
      el('span', { class: 'body' }, el('div', { class: 'name' }, record.placeName),
        el('div', { class: 'meta' }, `${DriveCard.date(record.startedAtMillis)} · ${record.distanceKm == null ? '거리 없음' : record.distanceKm.toFixed(1) + ' km'} · ${DriveCard.minutes(record)}`),
        el('div', {}, statusTags(record))), '›');
  }

  function editGoalScreen() {
    const goal = state.activeGoal;
    if (!goal) { go('#/'); return []; }
    const name = el('input', { type: 'text', maxlength: 60, value: goal.name, 'aria-label': '목표 이름' });
    return [bar('목표 수정', '#/'), el('label', { class: 'field' }, '목표 이름', name),
      el('button', { class: 'btn outline', style: 'margin-top:8px', onclick: () => act(async () => {
        await api(`/goals/${goal.id}`, { method: 'PATCH', json: { name: name.value } }); await refresh(); toast('저장했습니다.');
      }) }, '이름 저장'),
      el('p', { class: 'notice' }, '장소는 차량 화면에서 내비의 즐겨찾기와 최근 목적지로 변경할 수 있어요.'),
      el('h2', {}, '목표 삭제'),
      el('p', { class: 'notice' }, '목표를 삭제하면 이 목표에 연결된 주행 기록과 올려둔 사진이 함께 삭제되며 복구할 수 없습니다. 이미 저장하거나 공유한 카드 이미지는 삭제되지 않습니다.'),
      el('button', { class: 'btn danger', onclick: () => act(async () => {
        if (!confirm('목표와 연결된 기록·사진을 모두 삭제할까요?')) return;
        await api(`/goals/${goal.id}`, { method: 'DELETE' }); await refresh(); go('#/');
      }) }, '목표와 기록 삭제')];
  }

  function drawRoutePreview(canvas, record) {
    const rect = canvas.getBoundingClientRect(), ratio = window.devicePixelRatio || 1;
    canvas.width = rect.width * ratio; canvas.height = rect.height * ratio;
    const ctx = canvas.getContext('2d');
    const color = getComputedStyle(document.documentElement).getPropertyValue('--fg').trim() || '#000';
    DriveCard.drawRoute(ctx, record.route, { x: 24 * ratio, y: 24 * ratio, w: canvas.width - 48 * ratio, h: canvas.height - 48 * ratio }, color, 3 * ratio, false);
  }

  function recordScreen(id) {
    const record = recordOf(id);
    if (!record) return [bar('기록', '#/'), el('div', { class: 'empty' }, '기록을 찾을 수 없습니다.')];
    const goal = state.activeGoal && state.activeGoal.id === record.goalId ? state.activeGoal : null;
    const place = goal && goal.places.find(item => item.id === record.placeId);
    const isVisit = place && place.visitRecordId === record.id;
    const photoSlot = el('div', {}, el('div', { class: 'empty' }, '아직 사진이 없어요'));
    const file = el('input', { type: 'file', accept: 'image/*', hidden: true, onchange: e => act(async () => {
      if (!e.target.files[0]) return;
      const blob = await prepareUpload(e.target.files[0]);
      try { await api(`/records/${id}/photo`, { method: 'PUT', headers: { 'Content-Type': 'image/jpeg' }, body: blob }); }
      catch (error) { throw new Error('사진 업로드에 실패했어요. 주행 기록은 그대로 보관되어 있습니다. 다시 시도해 주세요.'); }
      await refresh(); render();
    }) });
    if (record.hasPhoto) act(async () => {
      const blob = await api(`/records/${id}/photo`);
      photoSlot.replaceChildren(el('img', { class: 'photo-box', alt: `${record.placeName} 사진`, src: URL.createObjectURL(blob) }));
    });
    const preview = el('canvas', { class: 'route-box', role: 'img', 'aria-label': '주행 경로' });
    setTimeout(() => drawRoutePreview(preview, record));
    return [bar('기록 상세', '#/'), el('h1', {}, record.placeName), el('p', { class: 'muted' }, DriveCard.date(record.startedAtMillis)), el('div', {}, statusTags(record)),
      el('div', { class: 'stats' },
        el('div', {}, el('b', {}, record.distanceKm == null ? '—' : `${record.distanceKm.toFixed(1)} km`), el('span', {}, DriveCard.distanceLabel(record))),
        el('div', {}, el('b', {}, DriveCard.minutes(record)), el('span', {}, '기록 시간')),
        el('div', {}, el('b', {}, isVisit ? '확인' : '미확인'), el('span', {}, '방문'))),
      record.status === 'PARTIAL' ? el('p', { class: 'notice' }, '앱이 중단되기 전까지 저장된 구간이에요. 위치가 없는 구간은 점선으로 표시하며 임의로 채우지 않습니다.') : null,
      preview, el('h2', {}, '사진'), photoSlot, file,
      el('div', { class: 'stack', style: 'margin-top:10px' },
        el('button', { class: 'btn' + (record.hasPhoto ? ' outline' : ''), onclick: () => file.click() }, record.hasPhoto ? '사진 변경' : '사진 선택'),
        record.hasPhoto ? el('button', { class: 'btn', onclick: () => go(`#/record/${id}/card`) }, '카드 만들기') : null,
        place ? (isVisit
          ? el('button', { class: 'btn outline', onclick: () => act(async () => {
            if (!confirm('이 방문 확인을 취소할까요? 진행률이 함께 줄어듭니다.')) return;
            await api(`/records/${id}/visit`, { method: 'DELETE' }); await refresh(); render(); }) }, '방문 확인 취소')
          : (place.visitRecordId ? null : el('button', { class: 'btn outline', onclick: () => act(async () => {
            await api(`/records/${id}/visit`, { method: 'PUT' }); await refresh(); render(); }) }, '방문 확인'))) : null,
        el('button', { class: 'btn danger', onclick: () => act(async () => {
          if (!confirm('이 기록과 사진을 삭제할까요? 복구할 수 없습니다.')) return;
          await api(`/records/${id}`, { method: 'DELETE' }); await refresh(); go('#/'); }) }, '기록 삭제'))];
  }

  function defaultCardOptions(record) {
    const saved = (() => { try { return JSON.parse(record.cardJson || '{}'); } catch (_) { return {}; } })();
    return Object.assign({ template: 'PHOTO', tone: 'color', showRoute: true, showDistance: true, showTime: true, showDate: true, showPlace: true, trimEnds: true, crop: { zoom: 1, x: 0, y: 0 } }, saved,
      { title: record.title || `${DriveCard.shortPlace(record.placeName)}에 남긴 하루` });
  }

  function cardScreen(id, previewOnly) {
    const record = recordOf(id);
    if (!record) return [bar('카드', '#/')];
    const options = cardScreen.options && cardScreen.options.id === id ? cardScreen.options.value : defaultCardOptions(record);
    cardScreen.options = { id, value: options };
    const canvas = el('canvas', { role: 'img', 'aria-label': '카드 미리보기' });
    const wrap = el('div', { class: 'canvas-wrap' }, canvas);
    let photo = null;
    const paint = () => DriveCard.renderRecord(canvas, record, photo, options);
    act(async () => { photo = await photoFor(record); paint(); });
    setTimeout(paint);
    if (previewOnly) {
      const save = async share => act(async () => {
        const blob = await canvasBlob(canvas);
        const file = new File([blob], `yeojeong-${DriveCard.date(record.startedAtMillis).replaceAll('.', '')}.png`, { type: 'image/png' });
        if (share && navigator.canShare && navigator.canShare({ files: [file] })) {
          try { await navigator.share({ files: [file], title: options.title }); }
          catch (error) { if (error.name !== 'AbortError') throw error; }
        } else { download(blob, file.name); toast('이미지를 저장했습니다.'); }
      });
      const canShare = !!(navigator.canShare && window.isSecureContext);
      return [bar('공유 미리보기', `#/record/${id}/card`), wrap, el('div', { class: 'row', style: 'margin-top:12px' },
        el('button', { class: 'btn outline', onclick: () => save(false) }, '이미지 저장'),
        canShare ? el('button', { class: 'btn', onclick: () => save(true) }, '공유') : null),
        el('p', { class: 'small muted', style: 'margin-top:8px' }, canShare ? '공유 전 내용을 확인하세요. SNS에 자동으로 게시되지 않습니다.' : '이 브라우저에서는 시스템 공유를 사용할 수 없어 이미지 저장을 제공합니다. SNS에 자동으로 게시되지 않습니다.')];
    }
    // Drag to pan inside the cover-fit frame.
    let dragging = null;
    wrap.onpointerdown = e => { dragging = { x: e.clientX, y: e.clientY, cx: options.crop.x, cy: options.crop.y }; wrap.setPointerCapture(e.pointerId); };
    wrap.onpointermove = e => { if (!dragging) return;
      const k = 2 / wrap.clientWidth;
      options.crop.x = Math.max(-1, Math.min(1, dragging.cx + (e.clientX - dragging.x) * k));
      options.crop.y = Math.max(-1, Math.min(1, dragging.cy + (e.clientY - dragging.y) * k));
      paint(); };
    wrap.onpointerup = wrap.onpointercancel = () => { dragging = null; };
    const seg = (items, key) => el('div', { class: 'seg' }, items.map(([value, label]) => el('button', { 'aria-pressed': String(options[key] === value), onclick: e => {
      options[key] = value;
      [...e.target.parentNode.children].forEach(child => child.setAttribute('aria-pressed', String(child === e.target)));
      paint(); } }, label)));
    const toggle = (key, label) => el('label', { class: 'switch' }, label, el('input', { type: 'checkbox', checked: options[key], onchange: e => { options[key] = e.target.checked; paint(); } }));
    return [bar('카드 편집', `#/record/${id}`), wrap,
      el('p', { class: 'small muted', style: 'margin-top:6px' }, '사진을 끌어 위치를 맞추세요.'),
      el('input', { type: 'range', min: 1, max: 3, step: 0.01, value: options.crop.zoom, 'aria-label': '확대', oninput: e => { options.crop.zoom = Number(e.target.value); paint(); } }),
      el('h2', {}, '템플릿'), seg([['PHOTO', '포토'], ['JOURNAL', '저널']], 'template'),
      seg([['color', '컬러'], ['mono', '흑백'], ['mono-dark', '흑백 어둡게']], 'tone'),
      el('label', { class: 'field' }, '제목', el('input', { type: 'text', maxlength: 40, value: options.title, oninput: e => { options.title = e.target.value; paint(); } })),
      el('h2', {}, '표시할 정보'), toggle('showRoute', '경로'), toggle('trimEnds', '경로 양 끝 구간 숨기기'), toggle('showDistance', '거리'), toggle('showTime', '기록 시간'), toggle('showDate', '날짜'), toggle('showPlace', '장소 이름'),
      el('p', { class: 'small muted' }, '카드에는 정확한 주소와 사진 위치 정보가 포함되지 않습니다.'),
      el('button', { class: 'btn', style: 'margin-top:12px', onclick: () => act(async () => {
        await api(`/records/${id}/card`, { method: 'PUT', json: { title: options.title, cardJson: JSON.stringify(Object.assign({}, options, { title: undefined })) } });
        await refresh(); go(`#/record/${id}/preview`);
      }) }, '미리보기')];
  }

  function goalCardScreen(goalId) {
    const goal = goalId ? state.archivedGoals.find(item => item.id === goalId) : state.activeGoal;
    if (!goal) return [bar('완성 카드', '#/')];
    const canvas = el('canvas', { role: 'img', 'aria-label': '완성 카드 미리보기' });
    const records = {}, photos = {};
    goal.places.forEach(place => { const record = recordFor(place); if (record) records[place.id] = record; });
    const paint = () => DriveCard.renderGoal(canvas, goal, records, photos);
    setTimeout(paint);
    act(async () => {
      for (const place of goal.places) if (records[place.id]) photos[place.id] = await photoFor(records[place.id]);
      paint();
    });
    const missing = goal.places.filter(place => !(records[place.id] && records[place.id].hasPhoto));
    const save = share => act(async () => {
      const blob = await canvasBlob(canvas);
      const file = new File([blob], 'yeojeong-complete.png', { type: 'image/png' });
      if (share && navigator.canShare && navigator.canShare({ files: [file] })) {
        try { await navigator.share({ files: [file], title: goal.name }); }
        catch (error) { if (error.name !== 'AbortError') throw error; }
      } else { download(blob, file.name); toast('이미지를 저장했습니다.'); }
    });
    const canShare = !!(navigator.canShare && window.isSecureContext);
    return [bar('완성 카드', goalId ? '#/archive' : '#/'), el('div', { class: 'canvas-wrap' }, canvas),
      missing.length ? el('p', { class: 'notice' }, `${missing.map(place => DriveCard.shortPlace(place.name)).join(', ')}의 사진을 추가하면 카드가 완성돼요.`) : null,
      missing.filter(place => records[place.id]).map(place => el('button', { class: 'btn outline', style: 'margin-top:8px', onclick: () => go(`#/record/${records[place.id].id}`) }, `${DriveCard.shortPlace(place.name)} 사진 추가`)),
      el('div', { class: 'row', style: 'margin-top:12px' }, el('button', { class: 'btn outline', onclick: () => save(false) }, '이미지 저장'), canShare ? el('button', { class: 'btn', onclick: () => save(true) }, '공유') : null),
      !goalId ? el('button', { class: 'btn outline', style: 'margin-top:8px', onclick: () => act(async () => {
        await api(`/goals/${goal.id}/archive`, { method: 'POST' }); await refresh(); go('#/');
      }) }, '보관하고 새 목표 시작') : null];
  }

  function archiveScreen() {
    return [bar('보관함', '#/'),
      el('h2', {}, '완성한 목표'), state.archivedGoals.length ? state.archivedGoals.map(goal => el('button', { class: 'place', onclick: () => go(`#/archive/${goal.id}`) },
        el('span', { class: 'body' }, el('div', { class: 'name' }, goal.name), el('div', { class: 'meta' }, `${goal.places.length}곳 · ${DriveCard.date(goal.achievedAtMillis || goal.createdAtMillis)}`)), '›')) : el('p', { class: 'muted' }, '아직 완성한 목표가 없어요.'),
      el('h2', {}, '이전 기록'), state.records.length ? state.records.map(recordRow) : el('p', { class: 'muted' }, '아직 기록이 없어요.')];
  }

  // --- router ----------------------------------------------------------------------------------
  async function render() {
    if (!token) { app.replaceChildren(...[linkScreen()].flat(9).filter(Boolean)); return; }
    if (!state) {
      app.replaceChildren(el('div', { class: 'empty' }, '불러오는 중…'));
      const ok = await act(async () => { await refresh(); return true; });
      if (!ok) { app.replaceChildren(el('div', { class: 'empty' }, '서버에 연결할 수 없습니다.'), el('button', { class: 'btn', onclick: render }, '다시 시도')); return; }
    }
    const path = location.hash.replace(/^#/, '') || '/';
    let match, nodes;
    if (path === '/') nodes = homeScreen();
    else if (path === '/goal/edit') nodes = editGoalScreen();
    else if (path === '/goal/card') nodes = goalCardScreen(null);
    else if (path === '/archive') nodes = archiveScreen();
    else if ((match = path.match(/^\/archive\/([\w-]+)$/))) nodes = goalCardScreen(match[1]);
    else if ((match = path.match(/^\/record\/([\w-]+)$/))) nodes = recordScreen(match[1]);
    else if ((match = path.match(/^\/record\/([\w-]+)\/card$/))) nodes = cardScreen(match[1], false);
    else if ((match = path.match(/^\/record\/([\w-]+)\/preview$/))) nodes = cardScreen(match[1], true);
    else nodes = homeScreen();
    app.replaceChildren(...[nodes].flat(9).filter(Boolean));
    window.scrollTo(0, 0);
  }

  window.addEventListener('hashchange', render);
  // Pick up car progress when the driver returns to the phone, without disturbing an open editor.
  document.addEventListener('visibilitychange', () => {
    const path = location.hash || '#/';
    if (!document.hidden && token && state && (path === '#/' || path === '#/archive')) act(async () => { await refresh(); render(); });
  });
  render();
})();
