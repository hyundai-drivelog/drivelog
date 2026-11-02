/* Card images are drawn on canvas so the preview and the exported file are the same pixels. */
(function () {
  const W = 1080, H = 1350;
  const FONT = '"Pretendard", -apple-system, "Apple SD Gothic Neo", "Noto Sans KR", "Malgun Gothic", sans-serif';

  function font(ctx, weight, size) { ctx.font = `${weight} ${size}px ${FONT}`; }

  function text(ctx, value, x, y, weight, size, color, align) {
    font(ctx, weight, size);
    ctx.fillStyle = color;
    ctx.textAlign = align || 'left';
    ctx.textBaseline = 'alphabetic';
    ctx.fillText(value, x, y);
  }

  function ellipsize(ctx, value, maxWidth) {
    if (ctx.measureText(value).width <= maxWidth) return value;
    let cut = value;
    while (cut.length > 1 && ctx.measureText(cut + '…').width > maxWidth) cut = cut.slice(0, -1);
    return cut + '…';
  }

  /** Cover-fit with user pan/zoom. crop = {zoom >= 1, x, y in -1..1}. */
  function drawPhoto(ctx, photo, rect, crop, mono) {
    ctx.save();
    ctx.beginPath();
    ctx.rect(rect.x, rect.y, rect.w, rect.h);
    ctx.clip();
    if (photo) {
      const zoom = crop ? crop.zoom : 1;
      const scale = Math.max(rect.w / photo.width, rect.h / photo.height) * zoom;
      const dw = photo.width * scale, dh = photo.height * scale;
      const panX = (dw - rect.w) / 2 * (crop ? crop.x : 0);
      const panY = (dh - rect.h) / 2 * (crop ? crop.y : 0);
      if (mono) ctx.filter = 'grayscale(1) contrast(1.05)';
      ctx.drawImage(photo, rect.x + (rect.w - dw) / 2 + panX, rect.y + (rect.h - dh) / 2 + panY, dw, dh);
      ctx.filter = 'none';
    } else {
      ctx.fillStyle = '#d9d9db';
      ctx.fillRect(rect.x, rect.y, rect.w, rect.h);
    }
    ctx.restore();
  }

  /** Hide both ends by default: they reveal where a drive started and ended, often a home address. */
  function visibleRoute(route, trimEnds) {
    const pts = (route || []).filter(p => Array.isArray(p) && Number.isFinite(p[0]) && Number.isFinite(p[1]));
    if (!trimEnds || pts.length < 12) return pts;
    const cut = Math.max(1, Math.round(pts.length * 0.1));
    return pts.slice(cut, pts.length - cut);
  }

  function projectRoute(pts, rect) {
    if (pts.length < 2) return [];
    const lat0 = pts.reduce((sum, p) => sum + p[0], 0) / pts.length;
    const k = Math.cos(lat0 * Math.PI / 180);
    const xs = pts.map(p => p[1] * k), ys = pts.map(p => -p[0]);
    const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
    const dx = Math.max(maxX - minX, 1e-7), dy = Math.max(maxY - minY, 1e-7);
    const scale = Math.min(rect.w / dx, rect.h / dy);
    const ox = rect.x + (rect.w - dx * scale) / 2, oy = rect.y + (rect.h - dy * scale) / 2;
    return pts.map((p, i) => [ox + (xs[i] - minX) * scale, oy + (ys[i] - minY) * scale, p[2]]);
  }

  /** A recording gap (no fix for over a minute) is drawn dashed, never smoothed over. */
  function drawRoute(ctx, pts, rect, color, width, shadow) {
    const shape = projectRoute(pts, rect);
    if (!shape.length) return;
    ctx.save();
    ctx.lineJoin = 'round'; ctx.lineCap = 'round'; ctx.strokeStyle = color; ctx.lineWidth = width;
    if (shadow) { ctx.shadowColor = 'rgba(0,0,0,.45)'; ctx.shadowBlur = 12; }
    let solid = new Path2D(), dashed = new Path2D();
    shape.forEach((p, i) => {
      if (i === 0) { solid.moveTo(p[0], p[1]); return; }
      const prev = shape[i - 1];
      if (p[2] && prev[2] && p[2] - prev[2] > 60000) { dashed.moveTo(prev[0], prev[1]); dashed.lineTo(p[0], p[1]); solid.moveTo(p[0], p[1]); }
      else solid.lineTo(p[0], p[1]);
    });
    ctx.stroke(solid);
    ctx.setLineDash([width * 1.2, width * 2.4]);
    ctx.stroke(dashed);
    ctx.restore();
  }

  function distanceLabel(record) {
    return record.distanceSource === 'VEHICLE' ? '차량 주행거리' : '추정 거리';
  }

  function minutes(record) { return `${Math.max(1, Math.round(record.elapsedSeconds / 60))}분`; }

  function date(ms) {
    const d = new Date(ms);
    return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`;
  }

  function shortPlace(name) { return name.replace(/\s*(해수욕장|해변)$/, ''); }

  function bigDistance(ctx, record, x, y, size, color, unitColor, align) {
    const value = record.distanceKm == null ? '—' : record.distanceKm.toFixed(1);
    font(ctx, 800, size);
    const width = ctx.measureText(value).width;
    text(ctx, value, x, y, 800, size, color);
    if (record.distanceKm != null) text(ctx, 'km', x + width + size * 0.09, y, 400, size * 0.34, unitColor || color);
    return width;
  }

  /** Truthful labels stay on the image even when other fields are hidden. */
  function caveats(record) {
    const out = [];
    if (record.status === 'PARTIAL') out.push('불완전한 기록');
    if (record.simulated) out.push('시뮬레이션 데이터');
    return out.join(' · ');
  }

  function drawPhotoTemplate(ctx, record, photo, o) {
    const full = { x: 0, y: 0, w: W, h: H };
    drawPhoto(ctx, photo, full, o.crop, o.tone === 'mono');
    const top = ctx.createLinearGradient(0, 0, 0, 420);
    top.addColorStop(0, 'rgba(0,0,0,.45)'); top.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = top; ctx.fillRect(0, 0, W, 420);
    const bottom = ctx.createLinearGradient(0, H - 420, 0, H);
    bottom.addColorStop(0, 'rgba(0,0,0,0)'); bottom.addColorStop(1, 'rgba(0,0,0,.72)');
    ctx.fillStyle = bottom; ctx.fillRect(0, H - 420, W, 420);
    if (o.showDistance) {
      text(ctx, distanceLabel(record), 60, 92, 500, 30, '#fff');
      bigDistance(ctx, record, 56, 262, 180, '#fff');
    }
    if (o.showRoute) drawRoute(ctx, visibleRoute(record.route, o.trimEnds), { x: 70, y: 340, w: W - 140, h: 620 }, '#fff', 8, true);
    font(ctx, 800, 76);
    text(ctx, ellipsize(ctx, o.title, W - 120), 56, H - 150, 800, 76, '#fff');
    const meta = [o.showPlace ? shortPlace(record.placeName) : null, o.showTime ? `${minutes(record)} 기록` : null, o.showDate ? date(record.startedAtMillis) : null].filter(Boolean).join('   |   ');
    text(ctx, meta, 60, H - 76, 500, 30, '#fff');
    text(ctx, '여정', W - 56, H - 76, 800, 30, '#fff', 'right');
    const note = caveats(record);
    if (note) text(ctx, note, 60, H - 34, 500, 22, 'rgba(255,255,255,.85)');
  }

  function drawJournalTemplate(ctx, record, photo, o) {
    const dark = o.tone === 'mono-dark';
    const paper = dark ? '#000' : '#fff', ink = dark ? '#fff' : '#0e0e0f', sub = dark ? '#bbb' : '#55555a';
    ctx.fillStyle = paper; ctx.fillRect(0, 0, W, H);
    text(ctx, 'A DAY AWAY', 56, 84, 500, 24, ink);
    font(ctx, 800, 76);
    text(ctx, ellipsize(ctx, o.title, W - 112), 52, 186, 800, 76, ink);
    drawPhoto(ctx, photo, { x: 40, y: 232, w: W - 80, h: 690 }, o.crop, o.tone !== 'color');
    if (o.showRoute) drawRoute(ctx, visibleRoute(record.route, o.trimEnds), { x: 50, y: 960, w: 550, h: 250 }, ink, 6, false);
    if (o.showPlace) text(ctx, shortPlace(record.placeName), 50, H - 62, 800, 38, ink);
    ctx.fillStyle = dark ? '#555' : '#bbb'; ctx.fillRect(640, 962, 2, 290);
    if (o.showDistance) {
      text(ctx, distanceLabel(record), 676, 990, 500, 26, sub);
      bigDistance(ctx, record, 672, 1110, 118, ink);
      ctx.fillStyle = dark ? '#555' : '#bbb'; ctx.fillRect(676, 1136, W - 720, 2);
    }
    if (o.showTime) {
      text(ctx, minutes(record), 676, 1190, 800, 34, ink);
      font(ctx, 800, 34);
      const w = ctx.measureText(minutes(record)).width;
      text(ctx, '·  기록 경과 시간', 676 + w + 16, 1190, 400, 24, sub);
    }
    if (o.showDate) text(ctx, date(record.startedAtMillis), 676, 1240, 500, 28, ink);
    text(ctx, '여정', W - 44, H - 40, 800, 28, ink, 'right');
    const note = caveats(record);
    if (note) text(ctx, note, 50, H - 24, 500, 22, sub);
  }

  /** Goal completion: stacked place photos; a missing photo is an explicit placeholder. */
  function drawGoalCard(ctx, goal, recordsByPlace, photos) {
    ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);
    text(ctx, 'A DRIVE TO REMEMBER', 56, 84, 500, 24, '#fff');
    font(ctx, 800, 68);
    text(ctx, ellipsize(ctx, goal.name, W - 112), 52, 176, 800, 68, '#fff');
    text(ctx, `${Object.keys(recordsByPlace).length} / ${goal.places.length}곳 방문 완료`, 56, 232, 500, 30, '#ccc');
    const top = 274, gap = 14, rowH = Math.floor((H - top - 130 - gap * 2) / 3);
    goal.places.forEach((place, i) => {
      const y = top + i * (rowH + gap), rect = { x: 40, y, w: W - 80, h: rowH };
      const photo = photos[place.id];
      drawPhoto(ctx, photo, rect, null, false);
      if (!photo) text(ctx, '사진을 추가해 보세요', W / 2, y + rowH / 2 + 12, 500, 30, '#555', 'center');
      const shade = ctx.createLinearGradient(0, y + rowH - 110, 0, y + rowH);
      shade.addColorStop(0, 'rgba(0,0,0,0)'); shade.addColorStop(1, 'rgba(0,0,0,.65)');
      ctx.fillStyle = shade; ctx.fillRect(rect.x, y + rowH - 110, rect.w, 110);
      text(ctx, shortPlace(place.name), 66, y + rowH - 26, 800, 40, '#fff');
      const record = recordsByPlace[place.id];
      if (record) text(ctx, date(record.startedAtMillis), W - 66, y + rowH - 28, 500, 26, '#fff', 'right');
    });
    const all = Object.values(recordsByPlace);
    const sum = all.reduce((total, r) => total + (r.distanceKm || 0), 0);
    if (sum > 0) text(ctx, `총 ${sum.toFixed(1)} km · 추정 거리 포함`, 56, H - 56, 500, 28, '#ccc');
    text(ctx, '여정', W - 44, H - 56, 800, 28, '#fff', 'right');
    if (all.some(r => r.simulated)) text(ctx, '시뮬레이션 데이터 포함', 56, H - 20, 500, 22, '#aaa');
  }

  window.DriveCard = {
    W, H, visibleRoute, drawRoute, date, minutes, shortPlace, distanceLabel,
    renderRecord(canvas, record, photo, options) {
      canvas.width = W; canvas.height = H;
      const ctx = canvas.getContext('2d');
      ctx.clearRect(0, 0, W, H);
      if (options.template === 'JOURNAL') drawJournalTemplate(ctx, record, photo, options);
      else drawPhotoTemplate(ctx, record, photo, options);
    },
    renderGoal(canvas, goal, recordsByPlace, photos) {
      canvas.width = W; canvas.height = H;
      drawGoalCard(canvas.getContext('2d'), goal, recordsByPlace, photos);
    },
  };
})();
